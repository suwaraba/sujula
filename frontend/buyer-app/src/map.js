/*
 * A map, in about two hundred lines and with nothing to download.
 *
 * This exists because of a fact about the place this marketplace serves:
 * most of the delivery area has no street addresses. "Behind the mosque,
 * Serrekunda" is a real address and no geocoder will find it, so the pin IS
 * the address — the buyer drags it onto the compound and that is the answer
 * the driver gets.
 *
 * Raster tiles positioned by hand, pointer events for the drag, and the pin
 * fixed at the centre so the map moves under it: on a phone, dragging a small
 * marker with a thumb that covers it is the version that does not work. If the
 * tiles cannot be fetched — an offline phone, a blocked host — the pin, the
 * coordinates and the buttons all still work, and the panel says so rather
 * than showing a grey square.
 */

import { config } from './config.js';

const TILE = 256;
const MIN_ZOOM = 3;
const MAX_ZOOM = 18;

const lngToX = (lng, z) => (lng + 180) / 360 * Math.pow(2, z);
const latToY = (lat, z) => {
  const rad = lat * Math.PI / 180;
  return (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * Math.pow(2, z);
};
const xToLng = (x, z) => x / Math.pow(2, z) * 360 - 180;
const yToLat = (y, z) => {
  const n = Math.PI - 2 * Math.PI * y / Math.pow(2, z);
  return 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
};

const PIN_SVG = `<svg class="map-pin" width="34" height="46" viewBox="0 0 34 46" aria-hidden="true">
  <path d="M17 45C17 45 32 28.5 32 17A15 15 0 1 0 2 17C2 28.5 17 45 17 45Z" fill="#c9761a" stroke="#fff" stroke-width="2.5"/>
  <circle cx="17" cy="17" r="5.5" fill="#fff"/>
</svg>`;

/**
 * @param {HTMLElement} container
 * @param {{lat:number,lng:number,zoom:number,onMove:function}} options
 */
export function createMap(container, options) {
  const opts = Object.assign({}, config.defaultMapCentre, options || {});
  let zoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(opts.zoom || 13)));
  let cx = lngToX(opts.lng, zoom);
  let cy = latToY(opts.lat, zoom);
  let tilesEverLoaded = false;
  let tilesTried = 0;
  let tilesFailed = 0;

  container.classList.add('map');
  container.innerHTML = `
    <div class="map-tiles"></div>
    ${PIN_SVG}
    <div class="map-hint">Drag the map so the pin sits on the house</div>
    <div class="map-controls">
      <button type="button" data-zoom="1" aria-label="Zoom in">+</button>
      <button type="button" data-zoom="-1" aria-label="Zoom out">−</button>
    </div>
    <div class="map-attrib"></div>`;

  const tilesLayer = container.querySelector('.map-tiles');
  const attrib = container.querySelector('.map-attrib');
  const hint = container.querySelector('.map-hint');
  attrib.textContent = config.tileAttribution;

  const pool = new Map();

  function draw() {
    const width = container.clientWidth || 320;
    const height = container.clientHeight || 260;
    const halfW = width / 2;
    const halfH = height / 2;
    const scale = Math.pow(2, zoom);

    const first = Math.floor(cx - halfW / TILE);
    const last = Math.ceil(cx + halfW / TILE);
    const top = Math.floor(cy - halfH / TILE);
    const bottom = Math.ceil(cy + halfH / TILE);

    const wanted = new Set();

    for (let i = first; i <= last; i++) {
      for (let j = top; j <= bottom; j++) {
        if (j < 0 || j >= scale) continue;             // above the pole
        const wrapped = ((i % scale) + scale) % scale; // round the world
        const key = `${zoom}/${wrapped}/${j}`;
        wanted.add(key + '@' + i);

        let img = pool.get(key + '@' + i);
        if (!img) {
          img = new Image();
          img.decoding = 'async';
          img.loading = 'eager';
          img.alt = '';
          tilesTried++;
          img.addEventListener('load', () => { tilesEverLoaded = true; settle(); });
          img.addEventListener('error', () => { img.style.visibility = 'hidden'; tilesFailed++; settle(); });
          img.src = config.tileUrl
            .replace('{z}', zoom).replace('{x}', wrapped).replace('{y}', j);
          tilesLayer.appendChild(img);
          pool.set(key + '@' + i, img);
        }
        img.style.left = Math.round(halfW + (i - cx) * TILE) + 'px';
        img.style.top = Math.round(halfH + (j - cy) * TILE) + 'px';
      }
    }

    pool.forEach((img, key) => {
      if (!wanted.has(key)) { img.remove(); pool.delete(key); }
    });
  }

  function settle() {
    if (tilesEverLoaded) return;
    if (tilesTried > 0 && tilesFailed >= tilesTried) {
      hint.textContent = 'Map images could not be loaded. The pin still works — '
        + 'move it with the buttons, or use my location.';
    }
  }

  function centre() {
    return { latitude: yToLat(cy, zoom), longitude: xToLng(cx, zoom), zoom };
  }

  function announce() {
    if (options && options.onMove) options.onMove(centre());
  }

  /* ── Dragging ───────────────────────────────────────────────────────────── */

  let dragging = false;
  let lastX = 0;
  let lastY = 0;
  let pointerId = null;

  container.addEventListener('pointerdown', event => {
    if (event.target.closest('.map-controls')) return;
    dragging = true;
    pointerId = event.pointerId;
    lastX = event.clientX;
    lastY = event.clientY;
    container.classList.add('dragging');
    container.setPointerCapture(pointerId);
  });

  container.addEventListener('pointermove', event => {
    if (!dragging || event.pointerId !== pointerId) return;
    cx -= (event.clientX - lastX) / TILE;
    cy -= (event.clientY - lastY) / TILE;
    lastX = event.clientX;
    lastY = event.clientY;
    draw();
  });

  function endDrag(event) {
    if (!dragging || (event && event.pointerId !== pointerId)) return;
    dragging = false;
    container.classList.remove('dragging');
    try { container.releasePointerCapture(pointerId); } catch { /* already gone */ }
    announce();
  }

  container.addEventListener('pointerup', endDrag);
  container.addEventListener('pointercancel', endDrag);

  container.addEventListener('wheel', event => {
    event.preventDefault();
    setZoom(zoom + (event.deltaY < 0 ? 1 : -1));
  }, { passive: false });

  container.querySelectorAll('[data-zoom]').forEach(button => {
    button.addEventListener('click', () => setZoom(zoom + Number(button.dataset.zoom)));
  });

  function setZoom(next) {
    const target = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next));
    if (target === zoom) return;
    const point = centre();
    zoom = target;
    cx = lngToX(point.longitude, zoom);
    cy = latToY(point.latitude, zoom);
    pool.forEach(img => img.remove());
    pool.clear();
    draw();
    announce();
  }

  const resize = new ResizeObserver(() => draw());
  resize.observe(container);

  draw();

  return {
    centre,
    setCentre(lat, lng, nextZoom) {
      if (nextZoom) zoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(nextZoom)));
      cx = lngToX(lng, zoom);
      cy = latToY(lat, zoom);
      pool.forEach(img => img.remove());
      pool.clear();
      draw();
      announce();
    },
    destroy() {
      resize.disconnect();
      pool.clear();
      container.innerHTML = '';
    }
  };
}

/** The phone's own idea of where it is. Never used for the delivery address
 *  without the buyer confirming it — the buyer is usually not where the parcel
 *  is going. */
export function currentPosition() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('This device cannot report its position.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      position => resolve({
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracy: position.coords.accuracy
      }),
      error => reject(new Error(
        error.code === 1 ? 'Permission for your location was refused.'
                         : 'Your position could not be read.')),
      { enableHighAccuracy: true, timeout: 12000, maximumAge: 120000 }
    );
  });
}
