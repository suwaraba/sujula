# Sujula clients

Five of them, against one API:

| | |
|---|---|
| `admin/` | the platform's own console — desktop, all 95 `/admin` endpoints |
| *buyer* | not yet built |
| *vendor* | not yet built |
| *driver* | not yet built |
| *pickup* | not yet built |

Each is a separate application with its own build. They share nothing but the
API, deliberately: a driver's phone and an administrator's desktop have almost
no screen in common, and a shared component library between them would be a
library of things used once.

What is worth copying from `admin/` when the others are written is the shape of
`src/api` — one typed function per endpoint, a client that owns the bearer
token, the CSRF double-submit and the refresh single-flight, and a money module
that formats to each currency's own scale and never does arithmetic.
