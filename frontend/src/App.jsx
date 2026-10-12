import { useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import './App.css'
import { api, SESSION_EXPIRED_EVENT } from './api'

function formatRemaining(milliseconds) {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000))
  const hours = String(Math.floor(totalSeconds / 3600)).padStart(2, '0')
  const minutes = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0')
  const seconds = String(totalSeconds % 60).padStart(2, '0')
  return `${hours} : ${minutes} : ${seconds}`
}

function normalizeOrder(order, products) {
  return {
    ...order,
    createdAt: new Date(order.createdAt).toLocaleString(),
    items: order.items.map((item) => {
      const product = products.find((productItem) => productItem.id === item.itemId)
      return {
        ...item,
        id: item.itemId,
        name: item.itemName,
        price: item.unitPrice,
        size: product?.sizes?.[0] ?? 'ONE_SIZE',
        type: product?.type ?? 'Item',
        accent: product?.accent ?? 'blue',
      }
    }),
  }
}

function Notice({ children, onDismiss, role = 'status' }) {
  const [dismissed, setDismissed] = useState(false)
  if (dismissed) return null

  return (
    <div className="notice" role={role}>
      <span>{children}</span>
      <button className="notice-close" type="button" aria-label="Dismiss message" onClick={() => {
        setDismissed(true)
        onDismiss?.()
      }}>×</button>
    </div>
  )
}

