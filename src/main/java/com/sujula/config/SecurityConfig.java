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
                        .ignoringRequestMatchers("/api/payments/callback"))
                .authorizeHttpRequests(auth -> auth
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
                        .anyRequest().authenticated()
                );
        return http.build();
    }

}
