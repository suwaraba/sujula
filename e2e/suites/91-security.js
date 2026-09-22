/**
 * The security surface, probed the way somebody attacking it would.
 *
 * The other suites ask whether each endpoint does its job. This one asks the
 * opposite question — what an endpoint does for somebody it was not built for —
 * and it is a separate folder because the two go red for different reasons and
 * are read by different people. A catalogue assertion failing is a bug report.
 * One of these failing is an incident.
 *
 * Three kinds of row, and the distinction is the whole point of the folder:
 *
 *   Guarding a property that HOLDS today. Most of these. They exist so that the
 *   day somebody changes an ownership query, relaxes a matcher or adds a
 *   controller method, the run says so — a security property nobody asserts is
 *   one that survives by luck until it does not.
 *
 *   Stating a property that DOES NOT hold today. Deliberately red. Each one is
 *   marked KNOWN GAP in its note, with what is wrong and what the fix is. They
 *   are written as the assertion the fixed application must pass rather than as
 *   a description of the bug, so the row turns green when the fix lands and
 *   nobody has to remember to come back and rewrite it.
 *
 *   Negative controls. A row asserting that a defence which IS in place refuses
 *   the same request — because "the attack failed" means nothing without
 *   evidence that the request would otherwise have worked.
 *
 * This folder runs LAST, after the role matrix. Two of its requests establish a
 * cookie-backed session on purpose, and a JSESSIONID in newman's shared jar
 * would authenticate requests that later suites intend to send as a guest. The
 * last request in the folder throws that session away again.
 */

const { req, folder } = require('../lib/collection');
const seed = require('../lib/seed');

/** Sent where a request must carry the session cookie and nothing else. */
const NO_CSRF = { 'X-XSRF-TOKEN': null };

/** A JWT header and payload that claim what nobody granted. */
const FORGED_ADMIN_CLAIMS = {
  sub: String(seed.users.fatou.id),
  role: 'ADMIN',
  iss: 'sujula-e2e',
  typ: 'access',
  exp: 9999999999,
  sid: seed.sessions.aminataLaptop,
};

