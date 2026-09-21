/*
 * Money, formatted at the currency's own scale.
 *
 * XOF has no minor units: 1250.50 CFA is not an amount that exists, and a
 * storefront that prints it has invented a price nobody can pay. The scale
 * comes from /currencies — the server's CurrencyCatalogue is the authority and
 * this is its client-side mirror, never a hardcoded two.
 */

import { state } from './state.js';

const formatters = new Map();

export function minorUnitsOf(code) {
  const found = state.currencies.find(c => c.code === code);
  return found ? found.minorUnits : 2;
}

export function symbolOf(code) {
  const found = state.currencies.find(c => c.code === code);
  return (found && found.symbol) || code;
}

/** The buyer's display currency formatted for display. */
export function money(amount, code) {
  const currency = code || state.currencyCode;
  if (amount === null || amount === undefined || currency == null) return '';
  const digits = minorUnitsOf(currency);
  const key = currency + ':' + digits;
  let fmt = formatters.get(key);
  if (!fmt) {
    try {
      fmt = new Intl.NumberFormat(navigator.language || 'en', {
        style: 'currency',
        currency,
        minimumFractionDigits: digits,
        maximumFractionDigits: digits
      });
    } catch {
      // An unknown ISO code (a deployment may add one) must not blank the price.
      fmt = {
        format: v => symbolOf(currency) + ' ' + Number(v).toFixed(digits)
      };
    }
    formatters.set(key, fmt);
  }
  return fmt.format(Number(amount));
}

/**
 * The seller's own price, shown beside the converted one.
 *
 * Both halves are on the screen on purpose: the buyer is charged in their
 * currency and the seller is paid in theirs, and a shopper sending money home
 * is the person most likely to want to see both numbers.
 */
export function nativeMoney(amount, code) {
  if (amount === null || amount === undefined || !code) return '';
  return money(amount, code);
}

export function distance(km) {
  if (km === null || km === undefined) return '';
  const n = Number(km);
  if (n < 1) return Math.round(n * 1000) + ' m';
  if (n < 10) return n.toFixed(1) + ' km';
  return Math.round(n) + ' km';
}

/**
 * The two lines of a price: what the buyer pays, and what the seller listed.
 *
 * The case this exists for is the one where the server sends no converted
 * price at all — no rate for that pair today. The wrong answers are a blank
 * space and a number we made up; the right one is the seller's own price,
 * labelled as unconverted, so nobody is quoted a figure that no rate stands
 * behind.
 */
export function priceLines(item) {
  const display = item.currency;
  const listing = item.listingCurrency;
  const differ = listing && display && listing !== display;

  if (item.price !== null && item.price !== undefined) {
    return {
      main: money(item.price, display),
      note: differ && item.listingPrice != null
        ? money(item.listingPrice, listing) + ' from the seller'
        : null,
      unconverted: false
    };
  }

  if (item.listingPrice !== null && item.listingPrice !== undefined) {
    return {
      main: money(item.listingPrice, listing),
      note: differ ? 'No rate to ' + display + ' right now — this is the seller\u2019s own price' : null,
      unconverted: true
    };
  }

  return { main: '', note: null, unconverted: false };
}
