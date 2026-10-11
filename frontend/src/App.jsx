import { useEffect, useMemo, useState } from 'react'
import './App.css'

const initialProducts = [
  {
    id: 'jersey-1',
    name: 'City Edition Jersey',
    type: 'Jersey',
    price: 29.99,
    originalPrice: 49.99,
    stock: 12,
    sizes: ['S', 'M', 'L'],
    accent: 'blue',
  },
  {
    id: 'cap-1',
    name: 'Classic Team Cap',
    type: 'Cap',
    price: 14.99,
    originalPrice: 24.99,
    stock: 6,
    sizes: ['ONE_SIZE'],
    accent: 'orange',
  },
]
const saleEndsAt = Date.now() + (18 * 60 + 42) * 1000

function formatRemaining(milliseconds) {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000))
  const hours = String(Math.floor(totalSeconds / 3600)).padStart(2, '0')
  const minutes = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0')
  const seconds = String(totalSeconds % 60).padStart(2, '0')
  return `${hours} : ${minutes} : ${seconds}`
}

function App() {
  const [products, setProducts] = useState(initialProducts)
  const [cart, setCart] = useState([])
  const [orders, setOrders] = useState([])
  const [page, setPage] = useState('shop')
  const [paymentOrderId, setPaymentOrderId] = useState(null)
  const [selectedSizes, setSelectedSizes] = useState({
    'jersey-1': 'M',
    'cap-1': 'ONE_SIZE',
  })
  const [queueJoined, setQueueJoined] = useState(false)
  const [notice, setNotice] = useState('')
  const [remaining, setRemaining] = useState((18 * 60 + 42) * 1000)

  useEffect(() => {
    const timer = setInterval(() => setRemaining(saleEndsAt - Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const total = useMemo(
    () => cart.reduce((sum, item) => sum + item.price * item.quantity, 0),
    [cart],
  )

  function addToCart(product) {
    const size = selectedSizes[product.id]
    const existingQuantity = cart.find(
      (item) => item.id === product.id && item.size === size,
    )?.quantity ?? 0
    if (existingQuantity >= product.stock) {
      setNotice(`${product.name} is out of stock`)
      return
    }
    setCart((currentCart) => {
      const existing = currentCart.find(
        (item) => item.id === product.id && item.size === size,
      )
      if (existing) {
        return currentCart.map((item) =>
          item.id === product.id && item.size === size
            ? { ...item, quantity: item.quantity + 1 }
            : item,
        )
      }
      return [...currentCart, { ...product, size, quantity: 1 }]
    })
    setNotice(`${product.name} added to your cart`)
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
    setQueueJoined(true)
    setNotice('Your request was added to the FIFO checkout queue')
  }

  function placeOrder() {
    if (!queueJoined || !cart.length) return
    const unavailable = cart.find((cartItem) => {
      const product = products.find((item) => item.id === cartItem.id)
      return !product || product.stock < cartItem.quantity
    })
    if (unavailable) {
      setNotice(`${unavailable.name} is no longer available in that quantity`)
      return
    }

    setProducts((currentProducts) =>
      currentProducts.map((product) => {
        const purchased = cart.find((item) => item.id === product.id)
        return purchased
          ? { ...product, stock: product.stock - purchased.quantity }
          : product
      }),
    )
    setOrders((currentOrders) => [{
      id: `FD-${String(currentOrders.length + 1).padStart(4, '0')}`,
      items: cart,
      total,
      paymentStatus: 'PENDING',
      deliveryStatus: 'NOT_STARTED',
      createdAt: new Date().toLocaleString(),
    }, ...currentOrders])
    setCart([])
    setQueueJoined(false)
    setNotice('Order placed successfully. Payment is pending.')
    setPage('orders')
  }

  function markPaymentPaid(orderId) {
    setOrders((currentOrders) => currentOrders.map((order) =>
      order.id === orderId ? { ...order, paymentStatus: 'PAID' } : order,
    ))
    setNotice('Payment status updated to paid')
    setPage('orders')
    setPaymentOrderId(null)
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <button className="brand brand-button" onClick={() => setPage('shop')} type="button">
          <span className="brand-mark">F</span><span>FlashDrop</span>
        </button>
        <div className="live-indicator"><span className="pulse" />Live sale</div>
        <nav className="main-nav">
          <button className={page === 'shop' ? 'active' : ''} onClick={() => setPage('shop')} type="button">Shop</button>
          <button className={page === 'orders' ? 'active' : ''} onClick={() => setPage('orders')} type="button">My orders</button>
        </nav>
        <button className="cart-button" onClick={() => setPage('shop')} type="button">Cart <span>{cart.length}</span></button>
      </header>

      <section className="hero">
        <div className="countdown-card">
          <p>{remaining > 0 ? 'Sale ends in' : 'Sale ended'}</p>
          <strong>{formatRemaining(remaining)}</strong>
          <span>hours &nbsp; minutes &nbsp; seconds</span>
        </div>
      </section>

      {notice && <div className="notice" role="status">{notice}</div>}

      {page === 'orders' ? (
        <OrdersPage
          orders={orders}
          onMakePayment={(orderId) => {
            setPaymentOrderId(orderId)
            setPage('payment')
          }}
        />
      ) : page === 'payment' ? (
        <PaymentPage
          order={orders.find((item) => item.id === paymentOrderId)}
          onBack={() => setPage('orders')}
          onPay={markPaymentPaid}
        />
      ) : (
        <ShopPage
          cart={cart}
          products={products}
          selectedSizes={selectedSizes}
          setSelectedSizes={setSelectedSizes}
          addToCart={addToCart}
          total={total}
          queueJoined={queueJoined}
          joinQueue={joinQueue}
          placeOrder={placeOrder}
          orders={orders}
        />
      )}
    </main>
  )
}

function ShopPage({
  cart, products, selectedSizes, setSelectedSizes, addToCart, total,
  queueJoined, joinQueue, placeOrder, orders,
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
                <div className="card-footer"><span className="stock">{product.stock} left</span><button className="add-button" disabled={product.stock === 0} onClick={() => addToCart(product)} type="button">{product.stock === 0 ? 'Sold out' : 'Add to cart'} <span>+</span></button></div>
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
          <button className="queue-button" disabled={orders.length > 0 && !cart.length} onClick={queueJoined ? placeOrder : joinQueue} type="button">
            {queueJoined ? 'Place order' : 'Join checkout queue'} <span>→</span>
          </button>
        </section>
        <section className="panel order-panel"><p className="eyebrow">Order tracking</p><h2>Your orders</h2><div className="order-status"><span className={`status-dot ${orders.length ? 'complete' : 'pending'}`} /><div><strong>{orders.length ? `${orders.length} order(s) placed` : 'No active orders'}</strong><small>Open My orders to view payment and delivery status</small></div></div></section>
      </aside>
    </section>
  )
}

function OrdersPage({ orders, onMakePayment }) {
  return (
    <section className="orders-page">
      <div className="section-heading"><div><p className="eyebrow">Account</p><h2>My orders</h2></div><span className="batch-label">{orders.length} total</span></div>
      {!orders.length ? <div className="panel empty-orders"><h3>No orders yet</h3><p>Orders placed through the flash sale will appear here.</p></div> : <div className="orders-list">{orders.map((order) => <article className="panel order-card" key={order.id}><div className="order-card-heading"><div><p className="eyebrow">{order.id}</p><h3>{order.createdAt}</h3></div><strong>${order.total.toFixed(2)}</strong></div><div className="order-items">{order.items.map((item) => <div className="cart-line" key={`${order.id}-${item.id}-${item.size}`}><div className={`mini-art ${item.accent}`}>{item.type[0]}</div><div><strong>{item.name}</strong><small>{item.size.replace('_', ' ')} · Qty {item.quantity}</small></div><b>${(item.price * item.quantity).toFixed(2)}</b></div>)}</div><div className="status-grid"><div><span>Payment</span><strong className={order.paymentStatus === 'PAID' ? 'paid' : ''}>{order.paymentStatus}</strong></div><div><span>Delivery</span><strong>{order.deliveryStatus}</strong></div>{order.paymentStatus === 'PENDING' && <button className="pay-button" onClick={() => onMakePayment(order.id)} type="button">Make payment</button>}</div></article>)}</div>}
    </section>
  )
}

function PaymentPage({ order, onBack, onPay }) {
    if (!order) {
      return (
        <section className="payment-page">
          <div className="panel empty-orders"><h3>Payment session not found</h3><button className="pay-button" onClick={onBack} type="button">Back to orders</button></div>
        </section>
      )
    }

    return (
      <section className="payment-page">
        <button className="back-button" onClick={onBack} type="button">← Back to My orders</button>
        <div className="payment-layout">
          <div>
            <p className="eyebrow">Secure checkout</p>
            <h2>Make payment</h2>
            <p className="payment-copy">Complete this mock payment to confirm your order.</p>
            <div className="panel payment-form">
              <label>Cardholder name<input defaultValue="Demo Customer" /></label>
              <label>Card number<input defaultValue="4242 4242 4242 4242" /></label>
              <div className="payment-fields"><label>Expiry<input defaultValue="12/30" /></label><label>CVV<input defaultValue="123" /></label></div>
              <button className="queue-button" onClick={() => onPay(order.id)} type="button">Pay ${order.total.toFixed(2)} <span>→</span></button>
              <small className="payment-hint">Demo gateway: no real payment is processed.</small>
            </div>
          </div>
          <div className="panel payment-summary"><p className="eyebrow">Order summary</p><h3>{order.id}</h3>{order.items.map((item) => <div className="summary-line" key={`${item.id}-${item.size}`}><span>{item.name} · {item.size.replace('_', ' ')} × {item.quantity}</span><strong>${(item.price * item.quantity).toFixed(2)}</strong></div>)}<div className="total-row"><span>Total</span><strong>${order.total.toFixed(2)}</strong></div></div>
        </div>
      </section>
    )
}

export default App
