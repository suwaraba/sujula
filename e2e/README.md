# Endpoint collection

Every endpoint this application serves, called by every kind of person who can
call it, against a real running server.

    ./run.sh

That starts a server, waits for it, runs the collection and stops it again.
Nothing else needs installing: the profile it boots under uses an in-memory
database, so there is no MySQL to set up, no credentials to obtain, and no
Google or Cloudflare key to find.

To use the Postman app instead, start the server yourself:

    mvn -o spring-boot:run -Dspring-boot.run.profiles=e2e

and import `sujula.postman_collection.json` together with
`sujula.postman_environment.json`.

## What it runs against

`application-e2e.properties` boots the whole application against H2 in MySQL
mode and loads `src/main/resources/db/seed/dev-seed.sql` into it. So the world
the collection asserts about is the world that file describes: Lamin's shop on
Kairaba Avenue, Awa's cloth in Ziguinchor, Oliver in London paying for a parcel
going to Aminata's sister, Ebrima carrying it and Isatou holding one at the
Westfield counter.

Every id, code, password and tracking number the collection sends is read out
of that file. Nothing is invented, and the collection creates no fixtures of its
own except the throwaway account it registers to test signing out. That is the
point: a collection that sets up its own world only proves the application is
consistent with itself, while one that asserts against a seed written by hand
notices the day an answer changes.

All seeded passwords are `Sujula123!`.

## Start the server fresh for each run

The seed holds one-shot credentials — refresh tokens that rotate when used, a
phone challenge that is consumed when confirmed, stock a checkout decrements —
so a second run against an already-used server is a run against a different
world. `run.sh` restarts the server every time, which costs about twelve
seconds because the database is in memory.

Where a one-shot fixture is unavoidable the collection asserts *both* outcomes
rather than only the fresh one: on a used server it holds the application to
refusing the spent credential, which is a property worth checking anyway.

## Layout

| | |
|---|---|
| `run.sh` | boots a server, runs the collection, stops it |
| `build.js` | assembles the collection from `suites/` |
| `suites/*.js` | the requests and what must be true of each answer |
| `suites/91-security.js` | the same surface, probed the way somebody attacking it would |
| `lib/seed.js` | every id and code the seed contains, named once |
| `lib/personas.js` | who makes each request |
| `lib/collection.js` | turns a declarative request into a Postman item |
| `lib/helpers.js` | the assertion helpers, shared into every test script |
| `sujula.postman_collection.json` | the built collection — committed, importable |

Edit a suite, run `node build.js`, and the collection file is rewritten. It is
committed rather than generated on demand so that somebody who has never run
Node can still import it.

## The folders run in order

Folder `00` captures the CSRF token that every write outside the bearer-token
surface needs, and folder `01` captures an access token for each seeded person.
Running a later folder on its own sends an empty `Authorization` header and
everything in it is refused.

## Folder 91 is expected to be red

Every other folder asserts that the application does its job, and a failure
there is a bug report. Folder 91 asserts what the application does for somebody
it was not built for, and some of its rows are written as the assertion a fixed
application must pass rather than as a description of how it behaves today.
Those rows say `KNOWN GAP` in their note, with what is wrong and what the fix
is, and they turn green when the fix lands — so nobody has to remember to come
back and rewrite them.

Against the application as it stands, six assertions in that folder fail:

| What fails | Why |
|---|---|
| `/api/users/by-email` answers 403 for an address somebody holds and 404 for one nobody does | `@PostAuthorize` lets the method run before the check, and the service has no guard of its own — so the status is an oracle over the whole user base, usable by any signed-in customer |
| Two cookie-authenticated writes on `/me` and `/auth` are accepted with no CSRF token | those prefixes are on `ignoringRequestMatchers`, and `POST /api/users/login` issues a session cookie that rides a cross-site request on its own |
| `JSESSIONID` states neither `SameSite` nor `Secure` | what stops the forgery above in a browser today is the browser's Lax default, not anything this application says |
| No `Referrer-Policy` on any response | several URLs here *are* credentials — the invoice link, the parcel QR, `/parcels/{code}` — and a referrer header carries the whole path off-site |

Run that folder on its own with:

    ./run.sh --folder "91 — Security: what the surface does for somebody it was not built for"

It needs folders 00 and 01 to have run first for the CSRF token and the access
tokens, so prefer a whole run while the list above is still the expected result.
