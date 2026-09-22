package com.sujula.controller;

import com.sujula.dto.request.catalogue.ProductFilter;
import com.sujula.dto.response.catalogue.CatalogueResponses;
import com.sujula.model.constant.ProductCondition;
import com.sujula.service.catalogue.BrowsingContext;
import com.sujula.service.catalogue.BrowsingContextResolver;
import com.sujula.service.catalogue.CatalogueLocationParams;
import com.sujula.service.catalogue.CatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Search, and the typeahead in front of it.
 *
 * <p>Both take a delivery location for the same reason everything else in the
 * catalogue does: searching "phone" from Madrid for a parcel going to Serrekunda
 * should surface what can reach Serrekunda. A search ranked against the person
 * typing is a search that answers a question nobody asked.
 */
@RestController
@RequestMapping("/search")
@Tag(name = "search", description = "Full-text search with facets, and typeahead")
public class SearchController {

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Suggestions cache hard, and can afford to.
     *
     * <p>They are keyed on a short prefix, shared by every shopper typing it, and
     * a minute of staleness on a typeahead costs nobody anything — while the
     * query fires on most keystrokes, which is the most repeated request the
     * whole site makes.
     */
    private static final Duration SUGGEST_CACHE = Duration.ofMinutes(10);

    private final CatalogueService catalogue;
    private final BrowsingContextResolver contexts;
    private final AuthenticatedCaller caller;

    public SearchController(CatalogueService catalogue, BrowsingContextResolver contexts,
                            AuthenticatedCaller caller) {
        this.catalogue = catalogue;
        this.contexts = contexts;
        this.caller = caller;
    }

    @GetMapping
    @Operation(summary = "Search the catalogue",
               description = "Full text over name, short description and SKU, with facets for "
                       + "category, brand and condition. Each facet is counted against the current "
                       + "filter minus its own dimension, so a shopper can widen a search without "
                       + "clearing it first. Ranked against the delivery location.")
    public ResponseEntity<CatalogueResponses.ProductPage> search(
            @RequestParam String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String store,
            @RequestParam(required = false) ProductCondition condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false) String deliverableTo,
            @RequestParam(required = false) Double deliveryLat,
            @RequestParam(required = false) Double deliveryLng,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication, HttpServletRequest request) {

        ProductFilter filter = new ProductFilter(q, null, category, null, brand, store,
                condition, minPrice, maxPrice, minRating, inStockOnly);

        return ResponseEntity.ok(catalogue.browse(filter,
                context(deliverableTo, deliveryLat, deliveryLng, deliveryCountry, currency,
                        authentication, request),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }

    @GetMapping("/suggest")
    @Operation(summary = "Typeahead",
               description = "Product names only, prefix-weighted, capped at ten. Two characters "
                       + "minimum: one letter matches most of the catalogue, so the suggestion is "
                       + "noise and the query is expensive.")
    public ResponseEntity<CatalogueResponses.Suggestions> suggest(
            @RequestParam String q,
            @RequestParam(required = false) String deliverableTo,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(defaultValue = "8") int limit,
            Authentication authentication, HttpServletRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(SUGGEST_CACHE).cachePublic())
                .body(catalogue.suggest(q,
                        context(deliverableTo, null, null, deliveryCountry, null,
                                authentication, request),
                        limit));
    }

    private BrowsingContext context(String deliverableTo, Double deliveryLat, Double deliveryLng,
                                    String deliveryCountry, String currency,
                                    Authentication authentication, HttpServletRequest request) {
        return contexts.resolve(
                CatalogueLocationParams.of(deliverableTo, deliveryLat, deliveryLng,
                        deliveryCountry, currency, null),
                request, caller.userIdOrNull(authentication));
    }
}
