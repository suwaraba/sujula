# Sujula clients

Five of them, against one API:

| | |
|---|---|
| `admin/` | the platform's own console — desktop, all 95 `/admin` endpoints |
| `driver-app/` | the courier's phone — an installable PWA, built to work with no signal |
| `vendor-app/` | the seller's shop — web, and the same bundle wrapped for Android and iOS |
| `pickup-app/` | the counter that holds parcels — an installable PWA for a shop tablet |
| *buyer* | not yet built |

Each is a separate application with its own build. They share nothing but the
API, deliberately: a driver's phone and an administrator's desktop have almost
no screen in common, and a shared component library between them would be a
library of things used once.

What is worth copying from `admin/` when the others are written is the shape of
`src/api` — one typed function per endpoint, a client that owns the bearer
token, the CSRF double-submit and the refresh single-flight, and a money module
that formats to each currency's own scale and never does arithmetic.

## Two things every one of these gets wrong the first time

**The dev proxy is not a list of the API's prefixes.** It is a list of the
prefixes *that client* calls. Spring serves the API at the root, so some
endpoints collide with a client's own routes — `vendor-app/` has screens at
`/orders` and `/products`, and proxying either sent deep links to the backend,
which answered 401 instead of opening the app. The same collision reappears in
production if a client is served from the API's origin, so each client's README
says where it must live.

**Ports:** `admin/` 5173, `vendor-app/` 5174, `driver-app/` 5175, `pickup-app/`
5176, so more than one can run against the same backend.