function App() {
  const navigate = useNavigate()
  const location = useLocation()
  const [products, setProducts] = useState([])
  const [cart, setCart] = useState([])
  const [orders, setOrders] = useState([])
  const [selectedSizes, setSelectedSizes] = useState({
    'jersey-1': 'M',
    'cap-1': 'ONE_SIZE',
  })
  const [queueJoined, setQueueJoined] = useState(false)
  const [checkoutRequestId, setCheckoutRequestId] = useState(null)
  const [checkoutProcessing, setCheckoutProcessing] = useState(false)
  const [queueStartedAt, setQueueStartedAt] = useState(null)
  const [queueError, setQueueError] = useState('')
  const [paymentExpiresAt, setPaymentExpiresAt] = useState(null)
  const [pendingNavigation, setPendingNavigation] = useState(null)
  const [cancellingPayment, setCancellingPayment] = useState(false)
  const checkoutAttempt = useRef(null)
  const [notice, setNotice] = useState('')
  const [saleEndsAt, setSaleEndsAt] = useState(null)
  const [remaining, setRemaining] = useState(0)
  const [token, setToken] = useState(() => localStorage.getItem('flashdrop_token'))
  const [loginError, setLoginError] = useState('')

  useEffect(() => () => { checkoutAttempt.current = null }, [token])

  useEffect(() => {
    function expireSession() {
      checkoutAttempt.current = null
      setCheckoutProcessing(false)
      setCheckoutRequestId(null)
      setQueueJoined(false)
      setQueueStartedAt(null)
      setQueueError('')
      setPaymentExpiresAt(null)
      setPendingNavigation(null)
      setCart([])
      setOrders([])
      setProducts([])
      setSaleEndsAt(null)
      setRemaining(0)
      setNotice('')
      setToken(null)
      setLoginError('Your session ended. Please sign in again. If the server restarted, create your account again.')
      navigate('/shop', { replace: true })
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, expireSession)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, expireSession)
  }, [navigate])

  useEffect(() => {
    if (!token) return undefined
    let active = true
    Promise.all([api.getItems(), api.getSale(), api.getCart(), api.getOrders()])
      .then(([items, sale, cartResponse, loadedOrders]) => {
        if (!active) return
        setProducts(items.map((item) => ({
          ...item,
          originalPrice: item.price * 1.67,
          accent: item.type === 'Jersey' ? 'blue' : 'orange',
        })))
        setSaleEndsAt(new Date(sale.endsAt).getTime())
        setRemaining(new Date(sale.endsAt).getTime() - Date.now())
        setCart(cartResponse.items.map((item) => {
          const product = items.find((productItem) => productItem.id === item.itemId)
          return { ...product, quantity: item.quantity, size: product?.sizes?.[0] ?? 'ONE_SIZE', accent: product?.type === 'Jersey' ? 'blue' : 'orange' }
        }))
        setOrders(loadedOrders.map((order) => normalizeOrder(order, items.map((item) => ({ ...item, accent: item.type === 'Jersey' ? 'blue' : 'orange' })))))
      })
      .catch((error) => {
        if (active && error.status !== 401) setNotice(`Backend unavailable: ${error.message}`)
      })
    return () => { active = false }
  }, [token])

  useEffect(() => {
    if (!saleEndsAt) return undefined
    const timer = setInterval(() => setRemaining(saleEndsAt - Date.now()), 1000)
    return () => clearInterval(timer)
  }, [saleEndsAt])

  useEffect(() => {
    if (!paymentExpiresAt || !checkoutRequestId || !queueJoined) return
    const paymentPath = `/payment/${checkoutRequestId}`
    function guardBackNavigation() {
      if (window.location.pathname !== paymentPath) {
        setPendingNavigation({ path: window.location.pathname })
        navigate(paymentPath, { replace: true })
      }
    }
    window.addEventListener('popstate', guardBackNavigation)
    return () => window.removeEventListener('popstate', guardBackNavigation)
  }, [paymentExpiresAt, checkoutRequestId, queueJoined, navigate])

  const total = useMemo(
    () => cart.reduce((sum, item) => sum + item.price * item.quantity, 0),
    [cart],
  )

  if (!token) {
    return <LoginPage onLogin={(registering, name, email, password) => (registering
      ? api.register(name, email, password)
      : api.login(email, password))
      .then((result) => {
        localStorage.setItem('flashdrop_token', result.token)
        setToken(result.token)
        setLoginError('')
        setNotice('')
      })
      .catch((error) => setLoginError(error.message))} error={loginError} onDismissError={() => setLoginError('')} />
  }

  function addToCart(product) {
    if (queueJoined) return
    const size = selectedSizes[product.id]
    api.addToCart(product.id, size)
      .then((cartResponse) => {
        const cartItem = cartResponse.items.find((item) => item.itemId === product.id)
        setCart((currentCart) => {
          const existing = currentCart.find((item) => item.id === product.id && item.size === size)
          if (existing) return currentCart.map((item) => item.id === product.id && item.size === size ? { ...item, quantity: cartItem.quantity } : item)
          return [...currentCart, { ...product, size, quantity: cartItem.quantity }]
        })
        setNotice(`${product.name} added to your cart`)
      })
      .catch((error) => setNotice(error.message))
  }

  function joinQueue() {
    if (!cart.length) {
      setNotice('Add an item before joining the flash-sale queue')
      return
    }
    if (queueJoined) {
      setNotice('You are already in the checkout queue')
      return
    }
    const requestId = crypto.randomUUID()
    setQueueJoined(true)
    setCheckoutRequestId(requestId)
    setQueueStartedAt(Date.now())
    setQueueError('')
    setPaymentExpiresAt(null)
    navigate('/queue')
    placeOrder(requestId)
  }

  async function placeOrder(requestId = checkoutRequestId, pay = false) {
    if (!requestId || checkoutAttempt.current) return
    const attempt = {}
    checkoutAttempt.current = attempt
    const isCurrent = () => checkoutAttempt.current === attempt
    setCheckoutProcessing(true)
    setQueueError('')
    setNotice('')
    try {
      let result = await (pay ? api.payCheckout(requestId) : api.checkout(requestId))
      while (result.status === 'PENDING' && isCurrent()) {
        await new Promise((resolve) => setTimeout(resolve, 1000))
        if (!isCurrent()) return
        result = await api.getCheckoutStatus(result.requestId)
      }
      if (!isCurrent()) return
      if (result.status === 'READY_FOR_PAYMENT') {
        setPaymentExpiresAt(result.paymentExpiresAt)
        navigate(`/payment/${result.requestId}`)
        return
      }
      if (result.status !== 'COMPLETED') throw new Error('Unexpected checkout status')
      const order = result.order
      setOrders((currentOrders) => [normalizeOrder(order, products), ...currentOrders.filter((existing) => existing.id !== order.id)])
      setQueueJoined(false)
      setCheckoutRequestId(null)
      setQueueStartedAt(null)
      setPaymentExpiresAt(null)
      setPendingNavigation(null)
      setCart([])
      setNotice('Payment completed and order confirmed.')
      navigate('/orders')
      try {
        const [items, cartResponse] = await Promise.all([api.getItems(), api.getCart()])
        if (!isCurrent()) return
        setProducts(items.map((item) => ({ ...item, originalPrice: item.price * 1.67, accent: item.type === 'Jersey' ? 'blue' : 'orange' })))
        setCart(cartResponse.items.map((item) => {
          const product = items.find((productItem) => productItem.id === item.itemId)
          return { ...product, quantity: item.quantity, size: product?.sizes?.[0] ?? 'ONE_SIZE', accent: product?.type === 'Jersey' ? 'blue' : 'orange' }
        }))
      } catch {
        if (isCurrent()) setNotice('Order placed successfully. Refresh to update inventory and cart.')
      }
    } catch (error) {
      if (!isCurrent()) return
      if (error.checkoutFailed) {
        setQueueJoined(false)
        setCheckoutRequestId(null)
        setPaymentExpiresAt(null)
        setQueueError(error.message)
        try {
          const cartResponse = await api.getCart()
          if (!isCurrent()) return
          setCart(cartResponse.items.map((item) => {
            const product = products.find((productItem) => productItem.id === item.itemId)
            return { ...product, quantity: item.quantity, size: product?.sizes?.[0] ?? 'ONE_SIZE', accent: product?.accent ?? 'blue' }
          }))
        } catch { /* The checkout error remains visible if refreshing the cart fails. */ }
      } else {
        setQueueError(`We couldn't check your order: ${error.message}. Retry to check the same request.`)
      }
    } finally {
      if (isCurrent()) {
        checkoutAttempt.current = null
        setCheckoutProcessing(false)
      }
    }
  }

  function logOut() {
    checkoutAttempt.current = null
    setCheckoutProcessing(false)
    setCheckoutRequestId(null)
    setQueueJoined(false)
    setQueueStartedAt(null)
    setQueueError('')
    setPaymentExpiresAt(null)
    setPendingNavigation(null)
    setCart([])
    setOrders([])
    setProducts([])
    setSaleEndsAt(null)
    setRemaining(0)
    setNotice('')
    localStorage.removeItem('flashdrop_token')
    setToken(null)
  }

  function requestNavigation(path, logout = false) {
    if (paymentExpiresAt && queueJoined && (logout || path !== `/payment/${checkoutRequestId}`)) {
      setPendingNavigation({ path, logout })
    } else if (logout) logOut()
    else navigate(path)
  }

  async function cancelPayment() {
    if (cancellingPayment || checkoutProcessing) return
    setCancellingPayment(true)
    try {
      const result = await api.cancelCheckout(checkoutRequestId)
      if (result.status === 'COMPLETED') {
        setOrders((current) => [normalizeOrder(result.order, products), ...current.filter((order) => order.id !== result.order.id)])
      } else if (result.status !== 'CANCELLED') throw new Error('Unable to cancel this checkout')
      setCart([])
      setQueueJoined(false)
      setCheckoutRequestId(null)
      setQueueStartedAt(null)
      setPaymentExpiresAt(null)
      setQueueError('')
      setPendingNavigation(null)
      setNotice(result.status === 'COMPLETED' ? 'Payment completed and order confirmed.' : 'Payment cancelled. Reserved items returned to inventory.')
      if (pendingNavigation?.logout) {
        logOut()
        return
      }
      navigate(result.status === 'COMPLETED' ? '/orders' : pendingNavigation?.path || '/shop')
      try {
        const items = await api.getItems()
        setProducts(items.map((item) => ({ ...item, originalPrice: item.price * 1.67, accent: item.type === 'Jersey' ? 'blue' : 'orange' })))
      } catch { setNotice('Checkout ended. Refresh to update inventory.') }
    } catch (error) {
      setQueueError(`Unable to confirm cancellation: ${error.message}. Please try again.`)
    } finally { setCancellingPayment(false) }
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <button className="brand brand-button" onClick={() => requestNavigation('/shop')} type="button">
          <span className="brand-mark">F</span><span>FlashDrop</span>
        </button>
        <div className="live-indicator"><span className="pulse" />Live sale</div>
        <nav className="main-nav">
          <button className={location.pathname === '/shop' ? 'active' : ''} onClick={() => requestNavigation('/shop')} type="button">Shop</button>
          <button className={location.pathname === '/orders' ? 'active' : ''} onClick={() => requestNavigation('/orders')} type="button">My orders</button>
        </nav>
        <button className="cart-button" onClick={() => requestNavigation('/shop')} type="button">Cart <span>{cart.length}</span></button>
        <button className="logout-button" onClick={() => requestNavigation('/shop', true)} type="button">Log out</button>
      </header>

      <section className="hero">
        <div className="countdown-card">
          <p>{remaining > 0 ? 'Sale ends in' : 'Sale ended'}</p>
          <strong>{formatRemaining(remaining)}</strong>
          <span>hours &nbsp; minutes &nbsp; seconds</span>
        </div>
      </section>

      {notice && <Notice key={notice} onDismiss={() => setNotice('')}>{notice}</Notice>}

      <Routes>
        <Route path="/" element={<Navigate to="/shop" replace />} />
        <Route path="/shop" element={<ShopPage cart={cart} products={products} selectedSizes={selectedSizes} setSelectedSizes={setSelectedSizes} addToCart={addToCart} total={total} queueJoined={queueJoined} joinQueue={joinQueue} onViewQueue={() => navigate(paymentExpiresAt ? `/payment/${checkoutRequestId}` : '/queue')} orders={orders} />} />
        <Route path="/queue" element={paymentExpiresAt ? <Navigate to={`/payment/${checkoutRequestId}`} replace /> : queueStartedAt ? <QueuePage startedAt={queueStartedAt} processing={checkoutProcessing} error={queueError} canRetry={queueJoined} onRetry={() => placeOrder()} onBack={() => navigate('/shop')} /> : <Navigate to="/shop" replace />} />
        <Route path="/orders" element={<OrdersPage orders={orders} />} />
        <Route path="/payment/:requestId" element={paymentExpiresAt ? <PaymentPage cart={cart} total={total} expiresAt={paymentExpiresAt} processing={checkoutProcessing || cancellingPayment} error={queueError} onBack={() => requestNavigation('/shop')} onPay={() => placeOrder(checkoutRequestId, true)} onRestart={() => {
          setQueueJoined(false)
          setCheckoutRequestId(null)
          setQueueStartedAt(null)
          setPaymentExpiresAt(null)
          setPendingNavigation(null)
          setQueueError('')
          navigate('/shop')
        }} /> : <Navigate to={queueError ? '/queue' : '/shop'} replace />} />
        <Route path="*" element={<Navigate to="/shop" replace />} />
      </Routes>
      {pendingNavigation && paymentExpiresAt && <CancelPaymentDialog busy={cancellingPayment || checkoutProcessing} error={queueError} onCancelPayment={cancelPayment} onStay={() => setPendingNavigation(null)} />}
    </main>
  )
}

