/**
 * Every endpoint, called by everybody who must not reach it.
 *
 * Generated from endpoints.json rather than written out, for the reason that
 * decides whether a matrix is worth having: four hundred and thirty-two
 * endpoints against seven callers is three thousand rows, and the row that
 * matters is the one somebody would have left out. An endpoint added next month
 * is covered here the moment `node capture-openapi.js` is re-run, without
 * anybody remembering that it exists.
 *
 * Three kinds of row, and each asserts something different:
 *
 *   Refused at the gate    The filter chain turns the caller away before any
 *                          controller runs. No body is needed and none is sent,
 *                          because Spring Security decides first — which is
 *                          also why these rows can be generated at all.
 *
 *   Wrong role, own read   A customer reading /vendor/balance passes the gate:
 *                          that surface is authenticated() and the shop is
 *                          resolved from the session. So the assertion is not
 *                          403 but "never 2xx" — whatever the service decides
 *                          to answer, it must not be this person's data.
 *
 *   Open, and open to all  A public endpoint answered without a token. Stated
 *                          as an assertion rather than assumed, because the
 *                          catalogue, the tracking page and the pickup-point
 *                          search are public on purpose and a rule accidentally
 *                          tightened is a storefront nobody can browse.
 *
 * What is NOT here: the split between ADMIN and SUPPORT, and every ownership
 * test. Both are decided inside a controller, after the body has been bound and
 * validated, so both need a body that binds — and a generated body would be
 * asserting about a request nobody makes. Those live in the surface suites.
 */

const { req, folder } = require('../lib/collection');
const { personas } = require('../lib/personas');
const { ruleFor } = require('../lib/access');
const { fill } = require('../lib/paths');
const inventory = require('../endpoints.json');

/**
 * The callers the matrix runs, one per role plus the guest.
 *
 * A second customer would double the run and prove nothing the first one did
 * not: what these rows test is the role. Where a test needs a DIFFERENT person
 * of the SAME role — Aminata asking for Oliver's order — it is written by hand
 * in the suite that owns the row, because only that suite knows which row.
 */
const CALLERS = ['guest', 'fatou', 'binta', 'lamin', 'aminata', 'ebrima', 'isatou'];

/**
 * Surfaces that belong to one role, and the personas who must not read them.
 *
 * `except` names the endpoints under a prefix that carry no data belonging to
 * anybody, so reaching them is not a leak. Each one is listed with its reason:
 * an exception with no reason beside it is how a surface quietly stops being
 * covered.
 */
const OWNED_SURFACES = [
  {
    prefix: '/vendor/',
    belongsTo: 'a seller',
    intruders: ['aminata', 'ebrima', 'isatou'],
    except: {
      // A list of column names for the bulk-import spreadsheet. It describes
      // the file format and contains no seller's products, prices or figures —
      // there is nothing here to withhold from anybody.
      '/vendor/products/import-template': 'a list of spreadsheet column names, and no data at all',
    },
  },
  {
    prefix: '/driver/',
    belongsTo: 'a driver',
    intruders: ['lamin', 'aminata', 'isatou'],
    except: {},
  },
  {
    prefix: '/pickup/',
    belongsTo: 'a counter operator',
    intruders: ['lamin', 'aminata', 'ebrima'],
    except: {},
  },
];

/**
 * Endpoints the matrix does not call, and why each is left out.
 *
 * Every exclusion is a request that would be testing the wrong thing, not one
 * that is inconvenient. They are all covered by hand elsewhere.
 */
const SKIP = [
  {
    // Signing out with a valid token would invalidate a persona every later
    // folder depends on. Covered in the identity suite against a throwaway
    // account registered for the purpose.
    match: (e) => e.path === '/auth/logout' || e.path === '/auth/logout-all',
    because: 'signing a persona out would fail every folder after this one',
  },
  {
    // Deleting an account, permanently. There is no undo and the matrix would
    // be running it against a seeded person as an administrator.
    match: (e) => e.method === 'DELETE' && /^\/(me|api\/users\/[^/]+\/permanent)$/.test(e.path),
    because: 'an erasure the matrix cannot undo',
  },
];

function skipReason(endpoint) {
  const skip = SKIP.find((rule) => rule.match(endpoint));
  return skip ? skip.because : null;
}

/** A name that reads in a run report without the path being guessed at. */
const label = (endpoint) => `${endpoint.method} ${endpoint.path}`;

