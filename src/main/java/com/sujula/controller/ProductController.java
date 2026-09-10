package com.sujula.controller;

import com.sujula.dto.request.product.ImageOrderRequest;
import com.sujula.dto.request.product.ProductImageRequest;
import com.sujula.dto.request.product.ProductRequest;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.PresignedUploadResponse;
import com.sujula.dto.response.product.ProductCardResponse;
import com.sujula.dto.response.product.ProductImageResponse;
import com.sujula.dto.response.product.ProductResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.user.User;
import com.sujula.service.ProductService;
import com.sujula.service.StorageService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The catalogue.
 *
 * <p>Two audiences, kept apart by path. Everything under {@code /api/products}
 * that is a plain GET is the shop front: open to anyone, published products
 * only, priced in whatever currency the caller asks for. Everything under
 * {@code /api/products/mine} is the seller's own back office: their products
 * only, unpublished ones included, priced in their own settlement currency and
 * never converted — a vendor is never shown a buyer's currency.
 *
 * <p>The browse endpoints all take the shopper's coordinates and delivery
 * country. Both are optional and both change what comes back: products that can
 * reach the given country sort ahead of those that cannot, and products near the
 * given point sort ahead of those far from it. Omitting them is not an error —
 * ranking simply falls back to promotion and score.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    /** Above this a page stops being a page and becomes an export of the catalogue. */
    private static final int MAX_PAGE_SIZE = 100;

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

    private final ProductService productService;
    private final StorageService storageService;

    public ProductController(ProductService productService, StorageService storageService) {
        this.productService = productService;
        this.storageService = storageService;
    }

    // ── Shop front (no login) ────────────────────────────────────────────────

    @GetMapping("/featured")
    public ResponseEntity<PagedResponse<ProductCardResponse>> featured(
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Double userLat,
            @RequestParam(required = false) Double userLng,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(productService.findFeaturedProducts(
                Boolean.TRUE, deliveryCountry, currency,
                validCoordinate(userLat, 90, "userLat"), validCoordinate(userLng, 180, "userLng"),
                pageOf(page, size))));
    }

    @GetMapping("/new-arrivals")
    public ResponseEntity<PagedResponse<ProductCardResponse>> newArrivals(
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Double userLat,
            @RequestParam(required = false) Double userLng,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(productService.findNewArrivalsProducts(
                deliveryCountry, currency,
                validCoordinate(userLat, 90, "userLat"), validCoordinate(userLng, 180, "userLng"),
                pageOf(page, size))));
    }

    @GetMapping("/best-sellers")
    public ResponseEntity<PagedResponse<ProductCardResponse>> bestSellers(
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Double userLat,
            @RequestParam(required = false) Double userLng,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(productService.findBestSellersProducts(
                deliveryCountry, currency,
                validCoordinate(userLat, 90, "userLat"), validCoordinate(userLng, 180, "userLng"),
                pageOf(page, size))));
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<PagedResponse<ProductCardResponse>> byCategory(
            @PathVariable Long categoryId,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Double userLat,
            @RequestParam(required = false) Double userLng,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(productService.findByCategoryProducts(
                categoryId, deliveryCountry, currency,
                validCoordinate(userLat, 90, "userLat"), validCoordinate(userLng, 180, "userLng"),
                pageOf(page, size))));
    }

    /** Search. Location-aware in exactly the same way as the browse endpoints above. */
    @GetMapping("/search")
    public ResponseEntity<PagedResponse<ProductCardResponse>> search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Double userLat,
            @RequestParam(required = false) Double userLng,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(productService.searchNearUser(
                trimToNull(query), deliveryCountry, currency,
                validCoordinate(userLat, 90, "userLat"), validCoordinate(userLng, 180, "userLng"),
                pageOf(page, size))));
    }

    @GetMapping("/{productId}/similar")
    public ResponseEntity<PagedResponse<ProductCardResponse>> similar(
            @PathVariable Long productId,
            @RequestParam(required = false) String deliveryCountry,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Double userLat,
            @RequestParam(required = false) Double userLng,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(PagedResponse.of(productService.findSimilarProducts(
                productId, deliveryCountry, currency,
                validCoordinate(userLat, 90, "userLat"), validCoordinate(userLng, 180, "userLng"),
                pageOf(page, size))));
    }

    /** The product page. 404 for an unpublished product, whether or not it once existed. */
    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> get(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.findPublishedById(productId));
    }

    // ── Seller back office ───────────────────────────────────────────────────
    // Scoped to the principal throughout: the vendor id comes from the session,
    // never from the URL, so there is no path to another seller's catalogue.

    @GetMapping("/mine")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<PagedResponse<ProductResponse>> myProducts(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(
                productService.findMyProducts(currentUserId(authentication), pageOf(page, size))));
    }

    @GetMapping("/mine/{productId}")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<ProductResponse> myProduct(Authentication authentication,
                                                     @PathVariable Long productId) {
        return ResponseEntity.ok(
                productService.findMineById(currentUserId(authentication), productId));
    }

    @PostMapping
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<ProductResponse> create(Authentication authentication,
                                                  @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.create(currentUserId(authentication), request));
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<ProductResponse> update(Authentication authentication,
                                                  @PathVariable Long productId,
                                                  @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(
                productService.update(productId, currentUserId(authentication), request));
    }

    /**
     * Withdraws the listing. The row survives — orders already placed still point
     * at it — so this is an unpublish, and the product can be listed again by
     * setting {@code active} back on.
     */
    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long productId) {
        productService.delete(productId, currentUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    // ── Product images ───────────────────────────────────────────────────────

    /**
     * Hands back a short-lived URL the vendor uploads the file to directly, plus
     * the public URL that upload will have. Post that public URL back to
     * {@link #addImage}; the file itself never travels through this service.
     */
    @PostMapping("/images/presign")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<PresignedUploadResponse> presignImageUpload(Authentication authentication,
                                                                      @RequestParam String contentType) {
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BadRequestException(
                    "Unsupported content type: " + contentType + ". Allowed: " + ALLOWED_IMAGE_TYPES);
        }

        String filename = "product-" + currentUserId(authentication) + "-"
                + UUID.randomUUID() + extensionFor(contentType);
        return ResponseEntity.ok(PresignedUploadResponse.builder()
                .uploadUrl(storageService.presignUpload("products", filename, contentType, PRESIGN_TTL))
                .publicUrl(storageService.publicUrl("products", filename))
                .build());
    }

    @PostMapping("/{productId}/images")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<ProductImageResponse> addImage(Authentication authentication,
                                                         @PathVariable Long productId,
                                                         @Valid @RequestBody ProductImageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addImage(
                productId, currentUserId(authentication),
                request.getImageUrl(), request.getAltText(), request.isMakeDefault()));
    }

    @PutMapping("/{productId}/images/order")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<List<ProductImageResponse>> reorderImages(Authentication authentication,
                                                                    @PathVariable Long productId,
                                                                    @Valid @RequestBody ImageOrderRequest request) {
        return ResponseEntity.ok(productService.reorderImages(
                productId, currentUserId(authentication), request.getImageIds()));
    }

    @PatchMapping("/images/{imageId}/default")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<ProductImageResponse> setDefaultImage(Authentication authentication,
                                                                @PathVariable Long imageId) {
        return ResponseEntity.ok(
                productService.setDefaultImage(imageId, currentUserId(authentication)));
    }

    @DeleteMapping("/images/{imageId}")
    @PreAuthorize("hasRole('VENDOR')")
    public ResponseEntity<Void> deleteImage(Authentication authentication, @PathVariable Long imageId) {
        productService.deleteImage(imageId, currentUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Pageable pageOf(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page cannot be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // Deliberately unsorted: the browse queries are native and carry their own
        // ranking, which a Pageable sort would append to and break.
        return PageRequest.of(page, size);
    }

    /**
     * A latitude of 900 is not a near-miss, it is a broken client — and left
     * unchecked it reaches the distance expression, where MySQL clamps it and
     * silently ranks the whole catalogue as if the shopper were somewhere else.
     */
    private static Double validCoordinate(Double value, double limit, String name) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || Math.abs(value) > limit) {
            throw new BadRequestException(name + " must be between -" + limit + " and " + limit);
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        throw new AccessDeniedException("Authentication is required");
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}
