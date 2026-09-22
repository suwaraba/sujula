package com.sujula.dto.response.catalogue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.ProductCondition;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** What the public catalogue returns. */
public final class CatalogueResponses {

    private CatalogueResponses() {}

    // ── Categories ───────────────────────────────────────────────────────────

    /**
     * One node of the category tree.
     *
     * <p>Nested rather than flat with parent ids, because a client renders a tree
     * and assembling one from a flat list is work every client would repeat.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CategoryNode(
            Long id,
            String name,
            String slug,
            String description,
            String imageUrl,
            int sortOrder,
            long productCount,
            List<CategoryNode> children) {}

    public record CategoryTree(List<CategoryNode> categories) {}

    /**
     * A category with what a filter panel needs to render itself.
     *
     * @param attributes the attribute names that actually appear on products in
     *                   this category, with the values they take. Derived from
     *                   the catalogue rather than configured, so a filter can
     *                   never offer a value nothing has
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CategoryDetail(
            CategoryNode category,
            List<CategoryNode> ancestors,
            List<AttributeSchema> attributes,
            List<FacetValue> brands,
            PriceRange priceRange) {}

    /** One filterable attribute and the values present in this category. */
    public record AttributeSchema(String name, List<String> values) {}

    /** What a price slider should span, in the buyer's currency. */
    public record PriceRange(String currency, BigDecimal min, BigDecimal max) {}

    // ── Products ─────────────────────────────────────────────────────────────

    /**
     * A product as a list shows it.
     *
     * @param price            in the buyer's display currency
     * @param listingCurrency  what the vendor set it in, so a client can say
     *                         "converted from" rather than implying the seller
     *                         priced it in euro
     * @param deliverable      whether this can reach the delivery location that
     *                         was given. Null when no destination was given —
     *                         which is not the same as false
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductCard(
            Long id,
            String slug,
            String name,
            String shortDescription,
            String imageUrl,
            BigDecimal price,
            BigDecimal compareAtPrice,
            String currency,
            BigDecimal listingPrice,
            String listingCurrency,
            boolean converted,
            ProductCondition condition,
            BigDecimal rating,
            Integer totalReviews,
            boolean inStock,
            Long vendorId,
            String storeName,
            String storeSlug,
            Long brandId,
            String brandName,
            Boolean deliverable,
            BigDecimal distanceKm) {}

    /** A page of products with the facets to narrow it. */
    public record ProductPage(
            List<ProductCard> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            String currency,
            Facets facets,
            AppliedDelivery delivery) {}

    /**
     * What the destination was resolved to, echoed back.
     *
     * <p>So a client can show "delivering to Serrekunda" and a developer can see
     * at a glance which location the ranking used — which is the question C1
     * exists to keep answerable.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppliedDelivery(
            String deliveryContextId,
            String deliveryCountry,
            boolean rankedByProximity,
            boolean needsPinConfirmation) {}

    public record Facets(
            List<FacetValue> categories,
            List<FacetValue> brands,
            List<FacetValue> conditions) {}

    public record FacetValue(String value, String label, long count) {}

    /**
     * The full product page.
     *
     * @param serviceability present only when a destination was given
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductDetail(
            Long id,
            String slug,
            String name,
            String shortDescription,
            String description,
            String sku,
            ProductCondition condition,
            BigDecimal price,
            BigDecimal compareAtPrice,
            String currency,
            BigDecimal listingPrice,
            String listingCurrency,
            boolean converted,
            BigDecimal rating,
            Integer totalReviews,
            Integer totalSold,
            boolean inStock,
            Integer stock,
            boolean allowBackorder,
            String dimensions,
            Double weightKg,
            Store store,
            Brand brand,
            CategoryNode category,
            List<Image> images,
            List<Attribute> attributes,
            List<Variant> variants,
            Serviceability serviceability) {}

    public record Image(Long id, String url, String altText, boolean isDefault, int sortOrder) {}

    public record Attribute(String name, String value) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Variant(
            Long id,
            String sku,
            BigDecimal price,
            String currency,
            BigDecimal listingPrice,
            String listingCurrency,
            Integer stock,
            boolean inStock,
            boolean active,
            Map<String, String> options) {}

    /**
     * Whether this product can reach the destination, and roughly when.
     *
     * <p>On the product page rather than at checkout because a diaspora buyer
     * needs to know before they choose, not after. Finding out at the last step
     * that a seller cannot ship to Serrekunda is how a cart gets abandoned.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Serviceability(
            boolean deliverable,
            BigDecimal distanceKm,
            boolean distanceEstimated,
            List<DeliveryOption> options,
            String message) {}

    public record DeliveryOption(
            DeliveryMode mode,
            boolean available,
            BigDecimal cost,
            String currency,
            Integer etaMinDays,
            Integer etaMaxDays,
            String reason) {}

    // ── Reviews and questions ────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Review(
            Long id,
            String authorName,
            int rating,
            String title,
            String comment,
            boolean verifiedPurchase,
            String vendorReply,
            LocalDateTime vendorRepliedAt,
            LocalDateTime createdAt) {}

    /**
     * @param histogram how many reviews sit at each star, so a client can draw
     *                  the distribution. Four stars from consensus and four
     *                  stars from a fight are different products
     */
    public record ReviewPage(
            List<Review> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            BigDecimal averageRating,
            Map<Integer, Long> histogram) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Question(
            Long id,
            String askedBy,
            String question,
            String answer,
            String answeredBy,
            LocalDateTime answeredAt,
            LocalDateTime askedAt) {}

    public record QuestionPage(List<Question> items, int page, int size,
                               long totalElements, int totalPages) {}

    /** What POST /products/{id}/questions returns: received, not yet visible. */
    public record QuestionSubmitted(Long id, String status, String message) {}

    // ── Stores and brands ────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Store(
            Long id,
            String name,
            String slug,
            String description,
            String logoUrl,
            String bannerUrl,
            String city,
            String countryCode,
            BigDecimal rating,
            Integer totalReviews,
            Integer totalSold,
            boolean acceptingOrders) {}

    public record Brand(Long id, String name, String slug, String logoUrl, String website) {}

    public record Brands(List<Brand> brands) {}

    // ── Search ───────────────────────────────────────────────────────────────

    public record Suggestions(String query, List<String> suggestions) {}
}
