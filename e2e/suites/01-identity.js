/**
 * Getting in, and being refused.
 *
 * Every later folder depends on this one: the tokens it captures are what every
 * other request authenticates with. It is also the folder that covers the half
 * of authentication nobody writes tests for — the accounts that must NOT get a
 * token, the refresh token that must be treated as stolen, the second factor
 * that must not be steppable-around.
 */

const { req, folder } = require('../lib/collection');
const { personas, refused } = require('../lib/personas');
const totp = require('../lib/totp');
const seed = require('../lib/seed');

/** One sign-in, for a persona whose account has no second factor. */
const signIn = (key) => {
  const persona = personas[key];
  return req({
    name: `Sign in: ${persona.label}`,
    method: 'POST',
    path: '/auth/login',
    body: { email: persona.user.email, password: seed.PASSWORD, deviceLabel: 'Sujula endpoint collection' },
    status: 200,
    json: {
      mfaRequired: false,
      'tokens.tokenType': 'Bearer',
      'tokens.accessToken': { $minLength: 20 },
      'tokens.refreshToken': { $minLength: 20 },
      'tokens.user.id': persona.user.id,
      'tokens.user.email': persona.user.email,
      'tokens.user.role': persona.role,
    },
    bodyExcludes: [
      { value: seed.PASSWORD, why: 'a password is never echoed, not even the one just sent' },
      { value: '$2a$', why: 'nor the hash of it' },
    ],
    capture: {
      [persona.token]: 'tokens.accessToken',
      [persona.token + 'Refresh']: 'tokens.refreshToken',
      [persona.token + 'Session']: 'tokens.sessionId',
    },
  });
};

