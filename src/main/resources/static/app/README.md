# The buyer application

The storefront a shopper uses: browse, basket, checkout, orders, and the parcel
tracking page for the person the parcel is actually for.

It is served by the Spring application itself — these files sit in
`src/main/resources/static/app/` and are reachable at `/app/` (with `/`
redirecting there). There is no build step, no bundler and no framework: plain
ES modules, one stylesheet, and the same fetch calls every other client of this
API would make.

## Why no build step

Three reasons, and they are all about this marketplace rather than about taste.

* **It has to load on a bad connection.** The first screen is markup and one
  stylesheet. Nothing has to be downloaded and parsed before a shopper can read
  a price.
* **It has to survive being packaged.** The Android and iOS wrappers load these
  exact files from a local bundle. A toolchain that rewrites paths or assumes a
  server is one more thing that can break between a release and a phone.
* **It has to be readable by whoever maintains it next.** The whole client is
  about two thousand lines in one directory.

## How it is laid out

```
index.html          the shell: two bars, the destination strip, the tab bar
styles.css          the design system — tokens, light and dark, safe areas
js/
  main.js           boot order, routes, the entry question
  config.js         where the API is; the only thing a native build overrides
  api.js            every server call, token refresh, idempotency keys
  state.js          what is remembered, and what survives being closed
  delivery.js       the destination, and the context minted from it
  money.js          amounts at the currency's own scale
  map.js            a slippy map and a draggable pin, with no dependencies
  router.js         hash routing
  ui.js             escaping, icons, modals, toasts
  components/       the chrome, the destination question, cards, the basket
  views/            one file per screen
```

## The two locations, in the client

The rule the whole API is built around applies here too, and the client is
written so that breaking it would be conspicuous.

* **The payer context** — where the buyer is — is `state.payer`, resolved from
  `/geo/resolve-context`. It decides one thing: `state.currencyCode`, the
  currency the buyer is charged in.
* **The delivery context** — where the parcel goes — is `state.place`, and the
  server-side context minted from it in `delivery.js`. It decides what the
  catalogue is ranked by, whether a seller can reach the address, what the leg
  costs, and which counters are offered.

Neither is derived from the other anywhere in this client. A buyer in Madrid
sending a phone to Serrekunda is the case every screen was drawn for.

`state.place` is kept in `localStorage` and the server-side context is minted
from it on demand. The context expires in twelve hours; the answer does not, so
the shopper is asked once rather than daily.

## The destination question

`components/deliveryModal.js` is the first thing a new shopper sees. It asks
for a destination three ways, because in the delivery area they are not
equivalent:

1. **Country and town** — always available, and enough to rank a catalogue.
2. **Find the address** — `/geo/validate-address`, where a geocoder is
   configured and the address is one it knows.
3. **Drag the pin** — the answer for the many places with no street address.
   This is not a fallback: for much of the delivery area it is the real one,
   and it is the coordinate the driver is given.

It is escapable, and asked again on the next visit while it is unanswered. Once
answered it is never asked again — it sits in the strip under the navbar with a
Change link.

## Configuration

Nothing here needs configuring to run against its own server. A native build,
or a deployment serving the client from somewhere else, sets `window.SUJULA_CONFIG`
before `js/main.js` loads:

```html
<script>
  window.SUJULA_CONFIG = {
    apiBase: 'https://api.sujula.gm',
    tileUrl: 'https://tiles.example.com/{z}/{x}/{y}.png',
    tileAttribution: '© Example',
    defaultMapCentre: { lat: 13.4549, lng: -16.5790, zoom: 12 },
    platform: 'android',
    appVersion: '1.2.0'
  };
</script>
```

`platform` and `appVersion` are checked against `minimumAppVersions` in
`/config/public`, which is how a deployment tells an old build to update.

### Map tiles

The default tile URL points at the OpenStreetMap community servers, which are
fine for development and which their operators ask you not to point a product
at. Set `tileUrl` to a provider you pay before any real traffic. If tiles
cannot be loaded at all the picker still works — the pin, the coordinates and
the buttons do not depend on them.

## Packaging it as an Android or iOS app

See `NATIVE.md` in this directory.
