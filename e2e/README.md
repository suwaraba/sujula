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
