const API_BASE = 'http://localhost:8080/api'
export const SESSION_EXPIRED_EVENT = 'flashdrop:session-expired'

async function request(path, options = {}) {
  const requiresAuth = !path.startsWith('/auth/')
  const token = requiresAuth ? localStorage.getItem('flashdrop_token') : null
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...options,
  })
  if (requiresAuth && token !== localStorage.getItem('flashdrop_token')) {
    throw new Error('Session changed; please sign in again')
  }
  if (requiresAuth && response.status === 401 && token) {
    localStorage.removeItem('flashdrop_token')
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
  }
  if (!response.ok) {
    const body = await response.text()
    let details
    try { details = JSON.parse(body) } catch { /* Plain-text errors are also supported. */ }
    const error = new Error(details?.message || body || `Request failed with status ${response.status}`)
    error.status = response.status
    error.checkoutFailed = ['FAILED', 'EXPIRED'].includes(details?.status)
    throw error
  }
  return response.json()
}

export const api = {
  login: (email, password) => request('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  }),
  register: (name, email, password) => request('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ name, email, password }),
  }),
  getItems: () => request('/items'),
  getSale: () => request('/sale'),
  getCart: () => request('/me/cart'),
  addToCart: (itemId, size, quantity = 1) => request('/me/cart/items', {
    method: 'POST',
    body: JSON.stringify({ itemId, size, quantity }),
  }),
  checkout: (requestId) => request('/me/checkout', {
    method: 'POST',
    body: JSON.stringify({ requestId }),
  }),
  getCheckoutStatus: (requestId) => request(`/me/checkout/${encodeURIComponent(requestId)}`),
  payCheckout: (requestId) => request(`/me/checkout/${encodeURIComponent(requestId)}/payment`, {
    method: 'POST',
    body: JSON.stringify({ paymentConfirmed: true }),
  }),
  cancelCheckout: (requestId) => request(`/me/checkout/${encodeURIComponent(requestId)}/cancel`, {
    method: 'POST',
  }),
  getOrders: () => request('/me/orders'),
}