function CancelPaymentDialog({ busy, error, onCancelPayment, onStay }) {
  const dialogRef = useRef(null)
  useEffect(() => {
    const dialog = dialogRef.current
    dialog.showModal()
    return () => dialog.close()
  }, [])

  return (
    <dialog className="cancel-dialog" ref={dialogRef} aria-labelledby="cancel-payment-title" aria-describedby="cancel-payment-copy" onCancel={(event) => { event.preventDefault(); if (!busy) onStay() }}>
      <h2 id="cancel-payment-title">Cancel payment?</h2>
      <p id="cancel-payment-copy">Cancelling returns your reserved items to inventory. Go back to payment to continue; your timer keeps running while this popup is open.</p>
      {error && <Notice key={error} role="alert">{error}</Notice>}
      <div className="cancel-dialog-actions">
        <button className="queue-button" disabled={busy} onClick={onStay} type="button">Go back to payment</button>
        <button className="cancel-payment-button" disabled={busy} onClick={onCancelPayment} type="button">{busy ? 'Please wait…' : 'Cancel payment'}</button>
      </div>
    </dialog>
  )
}

function LoginPage({ onLogin, error, onDismissError }) {
  const [registering, setRegistering] = useState(false)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('demo@example.com')
  const [password, setPassword] = useState('password')
  return (
    <main className="login-page">
      <div className="panel login-card">
        <div className="brand"><span className="brand-mark">F</span><span>FlashDrop</span></div>
        <p className="eyebrow">{registering ? 'Create account' : 'Secure sign in'}</p>
        <h1>{registering ? 'Join FlashDrop' : 'Welcome back'}</h1>
        <p className="login-copy">{registering ? 'Create your account to keep your cart and orders private.' : 'Sign in to access your cart, checkout, and orders.'}</p>
        <form onSubmit={(event) => { event.preventDefault(); onLogin(registering, name, email, password) }}>
          {registering && <label>Name<input value={name} onChange={(event) => setName(event.target.value)} /></label>}
          <label>Email<input value={email} onChange={(event) => setEmail(event.target.value)} type="email" /></label>
          <label>Password<input value={password} onChange={(event) => setPassword(event.target.value)} type="password" /></label>
          {error && <Notice key={error} role="alert" onDismiss={onDismissError}>{error}</Notice>}
          <button className="queue-button" type="submit">{registering ? 'Create account' : 'Sign in'} <span>→</span></button>
        </form>
        <button className="back-button" onClick={() => setRegistering(!registering)} type="button">
          {registering ? 'Already have an account? Sign in' : 'New here? Create an account'}
        </button>
        {!registering && <small className="payment-hint">Demo credentials: demo@example.com / password</small>}
      </div>
    </main>
  )
}

