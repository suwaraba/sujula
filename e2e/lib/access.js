/**
 * What the filter chain decides, before any controller sees a request.
 *
 * This is SecurityConfig's authorizeHttpRequests block, transcribed in the same
 * order — Spring takes the first rule that matches, so the order is the
 * meaning. It exists so the role matrix can be generated rather than typed:
 * four hundred endpoints times seven callers is not a thing anybody maintains
 * by hand, and the rows nobody writes are the ones that matter.
 *
 * It covers the OUTER gate only. Which of the two staff roles may decide a
 * dispute, and whether this vendor owns that parcel, are decided inside the
 * controllers by StaffCaller and by the ownership test in each query — and
 * those are written by hand, in the suite for the surface they belong to,
 * because they need a request body that binds before the check is reached.
 */

/**
 * Ant-style matcher: `*` stays inside a segment, `**` crosses them.
 *
 * Spring parses these with PathPatternParser, where a trailing `/**` matches
 * ZERO or more segments — so `/api/categories/**` covers `/api/categories`
 * itself. Getting that wrong here does not make the matrix fail safely: it
 * makes it expect a refusal from an endpoint that is deliberately public, and
 * the run goes red on correct behaviour. Found exactly that way, by
 * GET /api/categories answering 200.
 */
function matches(pattern, path) {
  const trailing = pattern.endsWith('/**');
  const escaped = pattern
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    // Split on the double star first, so the replacement for a single star
    // inside a segment cannot widen it into one that crosses segments.
    .split('**')
    .map((part) => part.replace(/\*/g, '[^/]*'))
    .join('.*');
  if (new RegExp('^' + escaped + '$').test(path)) return true;
  if (!trailing) return false;
  // The zero-segment case: /api/categories/** also matches /api/categories.
  return matches(pattern.slice(0, -3), path);
}

/** permitAll */
const OPEN = { who: 'open' };
/** authenticated() — any signed-in caller passes the gate */
const SIGNED_IN = { who: 'signed-in' };
/** hasAnyRole(...) */
const roles = (...names) => ({ who: 'roles', roles: names });

/**
 * In SecurityConfig's own order. A method of null means every method.
 */
