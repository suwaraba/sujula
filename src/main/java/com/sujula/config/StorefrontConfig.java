package com.sujula.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where a browser that types the bare hostname ends up.
 *
 * <p>The buyer application lives under {@code /app/} rather than at the root,
 * and deliberately. Every API path on this service is a bare noun —
 * {@code /products}, {@code /search}, {@code /carts}, {@code /orders} — so a
 * single-page application mounted at the root with a catch-all forward would
 * sit in front of all of them, and the first route it swallowed would be one
 * nobody noticed until a client called it.
 *
 * <p>Under a prefix there is no catch-all to write: the shell is one file at a
 * fixed path, and the application routes itself in the fragment
 * ({@code /app/#/product/...}). That choice is also what makes the native
 * builds possible — a wrapped WebView loads the shell from {@code file://},
 * where there is no server to ask for a fallback and a path-routed application
 * would show a blank screen on every route but the first.
 */
@Configuration
public class StorefrontConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/app/");
        registry.addRedirectViewController("/app", "/app/");
        // A directory is not a resource: the static handler serves
        // /app/index.html but has nothing to answer /app/ with, and the
        // welcome-page rule only covers the root. Forwarding is what makes the
        // trailing-slash URL — the one the redirects above hand out — resolve.
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }
}
