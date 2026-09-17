/**
 * The two endpoints that answer before anything else does, and the reference
 * data a client needs before it can draw a screen.
 *
 * This folder runs first for a practical reason as well as a logical one: the
 * CSRF token is captured here. The application issues it as a cookie on any
 * GET, and every write outside the bearer-token surface has to send it back as
 * a header — so if this folder does not run, four hundred later requests
 * answer 403 and the report says nothing useful about any of them.
 */

const { req, folder } = require('../lib/collection');

module.exports = folder(
  '00 — System, and the token everything else needs',
  'Liveness, readiness, public configuration, and the CSRF handshake.',
  [
    req({
      name: 'Liveness answers without touching anything outside the JVM',
      path: '/health/liveness',
      note: 'An orchestrator restarts a container on this. It must not consult the '
          + 'database: a liveness probe that fails when the database is briefly away '
          + 'restarts every instance at once and turns a recoverable outage into an '
          + 'outage with no instances left.',
      status: 200,
      json: {
        status: 'UP',
        uptimeSeconds: { $type: 'number' },
      },
      absent: ['checks'],
    }),

    req({
      name: 'Readiness does consult the database, and says what it found',
      path: '/health/readiness',
      note: 'The opposite decision from liveness, for the opposite reason: readiness '
          + 'decides whether to send this instance traffic, and an instance whose '
          + 'database has gone should be taken out of rotation rather than left '
          + 'answering with errors.',
      status: 200,
      json: {
        status: 'UP',
        'checks.database.status': 'UP',
        'checks.database.tookMs': { $type: 'number' },
      },
    }),

    req({
      name: 'Neither probe needs an account',
      path: '/health/readiness',
      note: 'Stated as its own request because it is a security property rather than '
          + 'an incidental one. An orchestrator has no credentials, and a probe that '
          + 'required them would report every instance unhealthy.',
      status: 200,
    }),

    req({
      name: 'Public configuration, and the CSRF token captured from it',
      path: '/config/public',
      note: 'An allow-list assembled by hand rather than a filtered view of '
          + 'configuration — which is one careless rename away from publishing a '
          + 'secret. This request is also where the collection picks up its CSRF '
          + 'token: the application issues one as a cookie on any GET.',
      status: 200,
      json: {
        baseCurrency: 'GMD',
        baseCountry: 'GM',
        defaultLocale: 'en-GM',
        fxQuoteTtlSeconds: { $type: 'number' },
        'features.multiCurrency': true,
        'features.pickupPoints': true,
        'support.email': { $exists: true },
      },
      bodyExcludes: [
        { value: 'field-encryption', why: 'configuration is never echoed, only an allow-list' },
        { value: 'webhook', why: 'a signing secret must not be reachable from a public endpoint' },
        { value: 'jdbc:', why: 'nor a datasource URL' },
      ],
      script: `
const cookie = pm.cookies.get('XSRF-TOKEN');
pm.test("Public configuration — the CSRF cookie was issued", function () {
  pm.expect(cookie, 'no XSRF-TOKEN cookie; every write after this will answer 403').to.be.a('string');
});
pm.collectionVariables.set('csrfToken', cookie);
`,
    }),

    req({
      name: 'Countries: buying and shipping are separate flags',
      path: '/countries',
      note: 'GB buys and does not ship. A buyer in London orders for delivery to '
          + 'Serrekunda, and one "supported" boolean could not express that — which '
          + 'is C1 showing up in a piece of reference data.',
      status: 200,
      json: { base: 'GM', 'countries.0.code': 'GM' },
      script: `
const H2 = eval(pm.collectionVariables.get('helpers'));
const list = H2.json(pm.response).countries;
const gb = list.find(function (c) { return c.code === 'GB'; });
const gm = list.find(function (c) { return c.code === 'GM'; });
pm.test("Countries — Great Britain buys but does not ship", function () {
  pm.expect(gb, 'GB missing from the country list').to.be.an('object');
  pm.expect(gb.buy, 'GB should be able to buy').to.equal(true);
  pm.expect(gb.ship, 'GB should not be a shipping destination').to.equal(false);
});
pm.test("Countries — The Gambia does both", function () {
  pm.expect(gm.buy).to.equal(true);
  pm.expect(gm.ship).to.equal(true);
});
`,
    }),

    req({
      name: 'Locales carry rtl, so a client knows to flip',
      path: '/locales',
      status: 200,
      json: { defaultLocale: 'en-GM' },
      script: `
const H2 = eval(pm.collectionVariables.get('helpers'));
const locales = H2.json(pm.response).locales;
pm.test("Locales — Arabic is marked right to left", function () {
  const ar = locales.find(function (l) { return l.tag === 'ar'; });
  pm.expect(ar, 'Arabic missing from the locale list').to.be.an('object');
  pm.expect(ar.rtl).to.equal(true);
});
pm.test("Locales — every entry answers the question", function () {
  locales.forEach(function (l) {
    pm.expect(l.rtl, l.tag + ' has no rtl flag').to.be.a('boolean');
  });
});
`,
    }),

    req({
      name: 'The metrics scrape is not public',
      path: '/actuator/prometheus',
      note: 'It carries request counts, error rates and timings per endpoint — enough '
          + 'to tell a stranger when the platform is struggling and which path to '
          + 'press on.',
      status: 403,
    }),

    req({
      name: 'Nor is the actuator health endpoint, which is a different thing from /health',
      path: '/actuator/health',
      note: 'The two are easy to confuse and are deliberately not the same: /health/* '
          + 'is the public probe pair and answers with nothing a stranger can use, '
          + 'while the actuator tree is administrator-only.',
      status: 403,
    }),

    req({
      name: 'A path that does not exist is a 404 rather than anything more helpful',
      path: '/this-endpoint-has-never-existed',
      status: [401, 403, 404],
      note: 'Under this filter chain an unmatched path falls to anyRequest().authenticated(), '
          + 'so an anonymous caller gets 403 rather than 404 — which is the right way '
          + 'round: enumerating which paths exist should not be free.',
    }),
  ],
);