const RULES = [
  // Documentation, when the deployment serves it at all.
  [null, ['/swagger-ui.html', '/swagger-ui/**', '/v3/api-docs', '/v3/api-docs/**', '/openapi.json'], OPEN],

  // Machines and probes: open at the gate, defended inside the handler.
  ['POST', ['/webhooks/**'], OPEN],
  ['GET', ['/health/liveness', '/health/readiness'], OPEN],
  [null, ['/actuator/**'], roles('ADMIN')],

  // Getting in, before there is an identity to check.
  ['POST', ['/auth/register', '/auth/login', '/auth/refresh', '/auth/verify-email',
            '/auth/password/forgot', '/auth/password/reset'], OPEN],
  ['POST', ['/auth/oauth/*/callback'], OPEN],
  [null, ['/auth/**', '/me', '/me/**'], SIGNED_IN],

  // Questions a shopper asks before signing in.
  [null, ['/geo/**'], OPEN],
  ['POST', ['/delivery/serviceability', '/delivery/quote'], OPEN],
  [null, ['/delivery-contexts', '/delivery-contexts/**'], OPEN],
  [null, ['/currencies', '/currencies/**', '/countries', '/locales', '/config/public'], OPEN],

  // Browsing precedes signing in; writing onto a seller's shopfront does not.
  ['POST', ['/products/*/questions'], SIGNED_IN],
  ['GET', ['/categories', '/categories/**', '/products', '/products/**',
           '/stores/**', '/brands', '/search', '/search/**'], OPEN],

  // Filling a basket is open. Paying is not: an order needs an owner.
  [null, ['/carts', '/carts/**'], OPEN],
  [null, ['/checkout', '/checkout/**'], SIGNED_IN],
  [null, ['/orders', '/orders/**'], SIGNED_IN],

  // The two surfaces built for somebody with no account at all.
  ['GET', ['/track/*'], OPEN],
  ['GET', ['/invoices/*'], OPEN],
  ['GET', ['/parcels/*'], OPEN],
  ['POST', ['/parcels/*/request-code', '/parcels/*/choose-pickup-point',
            '/parcels/*/reschedule', '/parcels/*/authorise-safe-drop'], OPEN],

  // A seller's own everything. Signed in is the outer gate only — the vendor is
  // resolved from the session and goes into every query.
  [null, ['/vendor/stores', '/vendor/stores/**',
          '/vendor/products', '/vendor/products/**', '/vendor/imports/**',
          '/vendor/inventory', '/vendor/inventory/**',
          '/vendor/imei-units', '/vendor/imei-units/**',
          '/vendor/promotions', '/vendor/promotions/**',
          '/vendor/coupons', '/vendor/coupons/**',
          '/vendor/orders', '/vendor/orders/**',
          '/vendor/analytics/**', '/vendor/balance', '/vendor/transactions',
          '/vendor/payouts', '/vendor/payouts/**', '/vendor/statements/**'], SIGNED_IN],

  [null, ['/driver', '/driver/**'], SIGNED_IN],

  // Finding a counter is public; running one is not.
  ['GET', ['/pickup-points', '/pickup-points/*'], OPEN],
  [null, ['/pickup', '/pickup/**'], SIGNED_IN],

  [null, ['/notifications', '/notifications/**'], SIGNED_IN],
  [null, ['/returns', '/returns/**', '/disputes', '/disputes/**', '/messages/**'], SIGNED_IN],
  ['PATCH', ['/reviews/*'], SIGNED_IN],
  ['DELETE', ['/reviews/*'], SIGNED_IN],
  ['POST', ['/reviews/*/reply', '/reviews/*/report'], SIGNED_IN],

  // The older /api surface, which the newer paths were grown alongside.
  ['GET', ['/api/categories/**'], OPEN],
  ['GET', ['/api/vendors/storefront', '/api/vendors/storefront/**'], OPEN],
  ['GET', ['/api/products/mine', '/api/products/mine/**'], SIGNED_IN],
  ['GET', ['/api/products/**'], OPEN],
  ['POST', ['/api/users/register', '/api/users/login', '/api/users/password/forgot',
            '/api/users/password/reset', '/api/users/verify-email',
            '/api/users/resend-verification', '/api/delivery/quote',
            '/api/payments/callback'], OPEN],
  [null, ['/api/cart', '/api/cart/**'], OPEN],
  [null, ['/api/guest/orders', '/api/guest/orders/lookup', '/api/guest/orders/*/cancel',
          '/api/guest/orders/*/payment', '/api/guest/orders/*/payment/methods'], OPEN],

  // Staff only at the gate; which of the two staff roles may do what is decided
  // inside, per endpoint, because a read and a write sit under the same prefix.
  [null, ['/admin', '/admin/**'], roles('ADMIN', 'SUPPORT')],
];

/** anyRequest().authenticated() */
const FALLBACK = SIGNED_IN;

/** The first rule that matches this method and path, exactly as Spring picks it. */
function ruleFor(method, path) {
  for (const [ruleMethod, patterns, outcome] of RULES) {
    if (ruleMethod && ruleMethod !== method) continue;
    if (patterns.some((pattern) => matches(pattern, path))) return outcome;
  }
  return FALLBACK;
}

/**
 * Callers the filter chain refuses outright, out of the ones given.
 *
 * Deliberately nothing to do with whether the caller owns the row: a customer
 * reaching /vendor/balance passes this gate and is refused inside the service.
 * Those are covered separately — see the wrong-role reads in the matrix.
 */
function refusedBy(method, path, callers) {
  const rule = ruleFor(method, path);
  if (rule.who === 'open') return [];
  return callers.filter((caller) => {
    if (caller.role === null) return true;                       // anonymous
    if (rule.who === 'signed-in') return false;
    return !rule.roles.includes(caller.role);
  });
}

module.exports = { ruleFor, refusedBy, matches, RULES, OPEN, SIGNED_IN };