function refusedAtTheGate(endpoint) {
  const rule = ruleFor(endpoint.method, endpoint.path);
  if (rule.who === 'open') return [];

  return CALLERS
    .filter((key) => {
      const persona = personas[key];
      if (persona.role === null) return true;                 // nobody signed in
      if (rule.who === 'signed-in') return false;             // any account passes
      return !rule.roles.includes(persona.role);
    })
    .map((key) => {
      const persona = personas[key];
      const who = persona.role === null ? 'nobody signed in' : `${key}, ${persona.role}`;
      return req({
        name: `${label(endpoint)} — refused: ${who}`,
        method: endpoint.method,
        path: fill(endpoint.path),
        as: persona.role === null ? undefined : key,
        note: persona.role === null
          ? 'No Authorization header. The filter chain decides before the controller '
            + 'runs, so no body is sent — there is nothing for one to reach.'
          : `Role ${persona.role} against a rule that admits `
            + `${rule.who === 'roles' ? rule.roles.join(' or ') : 'any account'}.`,
        // 401 and 403 both count. This application answers 403 for an
        // anonymous caller throughout, which is a consequence of the chain
        // rather than a decision — what matters is that it is not 200, and
        // pinning the exact code here would make the matrix red the day
        // somebody corrects it.
        status: [401, 403],
      });
    });
}

function wrongRoleReads(endpoint) {
  if (endpoint.method !== 'GET') return [];                   // a write needs a body
  const surface = OWNED_SURFACES.find((s) => endpoint.path.startsWith(s.prefix));
  if (!surface) return [];
  if (surface.except[endpoint.path]) return [];
  if (ruleFor(endpoint.method, endpoint.path).who === 'open') return [];

  return surface.intruders.map((key) => req({
    name: `${label(endpoint)} — ${key} is not ${surface.belongsTo}`,
    method: 'GET',
    path: fill(endpoint.path),
    as: key,
    note: 'This surface is authenticated() at the gate, so this caller gets past the '
        + 'filter chain — the refusal has to come from the service, which resolves '
        + `${surface.belongsTo} from the session and puts them into the query. The `
        + 'assertion is therefore "never a success" rather than a particular code: a '
        + 'not-found and a forbidden are both defensible, and 200 is not.',
    script: `
pm.test(${JSON.stringify(`${label(endpoint)} — ${key} gets no answer`)}, function () {
  pm.expect(pm.response.code, 'answered ' + pm.response.code
    + ', which means this caller reached data that is not theirs')
    .to.be.at.least(400);
});
`,
  }));
}

function openToEveryone(endpoint) {
  if (ruleFor(endpoint.method, endpoint.path).who !== 'open') return [];
  // A write to a public path is a real request with real consequences — a
  // webhook, a checkout callback, a recipient rescheduling a delivery — so the
  // matrix reads rather than writes. The writes are in the surface suites.
  if (endpoint.method !== 'GET') return [];

  return [req({
    name: `${label(endpoint)} — open, and answers with no account`,
    method: 'GET',
    path: fill(endpoint.path),
    note: 'Public on purpose. Browsing precedes signing in on this marketplace, and '
        + 'the recipient reading a tracking page has no account to sign in to — so '
        + 'this is asserted rather than assumed, because a rule tightened by accident '
        + 'is a storefront nobody can reach.',
    script: `
pm.test(${JSON.stringify(`${label(endpoint)} — no credential was demanded`)}, function () {
  pm.expect([401, 403], 'a public endpoint asked for a credential')
    .to.not.include(pm.response.code);
});
`,
  })];
}

const gate = [];
const owned = [];
const open = [];
const skipped = [];

inventory.endpoints.forEach((endpoint) => {
  const reason = skipReason(endpoint);
  if (reason) {
    skipped.push({ endpoint, reason });
    return;
  }
  gate.push(...refusedAtTheGate(endpoint));
  owned.push(...wrongRoleReads(endpoint));
  open.push(...openToEveryone(endpoint));
});

module.exports = folder(
  '90 — The role matrix',
  `Generated from endpoints.json: ${inventory.operations} operations, `
  + `${gate.length + owned.length + open.length} rows.\n\n`
  + `Left out on purpose: ${skipped.map((s) => `${s.endpoint.method} ${s.endpoint.path} (${s.reason})`).join('; ') || 'nothing'}.`,
  [
    folder(
      '90.1 — Refused at the filter chain',
      'No body is sent: Spring Security decides before the controller runs, so there '
      + 'is nothing for a body to reach.',
      gate,
    ),
    folder(
      '90.2 — Past the gate, and still not your data',
      'Surfaces that are authenticated() rather than role-gated. Every caller here '
      + 'gets through the filter chain; the service is what must refuse them.',
      owned,
    ),
    folder(
      '90.3 — Public, and asserted to be',
      'Endpoints a shopper or a recipient reaches with no account at all.',
      open,
    ),
  ],
);
