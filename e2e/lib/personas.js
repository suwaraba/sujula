/**
 * Who makes each request.
 *
 * A persona is a seeded account plus the collection variable its access token
 * lands in. The identity suite signs each of them in once; every later request
 * names one and gets that person's bearer token attached.
 *
 * `guest` is deliberately in the list rather than expressed by leaving the
 * persona out. On this marketplace a caller with no account is a first-class
 * case — a shopper filling a basket, a recipient reading a tracking page, a
 * provider posting a webhook — so an anonymous request should look like a
 * decision in the collection rather than like an omission.
 */

const seed = require('./seed');

/** Roles as the application spells them, so a matrix row cannot invent one. */
const ROLES = ['ADMIN', 'SUPPORT', 'VENDOR', 'CUSTOMER', 'DELIVERY', 'PICKUP_OPERATOR'];

const personas = {
  guest: {
    label: 'a shopper with no account',
    role: null,
    token: null,
  },
  fatou: {
    label: 'Fatou, administrator',
    user: seed.users.fatou,
    role: 'ADMIN',
    token: 'tokenFatou',
  },
  binta: {
    label: 'Binta, support agent',
    user: seed.users.binta,
    role: 'SUPPORT',
    token: 'tokenBinta',
  },
  lamin: {
    label: 'Lamin, Kombo Electronics, settles in dalasi',
    user: seed.users.lamin,
    role: 'VENDOR',
    token: 'tokenLamin',
  },
  awa: {
    label: 'Awa, Teranga Textiles, settles in CFA',
    user: seed.users.awa,
    role: 'VENDOR',
    token: 'tokenAwa',
  },
  mariama: {
    label: 'Mariama, a stall in Dakar that banks in Banjul',
    user: seed.users.mariama,
    role: 'VENDOR',
    token: 'tokenMariama',
  },
  aminata: {
    label: 'Aminata in Serrekunda, with an authenticator enrolled',
    user: seed.users.aminata,
    role: 'CUSTOMER',
    token: 'tokenAminata',
  },
  oliver: {
    label: 'Oliver in London, paying in sterling for a parcel going to Serrekunda',
    user: seed.users.oliver,
    role: 'CUSTOMER',
    token: 'tokenOliver',
  },
  modou: {
    label: 'Modou, neither address nor phone proved yet',
    user: seed.users.modou,
    role: 'CUSTOMER',
    token: 'tokenModou',
  },
  ndeye: {
    label: 'Ndeye in Dakar, a second customer with no orders of her own',
    user: seed.users.ndeye,
    role: 'CUSTOMER',
    token: 'tokenNdeye',
  },
  ebrima: {
    label: 'Ebrima, driver',
    user: seed.users.ebrima,
    role: 'DELIVERY',
    token: 'tokenEbrima',
  },
  isatou: {
    label: 'Isatou, who runs the Westfield counter',
    user: seed.users.isatou,
    role: 'PICKUP_OPERATOR',
    token: 'tokenIsatou',
  },
};

/**
 * The people who cannot sign in at all, and the reason each is refused.
 *
 * They have no token and never will, so they are not personas — a matrix row
 * naming one would be asserting about an empty Authorization header rather
 * than about that person. The identity suite covers them directly.
 */
const refused = {
  sulayman: { user: seed.users.sulayman, because: 'blocked and fraud-flagged' },
  alieu: { user: seed.users.alieu, because: 'suspended by sanction ' + seed.sanctions.alieuSuspension },
};

/**
 * One caller per role, plus the guest.
 *
 * This is what the role matrix iterates: nine callers against every endpoint.
 * A second customer would double the run and prove nothing a first one did
 * not — the thing being tested is the role, and the ownership tests that need
 * a *different* person of the same role are written by hand, in the suite
 * where the row they are reaching for lives.
 */
const matrixCallers = ['guest', 'fatou', 'binta', 'lamin', 'aminata', 'ebrima', 'isatou'];

module.exports = { personas, refused, matrixCallers, ROLES };