function ShopPage({
  cart, products, selectedSizes, setSelectedSizes, addToCart, total,
  queueJoined, joinQueue, onViewQueue, orders,
}) {
  return (
    <section className="content-grid">
      <div className="catalog">
        <div className="section-heading"><div><p className="eyebrow">Today&apos;s drop</p><h2>Choose your essentials</h2></div><span className="batch-label">10 requests / second</span></div>
        <div className="product-grid">
          {products.map((product) => (
            <article className="product-card" key={product.id}>
              <div className={`product-art ${product.accent}`}><span>{product.type}</span><div className="product-shape">{product.type === 'Jersey' ? 'J' : 'C'}</div></div>
              <div className="product-info">
                <div className="product-title"><div><p className="product-type">{product.type}</p><h3>{product.name}</h3></div><strong>${product.price.toFixed(2)}</strong></div>
                <p className="was-price">Was ${product.originalPrice.toFixed(2)}</p>
                <div className="size-row"><span>Size</span><div className="size-options">{product.sizes.map((size) => <button className={selectedSizes[product.id] === size ? 'selected' : ''} key={size} onClick={() => setSelectedSizes({ ...selectedSizes, [product.id]: size })} type="button">{size.replace('_', ' ')}</button>)}</div></div>
                <div className="card-footer"><span className="stock">{product.stock} left</span><button className="add-button" disabled={queueJoined || product.stock === 0} onClick={() => addToCart(product)} type="button">{product.stock === 0 ? 'Sold out' : 'Add to cart'} <span>+</span></button></div>
              </div>
            </article>
          ))}
        </div>
      </div>
      <aside className="sidebar">
        <section className="panel cart-panel">
          <div className="panel-heading"><h2>Your cart</h2><span>{cart.length} items</span></div>
          {cart.length ? cart.map((item) => <div className="cart-line" key={`${item.id}-${item.size}`}><div className={`mini-art ${item.accent}`}>{item.type[0]}</div><div><strong>{item.name}</strong><small>{item.size.replace('_', ' ')} · Qty {item.quantity}</small></div><b>${(item.price * item.quantity).toFixed(2)}</b></div>) : <p className="empty-state">Your cart is waiting for a great deal.</p>}
          <div className="total-row"><span>Estimated total</span><strong>${total.toFixed(2)}</strong></div>
          <button className="queue-button" disabled={!queueJoined && !cart.length} onClick={queueJoined ? onViewQueue : joinQueue} type="button">
            {queueJoined ? 'View your checkout' : 'Join checkout queue'} <span>→</span>
          </button>
        </section>
        <section className="panel order-panel"><p className="eyebrow">Order tracking</p><h2>Your orders</h2><div className="order-status"><span className={`status-dot ${orders.length ? 'complete' : 'pending'}`} /><div><strong>{orders.length ? `${orders.length} order(s) placed` : 'No active orders'}</strong><small>Open My orders to view payment and delivery status</small></div></div></section>
      </aside>
    </section>
  )
}

