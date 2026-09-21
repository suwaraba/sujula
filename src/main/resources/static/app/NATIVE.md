# Packaging the buyer application for Android and iOS

The client in this directory is already a mobile application in every respect
but the store listing: it is built mobile-first, it has a bottom tab bar, it
respects the notch and the home indicator, every tappable thing is at least
44px, and it routes in the fragment so a wrapped WebView with no server behind
it still works on every screen.

What follows turns it into an `.apk`/`.aab` and an `.ipa`. Nothing in this
directory has to change.

## Capacitor

Capacitor wraps a directory of web assets. It wants that directory to contain
an `index.html`, which this one does.

```bash
mkdir sujula-app && cd sujula-app
npm init -y
npm install @capacitor/core @capacitor/cli @capacitor/android @capacitor/ios

# Point Capacitor at a copy of this directory.
cp -r ../src/main/resources/static/app ./www
```

`capacitor.config.json`:

```json
{
  "appId": "gm.sujula.app",
  "appName": "Sujula",
  "webDir": "www",
  "server": { "androidScheme": "https" },
  "android": { "allowMixedContent": false }
}
```

Then, before building, write the API base into the copied shell — the bundled
app has no origin of its own to infer one from. Add this to `www/index.html`
immediately above the `<script type="module" src="js/main.js">` line:

```html
<script>
  window.SUJULA_CONFIG = {
    apiBase: 'https://api.sujula.gm',
    platform: 'android',
    appVersion: '1.0.0'
  };
</script>
```

Use `platform: 'ios'` for the iOS build. Both are checked against
`minimumAppVersions` from `/config/public`, so a deployment can tell an old
install to update.

```bash
npx cap add android
npx cap add ios
npx cap sync
npx cap open android     # builds in Android Studio
npx cap open ios         # builds in Xcode
```

## What the server needs for a native build

The web build is served from the same origin as the API, so nothing is needed.
A packaged app is not, and two things follow:

* **CORS.** The API must allow the origins Capacitor uses —
  `https://localhost` on Android and `capacitor://localhost` on iOS — with
  credentials, and must allow the `Authorization`, `Content-Type`,
  `Idempotency-Key` and `X-XSRF-TOKEN` headers.
* **CSRF.** The client authenticates with a bearer token and echoes the
  `XSRF-TOKEN` cookie back when the browser has one. A packaged app may not
  have a cookie jar the WebView shares with `fetch`, which affects the handful
  of state-changing paths that are not already on the CSRF ignore list in
  `SecurityConfig` — cancelling an order, confirming receipt, posting a
  review. Either add those paths to that list (they are bearer-authenticated,
  which is the reason the auth and basket paths are already there) or keep the
  cookie flowing. The read paths, the basket, checkout and sign-in all work as
  they are.

Neither is a change this directory can make, and neither is needed for the web
build, so both are left as a deployment decision.

## Permissions

* **Location** is asked for only when the shopper taps "Use my current
  position" in the destination picker, and the screen says plainly that where
  they are is usually not where the parcel is going. Declare it as an optional,
  when-in-use permission.
* **Nothing else.** No contacts, no storage, no camera.

## What is deliberately not here

No push notifications and no deep links. Both are worth having and both need a
server-side decision — a push credential, an associated-domains file — that
belongs with the deployment rather than with the client.
