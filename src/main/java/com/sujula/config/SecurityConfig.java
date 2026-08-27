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

@Configuration
@EnableMethodSecurity
@EnableWebSecurity
public class SecurityConfig {

    @Value("${google.maps.api.key}")
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // A payment provider posts a machine callback and has no
                        // CSRF token to present; the endpoint authenticates it
                        // with a shared secret instead.
                        .ignoringRequestMatchers("/api/payments/callback"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/users/register",
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
                        // A guest pays for their own order, identified by order
                        // number plus the email used at checkout — both, so an
                        // order number on its own reveals nothing.
                        .requestMatchers("/api/guest/orders/*/payment",
                                         "/api/guest/orders/*/payment/methods").permitAll()
                        .anyRequest().authenticated()
                );
        return http.build();
    }

}
