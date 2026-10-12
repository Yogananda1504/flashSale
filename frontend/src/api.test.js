import test, { beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { api, SESSION_EXPIRED_EVENT } from './api.js'

let values
beforeEach(() => {
  values = new Map([['flashdrop_token', 'old-token']])
  globalThis.localStorage = {
    getItem: (key) => values.get(key) ?? null,
    removeItem: (key) => values.delete(key),
  }
  globalThis.window = new EventTarget()
})

test('login does not send a stale bearer token or expire the session on bad credentials', async () => {
  let expired = false
  window.addEventListener(SESSION_EXPIRED_EVENT, () => { expired = true })
  globalThis.fetch = async (_url, options) => {
    assert.equal(options.headers.Authorization, undefined)
    return new Response('Invalid email or password', { status: 401 })
  }
  await assert.rejects(api.login('demo@example.com', 'wrong'), /Invalid email or password/)
  assert.equal(expired, false)
})

test('a protected 401 clears the token and tells the UI to sign out', async () => {
  let expired = false
  window.addEventListener(SESSION_EXPIRED_EVENT, () => { expired = true })
  globalThis.fetch = async () => new Response('', { status: 401 })
  await assert.rejects(api.getItems(), (error) => error.status === 401)
  assert.equal(localStorage.getItem('flashdrop_token'), null)
  assert.equal(expired, true)
})

test('a late 401 from the previous session cannot sign out a new session', async () => {
  globalThis.fetch = async () => {
    values.set('flashdrop_token', 'new-token')
    return new Response('', { status: 401 })
  }
  await assert.rejects(api.getItems(), /Session changed/)
  assert.equal(localStorage.getItem('flashdrop_token'), 'new-token')
})

test('a late successful response from the previous session is discarded', async () => {
  globalThis.fetch = async () => {
    values.set('flashdrop_token', 'new-token')
    return Response.json([{ id: 'private-data' }])
  }
  await assert.rejects(api.getOrders(), /Session changed/)
})

test('joining the queue does not submit payment confirmation', async () => {
  globalThis.fetch = async (_url, options) => {
    assert.deepEqual(JSON.parse(options.body), { requestId: 'checkout-1' })
    return Response.json({ requestId: 'checkout-1', status: 'PENDING' }, { status: 202 })
  }
  assert.equal((await api.checkout('checkout-1')).status, 'PENDING')
})

test('payment confirms the admitted session using its existing identifier', async () => {
  globalThis.fetch = async (url, options) => {
    assert.ok(url.endsWith('/me/checkout/checkout-1/payment'))
    assert.deepEqual(JSON.parse(options.body), { paymentConfirmed: true })
    return Response.json({ status: 'COMPLETED', order: { paymentStatus: 'PAID' } })
  }
  assert.equal((await api.payCheckout('checkout-1')).order.paymentStatus, 'PAID')
})

test('an expired payment is reported as a terminal checkout failure', async () => {
  globalThis.fetch = async () => Response.json({ status: 'EXPIRED', message: 'Payment timed out' }, { status: 410 })
  await assert.rejects(api.payCheckout('checkout-1'), (error) => error.status === 410 && error.checkoutFailed)
})

test('cancellation targets the existing checkout session', async () => {
  globalThis.fetch = async (url, options) => {
    assert.ok(url.endsWith('/me/checkout/checkout-1/cancel'))
    assert.equal(options.method, 'POST')
    return Response.json({ status: 'CANCELLED' })
  }
  assert.equal((await api.cancelCheckout('checkout-1')).status, 'CANCELLED')
})
