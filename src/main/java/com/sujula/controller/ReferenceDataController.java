package com.sujula.controller;

import com.sujula.dto.response.reference.ReferenceResponses;
import com.sujula.service.reference.ReferenceDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * What this deployment supports, and what a client may know about it.
 *
 * <p>All public and all the same shape: configuration, converted, with nothing
 * per-caller in any of them. That is what lets them be cached, and they need to
 * be — a storefront fetches all three on first paint to decide which country
 * pickers to render, whether to lay itself out right-to-left, and which features
 * to show at all.
 *
 * <p>The cache window is deliberately short. These change on a business's
 * timetable rather than a release's — a country is added, a feature is switched
 * on — and an hour of staleness on a feature flag is an hour of clients showing
 * a button the API will refuse.
 */
@RestController
@Tag(name = "reference", description = "Countries, locales, and public configuration")
public class ReferenceDataController {

    /**
     * Long enough to spare the server a request per page view, short enough that
     * turning a feature off takes effect within a coffee break.
     */
    private static final Duration CACHE = Duration.ofMinutes(5);

    private final ReferenceDataService reference;

    public ReferenceDataController(ReferenceDataService reference) {
        this.reference = reference;
    }

    @GetMapping("/countries")
    @Operation(summary = "Countries this marketplace buys from and ships to",
               description = "Two flags, not one. A buyer in London orders from a Gambian vendor "
                       + "for delivery to Serekunda: their country supports buying and not "
                       + "shipping, and one 'supported' boolean could not express that.")
    public ResponseEntity<ReferenceResponses.Countries> countries() {
        return cached().body(reference.countries());
    }

    @GetMapping("/locales")
    @Operation(summary = "Languages this marketplace speaks",
               description = "Each carries rtl so a client knows whether to flip its layout.")
    public ResponseEntity<ReferenceResponses.Locales> locales() {
        return cached().body(reference.locales());
    }

    @GetMapping("/config/public")
    @Operation(summary = "Feature flags, minimum app versions and support contacts",
               description = "An allow-list, assembled field by field — never a filtered view of "
                       + "the deployment's configuration, which is one careless rename away from "
                       + "publishing a secret. Below a minimum version a client should tell the "
                       + "user to update rather than fail in some less legible way.")
    public ResponseEntity<ReferenceResponses.PublicConfig> publicConfig() {
        return cached().body(reference.publicConfig());
    }

    private ResponseEntity.BodyBuilder cached() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(CACHE).cachePublic());
    }
}
