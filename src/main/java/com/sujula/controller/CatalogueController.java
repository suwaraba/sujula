package com.sujula.controller;

import com.sujula.dto.request.catalogue.ProductFilter;
import com.sujula.dto.response.catalogue.CatalogueResponses;
import com.sujula.model.constant.ProductCondition;
import com.sujula.service.catalogue.BrowsingContext;
import com.sujula.service.catalogue.BrowsingContextResolver;
import com.sujula.service.catalogue.CatalogueLocationParams;
import com.sujula.service.catalogue.CatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The public catalogue: categories, products, stores and brands.
 *
 * <p><strong>Every read here takes a delivery location, and none of them takes
 * the buyer's.</strong> That is C1, and on this surface it is the difference
 * between a marketplace that works for its actual users and one that does not. A
 * buyer in Madrid sending a phone to Serrekunda needs the catalogue ranked
 * against Serrekunda — the stock two miles from their sister, not the stock two
 * miles from them, which can never reach her.
 *
 * <p>So the location parameters are named for the destination: {@code
 * deliverableTo} (a delivery context id, the best answer because the cart and
 * checkout price against the same one), or {@code deliveryLat}/{@code
 * deliveryLng}/{@code deliveryCountry}. There is no {@code userLat} and there
 * should never be one.
 *
 * <p>Currency comes from somewhere else entirely: the shopper's explicit choice,
 * or failing that their browser and IP. It is never derived from where the
 * parcel is going. {@link BrowsingContextResolver} enforces that separation
 * structurally — the two halves are resolved by methods that are not given each
 * other's inputs.
 */
@RestController
@Tag(name = "catalogue", description = "Public browse: categories, products, stores, brands")
public class CatalogueController {

    /** Beyond this a page is not a page, it is an export. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Reference data changes on a business's timetable, not a release's, so it
     * caches — but briefly, because a category added this morning should appear
     * this morning.
     */
    private static final Duration REFERENCE_CACHE = Duration.ofMinutes(5);

    private final CatalogueService catalogue;
    private final BrowsingContextResolver contexts;
    private final AuthenticatedCaller caller;

    public CatalogueController(CatalogueService catalogue, BrowsingContextResolver contexts,
                               AuthenticatedCaller caller) {
        this.catalogue = catalogue;
        this.contexts = contexts;
        this.caller = caller;
    }

    // ── Categories ───────────────────────────────────────────────────────────

