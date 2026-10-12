const API_BASE = 'http://localhost:8080/api'

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!response.ok) {
    const message = await response.text()
    throw new Error(message || `Request failed with status ${response.status}`)
  }
  return response.json()
}

export const api = {
  getItems: () => request('/items'),
  getSale: () => request('/sale'),
  getCart: () => request('/cart'),
  addToCart: (itemId, size, quantity = 1) => request('/cart/items', {
    method: 'POST',
    body: JSON.stringify({ itemId, size, quantity }),
  }),
  checkout: () => request('/checkout', { method: 'POST' }),
  getOrders: () => request('/orders'),
  pay: (orderId) => request(`/orders/${orderId}/payment`, { method: 'POST' }),
}
