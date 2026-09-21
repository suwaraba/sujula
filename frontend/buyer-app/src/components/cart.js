/*
 * The basket, and the three things that must stay true about it.
 *
 * A basket is opened before anybody signs in, because on this marketplace most
 * are. It carries the buyer's display currency and the delivery context, and
 * both are pushed to the server when they change — a basket priced against
 * last week's destination would quote the wrong shipping and, worse, quote it
 * confidently.
 */

import { api, ApiError, idempotencyKey } from '../api.js';
import { state, setCart, setCartToken } from '../state.js';
import { ensureContext } from '../delivery.js';
import { toast } from '../ui.js';

let opening = null;

export async function ensureCart() {
  if (state.cart && state.cart.token) return state.cart;
  if (opening) return opening;

  opening = (async () => {
    const contextId = await ensureContext();

    if (state.cartToken) {
      try {
        const existing = await api.readCart(state.cartToken);
        setCart(existing);
        return existing;
      } catch (error) {
        // Expired, or bound to an account this device is no longer signed into.
        if (!(error instanceof ApiError) || !error.isNotFound) throw error;
        setCartToken(null);
      }
    }

    const created = await api.createCart({
      currency: state.currencyCode || null,
      deliveryContextId: contextId || null
    });
    setCart(created);
    return created;
  })().finally(() => { opening = null; });

  return opening;
}

export async function refreshCart() {
  if (!state.cartToken) return null;
  try {
    const cart = await api.readCart(state.cartToken);
    setCart(cart);
    return cart;
  } catch (error) {
    if (error instanceof ApiError && error.isNotFound) {
      setCartToken(null);
      setCart(null);
      return null;
    }
    throw error;
  }
}

export async function addToCart(productId, variantId, quantity) {
  const cart = await ensureCart();
  const updated = await api.addItem(cart.token, {
    productId, variantId: variantId || null, quantity: quantity || 1
  }, idempotencyKey());
  setCart(updated);
  return updated;
}

export async function setQuantity(itemId, quantity) {
  const cart = await ensureCart();
  const updated = await api.setQuantity(cart.token, itemId, quantity);
  setCart(updated);
  return updated;
}

export async function removeLine(itemId) {
  const cart = await ensureCart();
  const updated = await api.removeItem(cart.token, itemId);
  setCart(updated);
  return updated;
}

/**
 * Tell the basket what changed outside it.
 *
 * Called when the buyer changes currency or destination. Failures are not
 * shown: the basket re-prices on its own page anyway, and a toast about a
 * background sync is noise at the moment somebody is doing something else.
 */
export async function syncCartContext() {
  if (!state.cartToken) return;
  try {
    if (state.currencyCode) {
      const updated = await api.setCartCurrency(state.cartToken, state.currencyCode);
      setCart(updated);
    }
    const contextId = await ensureContext();
    if (contextId) {
      const updated = await api.setCartDeliveryContext(state.cartToken, contextId);
      setCart(updated);
    }
  } catch (error) {
    console.warn('[sujula] cart sync', error);
  }
}

/** Merge a guest basket into the account one after signing in. */
export async function mergeGuestCart(guestToken) {
  if (!guestToken) return;
  try {
    const cart = await api.createCart({
      currency: state.currencyCode || null,
      deliveryContextId: state.deliveryContextId || null
    });
    const merged = await api.mergeCart(cart.token, guestToken);
    setCart(merged);
  } catch (error) {
    console.warn('[sujula] cart merge', error);
    toast('Your basket could not be carried over. It is still here if you sign out.');
  }
}