function QueuePage({ startedAt, processing, error, canRetry, onRetry, onBack }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!processing && !canRetry) return undefined
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [processing, canRetry])

  return (
    <section className="waiting-page" aria-labelledby="queue-title">
      <div className="panel waiting-card" aria-busy={processing}>
        <div className={`waiting-icon ${processing ? 'spinning' : ''}`} aria-hidden="true">{error ? '!' : '↻'}</div>
        <p className="eyebrow">Flash-sale checkout</p>
        <div role="status" aria-live="polite">
          <h2 id="queue-title">{error ? (canRetry ? 'Checking paused' : 'Checkout could not complete') : 'You’re in the checkout queue'}</h2>
          <p className="waiting-copy">{error ? (canRetry ? 'You can check your request again or return to the shop.' : 'Return to the shop to review your cart and try again.') : 'Please wait for your turn. We’ll open the payment page automatically when you can check out.'}</p>
        </div>
        {error && <Notice key={error} role="alert">{error}</Notice>}
        <div className="waiting-time"><span>Time elapsed</span><strong>{formatRemaining(now - startedAt)}</strong></div>
        <ol className="waiting-steps" aria-label="Checkout progress">
          <li className="done"><span aria-hidden="true">✓</span>Queue joined</li>
          <li className={processing ? 'current' : ''}><span aria-hidden="true">2</span>Waiting for your turn</li>
          <li><span aria-hidden="true">3</span>Complete payment to place your order</li>
        </ol>
        <p className="queue-note">Keep this tab open while you wait. Items are reserved when your turn arrives, until you pay, cancel, or the payment timer expires.</p>
        <div className="waiting-actions">
          {processing && <button className="queue-button loading-button" disabled type="button"><span className="button-spinner" aria-hidden="true" />Waiting for checkout…</button>}
          {error && canRetry && <button className="queue-button" onClick={onRetry} type="button">Check my request again <span>→</span></button>}
          <button className="back-button" onClick={onBack} type="button">{canRetry ? 'Back to shop — checkout continues' : 'Back to shop'}</button>
        </div>
      </div>
    </section>
  )
}

