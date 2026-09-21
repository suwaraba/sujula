/*
 * One product, as it appears in a grid.
 *
 * Two things on this card are not on a normal marketplace card, and both are
 * the point of this one:
 *
 *   · the distance — from the SELLER to the DELIVERY address, which is why a
 *     buyer in Madrid sees "4 km" on a phone in Kanifing;
 *   · the seller's own price beside the converted one, because the person
 *     paying in euro often knows perfectly well what the thing costs in dalasi.
 */

import { money, distance, priceLines } from '../money.js';
import { esc, icon, stars } from '../ui.js';

const PLACEHOLDER =
  'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">
       <rect width="200" height="200" fill="#e8e5df"/>
       <path d="M60 130l28-34 20 24 14-16 24 26z" fill="#c9c4ba"/>
       <circle cx="76" cy="74" r="12" fill="#c9c4ba"/>
     </svg>`);

export function deliveryPill(card) {
  if (card.deliverable === false) {
    return `<span class="pill pill-no">${icon('close', 12)} Not delivered there</span>`;
  }
  if (card.distanceKm != null) {
    const near = Number(card.distanceKm) <= 25;
    return `<span class="pill ${near ? 'pill-near' : 'pill-far'}">${icon('truck', 12)} ${esc(distance(card.distanceKm))} away</span>`;
  }
  if (card.deliverable === true) {
    return `<span class="pill pill-near">${icon('check', 12)} Delivers there</span>`;
  }
  return '';
}

export function productCard(card) {
  const price = priceLines(card);

  return `<a class="product-card" href="#/p/${encodeURIComponent(card.slug)}">
    <img class="thumb" loading="lazy" src="${esc(card.imageUrl || PLACEHOLDER)}"
         onerror="this.src='${PLACEHOLDER}'" alt="${esc(card.name)}">
    <div class="body">
      <span class="name">${esc(card.name)}</span>
      <span class="price">${esc(price.main)}</span>
      ${price.note ? `<span class="native">${esc(price.note)}</span>` : ''}
      <div class="foot">
        ${deliveryPill(card)}
        ${card.rating ? stars(card.rating) : ''}
        ${card.inStock === false ? '<span class="pill pill-no">Sold out</span>' : ''}
      </div>
      ${card.storeName ? `<span class="tiny muted truncate">${icon('store', 11)} ${esc(card.storeName)}</span>` : ''}
    </div>
  </a>`;
}

export function productGrid(items) {
  return `<div class="grid-products">${items.map(productCard).join('')}</div>`;
}

/**
 * The banner above a grid, which says how the grid was ordered.
 *
 * Three different states, and running them together is how a shopper stops
 * believing the page. A town with no pin is NOT the same as no destination at
 * all: the first is filtered to a country and merely not sorted by distance,
 * the second is an arbitrary order. Telling somebody who has already given
 * their sister's town to "say where it is going" reads as the page having
 * forgotten, and they answer it again.
 */
export function rankingNotice(applied, place) {
  if (applied && applied.rankedByProximity) {
    return applied.needsPinConfirmation
      ? `<div class="notice notice-warn" style="margin-bottom:14px">
           ${icon('pin', 16)} The destination pin is approximate, so distances are rough.
           <button class="link" data-action="change-place" type="button">Place the pin exactly</button>
         </div>`
      : '';
  }

  if (place) {
    return `<div class="notice" style="margin-bottom:14px">
        ${icon('pin', 16)} Showing what can reach <strong>${esc(place)}</strong>, but not sorted by
        distance — we do not have a pin for it.
        <button class="link" data-action="change-place" type="button">Put the pin on the map</button>
        and the nearest sellers come first.
      </div>`;
  }

  return `<div class="notice notice-warn" style="margin-bottom:14px">
      ${icon('pin', 16)} These are in no particular order.
      <button class="link" data-action="change-place" type="button">Say where it is going</button>
      and the nearest sellers come first.
    </div>`;
}
