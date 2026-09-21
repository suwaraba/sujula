/*
 * DOM helpers, icons, toasts and the modal.
 *
 * Views build markup as strings, so everything that comes from the server —
 * a product name, a store name, a seller's own description — goes through
 * esc() on the way in. There is no framework here doing it for us.
 */

export function esc(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

export function el(html) {
  const template = document.createElement('template');
  template.innerHTML = html.trim();
  return template.content.firstElementChild;
}

export function on(root, selector, event, handler) {
  root.querySelectorAll(selector).forEach(node => node.addEventListener(event, handler));
}

/* ── Icons ────────────────────────────────────────────────────────────────── */

const ICONS = {
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6 8-6s8 2 8 6"/>',
  cart: '<path d="M3 4h2l2.4 11.2a2 2 0 002 1.6h7.7a2 2 0 002-1.6L21 7H6"/><circle cx="10" cy="20" r="1.4"/><circle cx="18" cy="20" r="1.4"/>',
  box: '<path d="M21 8l-9-5-9 5 9 5 9-5z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/>',
  home: '<path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  pin: '<path d="M12 21s7-6.2 7-11a7 7 0 10-14 0c0 4.8 7 11 7 11z"/><circle cx="12" cy="10" r="2.6"/>',
  phone: '<path d="M5 3h4l2 5-2.5 1.5a12 12 0 006 6L16 13l5 2v4a1.6 1.6 0 01-1.8 1.6C11 20 4 13 3.4 4.8A1.6 1.6 0 015 3z"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 7l9 6 9-6"/>',
  whatsapp: '<path d="M20 11.8A7.9 7.9 0 018 18.7L4 20l1.3-3.9A7.9 7.9 0 1120 11.8z"/><path d="M9 9.5c0 3 2.5 5.5 5.5 5.5l.9-1.6-2-1-.8.9a4.6 4.6 0 01-1.9-1.9l.9-.8-1-2-1.6.9z"/>',
  star: '<path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8L3.5 9.7l5.9-.9z"/>',
  check: '<path d="M4 12.5l5 5L20 6.5"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  chevron: '<path d="M9 5l7 7-7 7"/>',
  truck: '<rect x="1.5" y="6" width="12" height="10" rx="1.5"/><path d="M13.5 9h4l3 3.2V16h-7z"/><circle cx="6" cy="18" r="2"/><circle cx="17" cy="18" r="2"/>',
  store: '<path d="M4 9h16v11H4z"/><path d="M3 9l1.6-5h14.8L21 9"/><path d="M9 20v-6h6v6"/>',
  shield: '<path d="M12 3l7 3v6c0 4.4-3 7.8-7 9-4-1.2-7-4.6-7-9V6z"/><path d="M9 12l2 2 4-4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  filter: '<path d="M4 6h16M7 12h10M10 18h4"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.7 2.5 15 0 18M12 3c-2.5 2.7-2.5 15 0 18"/>',
  logout: '<path d="M9 5H5v14h4"/><path d="M15 12H9M13 8l4 4-4 4"/>'
};

export function icon(name, size) {
  const path = ICONS[name] || '';
  const s = size || 22;
  return `<svg viewBox="0 0 24 24" width="${s}" height="${s}" fill="none" stroke="currentColor"
    stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
}

/* ── Toast ────────────────────────────────────────────────────────────────── */

export function toast(message, kind) {
  const root = document.getElementById('toast-root');
  if (!root) return;
  const node = el(`<div class="toast ${kind || ''}">${esc(message)}</div>`);
  root.appendChild(node);
  setTimeout(() => {
    node.style.opacity = '0';
    node.style.transition = 'opacity .25s';
    setTimeout(() => node.remove(), 260);
  }, kind === 'error' ? 5200 : 3000);
}

/* ── Modal ────────────────────────────────────────────────────────────────── */

let openModals = [];

/**
 * @param {object} opts
 * @param {string} opts.title
 * @param {string} [opts.subtitle]
 * @param {string} opts.body            markup, already escaped by the caller
 * @param {string} [opts.footer]
 * @param {boolean} [opts.dismissible]  false pins it open — used for the one
 *                                      question the storefront cannot work
 *                                      without an answer to
 */
export function modal(opts) {
  const root = document.getElementById('modal-root');
  const backdrop = el(`
    <div class="modal-backdrop" role="dialog" aria-modal="true" aria-label="${esc(opts.title)}">
      <div class="modal">
        <div class="modal-head">
          <div class="grow">
            <h2>${esc(opts.title)}</h2>
            ${opts.subtitle ? `<p class="sub">${esc(opts.subtitle)}</p>` : ''}
          </div>
          ${opts.dismissible === false ? '' :
            `<button class="modal-close" type="button" aria-label="Close">${icon('close', 18)}</button>`}
        </div>
        <div class="modal-body">${opts.body || ''}</div>
        ${opts.footer ? `<div class="modal-foot">${opts.footer}</div>` : ''}
      </div>
    </div>`);

  const controller = {
    node: backdrop,
    body: backdrop.querySelector('.modal-body'),
    foot: backdrop.querySelector('.modal-foot'),
    close() {
      backdrop.remove();
      openModals = openModals.filter(m => m !== controller);
      if (!openModals.length) document.body.style.overflow = '';
      if (opts.onClose) opts.onClose();
    }
  };

  const closeButton = backdrop.querySelector('.modal-close');
  if (closeButton) closeButton.addEventListener('click', controller.close);
  if (opts.dismissible !== false) {
    backdrop.addEventListener('mousedown', event => {
      if (event.target === backdrop) controller.close();
    });
  }

  root.appendChild(backdrop);
  document.body.style.overflow = 'hidden';
  openModals.push(controller);

  const focusable = backdrop.querySelector('input, select, button:not(.modal-close), textarea, a');
  if (focusable) setTimeout(() => focusable.focus(), 60);

  return controller;
}

document.addEventListener('keydown', event => {
  if (event.key !== 'Escape' || !openModals.length) return;
  const top = openModals[openModals.length - 1];
  const closeButton = top.node.querySelector('.modal-close');
  if (closeButton) top.close();
});

/* ── Loading ──────────────────────────────────────────────────────────────── */

export function skeletonGrid(count) {
  const cards = Array.from({ length: count || 8 }, () => '<div class="sk sk-card"></div>').join('');
  return `<div class="grid-products">${cards}</div>`;
}

export function emptyState(iconName, title, message, action) {
  return `<div class="empty">
    ${icon(iconName, 54)}
    <h2>${esc(title)}</h2>
    <p class="muted">${esc(message)}</p>
    ${action || ''}
  </div>`;
}

export function errorState(error, retryLabel) {
  return `<div class="empty">
    ${icon('shield', 54)}
    <h2>That did not load</h2>
    <p class="muted">${esc(error && error.message ? error.message : 'Please try again.')}</p>
    <button class="btn btn-ghost" data-retry>${esc(retryLabel || 'Try again')}</button>
  </div>`;
}

export function stars(rating) {
  const value = Number(rating || 0);
  return `<span class="pill" title="${value.toFixed(1)} out of 5">
    <span style="color:var(--sun-600)">${icon('star', 13)}</span>${value.toFixed(1)}</span>`;
}

export function dateLabel(value) {
  if (!value) return '';
  const date = new Date(value.endsWith && value.endsWith('Z') ? value : value + 'Z');
  if (isNaN(date)) return String(value);
  return date.toLocaleDateString(navigator.language || 'en', {
    day: 'numeric', month: 'short', year: 'numeric'
  });
}

export function titleCase(value) {
  if (!value) return '';
  return String(value).replace(/_/g, ' ').toLowerCase()
    .replace(/(^|\s)\w/g, c => c.toUpperCase());
}

export function setBusy(button, busy, busyLabel) {
  if (!button) return;
  if (busy) {
    button.dataset.label = button.innerHTML;
    button.innerHTML = esc(busyLabel || 'Working…');
    button.setAttribute('aria-disabled', 'true');
  } else {
    if (button.dataset.label) button.innerHTML = button.dataset.label;
    button.removeAttribute('aria-disabled');
  }
}