function OrdersPage({ orders }) {
  return (
    <section className="orders-page">
      <div className="section-heading"><div><p className="eyebrow">Account</p><h2>My orders</h2></div><span className="batch-label">{orders.length} total</span></div>
      {!orders.length ? <div className="panel empty-orders"><h3>No orders yet</h3><p>Orders placed through the flash sale will appear here.</p></div> : <div className="orders-list">{orders.map((order) => <article className="panel order-card" key={order.id}><div className="order-card-heading"><div><p className="eyebrow">{order.id}</p><h3>{order.createdAt}</h3></div><strong>${order.total.toFixed(2)}</strong></div><div className="order-items">{order.items.map((item) => <div className="cart-line" key={`${order.id}-${item.id}-${item.size}`}><div className={`mini-art ${item.accent}`}>{item.type[0]}</div><div><strong>{item.name}</strong><small>{item.size.replace('_', ' ')} · Qty {item.quantity}</small></div><b>${(item.price * item.quantity).toFixed(2)}</b></div>)}</div><div className="status-grid"><div><span>Payment</span><strong className={order.paymentStatus === 'PAID' ? 'paid' : ''}>{order.paymentStatus}</strong></div><div><span>Delivery</span><strong>{order.deliveryStatus}</strong></div></div></article>)}</div>}
    </section>
  )
}

