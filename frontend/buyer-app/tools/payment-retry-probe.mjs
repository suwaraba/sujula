const storage = new Map();
globalThis.localStorage = {
  getItem: key => storage.has(key) ? storage.get(key) : null,
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: key => storage.delete(key)
};

const state = await import('../src/state.js?payment-retry-probe');
const original = {
  orderId: 42,
  paymentMethod: 'CARD',
  idempotencyKey: 'retry-key-42',
  createdAt: new Date().toISOString()
};

if (!state.savePaymentRetryAttempt(original)) throw new Error('attempt was not persisted');
const recovered = state.getPaymentRetryAttempt();
if (recovered.idempotencyKey !== original.idempotencyKey
    || recovered.paymentMethod !== original.paymentMethod) {
  throw new Error('explicit retry identity changed');
}

const raw = JSON.parse(storage.get('sujula.paymentRetryAttempt'));
const fields = Object.keys(raw).sort().join(',');
if (fields !== 'createdAt,idempotencyKey,orderId,paymentMethod') {
  throw new Error(`unexpected persisted fields: ${fields}`);
}

if (state.clearPaymentRetryAttempt(42, 'wrong-key') !== false
    || !state.getPaymentRetryAttempt()) {
  throw new Error('an old response cleared the active attempt');
}
if (!state.clearPaymentRetryAttempt(42, 'retry-key-42')
    || state.getPaymentRetryAttempt() !== null) {
  throw new Error('success did not clear the attempt');
}

console.log('payment retry storage probe passed');