    @GetMapping("/categories")
    @Operation(summary = "The category tree",
               description = "Nested, with product counts that include everything beneath each "
                       + "node. Cached — a shopper clicking Electronics expects the whole subtree.")
    public ResponseEntity<CatalogueResponses.CategoryTree> categories() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(REFERENCE_CACHE).cachePublic())
                .body(catalogue.categoryTree());
    }

    @GetMapping("/categories/{slug}")
    @Operation(summary = "One category, with the filters it can offer",
               description = "The attribute schema is derived from what is actually in the "
                       + "category, so a filter can never present a value nothing has. The price "
                       + "range is in the buyer's currency, not the vendors'.")
    public ResponseEntity<CatalogueResponses.CategoryDetail> category(
            @PathVariable String slug,
            @Parameter(description = "A delivery context id — where the goods are going")
            @RequestParam(required = false) String deliverableTo,
            @RequestParam(required = false) Double deliveryLat,
            @RequestParam(required = false) Double deliveryLng,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            Authentication authentication, HttpServletRequest request) {

        return ResponseEntity.ok(catalogue.categoryBySlug(slug,
                context(deliverableTo, deliveryLat, deliveryLng, deliveryCountry, currency,
                        authentication, request)));
    }

    // ── Products ─────────────────────────────────────────────────────────────

    @GetMapping("/products")
    @Operation(summary = "Browse products",
               description = "Ranked against the DELIVERY location, never the buyer's. Prices are "
                       + "converted into the buyer's currency, which is resolved from their browser "
                       + "and IP — the two are independent, and that is deliberate: a Madrid buyer "
                       + "shipping to Serrekunda pays in euro and browses Gambian stock.")
    public ResponseEntity<CatalogueResponses.ProductPage> products(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String store,
            @RequestParam(required = false) ProductCondition condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @Parameter(description = "A delivery context id — where the goods are going")
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
                pageOf(page, size)));
    }

    @GetMapping("/products/{slug}")
    @Operation(summary = "One product",
               description = "Priced in the buyer's currency, with serviceability to the delivery "
                       + "location when one was given. Answered here rather than at checkout "
                       + "because a diaspora buyer needs to know before they choose, not after.")
    public ResponseEntity<CatalogueResponses.ProductDetail> product(
            @PathVariable String slug,
            @RequestParam(required = false) String deliverableTo,
            @RequestParam(required = false) Double deliveryLat,
            @RequestParam(required = false) Double deliveryLng,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            Authentication authentication, HttpServletRequest request) {

        return ResponseEntity.ok(catalogue.productBySlug(slug,
                context(deliverableTo, deliveryLat, deliveryLng, deliveryCountry, currency,
                        authentication, request)));
    }

    @GetMapping("/products/{productId}/variants")
    @Operation(summary = "A product's variants, priced in the buyer's currency")
    public ResponseEntity<java.util.List<CatalogueResponses.Variant>> variants(
            @PathVariable Long productId,
            @RequestParam(required = false) String currency,
            Authentication authentication, HttpServletRequest request) {

        return ResponseEntity.ok(catalogue.variants(productId,
                context(null, null, null, null, currency, authentication, request)));
    }

    @GetMapping("/products/{productId}/related")
    @Operation(summary = "What else this shopper might consider",
               description = "Ranked against the same delivery location, which here mostly means "
                       + "'something else that can actually reach the recipient'.")
    public ResponseEntity<CatalogueResponses.ProductPage> related(
            @PathVariable Long productId,
            @RequestParam(required = false) String deliverableTo,
            @RequestParam(required = false) Double deliveryLat,
            @RequestParam(required = false) Double deliveryLng,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            Authentication authentication, HttpServletRequest request) {

        return ResponseEntity.ok(catalogue.related(productId,
                context(deliverableTo, deliveryLat, deliveryLng, deliveryCountry, currency,
                        authentication, request),
                pageOf(page, size)));
    }

    @GetMapping("/products/{productId}/reviews")
    @Operation(summary = "A product's reviews",
               description = "With the star distribution, because four stars from consensus and "
                       + "four stars from a fight are different products.")
    public ResponseEntity<CatalogueResponses.ReviewPage> reviews(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "newest") String sort) {

        return ResponseEntity.ok(catalogue.reviews(productId, reviewPage(page, size, sort)));
    }

    // ── Stores and brands ────────────────────────────────────────────────────

    @GetMapping("/stores/{slug}")
    @Operation(summary = "A public storefront",
               description = "A shop that is not approved, or has been suspended, is reported as "
                       + "not found rather than forbidden — it should not be discoverable by slug.")
    public ResponseEntity<CatalogueResponses.Store> store(@PathVariable String slug) {
        return ResponseEntity.ok(catalogue.storeBySlug(slug));
    }

    @GetMapping("/stores/{slug}/products")
    @Operation(summary = "What one store sells, ranked against the delivery location")
    public ResponseEntity<CatalogueResponses.ProductPage> storeProducts(
            @PathVariable String slug,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) ProductCondition condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false) String deliverableTo,
            @RequestParam(required = false) Double deliveryLat,
            @RequestParam(required = false) Double deliveryLng,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication, HttpServletRequest request) {

        ProductFilter filter = new ProductFilter(q, null, category, null, null, null,
                condition, minPrice, maxPrice, null, inStockOnly);

        return ResponseEntity.ok(catalogue.storeProducts(slug, filter,
                context(deliverableTo, deliveryLat, deliveryLng, deliveryCountry, currency,
                        authentication, request),
                pageOf(page, size)));
    }

    @GetMapping("/brands")
    @Operation(summary = "Brands with something to buy",
               description = "Only brands that actually have stock. A filter offering names with "
                       + "nothing behind them sends shoppers into empty pages, which reads as a "
                       + "broken search rather than an empty catalogue.")
    public ResponseEntity<CatalogueResponses.Brands> brands() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(REFERENCE_CACHE).cachePublic())
                .body(catalogue.brands());
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the two locations, separately.
     *
     * <p>The signed-in caller is passed because a delivery context bound to an
     * account can only be read by that account — not because being signed in
     * changes what the catalogue shows.
     */
    private BrowsingContext context(String deliverableTo, Double deliveryLat, Double deliveryLng,
                                    String deliveryCountry, String currency,
                                    Authentication authentication, HttpServletRequest request) {
        return contexts.resolve(
                CatalogueLocationParams.of(deliverableTo, deliveryLat, deliveryLng,
                        deliveryCountry, currency, null),
                request, caller.userIdOrNull(authentication));
    }

    private static Pageable pageOf(int page, int size) {
        // Unsorted: the browse query carries its own ranking, and a Pageable sort
        // would append an ORDER BY that overrides it.
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    private static Pageable reviewPage(int page, int size, String sort) {
        org.springframework.data.domain.Sort order = switch (sort == null ? "" : sort.toLowerCase()) {
            case "oldest" -> org.springframework.data.domain.Sort.by("createdAt").ascending();
            case "highest" -> org.springframework.data.domain.Sort.by("rating").descending()
                    .and(org.springframework.data.domain.Sort.by("createdAt").descending());
            case "lowest" -> org.springframework.data.domain.Sort.by("rating").ascending()
                    .and(org.springframework.data.domain.Sort.by("createdAt").descending());
            default -> org.springframework.data.domain.Sort.by("createdAt").descending();
        };
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), order);
    }
}