function PaymentPage({ cart, total, expiresAt, processing, error, onBack, onPay, onRestart }) {
    const [now, setNow] = useState(() => Date.now())
    useEffect(() => {
      const timer = setInterval(() => setNow(Date.now()), 250)
      return () => clearInterval(timer)
    }, [])
    const timeLeft = new Date(expiresAt).getTime() - now
    const expired = timeLeft <= 0
    if (!cart.length) {
      return (
        <section className="payment-page">
          <div className="panel empty-orders"><h3>Your cart is empty</h3><button className="pay-button" onClick={onBack} type="button">Back to shop</button></div>
        </section>
      )
    }

    return (
      <section className="payment-page">
        <button className="back-button" onClick={onBack} type="button">← Back to shop</button>
        <div className="payment-layout">
          <div>
            <p className="eyebrow">Checkout</p>
            <h2>Payment &amp; order</h2>
            <p className="payment-copy">Your turn is ready. Complete payment before the timer ends to place your order.</p>
            {expired ? <Notice key="payment-expired">Payment session expired. Join the queue again to try checkout.</Notice> : <div className="notice" role="timer" aria-label="Time remaining for payment"><span>Time to pay: {formatRemaining(timeLeft)}</span></div>}
            {error && <Notice key={error} role="alert">{error}</Notice>}
            <form className="panel payment-form" onSubmit={(event) => { event.preventDefault(); if (!expired && !processing) onPay() }}>
              <label>Demo cardholder name<input defaultValue="Demo Customer" required /></label>
              <label>Demo card number<input defaultValue="4242 4242 4242 4242" required pattern="[0-9 ]{16,19}" /></label>
              <div className="payment-fields"><label>Expiry<input defaultValue="12/30" required pattern="(0[1-9]|1[0-2])/[0-9]{2}" /></label><label>CVV<input defaultValue="123" required pattern="[0-9]{3,4}" /></label></div>
              <button className={`queue-button ${processing ? 'loading-button' : ''}`} disabled={expired || processing} type="submit">{processing ? <><span className="button-spinner" aria-hidden="true" />Processing payment…</> : `Pay & place order · $${total.toFixed(2)}`}</button>
              {expired && <button className="pay-button" disabled={processing} onClick={onRestart} type="button">Return to shop and rejoin queue</button>}
              <small className="payment-hint">Demo gateway: use the sample details. No real payment is processed.</small>
            </form>
          </div>
          <div className="panel payment-summary"><p className="eyebrow">Your checkout</p><h3>Review your items</h3>{cart.map((item) => <div className="summary-line" key={`${item.id}-${item.size}`}><span>{item.name} · {item.size.replace('_', ' ')} × {item.quantity}</span><strong>${(item.price * item.quantity).toFixed(2)}</strong></div>)}<div className="total-row"><span>Total</span><strong>${total.toFixed(2)}</strong></div></div>
        </div>
      </section>
    )
}

export default App