module.exports = folder(
  '01 — Identity: who gets a token, and who does not',
  'Signs in every seeded person, and covers the accounts that must be refused.',
  [
    // ── Everyone with a second factor off ────────────────────────────────
    signIn('fatou'),
    signIn('binta'),
    signIn('lamin'),
    signIn('awa'),
    signIn('mariama'),
    signIn('oliver'),
    signIn('modou'),
    signIn('ndeye'),
    signIn('ebrima'),
    signIn('isatou'),

    // ── The one with an authenticator ────────────────────────────────────
    req({
      name: 'Aminata: the password alone is not a sign-in',
      method: 'POST',
      path: '/auth/login',
      body: { email: seed.users.aminata.email, password: seed.PASSWORD },
      note: 'Answered 200 with no tokens rather than 401. The password WAS right, and '
          + 'a client has to tell "wrong password" apart from "now show me the code" '
          + 'to know which screen to draw next.',
      status: 200,
      json: { mfaRequired: true, tokens: { $exists: false } },
    }),

    req({
      name: 'Aminata: a spent recovery code is still refused',
      method: 'POST',
      path: '/auth/login',
      body: {
        email: seed.users.aminata.email,
        password: seed.PASSWORD,
        recoveryCode: seed.mfa.aminataRecoverySpent,
      },
      note: 'H8PD-2LVC was used once and kept rather than deleted, which is the whole '
          + 'point: a deleted code is indistinguishable from one that never existed, '
          + 'and this refusal is what makes a stolen list of codes worth less than it '
          + 'looks. The two unused codes are deliberately NOT spent here — a '
          + 'collection that burns them passes once and fails on every later run.',
      status: [401, 403],
      json: { message: { $exists: true } },
    }),

    req({
      name: 'Aminata: the authenticator code gets her in',
      method: 'POST',
      path: '/auth/login',
      preScript: totp(seed.mfa.aminataTotpSecret, 'aminataTotp'),
      body: {
        email: seed.users.aminata.email,
        password: seed.PASSWORD,
        totpCode: '{{aminataTotp}}',
        deviceLabel: 'Sujula endpoint collection',
      },
      note: 'The code is computed here rather than seeded, from the secret the seed '
          + 'enrolled against her account. A fixed code in a file would be a code '
          + 'that stopped working thirty seconds after it was written.',
      status: 200,
      json: {
        mfaRequired: false,
        'tokens.user.id': seed.users.aminata.id,
        'tokens.user.mfaEnabled': true,
      },
      capture: {
        tokenAminata: 'tokens.accessToken',
        tokenAminataRefresh: 'tokens.refreshToken',
        tokenAminataSession: 'tokens.sessionId',
      },
    }),

    req({
      name: 'Aminata: yesterday\'s authenticator code is refused',
      method: 'POST',
      path: '/auth/login',
      preScript: `
// The same algorithm, wound back two hours. Drift tolerance is one step either
// side, so this is far outside it — and a second factor that accepted an old
// code would be a shared secret with extra steps.
function base32ToHex(base32) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const character of base32.replace(/=+$/, '').toUpperCase()) {
    const value = alphabet.indexOf(character);
    if (value < 0) continue;
    bits += value.toString(2).padStart(5, '0');
  }
  let hex = '';
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    hex += parseInt(bits.substr(i, 8), 2).toString(16).padStart(2, '0');
  }
  return hex;
}
const counter = Math.floor((Date.now() - 7200000) / 1000 / 30).toString(16).padStart(16, '0');
const digest = CryptoJS.HmacSHA1(
  CryptoJS.enc.Hex.parse(counter),
  CryptoJS.enc.Hex.parse(base32ToHex(${JSON.stringify(seed.mfa.aminataTotpSecret)}))
).toString(CryptoJS.enc.Hex);
const offset = parseInt(digest.substr(digest.length - 1), 16);
const truncated = (parseInt(digest.substr(offset * 2, 8), 16) & 0x7fffffff) % 1000000;
pm.collectionVariables.set('aminataStaleTotp', truncated.toString().padStart(6, '0'));
`,
      body: {
        email: seed.users.aminata.email,
        password: seed.PASSWORD,
        totpCode: '{{aminataStaleTotp}}',
      },
      status: [401, 403],
    }),

    // ── The accounts that must not get in ────────────────────────────────
    req({
      name: `Sulayman is refused: ${refused.sulayman.because}`,
      method: 'POST',
      path: '/auth/login',
      body: { email: seed.users.sulayman.email, password: seed.PASSWORD },
      note: 'The password is correct. Blocking has to outrank it, or blocking an '
          + 'account would mean nothing while its owner still knew their password.',
      status: 403,
      json: { status: 403, message: { $matches: 'blocked' } },
      absent: ['tokens', 'accessToken'],
    }),

    req({
      name: `Alieu is refused: ${refused.alieu.because}`,
      method: 'POST',
      path: '/auth/login',
      body: { email: seed.users.alieu.email, password: seed.PASSWORD },
      note: 'And note where that disabling came from: users.enabled is 0 because a '
          + 'sanction row says so, not because somebody set a flag. An account that '
          + 'cannot sign in always has a row naming who decided that and why.',
      status: 403,
      json: { status: 403, message: { $matches: 'disabled' } },
    }),

    req({
      name: 'A wrong password is 401, and does not say which half was wrong',
      method: 'POST',
      path: '/auth/login',
      body: { email: seed.users.fatou.email, password: 'NotTheRightOne1!' },
      status: 401,
      json: { message: 'Invalid email or password' },
      bodyExcludes: [
        { value: 'Fatou', why: 'confirming the account exists is half an attacker\'s work' },
      ],
    }),

    req({
      name: 'An email nobody holds gets the identical answer',
      method: 'POST',
      path: '/auth/login',
      body: { email: 'nobody.at.all@example.gm', password: 'NotTheRightOne1!' },
      note: 'Byte for byte the same message as a wrong password on a real account. A '
          + 'different one here is a free account-enumeration oracle.',
      status: 401,
      json: { message: 'Invalid email or password' },
    }),

    // ── Refresh, and the replay ──────────────────────────────────────────
    req({
      name: 'A seeded refresh token works with no sign-in at all',
      method: 'POST',
      path: '/auth/refresh',
      body: { refreshToken: seed.refreshTokens.oliverLondon },
      note: 'The seed stores the SHA-256 of this value, exactly as the application '
          + 'would, so it is a real credential rather than a row that looks like one.\n\n'
          + 'A refresh ROTATES the token, which makes this a one-shot fixture: the '
          + 'first run spends it and a second run against the same server is '
          + 'replaying it. Both answers are correct behaviour and the script below '
          + 'says which one it got and holds it to the matching shape — rather than '
          + 'the alternative, which is a collection that can only ever be run once.',
      status: [200, 401],
      script: `
if (pm.response.code === 200) {
  pm.test("Seeded refresh — a fresh seed: the token is accepted and rotated", function () {
    pm.expect(H.get($body, 'user.id'), 'wrong account').to.eql(${seed.users.oliver.id});
    pm.expect(H.get($body, 'accessToken'), 'no access token').to.be.a('string');
    pm.expect(H.get($body, 'refreshToken'), 'no rotated refresh token').to.be.a('string');
    pm.expect(H.get($body, 'refreshToken'), 'the token came back unrotated')
      .to.not.eql(${JSON.stringify(seed.refreshTokens.oliverLondon)});
  });
  pm.collectionVariables.set('oliverRotatedRefresh', H.get($body, 'refreshToken'));
} else {
  pm.test("Seeded refresh — already spent on this server, so replaying it is refused", function () {
    pm.expect(pm.response.code, 'a spent refresh token must never be accepted twice').to.eql(401);
    pm.expect(H.get($body, 'accessToken'), 'a refused refresh handed out a token').to.be.undefined;
  });
}
`,
    }),

    req({
      name: 'The rotated-away token is treated as theft, not as a mistake',
      method: 'POST',
      path: '/auth/refresh',
      body: { refreshToken: seed.refreshTokens.rotatedAway },
      note: 'Session 1805 has already rotated past this value. Two possibilities: the '
          + 'legitimate holder lost the rotation, or somebody else is replaying a '
          + 'captured one — and only one of those is safe to guess at, so the session '
          + 'is revoked rather than renewed.',
      status: 401,
    }),

    req({
      name: 'And the replayed session comes back revoked, with the reason on it',
      path: '/me/sessions',
      as: 'oliver',
      note: 'Read as Oliver because session 1805 is his — user 1005 in the seed. TOKEN_REPLAY rather than a '
          + 'bare "revoked": somebody is going to ask why they were signed out.',
      status: 200,
      script: `
const sessions = Array.isArray($body) ? $body : ($body.sessions || $body.content || []);
pm.test("Sessions — 1805 is revoked and says TOKEN_REPLAY", function () {
  const replayed = sessions.find(function (s) { return s.id === ${seed.sessions.replayed}; });
  pm.expect(replayed, 'session ${seed.sessions.replayed} is not in Aminata\\'s list').to.be.an('object');
  pm.expect(JSON.stringify(replayed)).to.include('TOKEN_REPLAY');
});
pm.test("Sessions — no refresh token is ever listed", function () {
  sessions.forEach(function (s) {
    pm.expect(JSON.stringify(s)).to.not.include('sujula-dev-refresh');
  });
});
`,
    }),

    req({
      name: 'An expired refresh token is 401 and revokes nothing',
      method: 'POST',
      path: '/auth/refresh',
      body: { refreshToken: seed.refreshTokens.modouExpired },
      note: 'The opposite decision from the replay above, and for a reason: an expired '
          + 'token is not evidence of anything. Revoking on it would let anyone sign '
          + 'a person out by hoarding their own old tokens.',
      status: 401,
    }),

    req({
      name: 'A refresh token that was never issued is also 401',
      method: 'POST',
      path: '/auth/refresh',
      body: { refreshToken: 'this-was-never-a-token-on-this-platform' },
      status: 401,
    }),

    // ── The account itself ───────────────────────────────────────────────
    req({
      name: 'GET /me as Oliver: his own record, and nothing he did not ask for',
      path: '/me',
      as: 'oliver',
      status: 200,
      json: {
        'profile.id': seed.users.oliver.id,
        'profile.email': seed.users.oliver.email,
        'profile.role': 'CUSTOMER',
        'profile.preferredCurrency': 'GBP',
        'profile.countryCode': 'GB',
        'profile.phoneVerified': false,
        permissions: { $type: 'array' },
      },
      absent: ['profile.password', 'profile.totpSecret', 'profile.passwordHash'],
    }),

    req({
      name: 'GET /me without a token is refused',
      path: '/me',
      note: 'This application answers 403 rather than 401 for an anonymous caller '
          + 'throughout — a consequence of the filter chain, and consistent, which is '
          + 'what a client needs more than it needs the textbook code.',
      status: [401, 403],
    }),

    req({
      name: 'GET /me with a token that is not a token',
      path: '/me',
      headers: { Authorization: 'Bearer not-a-jwt-at-all' },
      status: [401, 403],
    }),

    req({
      name: 'GET /me with a well-formed token signed by somebody else',
      path: '/me',
      headers: {
        // Correct JWT shape, correct claims, wrong signing key. The application
        // must reject on the signature rather than on the shape — a check that
        // only parses is not a check.
        Authorization: 'Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9'
          + '.eyJzdWIiOiIxMDAxIiwicm9sZSI6IkFETUlOIiwiaXNzIjoic3VqdWxhLWUyZSIsInR5cCI6ImFjY2VzcyIsImV4cCI6NDEwMjQ0NDgwMH0'
          + '.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
      },
      note: 'The payload claims to be user 1001 with role ADMIN. If this ever answers '
          + '200 the platform has no authentication at all.',
      status: [401, 403],
    }),

    req({
      name: 'GET /me/permissions tells a client what to draw',
      path: '/me/permissions',
      as: 'lamin',
      status: 200,
      json: {
        role: 'VENDOR',
        permissions: { $type: 'array' },
        vendorId: seed.vendors.kombo.id,
        vendorStatus: 'APPROVED',
        canTrade: true,
      },
    }),

    // ── Registration ─────────────────────────────────────────────────────
    req({
      name: 'Registering asks for VENDOR and gets CUSTOMER',
      method: 'POST',
      path: '/auth/register',
      preScript: `
// A fresh address per run, so the collection can be run twice in a row against
// the same server without the second attempt colliding with the first.
pm.collectionVariables.set('freshEmail', 'collection.' + Date.now() + '@example.gm');
`,
      body: {
        email: '{{freshEmail}}',
        password: 'Sujula123!',
        firstName: 'Collection',
        lastName: 'Runner',
        phone: '+2203109999',
        preferredCurrency: 'GMD',
        preferredLanguage: 'en',
        role: 'VENDOR',
        deviceLabel: 'Sujula endpoint collection',
      },
      note: 'The body asks for VENDOR. Registration only ever creates customers — '
          + 'selling needs an approved store and being an administrator needs another '
          + 'administrator, and neither is something a sign-up form can grant itself.',
      status: [200, 201],
      json: {
        'user.role': 'CUSTOMER',
        'user.emailVerified': false,
        tokenType: 'Bearer',
        accessToken: { $minLength: 20 },
      },
      capture: {
        tokenFresh: 'accessToken',
        tokenFreshRefresh: 'refreshToken',
        freshUserId: 'user.id',
      },
    }),

    req({
      name: 'Registering the same address twice is refused',
      method: 'POST',
      path: '/auth/register',
      body: {
        email: seed.users.aminata.email,
        password: 'Sujula123!',
        firstName: 'Somebody',
        lastName: 'Else',
      },
      status: [400, 409],
    }),

    req({
      name: 'A password too weak to be one is refused before anything is written',
      method: 'POST',
      path: '/auth/register',
      body: {
        email: 'weak.password@example.gm',
        password: 'abc',
        firstName: 'Weak',
        lastName: 'Password',
      },
      status: 400,
    }),

    req({
      name: 'A body with no email at all is a 400, not a 500',
      method: 'POST',
      path: '/auth/register',
      body: { password: 'Sujula123!', firstName: 'No', lastName: 'Address' },
      status: 400,
    }),

    // ── The optional flag that once made a whole body invalid ────────────
    req({
      name: 'Changing a password without the optional keep-sessions flag',
      method: 'POST',
      path: '/auth/password/change',
      as: null,
      headers: { Authorization: 'Bearer {{tokenFresh}}' },
      body: { currentPassword: 'Sujula123!', newPassword: 'Sujula456!' },
      note: 'keepOtherSessions is optional and absent here. It was a primitive boolean '
          + 'once, and this JSON binder rejects a body with a primitive missing as '
          + '"not valid JSON" — so the most ordinary form of this request could not be '
          + 'made at all. That is why this request sends the short body rather than '
          + 'the complete one.',
      status: [200, 204],
    }),

    req({
      name: 'And the new password is the one that works',
      method: 'POST',
      path: '/auth/login',
      body: { email: '{{freshEmail}}', password: 'Sujula456!' },
      status: 200,
      json: { mfaRequired: false, 'tokens.accessToken': { $minLength: 20 } },
      capture: { tokenFresh: 'tokens.accessToken', tokenFreshRefresh: 'tokens.refreshToken' },
    }),

    req({
      name: 'While the old one does not',
      method: 'POST',
      path: '/auth/login',
      body: { email: '{{freshEmail}}', password: 'Sujula123!' },
      status: 401,
    }),

    // ── Phone verification ───────────────────────────────────────────────
    req({
      name: 'Modou confirms the phone challenge the seed left open',
      method: 'POST',
      path: '/auth/verify-phone/confirm',
      as: 'modou',
      body: { code: seed.phoneVerification.modouCode },
      note: 'The challenge is seeded relative to the moment the seed runs rather than '
          + 'to a date in the file — a challenge that expires at a fixed timestamp is '
          + 'expired before anybody can use it, which makes the row decorative.\n\n'
          + 'One-shot, like the refresh token above: confirming consumes the '
          + 'challenge. The second run gets the refusal instead, and that refusal is '
          + 'worth asserting on its own — a verification code that still worked after '
          + 'it had been used would not be a verification of anything.',
      status: [200, 204, 400],
      script: `
if (pm.response.code === 400) {
  pm.test("Modou's challenge — already spent, and a spent code is not a code", function () {
    pm.expect(H.text(pm.response)).to.include('No verification is outstanding');
  });
} else {
  pm.test("Modou's challenge — the seeded code verifies his number", function () {
    pm.expect([200, 204]).to.include(pm.response.code);
  });
}
`,
    }),

    req({
      name: 'A code that is not the code is refused',
      method: 'POST',
      path: '/auth/verify-phone/request',
      as: 'ndeye',
      body: { phone: '+2217700011' },
      status: [200, 201, 202],
      capture: { ndeyePhoneCode: 'code' },
      note: 'Under the e2e profile the code is returned in the response, because this '
          + 'platform has no SMS sender at all — see the note on '
          + 'sujula.auth.phone.expose-code-in-response. The next request proves a '
          + 'wrong code is still refused.',
    }),

    req({
      name: 'Six digits that are not hers do not verify her number',
      method: 'POST',
      path: '/auth/verify-phone/confirm',
      as: 'ndeye',
      body: { code: '000000' },
      status: [400, 401, 403, 404, 422],
    }),

    // ── Signing out ──────────────────────────────────────────────────────
    req({
      name: 'Signing out ends that session and no other',
      method: 'POST',
      path: '/auth/logout',
      as: null,
      headers: { Authorization: 'Bearer {{tokenFresh}}' },
      body: { refreshToken: '{{tokenFreshRefresh}}' },
      note: 'Run against the account this folder registered, never against a seeded '
          + 'persona: every later folder holds those tokens, and signing one out here '
          + 'would fail four hundred requests for a reason nobody could find.',
      status: [200, 204],
    }),

    req({
      name: 'And the refresh token it ended no longer refreshes',
      method: 'POST',
      path: '/auth/refresh',
      body: { refreshToken: '{{tokenFreshRefresh}}' },
      status: 401,
    }),
  ],
);
