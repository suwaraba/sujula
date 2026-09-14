package com.sujula.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.google.maps.GeoApiContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableMethodSecurity
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Same key the geocoding service uses. This read named a property nothing
     * defined, and an unresolvable @Value with no default fails context startup —
     * so the security configuration was what brought the application down.
     */
    @Value("${sujula.google.geocoding.api-key:}")
    private String apiKey;

    /**
     * Whether Swagger UI and the OpenAPI document are reachable without signing in.
     *
     * <p>Off unless switched on. The document is a complete map of the API — every
     * path, every role-gated write, every request shape — which is a gift to
     * anyone probing the service, so production does not serve it anonymously.
     * The dev profile turns it on.
     */
    @Value("${sujula.docs.enabled:false}")
    private boolean docsEnabled;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    @Bean
    public GeoApiContext geoApiContext() {
        return new GeoApiContext.Builder()
                .apiKey(apiKey)
                .build();
    }


    /**
     * Makes the readable-cookie CSRF pattern actually work for a browser client.
     *
     * <p>{@code withHttpOnlyFalse()} above exists so JavaScript can read the
     * {@code XSRF-TOKEN} cookie and echo it back in a header — what Angular does
     * natively, what axios does via {@code xsrfCookieName}, and what every
     * hand-rolled fetch wrapper does. The default handler, however, expects that
     * header to carry an XOR-masked token, and the raw cookie value is the only
     * thing a browser has. The two halves disagreed, so every POST was refused:
     * login, registration and checkout were all unreachable from a browser, which
     * only showed up once the application was actually run against.
     *
     * <p>Setting the attribute name to null opts out of the masking, so the raw
     * cookie value validates. The masking exists to frustrate a BREACH attack on
     * a token rendered into an HTML response body — which does not apply here,
     * because this service returns JSON and never renders the token into a page.
     */
    private static CsrfTokenRequestAttributeHandler spaCsrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(spaCsrfTokenRequestHandler())
                        // A payment provider posts a machine callback and has no
                        // CSRF token to present; the endpoint authenticates it
                        // with a shared secret instead.
                        //
                        // /auth and /me are the bearer-token surface. CSRF exists
                        // because a browser attaches cookies to a cross-site request
                        // on its own; an Authorization header is never attached on
                        // its own, so there is nothing for a forged request to ride.
                        // Requiring a cookie-delivered CSRF token here would also
                        // make the API unusable from a native client, which has no
                        // cookie jar to read one from.
                        //
                        // /geo, /delivery and /delivery-contexts are read-only
                        // public lookups. They take POST because a destination is
                        // an address — too long for a query string, and not
                        // something to leave in access logs — but they change
                        // nothing that a forged request could exploit, and a CSRF
                        // token cannot be required of a shopper who has no
                        // session yet.
                        .ignoringRequestMatchers("/api/payments/callback", "/auth/**", "/me/**",
                                                 "/geo/**", "/delivery/**", "/delivery-contexts",
                                                 "/delivery-contexts/**",
                                                 "/currencies", "/currencies/**",
                                                 "/carts", "/carts/**", "/checkout", "/checkout/**"))
                .authorizeHttpRequests(auth -> {
                    if (docsEnabled) {
                        auth.requestMatchers(
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll();
                    }
                    auth
                        // ── Token authentication ─────────────────────────────
                        // Getting in, proving an address, or recovering a lost
                        // password all have to work before there is an identity.
                        .requestMatchers(HttpMethod.POST,
                                "/auth/register",
                                "/auth/login",
                                "/auth/refresh",
                                "/auth/verify-email",
                                "/auth/password/forgot",
                                "/auth/password/reset").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/oauth/*/callback").permitAll()
                        // Everything else under /auth changes an account someone
                        // already holds: signing out, rotating a password, turning
                        // multi-factor on or off. /me is the account itself.
                        .requestMatchers("/auth/**", "/me", "/me/**").authenticated()

                        // ── Before there is an account ───────────────────────
                        // Looking an address up, asking whether you deliver to a
                        // town, and finding out what shipping costs are all
                        // questions a shopper asks before signing in — and a
                        // storefront that will not answer them until they do is
                        // one they leave. None of these writes anything a caller
                        // could later be identified by; a delivery context is the
                        // exception and is protected by an unguessable id rather
                        // than by a session.
                        .requestMatchers("/geo/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/delivery/serviceability",
                                                          "/delivery/quote").permitAll()
                        .requestMatchers("/delivery-contexts", "/delivery-contexts/**").permitAll()

                        // ── Reference data ───────────────────────────────────
                        // What this deployment supports: currencies and their
                        // minor units, the countries it buys from and ships to,
                        // the languages it speaks, and the feature flags a client
                        // needs before it can draw a single screen. A storefront
                        // fetches these on first paint, long before anyone signs
                        // in, and /config/public is an allow-list assembled by
                        // hand rather than a view onto configuration.
                        .requestMatchers("/currencies", "/currencies/**",
                                         "/countries", "/locales", "/config/public").permitAll()

                        // ── The public catalogue ─────────────────────────────
                        // Browsing precedes signing in, always. A shopper
                        // comparing phones for a relative at home has no reason
                        // to have an account yet, and a catalogue that demands
                        // one is a catalogue nobody reaches.
                        //
                        // Asking a question is the exception: it writes public
                        // text onto a seller's shopfront, which without an
                        // account behind it is a spam channel with no cost to
                        // the sender.
                        .requestMatchers(HttpMethod.POST, "/products/*/questions").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/categories", "/categories/**",
                                "/products", "/products/**",
                                "/stores/**", "/brands",
                                "/search", "/search/**").permitAll()

                        // ── Basket and checkout ──────────────────────────────
                        // Filling a basket is open to guests: on this
                        // marketplace most baskets are filled before anybody
                        // signs in, and a cart that demanded a login first is a
                        // cart most people never fill. The token is the
                        // credential, and a cart bound to an account is readable
                        // only by that account.
                        //
                        // Paying is not open. An order needs an owner — the
                        // refund, the status poll and the retry all resolve
                        // through it.
                        .requestMatchers("/carts", "/carts/**").permitAll()
                        .requestMatchers("/checkout", "/checkout/**").authenticated()

                        // A buyer's own orders. Every method resolves the order
                        // by id AND buyer in one query, so authentication here is
                        // the outer gate rather than the whole check.
                        .requestMatchers("/orders", "/orders/**").authenticated()
                        // The two surfaces that serve somebody with no account.
                        //
                        // Tracking: the recipient has a phone number and an SMS,
                        // and nothing else — no account to authenticate, no email
                        // to click a link in. The code is the credential, and the
                        // page is built to be worth nothing to a stranger holding
                        // it: a city, parcel counts, and fixed phrases.
                        //
                        // Invoices: the token is an HMAC over one order id and an
                        // expiry, minted only after ownership was proven on
                        // /orders/{id}/invoice. Open, because an invoice
                        // legitimately travels — to the recipient, to a bank, to
                        // whoever is reimbursing the buyer.
                        .requestMatchers(HttpMethod.GET, "/track/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/invoices/*").permitAll()

                        // The recipient's own parcel. Open on purpose and the
                        // clearest case of C5 on the platform: the person the
                        // goods are for may have no account, no app and no
                        // email, and she is the only one who knows whether she
                        // will be at home on Thursday. Two credentials rather
                        // than a session — the unguessable tracking code in the
                        // path reads a page that carries a first name and a
                        // town, and a six-digit code in the body authorises the
                        // three changes. The POSTs are open because requiring a
                        // sign-in here would hand the decision back to whoever
                        // has an account, which is exactly the wrong person.
                        .requestMatchers(HttpMethod.GET, "/parcels/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/parcels/*/request-code",
                                                          "/parcels/*/choose-pickup-point",
                                                          "/parcels/*/reschedule",
                                                          "/parcels/*/authorise-safe-drop")
                                .permitAll()

                        // A seller's own store: opening it, verifying it, saying
                        // who else may work in it, and naming where the money
                        // goes. Signed in is the outer gate only — every method
                        // resolves the store by id AND owner in one query, and
                        // the payout endpoint re-authenticates on top of that.
                        .requestMatchers("/vendor/stores", "/vendor/stores/**").authenticated()

                        // A seller's own catalogue. Authenticated is the outer
                        // gate only: every method resolves the listing by id AND
                        // vendor in one query, and a job reference is unguessable
                        // on top of that, because import errors name a seller's
                        // products, prices and SKUs.
                        .requestMatchers("/vendor/products", "/vendor/products/**",
                                         "/vendor/imports/**").authenticated()

                        // Stock, handsets and discounts. Everything under here
                        // resolves through the caller's own store, so a path
                        // variable cannot reach another seller's shelf, their
                        // margins or their customer list.
                        .requestMatchers("/vendor/inventory", "/vendor/inventory/**",
                                         "/vendor/imei-units", "/vendor/imei-units/**",
                                         "/vendor/promotions", "/vendor/promotions/**",
                                         "/vendor/coupons", "/vendor/coupons/**").authenticated()

                        // A seller's own fulfilment desk: accepting, packing,
                        // the collection code and the parcel label. Authenticated
                        // is the outer gate only — the vendor is resolved from
                        // the session and goes into every query, so a path
                        // variable cannot reach another shop's parcel.
                        //
                        // Note what is NOT open here. The collection code is a
                        // seller-only secret and the label names a recipient, so
                        // neither gets the permitAll that the tracking page and
                        // the invoice link have: those two are built to be worth
                        // nothing to a stranger holding them, and these are not.
                        .requestMatchers("/vendor/orders", "/vendor/orders/**").authenticated()

                        // A seller's figures and a seller's money. Authenticated
                        // is the outer gate only — the shop is resolved from the
                        // session and goes into every query, so there is no path
                        // variable that reaches another shop's takings.
                        //
                        // Note what is behind this rule: revenue, margins, a
                        // bank-ready statement, and the destinations a shop
                        // ships to. An unguarded route here hands a competitor a
                        // shop's whole trading position.
                        // The driver's app. Authenticated is the outer gate only
                        // — the driver is resolved from the session and goes
                        // into every query, so a path variable cannot open
                        // another driver's parcel.
                        //
                        // What is behind this rule is the sharpest data on the
                        // platform: recipients' home addresses and phone
                        // numbers, and where a driver has been all day. Both
                        // belong to people who never agreed to be visible to
                        // anyone but the person bringing their parcel.
                        .requestMatchers("/driver", "/driver/**").authenticated()

                        // Finding a counter is public. A shopper chooses where
                        // to collect before signing in, and frequently before
                        // they have an account at all — requiring one would make
                        // the delivery option invisible to exactly the people
                        // most likely to want it.
                        //
                        // What these carry is chosen to be safe open: an
                        // address, opening hours, and how full the counter is as
                        // a BAND rather than a count. No operator name, no
                        // contact email, no parcel counts, no earnings.
                        .requestMatchers(HttpMethod.GET, "/pickup-points", "/pickup-points/*")
                                .permitAll()

                        // Running one is not. These rows lead to recipients'
                        // names and phone numbers, and the operator is resolved
                        // from the session and goes into every query beside the
                        // point id.
                        .requestMatchers("/pickup", "/pickup/**").authenticated()

                        // The user's own inbox, settings and phones. No user id
                        // appears in any of these paths — every one takes its
                        // subject from the session — so being signed in is the
                        // whole of the gate and there is nothing to tamper with.
                        .requestMatchers("/notifications", "/notifications/**").authenticated()

                        // After the sale. Signed in is the outer gate only:
                        // every one of these rows is scoped by putting the
                        // caller into the query, and the two sides of a return
                        // or a dispute are found by two different ownership
                        // tests rather than by a role. There is deliberately no
                        // role check here — a buyer is a seller on somebody
                        // else's platform and both write to the same rows.
                        .requestMatchers("/returns", "/returns/**",
                                         "/disputes", "/disputes/**",
                                         "/messages/**").authenticated()

                        // A review's own endpoints. Reading reviews is public
                        // and lives on the catalogue; changing, answering and
                        // reporting one is not.
                        .requestMatchers(HttpMethod.PATCH, "/reviews/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/reviews/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/reviews/*/reply",
                                                          "/reviews/*/report").authenticated()

                        .requestMatchers("/vendor/analytics/**", "/vendor/balance",
                                         "/vendor/transactions", "/vendor/payouts",
                                         "/vendor/payouts/**", "/vendor/statements/**")
                                .authenticated()

                        .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
                        // Store pages and store search: a shopper deciding where to
                        // buy has not signed in yet, and these carry no private figures.
                        .requestMatchers(HttpMethod.GET, "/api/vendors/storefront", "/api/vendors/storefront/**").permitAll()
                        // Catalogue browse, search and the product page. Shoppers look
                        // before they sign in, and location-ranked search is the main
                        // way anything is found here. /mine is the seller's own back
                        // office and is matched first, so the wildcard cannot open it.
                        .requestMatchers(HttpMethod.GET, "/api/products/mine", "/api/products/mine/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/users/register",
                                "/api/users/login",
                                "/api/users/password/forgot",
                                "/api/users/password/reset",
                                "/api/users/verify-email",
                                "/api/users/resend-verification",
                                // Priced before anyone signs in, so a guest cart
                                // can show a total.
                                "/api/delivery/quote",
                                // Verified by shared secret inside the controller.
                                "/api/payments/callback"
                        ).permitAll()
                        // The cart is the storefront's front door: a shopper fills
                        // one before deciding whether to sign in, and the controller
                        // issues its own HttpOnly cookie to tell guest carts apart.
                        .requestMatchers("/api/cart", "/api/cart/**").permitAll()
                        // Guest checkout, end to end. A guest has no account by
                        // definition, so requiring authentication here would make
                        // the whole flow unreachable. Every one of these resolves
                        // an order by number plus the email used at checkout —
                        // both, so an order number on its own reveals nothing, and
                        // placing an order needs neither.
                        .requestMatchers("/api/guest/orders",
                                         "/api/guest/orders/lookup",
                                         "/api/guest/orders/*/cancel",
                                         "/api/guest/orders/*/payment",
                                         "/api/guest/orders/*/payment/methods").permitAll()
                        // The administrative surface. Staff only at the outer
                        // gate; which of the two staff roles may do what is
                        // decided inside, by StaffCaller, because the split is
                        // per endpoint rather than per path — a read and a write
                        // sit next to each other under the same prefix, and a
                        // path rule could not tell them apart.
                        .requestMatchers("/admin", "/admin/**")
                                .hasAnyRole("ADMIN", "SUPPORT")

                        .anyRequest().authenticated();
                })
                // Runs before the username/password filter so a bearer token is
                // resolved first, and yields to an existing session if one is
                // already established — the two mechanisms coexist rather than
                // compete, and both leave the same User on the principal.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

}