module.exports = folder(
  '91 — Security: what the surface does for somebody it was not built for',
  'Access control, session and token handling, CSRF, injection and what the '
  + 'error bodies give away. Rows marked KNOWN GAP in their notes are expected '
  + 'to fail against the application as it stands and describe the fix.',
  [
    // ── Account enumeration ──────────────────────────────────────────────────
    //
    // CLAUDE.md states the rule these rows test: "A not-found is the right
    // answer for someone else's row. 'Forbidden' confirms the row exists, which
    // is itself worth withholding." An endpoint that answers 403 for a row that
    // exists and 404 for one that does not has published exactly that.

    req({
      name: 'A customer asking for a stranger by email learns nothing from the status',
      method: 'GET',
      path: '/api/users/by-email',
      query: { email: seed.users.ndeye.email },
      as: 'oliver',
      note: 'KNOWN GAP. GET /api/users/by-email is annotated @PostAuthorize("hasRole(\'ADMIN\')"), '
          + 'so the method RUNS for any signed-in caller and only the response is withheld. '
          + 'UserServiceImpl.findByEmail has no guard of its own, so it throws '
          + 'ResourceNotFoundException for an address nobody holds and returns normally for one '
          + 'somebody does — which the caller reads as 404 against 403. That is an oracle over '
          + 'the whole user base, usable by any customer with an account. '
          + 'Fix: @PreAuthorize rather than @PostAuthorize, so the method never runs. '
          + 'The next row is the other half of the same probe.',
      status: 403,
    }),

    req({
      name: '…and the same request for an address nobody holds answers identically',
      method: 'GET',
      path: '/api/users/by-email',
      query: { email: 'nobody-holds-this-address@example.invalid' },
      as: 'oliver',
      note: 'KNOWN GAP, and this is the row that makes the previous one an oracle rather than a '
          + 'refusal: it answers 404 where the existing address answered 403. Two statuses for '
          + 'two facts is a yes/no question anybody with an account may ask about any email '
          + 'address. Both rows must answer the SAME status once the annotation is fixed, '
          + 'whichever status that is.',
      status: 403,
    }),

    req({
      name: 'Signing in reveals nothing about whether the account exists',
      method: 'POST',
      path: '/auth/login',
      body: { email: 'nobody-holds-this-address@example.invalid', password: 'Whatever123!' },
      note: 'The same 401 and the same wording as a real account with the wrong password — which '
          + 'the next row checks against. A login that says "no such user" for one and "wrong '
          + 'password" for the other is a membership oracle on the front door, and it needs no '
          + 'account to use.',
      status: 401,
      json: { message: 'Invalid email or password' },
    }),

    req({
      name: '…and neither does the wrong password on an account that does',
      method: 'POST',
      path: '/auth/login',
      body: { email: seed.users.ndeye.email, password: 'DefinitelyNotTheRightOne123!' },
      note: 'The control for the row above. Same status, same message, and deliberately not '
          + 'Modou — his account is locked out by the identity suite, which would answer 403 '
          + 'and make this row pass for the wrong reason.',
      status: 401,
      json: { message: 'Invalid email or password' },
    }),

    // ── Ownership, stated as refusals ────────────────────────────────────────

    req({
      name: "One buyer cannot read another buyer's order, and is not told it exists",
      method: 'GET',
      path: '/orders/' + seed.orders.aminataDelivered,
      as: 'oliver',
      note: 'Order 1403 is Aminata\'s; Oliver holds 1401. 404 rather than 403 is the assertion — '
          + 'the row exists, and a 403 here would confirm that to somebody counting order ids.',
      status: 404,
    }),

    req({
      name: "…nor her invoice, which carries a home address and a phone number",
      method: 'GET',
      path: '/orders/' + seed.orders.aminataDelivered + '/invoice',
      as: 'oliver',
      note: 'The invoice endpoint mints a signed link after proving ownership. Ownership failing '
          + 'must stop the link being minted at all, because the link is then the credential and '
          + 'is deliberately open to anybody holding it.',
      status: 404,
    }),

    req({
      name: "…nor an address out of her address book",
      method: 'GET',
      path: '/me/addresses/' + seed.addresses.aminataHome,
      as: 'oliver',
      note: 'The /me surface takes its subject from the session, so the only thing a path '
          + 'variable can reach here is a row belonging to somebody else. 404, again.',
      status: 404,
    }),

    req({
      name: 'A customer cannot reach a seller\'s takings',
      method: 'GET',
      path: '/vendor/balance',
      as: 'oliver',
      note: '/vendor/** is authenticated() at the gate rather than role-gated, so this request '
          + 'passes the filter chain and is refused inside. Asserted here as well as in the role '
          + 'matrix because what is behind it — revenue, margins, a shop\'s trading position — '
          + 'is worth a row that names it.',
      status: [401, 403, 404],
    }),

    req({
      name: "A customer cannot open a driver's shipment, which names a recipient",
      method: 'GET',
      path: '/driver/shipments/' + seed.shipments.inFlight,
      as: 'oliver',
      note: 'The sharpest data on the platform: a recipient\'s home address and phone number, '
          + 'belonging to somebody who never agreed to be visible to anybody but the person '
          + 'bringing her parcel.',
      status: [401, 403, 404],
    }),

    // ── Writes behind the wrong annotation ───────────────────────────────────
    //
    // UserController guards eleven administrative writes with @PostAuthorize,
    // which runs AFTER the method body. Today UserServiceImpl re-checks inside
    // every one of them and that second guard is what actually refuses these
    // requests — so the rows below pass. They assert the OUTCOME rather than
    // the status for that reason: a status alone cannot tell a refusal apart
    // from a write that happened and was then reported as refused.

    req({
      name: 'A customer blocking another customer is refused',
      method: 'PATCH',
      path: '/api/users/' + seed.users.ndeye.id + '/block',
      as: 'oliver',
      body: { blocked: true, fraud: true },
      note: 'Refused by UserServiceImpl.requireAdmin() rather than by the controller annotation: '
          + '@PostAuthorize runs after the method body, so the annotation alone would report 403 '
          + 'on a block that had already been written. The next row is what proves which of the '
          + 'two actually refused it.',
      status: [401, 403],
    }),

    req({
      name: '…and she can still sign in afterwards, which is how we know nothing was written',
      method: 'POST',
      path: '/auth/login',
      body: { email: seed.users.ndeye.email, password: seed.PASSWORD },
      note: 'The assertion that makes the row above mean something. A blocked account cannot '
          + 'sign in, so a 200 here says the block never reached the database. If this ever goes '
          + 'red while the row above stays green, the service-layer guard has been removed and '
          + '@PostAuthorize is reporting a refusal for a write that happened.',
      status: 200,
      json: { 'tokens.accessToken': { $exists: true } },
    }),

    req({
      name: 'A customer cannot delete another account',
      method: 'DELETE',
      path: '/api/users/' + seed.users.ndeye.id + '/permanent',
      as: 'oliver',
      note: 'Permanent erasure, guarded by @PostAuthorize on the controller and requireAdmin() '
          + 'in the service. The row after this one checks the account is still there.',
      status: [401, 403],
    }),

    req({
      name: '…and the account is still there',
      method: 'POST',
      path: '/auth/login',
      body: { email: seed.users.ndeye.email, password: seed.PASSWORD },
      note: 'Same shape as the block pair: the status of the refusal is not evidence, the state '
          + 'of the world afterwards is.',
      status: 200,
      json: { 'tokens.user.id': seed.users.ndeye.id },
    }),

    // ── Tokens ───────────────────────────────────────────────────────────────

    req({
      name: 'An unsigned token claiming ADMIN is refused',
      method: 'GET',
      path: '/me',
      headers: { Authorization: 'Bearer {{forgedNoneToken}}' },
      note: 'The alg=none attack: a well-formed JWT with the signature segment left empty and '
          + 'the administrator\'s id in the subject. Nimbus\'s MACVerifier declares only the HS '
          + 'family, so the algorithm never matches and the token never verifies — but this is '
          + 'the first thing anybody tries against a JWT surface and it is worth a row that '
          + 'fails loudly if the verifier is ever swapped for a permissive one.',
      preScript: `
function b64(o) {
  return CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse(JSON.stringify(o)))
    .replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '');
}
pm.collectionVariables.set('forgedNoneToken',
  b64({ alg: 'none', typ: 'JWT' }) + '.' + b64(${JSON.stringify(FORGED_ADMIN_CLAIMS)}) + '.');
`,
      status: [401, 403],
    }),

    req({
      name: 'A token with one character changed in the signature is refused',
      method: 'GET',
      path: '/me',
      headers: { Authorization: 'Bearer {{tamperedToken}}' },
      note: 'Oliver\'s real token with its last signature character rotated. Proves the '
          + 'signature is actually verified rather than the claims merely parsed — a decoder '
          + 'that reads the payload without checking the MAC passes every other test in this '
          + 'folder and fails this one.',
      preScript: `
const real = pm.collectionVariables.get('tokenOliver') || '';
const last = real.slice(-1);
pm.collectionVariables.set('tamperedToken',
  real.slice(0, -1) + (last === 'A' ? 'B' : 'A'));
`,
      status: [401, 403],
    }),

    req({
      name: 'A refresh token is not accepted where an access token belongs',
      method: 'GET',
      path: '/me',
      headers: { Authorization: 'Bearer {{tokenOliverRefresh}}' },
      note: 'The two credentials are deliberately different kinds of thing — one a signed JWT, '
          + 'the other an opaque random string — and the typ claim is checked on top. A refresh '
          + 'token is long-lived, so accepting one as a bearer credential would hand out a '
          + 'session that never lapses.',
      status: [401, 403],
    }),

    req({
      name: 'An empty bearer is not a credential',
      method: 'GET',
      path: '/me',
      headers: { Authorization: 'Bearer ' },
      note: 'The shape a collection produces when a token variable was never captured, and the '
          + 'shape a client produces when a login silently failed. It must be treated as no '
          + 'credential rather than as an empty one that matches an empty stored value.',
      status: [401, 403],
    }),

    // ── Telling "who are you" apart from "not you" ───────────────────────────
    //
    // BEFORE the cookie-session block below, for the same reason the token rows
    // are: these send no credential at all, and a JSESSIONID sitting in newman's
    // jar authenticates them into a 200. Found exactly that way — five rows
    // asserting 401 answered 200 with Oliver's profile.
    //
    // Two different instructions to a client, and one status cannot carry both.
    // 401 means sign in and try again; 403 means signing in will not help. The
    // split is made by ExceptionTranslationFilter: an anonymous request is sent
    // to the authentication entry point, an authenticated one to the
    // access-denied handler. These rows pin each side so the two cannot quietly
    // collapse back into one.

    req({
      name: 'A protected endpoint with no credentials at all answers 401',
      method: 'GET',
      path: '/me',
      note: 'No Authorization header and no session. The caller has not said who they are, so '
          + 'the answer is "say who you are" rather than "you are not allowed" — a client that '
          + 'reads 403 here has been told that signing in will not help, which is the opposite '
          + 'of true. Before the entry point was configured this answered 403 everywhere, which '
          + 'is what sent somebody to a browser address bar wondering what permission they were '
          + 'missing. The body is asserted too: a refusal made in the filter chain never reaches '
          + 'a @ControllerAdvice, so it used to come back empty while every other failure on this '
          + 'application speaks JSON — a client parsing the documented error shape got nothing.',
      status: 401,
      json: {
        status: 401,
        error: 'Unauthorized',
        message: { $exists: true },
        timestamp: { $exists: true },
      },
    }),

    req({
      name: 'A credential that does not verify is 401, not 403',
      method: 'GET',
      path: '/admin/users',
      headers: { Authorization: 'Bearer not.a.real.token' },
      note: 'The distinction the status is read FROM the security context to get right, rather '
          + 'than from the Authorization header: a header carrying a forged or expired token is '
          + 'a caller who has not identified themselves. Answering 403 here would tell them '
          + 'their credential was accepted and only their role fell short, which is both wrong '
          + 'and a hint worth withholding.',
      status: 401,
    }),

    req({
      name: 'A GET on a POST-only public endpoint answers 401 rather than admitting it exists',
      method: 'GET',
      path: '/auth/register',
      note: 'The permitAll rule for /auth/register is scoped to POST, so a GET falls through to '
          + 'the /auth/** catch-all and is refused unauthenticated. Not 405: Spring Security '
          + 'decides before the dispatcher ever looks for a handler, so the application never '
          + 'discovers there is no GET mapping. Worth a row because this is exactly what a '
          + 'browser address bar produces, and the answer should read as "you are not signed in" '
          + 'rather than as a permissions problem.',
      status: 401,
    }),

    req({
      name: 'The same endpoint by POST is open to anybody, which is the point of the rule',
      method: 'POST',
      path: '/auth/register',
      body: {
        email: 'security-folder-{{$timestamp}}@example.invalid',
        password: 'Sujula123!',
        firstName: 'Security',
        lastName: 'Folder',
        phone: '+2203100999',
      },
      note: 'The control for the row above: registration IS reachable without a credential, by '
          + 'the method it is published under. A fresh address each run, because the collection '
          + 'creates no fixtures it then depends on — this account is written and never read.',
      status: [200, 201],
      json: { accessToken: { $exists: true } },
    }),

    req({
      name: 'A signed-in caller reaching past their role answers 403, not 401',
      method: 'GET',
      path: '/actuator/prometheus',
      as: 'oliver',
      note: 'The other half of the split, and the reason the entry point cannot simply answer '
          + '401 everywhere. Oliver said who he is and was believed; what he lacks is the role. '
          + 'Telling him to sign in would send him round a loop he cannot leave.',
      status: 403,
      json: { status: 403, error: 'Forbidden', message: { $exists: true } },
      bodyExcludes: [
        { value: 'ADMIN', why: 'naming the role that would open it describes the way in' },
        { value: 'prometheus', why: 'nor should a refusal confirm what it was guarding' },
      ],
    }),

    req({
      name: 'A refusal a service raises itself keeps its own status',
      method: 'POST',
      path: '/auth/login',
      body: { email: seed.users.sulayman.email, password: seed.PASSWORD },
      note: 'Sulayman is blocked and his password is correct. This 403 comes from the '
          + 'application rather than from the filter chain — his credentials were read and found '
          + 'wanting, which is not the same as not presenting any — so the entry point must '
          + 'leave it alone. Asserted here because an entry point applied too widely would turn '
          + 'this into a 401 and tell a blocked user to try signing in again.',
      status: 403,
      json: { message: { $matches: 'blocked' } },
      absent: ['tokens', 'accessToken'],
    }),

    // ── Cookie sessions and CSRF ─────────────────────────────────────────────
    //
    // SecurityConfig exempts /auth/**, /me/**, /checkout/** and others from
    // CSRF, and states the reason in a comment: "an Authorization header is
    // never attached on its own, so there is nothing for a forged request to
    // ride." That is true of the bearer surface. It is not true of this
    // application, because POST /api/users/login establishes an ordinary
    // Spring Security session and the cookie it leaves IS attached on its own.

    req({
      name: 'POST /api/users/login leaves a session cookie behind',
      method: 'POST',
      path: '/api/users/login',
      body: { email: seed.users.oliver.email, password: seed.PASSWORD },
      note: 'Establishes the session the next four rows are about. Not an attack in itself — the '
          + 'endpoint is a documented cookie login — but it is the fact that makes the CSRF '
          + 'exemptions reachable, and it is asserted here so that a deployment which removes '
          + 'the cookie login sees these rows change together.',
      status: 200,
      json: { id: seed.users.oliver.id },
      script: `
const cookie = pm.cookies.get('JSESSIONID');
pm.test('POST /api/users/login — a JSESSIONID was issued', function () {
  pm.expect(cookie, 'no JSESSIONID; the cookie-session login may have been removed').to.be.a('string');
});

// KNOWN GAP. The session cookie is the credential for every CSRF-exempt write
// below, and it goes out as "JSESSIONID=…; Path=/; HttpOnly" — no SameSite and
// no Secure. HttpOnly is right and keeps it away from script; the two that are
// missing are the ones that decide whether a cross-site request carries it at
// all, and whether it may travel over plaintext. What stops the forgeries in a
// browser today is therefore the browser's own SameSite=Lax default rather
// than anything this application states.
// Fix: server.servlet.session.cookie.same-site=strict and
// server.servlet.session.cookie.secure, driven by the same flag as
// sujula.cart.cookie-secure. The same applies to the XSRF-TOKEN cookie, which
// is checked alongside it here whenever the response re-issues one.
const setCookies = pm.response.headers.all()
  .filter(function (h) { return h.key.toLowerCase() === 'set-cookie'; })
  .map(function (h) { return h.value; });

function attributes(name) {
  return setCookies.filter(function (v) { return v.indexOf(name + '=') === 0; }).join(' | ');
}

const session = attributes('JSESSIONID');
pm.test('JSESSIONID — the response issued one, so the checks below have something to read', function () {
  pm.expect(session, 'no Set-Cookie for JSESSIONID on the login response').to.not.equal('');
});
pm.test('JSESSIONID — SameSite is stated rather than left to the browser', function () {
  pm.expect(/SameSite=/i.test(session), 'Set-Cookie was: ' + session).to.be.true;
});
pm.test('JSESSIONID — Secure is stated', function () {
  pm.expect(/;\\s*Secure/i.test(session), 'Set-Cookie was: ' + session).to.be.true;
});
pm.test('JSESSIONID — HttpOnly is stated', function () {
  pm.expect(/;\\s*HttpOnly/i.test(session), 'Set-Cookie was: ' + session).to.be.true;
});
`,
    }),

    req({
      name: 'That session alone authenticates /me, with no bearer token anywhere',
      method: 'GET',
      path: '/me',
      note: 'No persona, so no Authorization header: the cookie from the previous request is the '
          + 'only credential. A 200 here is what turns every CSRF-exempt write below into a '
          + 'forgeable one.',
      status: 200,
      json: { 'profile.id': seed.users.oliver.id },
    }),

    req({
      name: 'With a session live, a revoked bearer token is not what decides the request',
      method: 'GET',
      path: '/me',
      headers: { Authorization: 'Bearer {{tamperedToken}}' },
      note: 'JwtAuthenticationFilter returns early when the context already carries an '
          + 'authentication — "an existing authentication wins" — so with the cookie session '
          + 'above in place, a garbage bearer token is never even parsed and the request '
          + 'succeeds on the cookie. Deliberate, and not a hole on its own, but it is why the '
          + 'token rows in this folder run BEFORE the session is established: run after, they '
          + 'measure the cookie and pass whatever the token says. It also means revoking a '
          + 'token does not end a session established the other way.',
      status: 200,
      json: { 'profile.id': seed.users.oliver.id },
    }),

    req({
      name: 'A cookie-authenticated write on /me is refused without a CSRF token',
      method: 'POST',
      path: '/me/export',
      headers: NO_CSRF,
      body: {},
      note: 'KNOWN GAP. /me/** is on the CSRF ignore list, so this request — session cookie, no '
          + 'token, no Authorization header, exactly what a cross-site form produces — is '
          + 'accepted and queues an export of the caller\'s personal data. The same holds for '
          + 'DELETE /me, DELETE /auth/mfa, POST /auth/logout-all and the whole of /checkout/**. '
          + 'In a browser the JSESSIONID cookie is sent without SameSite, so what stops the '
          + 'attack today is the browser\'s Lax default rather than anything this application '
          + 'does. Fix: take /auth/**, /me/** and /checkout/** off ignoringRequestMatchers, or '
          + 'stop issuing a cookie session and make the bearer token the only credential.',
      status: 403,
    }),

    req({
      name: 'Nor is a cookie-authenticated sign-out-everywhere',
      method: 'POST',
      path: '/auth/logout-all',
      headers: NO_CSRF,
      body: {},
      note: 'KNOWN GAP, same cause as the row above and a sharper consequence: a forged request '
          + 'signs somebody out of every device they own. Denial of service rather than '
          + 'disclosure, but it needs nothing from the attacker except that the victim visit a '
          + 'page.',
      status: 403,
    }),

    req({
      name: 'A CSRF-protected path refuses the same cookie without a token',
      method: 'POST',
      path: '/api/cart/items',
      headers: NO_CSRF,
      body: { variantId: seed.variants.phone, quantity: 1 },
      note: 'The negative control, and the reason the two rows above are findings rather than '
          + 'guesses: /api/cart is NOT on the ignore list, so the identical request shape is '
          + 'refused there. The defence works; it is the exemption list that is wrong.',
      status: 403,
    }),

    // ── The machine surfaces ─────────────────────────────────────────────────

    req({
      name: 'The payment callback refuses a caller with no shared secret',
      method: 'POST',
      path: '/api/payments/callback',
      body: { transactionId: 'forged-no-secret', status: 'PAID', amount: 1 },
      note: 'The endpoint that marks money received. Refused without the secret — but note what '
          + 'the secret is: a fixed string in a header, not a signature over the body and not '
          + 'bound to a timestamp. Anybody who sees one valid callback can replay it, and the '
          + 'header is a bearer credential that leaks into any proxy log it passes. '
          + '/webhooks/psp/{provider} does this properly and is what new providers should use.',
      status: [401, 403],
    }),

    req({
      name: 'A wrong shared secret is refused, and the answer says nothing else',
      method: 'POST',
      path: '/api/payments/callback',
      headers: { 'X-Sujula-Signature': 'not-the-configured-secret' },
      body: { transactionId: 'forged-bad-secret', status: 'PAID', amount: 1 },
      note: 'Refused, and the body must not distinguish "wrong secret" from "no secret '
          + 'configured" — telling somebody probing which of the two they got right tells them '
          + 'how to make progress.',
      status: [401, 403],
      bodyExcludes: [
        { value: 'callback-secret', why: 'a refusal must not name the property that holds the secret' },
      ],
    }),

    req({
      name: 'The metrics scrape is not public',
      method: 'GET',
      path: '/actuator/prometheus',
      note: 'Request counts, error rates and timings per endpoint: enough to tell somebody '
          + 'outside when the platform is struggling and which path to press on. Asserted from '
          + 'this folder as well as from 00 because the actuator exposure list and the filter '
          + 'rule are two separate settings and either one alone is not the lock.',
      status: [401, 403],
    }),

    // ── What the answers give away ───────────────────────────────────────────

    req({
      name: 'A bad path parameter is reported without a stack trace',
      method: 'GET',
      path: '/api/products/not-a-number',
      note: 'The commonest accidental disclosure on a Spring application: a type conversion that '
          + 'escapes the handler and returns a trace naming every framework, version and package '
          + 'in the process. A version list is a shopping list for anybody matching it against '
          + 'published advisories.',
      status: 400,
      bodyExcludes: [
        { value: 'org.springframework', why: 'a stack trace names the framework and its version' },
        { value: 'com.sujula', why: 'nor should it map the internal package layout' },
        { value: 'Caused by', why: 'a chained exception is a stack trace by another name' },
        { value: 'jdbc', why: 'and a datasource must never reach an error body' },
      ],
    }),

    req({
      name: 'A quote and a comment in a search term are data, not syntax',
      method: 'GET',
      path: '/search',
      query: { q: "' OR 1=1--" },
      note: 'The browse query is native SQL assembled from constants with every filter bound as '
          + 'a named parameter, so this is a search for a string nobody sells rather than a '
          + 'predicate. Asserted rather than assumed: the file it lives in concatenates SQL '
          + 'fragments, and the day somebody concatenates a value instead of binding it this row '
          + 'is what notices.',
      status: 200,
      bodyExcludes: [
        { value: 'SQLGrammarException', why: 'a syntax error means the term reached the parser' },
        { value: 'SQL statement', why: 'as does a JDBC error surfacing in the body' },
      ],
    }),

    req({
      name: 'An absurd page size is refused rather than served',
      method: 'GET',
      path: '/api/products/featured',
      query: { size: 100000 },
      note: 'An unbounded page size is two problems at once: a way to pull the whole table '
          + 'through one request, and a way to make the process allocate until it stops. Most '
          + 'controllers clamp; /admin/audit-log, /api/users and /api/vendors build their '
          + 'PageRequest without one, which is worth fixing even though all three are behind a '
          + 'role check.',
      status: 400,
    }),

    req({
      name: 'Responses carry the framing and sniffing headers',
      method: 'GET',
      path: '/config/public',
      note: 'X-Frame-Options and X-Content-Type-Options come from Spring Security\'s defaults '
          + 'and are asserted so that a later customisation of the header writers cannot drop '
          + 'them quietly. Referrer-Policy is NOT a default and is checked separately below.',
      status: 200,
      header: {
        'X-Frame-Options': 'DENY',
        'X-Content-Type-Options': 'nosniff',
      },
    }),

    req({
      name: 'Responses state a referrer policy',
      method: 'GET',
      path: '/config/public',
      note: 'KNOWN GAP. Nothing sets Referrer-Policy, so a browser follows its own default. It '
          + 'matters here because several URLs on this platform ARE credentials — the invoice '
          + 'link, the parcel label QR, /parcels/{trackingCode} — and a referrer header carries '
          + 'the whole path to whatever third party the page loads next. '
          + 'Fix: headers(h -> h.referrerPolicy(SAME_ORIGIN)) in SecurityConfig. '
          + 'Strict-Transport-Security is deliberately not asserted here: Spring emits it only '
          + 'on a secure request and this profile is plain HTTP, so the assertion would be '
          + 'about the test environment rather than about the application.',
      status: 200,
      header: { 'Referrer-Policy': { $exists: true } },
    }),

    // ── The recipient surface, which is open by design ───────────────────────

    req({
      name: 'A tracking page is worth nothing to a stranger holding the code',
      method: 'GET',
      path: '/parcels/' + seed.parcelCodes.inFlight,
      note: 'Open on purpose — the recipient has a phone number and nothing else — so the '
          + 'defence is not authentication but what the page is allowed to contain. A first '
          + 'name and a town, and none of the things below.',
      status: 200,
      bodyExcludes: [
        { value: seed.users.oliver.email, why: 'the payer\'s email is not the recipient\'s business, nor a stranger\'s' },
        { value: '+2207', why: 'no phone number, in any form' },
        { value: '+44', why: 'nor the payer\'s' },
        { value: 'GBP', why: 'nor what anybody paid' },
      ],
    }),

    req({
      name: 'A guessed tracking code is a not-found, and is not told it was close',
      method: 'GET',
      path: '/parcels/PARCAAAAAAAAAAAA',
      note: 'Sixteen characters from an unambiguous alphabet is the whole of the credential on '
          + 'this surface, so the only thing the application owes a wrong one is 404 — no hint '
          + 'about length, checksum, or how many parcels are live.',
      status: 404,
    }),

    req({
      name: 'A recipient instruction without the six digits is refused',
      method: 'POST',
      path: '/parcels/' + seed.parcelCodes.inFlight + '/reschedule',
      body: { code: '000000', preferredWindow: 'AFTERNOON' },
      note: 'The tracking code reads the page; the emailed six digits authorise a change. Two '
          + 'credentials rather than one, which is what stops somebody who found a code in a '
          + 'photograph redirecting a parcel. Five wrong guesses burn the code and only three '
          + 'may be issued an hour, so the search space is not brute-forceable.',
      status: 400,
    }),

    // ── Leave the world as it was found ──────────────────────────────────────

    req({
      name: 'The cookie session opened above is thrown away',
      method: 'POST',
      path: '/api/users/logout',
      note: 'Housekeeping with a reason: newman keeps one cookie jar for the whole run, so a '
          + 'JSESSIONID left behind would authenticate requests a later folder means to send as '
          + 'a guest — and a guest request that quietly succeeds is a green row that tested '
          + 'nothing. Ends the folder, and the run, with no ambient credential.',
      status: [200, 204],
    }),
  ],
);
