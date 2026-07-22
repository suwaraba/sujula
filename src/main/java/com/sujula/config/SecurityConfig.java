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
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/users/register",
                                "/api/users/password/forgot",
                                "/api/users/password/reset",
                                "/api/users/verify-email",
                                "/api/users/resend-verification"
                        ).permitAll()
                        .anyRequest().authenticated()
                );
        return http.build();
    }

}
