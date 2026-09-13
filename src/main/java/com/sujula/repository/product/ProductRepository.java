package com.sujula.repository.product;


import com.sujula.model.products.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySlug(String slug);

    /**
     * Row-level exclusive lock on the product, taken before reading stock at
     * checkout. Prevents two concurrent orders from both seeing the same
     * available stock and both deducting it — the second blocks on the DB lock
     * until the first commits, then reads the already-reduced quantity. Must be
     * called inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    boolean existsByVendorIdAndNameIgnoreCase(Long vendorId, String productName);

    /**
     * Listings this vendor prices in some other currency. Checkout refuses a
     * product whose currency disagrees with its vendor's settlement currency, so
     * changing that currency has to account for what is already listed.
     */
    long countByVendorIdAndPriceCurrencyNot(Long vendorId, String priceCurrency);

    boolean existsByVendorIdAndNameIgnoreCaseAndIdNot(Long vendorId, String productName, Long productId);

    /**
     * The vendor's own catalogue, unpublished listings included — the browse
     * queries below all filter on active, so a vendor could otherwise never see
     * a product they had unpublished, let alone put it back.
     */
    Page<Product> findByVendorIdOrderByCreatedAtDesc(Long vendorId, Pageable pageable);

    // ── The seller's own back office ─────────────────────────────────────────

    /**
     * One listing, but only if this seller owns it.
     *
     * <p>Ownership is the query. Every write on the {@code /vendor/products}
     * surface resolves through this or its fetching sibling, so another
     * seller's product is not found rather than found and refused — and a
     * "forbidden" would confirm that product 4012 exists, which tells a
     * competitor how many listings the platform has.
     */
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.vendor.id = :vendorId")
    Optional<Product> findByIdAndVendorId(@Param("id") Long id, @Param("vendorId") Long vendorId);

    /**
     * The same, with the collections a detail response reads already loaded.
     *
     * <p>{@code spring.jpa.open-in-view} is off, so anything the response touches
     * has to be fetched inside the transaction. Images and variants are separate
     * queries rather than one join: fetching two collections in a single query
     * is a cartesian product, and Hibernate would rather throw than guess.
     */
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.images "
         + "WHERE p.id = :id AND p.vendor.id = :vendorId")
    Optional<Product> findByIdAndVendorIdWithImages(@Param("id") Long id,
                                                    @Param("vendorId") Long vendorId);

    /**
     * The seller's listings, including the ones no buyer can see.
     *
     * <p>Deliberately unlike every public query in this class, which filters on
     * {@code active}. A back office that hid drafts and suspended listings would
     * hide exactly the rows the seller has to act on.
     */
    @Query(value      = "SELECT p FROM Product p WHERE p.vendor.id = :vendorId "
                      + "AND (:status IS NULL OR p.status = :status) "
                      + "AND (:includeArchived = TRUE OR p.status <> com.sujula.model.constant.ProductStatus.ARCHIVED) "
                      + "AND (:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) "
                      + "     OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%'))) "
                      + "ORDER BY p.updatedAt DESC",
           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.vendor.id = :vendorId "
                      + "AND (:status IS NULL OR p.status = :status) "
                      + "AND (:includeArchived = TRUE OR p.status <> com.sujula.model.constant.ProductStatus.ARCHIVED) "
                      + "AND (:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) "
                      + "     OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Product> findForVendor(@Param("vendorId") Long vendorId,
                                @Param("status") com.sujula.model.constant.ProductStatus status,
                                @Param("includeArchived") boolean includeArchived,
                                @Param("search") String search,
                                Pageable pageable);

    /** Everything this seller has, for an export. Ordered so two exports match. */
    @Query("SELECT p FROM Product p WHERE p.vendor.id = :vendorId "
         + "AND (:includeArchived = TRUE OR p.status <> com.sujula.model.constant.ProductStatus.ARCHIVED) "
         + "ORDER BY p.id ASC")
    List<Product> findAllForExport(@Param("vendorId") Long vendorId,
                                   @Param("includeArchived") boolean includeArchived);

    /** How many listings this seller is in a given state of, for the back-office counts. */
    @Query("SELECT p.status, COUNT(p) FROM Product p WHERE p.vendor.id = :vendorId GROUP BY p.status")
    List<Object[]> countByStatusForVendor(@Param("vendorId") Long vendorId);

    boolean existsBySkuIgnoreCaseAndVendorId(String sku, Long vendorId);

    @Query("SELECT COUNT(p) > 0 FROM Product p WHERE LOWER(p.sku) = LOWER(:sku) "
         + "AND p.vendor.id = :vendorId AND p.id <> :exceptId")
    boolean existsBySkuForVendorExcept(@Param("sku") String sku, @Param("vendorId") Long vendorId,
                                       @Param("exceptId") Long exceptId);

    /**
     * Whether anybody has ever ordered this listing.
     *
     * <p>What decides archive from delete. An order line points at the product,
     * and a buyer's receipt, invoice and review all have to keep resolving years
     * later — so a listing somebody bought is never removed, whatever the seller
     * asks for.
     */
    @Query("SELECT COUNT(oi) > 0 FROM OrderItem oi WHERE oi.product.id = :productId")
    boolean hasBeenOrdered(@Param("productId") Long productId);

    // ── Proximity-ranked browse queries ──────────────────────────────────────
    // Every query below is a native query (MySQL — no PostGIS/spatial extension
    // available) that great-circle-distances each product from the DELIVERY
    // point — where the parcel is going, never where the person paying is.
    //
    // The distinction is the whole ranking. A buyer in Madrid sending a phone to
    // Serrekunda wants the stock that is two miles from their sister, not the
    // stock that is two miles from them and cannot reach her. These parameters
    // were once named userLat/userLng, which invited exactly that mistake:
    // a client reads "user" and sends the browser's own position.
    //
    // Distances are measured from (deliveryLat, deliveryLng)
    // using the Haversine formula. Products within `radiusKm` sort ahead of
    // everything else (bucket 0 vs 1); within a bucket, products on promotion
    // (compareAtPrice > price) sort first, then by `score` descending. Distance,
    // rating, etc. are only used as later tiebreakers. Passing null lat/lng simply
    // puts every product in bucket 1, so ordering falls back to promotion + score.

    String DISTANCE_KM_EXPR =
            "(6371 * acos(least(1.0, greatest(-1.0, " +
                    "cos(radians(:deliveryLat)) * cos(radians(p.latitude)) * cos(radians(p.longitude) - radians(:deliveryLng)) " +
                    "+ sin(radians(:deliveryLat)) * sin(radians(p.latitude))))))";

    String PROXIMITY_BUCKET_EXPR =
            "(CASE WHEN :deliveryLat IS NOT NULL AND :deliveryLng IS NOT NULL " +
                    "AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL " +
                    "AND " + DISTANCE_KM_EXPR + " <= :radiusKm THEN 0 ELSE 1 END)";

    String PROMOTION_RANK_EXPR =
            "(CASE WHEN p.compare_at_price IS NOT NULL AND p.compare_at_price > p.price THEN 0 ELSE 1 END)";

    String SCORE_EXPR = "COALESCE(p.score, 0)";

    String COUNTRY_FILTER_EXPR =
            "(:deliveryCountry IS NULL OR :deliveryCountry = '' " +
                    "OR p.delivery_scope = 'GLOBAL' OR LOWER(p.country) = LOWER(:deliveryCountry))";

    String RANK_ORDER = PROXIMITY_BUCKET_EXPR + " ASC, " + PROMOTION_RANK_EXPR + " ASC, " + SCORE_EXPR + " DESC";

    // ── The filtered browse ──────────────────────────────────────────────────
    // One query behind GET /products and GET /search. Every filter is optional
    // and written as "(:x IS NULL OR ...)", which lets one prepared statement
    // serve every combination rather than a query builder assembling SQL from
    // user input.
    //
    // The ranking reference point is the DELIVERY location, as everywhere else
    // in this file. A buyer in Madrid sending to Serrekunda is ranked against
    // Serrekunda.

    String FILTER_EXPR =
            "p.active = true " +
            "AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "AND (:brandId IS NULL OR p.brand_id = :brandId) " +
            "AND (:vendorId IS NULL OR p.vendor_id = :vendorId) " +
            "AND (:productCondition IS NULL OR p.product_condition = :productCondition) " +
            // Price bounds are in the vendor's listing currency, which is why the
            // service converts the buyer's bounds into it before binding them:
            // comparing a euro bound against a dalasi column silently returns
            // everything.
            "AND (:minPrice IS NULL OR p.price >= :minPrice) " +
            "AND (:maxPrice IS NULL OR p.price <= :maxPrice) " +
            "AND (:minRating IS NULL OR COALESCE(p.rating, 0) >= :minRating) " +
            // Backorderable stock still counts as buyable — a vendor who accepts
            // backorders has said so, and hiding their listing under
            // inStockOnly would be enforcing a stricter rule than they set.
            "AND (:inStockOnly = false OR p.stock > 0 OR p.allow_backorder = true) " +
            "AND (:query IS NULL OR :query = '' " +
            "     OR LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "     OR LOWER(COALESCE(p.short_description, '')) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "     OR LOWER(COALESCE(p.sku, '')) LIKE LOWER(CONCAT('%', :query, '%'))) " +
            "AND " + COUNTRY_FILTER_EXPR;

    @Query(
            value = "SELECT p.* FROM products p WHERE " + FILTER_EXPR + " " +
                    "ORDER BY " + RANK_ORDER + ", p.rating DESC, p.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM products p WHERE " + FILTER_EXPR,
            nativeQuery = true
    )
    Page<Product> browse(
            @Param("query") String query,
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            @Param("vendorId") Long vendorId,
            @Param("productCondition") String productCondition,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("minRating") java.math.BigDecimal minRating,
            @Param("inStockOnly") boolean inStockOnly,
            @Param("deliveryCountry") String deliveryCountry,
            @Param("deliveryLat") Double deliveryLat,
            @Param("deliveryLng") Double deliveryLng,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);

    // ── Facets ───────────────────────────────────────────────────────────────
    // Counted against the same filter the results used, minus the facet's own
    // dimension — otherwise every brand but the selected one reads as zero and
    // the shopper cannot widen their search without clearing it first.

    @Query(value = "SELECT p.brand_id, COUNT(*) FROM products p " +
                   "WHERE " + FILTER_EXPR + " AND p.brand_id IS NOT NULL " +
                   "GROUP BY p.brand_id ORDER BY COUNT(*) DESC",
           nativeQuery = true)
    List<Object[]> facetByBrand(
            @Param("query") String query, @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId, @Param("vendorId") Long vendorId,
            @Param("productCondition") String productCondition,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("minRating") java.math.BigDecimal minRating,
            @Param("inStockOnly") boolean inStockOnly,
            @Param("deliveryCountry") String deliveryCountry);

    @Query(value = "SELECT p.category_id, COUNT(*) FROM products p " +
                   "WHERE " + FILTER_EXPR + " AND p.category_id IS NOT NULL " +
                   "GROUP BY p.category_id ORDER BY COUNT(*) DESC",
           nativeQuery = true)
    List<Object[]> facetByCategory(
            @Param("query") String query, @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId, @Param("vendorId") Long vendorId,
            @Param("productCondition") String productCondition,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("minRating") java.math.BigDecimal minRating,
            @Param("inStockOnly") boolean inStockOnly,
            @Param("deliveryCountry") String deliveryCountry);

    @Query(value = "SELECT p.product_condition, COUNT(*) FROM products p " +
                   "WHERE " + FILTER_EXPR + " GROUP BY p.product_condition ORDER BY COUNT(*) DESC",
           nativeQuery = true)
    List<Object[]> facetByCondition(
            @Param("query") String query, @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId, @Param("vendorId") Long vendorId,
            @Param("productCondition") String productCondition,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("minRating") java.math.BigDecimal minRating,
            @Param("inStockOnly") boolean inStockOnly,
            @Param("deliveryCountry") String deliveryCountry);

    // ── Lookups ──────────────────────────────────────────────────────────────

    /** A published product by its slug, which is what a public URL carries. */
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.vendor LEFT JOIN FETCH p.category "
         + "LEFT JOIN FETCH p.brand WHERE LOWER(p.slug) = LOWER(:slug) AND p.active = true")
    Optional<Product> findPublishedBySlug(@Param("slug") String slug);

    /**
     * Typeahead.
     *
     * <p>Names only, and prefix-weighted: "sam" should surface Samsung before
     * something merely containing "sam" in the middle. Returns names rather than
     * entities because a suggestion list needs eight strings, not eight object
     * graphs.
     */
    @Query(value = "SELECT p.name FROM products p WHERE p.active = true " +
                   "AND LOWER(p.name) LIKE LOWER(CONCAT('%', :prefix, '%')) " +
                   "AND " + COUNTRY_FILTER_EXPR + " " +
                   "ORDER BY CASE WHEN LOWER(p.name) LIKE LOWER(CONCAT(:prefix, '%')) THEN 0 ELSE 1 END, " +
                   "COALESCE(p.total_sold, 0) DESC, p.name ASC " +
                   "LIMIT :limit",
           nativeQuery = true)
    List<String> suggestNames(@Param("prefix") String prefix,
                              @Param("deliveryCountry") String deliveryCountry,
                              @Param("limit") int limit);

    // ── Featured products ─────────────────────────────────────────────────────
    @Query(
            value = "SELECT p.* FROM products p " +
                    "WHERE p.active = true AND (:featured IS NULL OR p.featured = :featured) " +
                    "AND " + COUNTRY_FILTER_EXPR + " " +
                    "ORDER BY " + RANK_ORDER + ", p.rating DESC, p.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM products p " +
                    "WHERE p.active = true AND (:featured IS NULL OR p.featured = :featured) " +
                    "AND " + COUNTRY_FILTER_EXPR,
            nativeQuery = true
    )
    Page<Product> findFeaturedProducts(
            @Param("featured") Boolean featured,
            @Param("deliveryCountry") String deliveryCountry,
            @Param("deliveryLat") Double deliveryLat,
            @Param("deliveryLng") Double deliveryLng,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);

    // ── New arrivals ──────────────────────────────────────────────────────────
    @Query(
            value = "SELECT p.* FROM products p " +
                    "WHERE p.active = true AND " + COUNTRY_FILTER_EXPR + " " +
                    "ORDER BY " + RANK_ORDER + ", p.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM products p " +
                    "WHERE p.active = true AND " + COUNTRY_FILTER_EXPR,
            nativeQuery = true
    )
    Page<Product> findNewArrivalsProducts(
            @Param("deliveryCountry") String deliveryCountry,
            @Param("deliveryLat") Double deliveryLat,
            @Param("deliveryLng") Double deliveryLng,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);

    // ── Best sellers ──────────────────────────────────────────────────────────
    @Query(
            value = "SELECT p.* FROM products p " +
                    "WHERE p.active = true AND p.total_sold > 0 AND " + COUNTRY_FILTER_EXPR + " " +
                    "ORDER BY " + RANK_ORDER + ", p.total_sold DESC",
            countQuery = "SELECT COUNT(*) FROM products p " +
                    "WHERE p.active = true AND p.total_sold > 0 AND " + COUNTRY_FILTER_EXPR,
            nativeQuery = true
    )
    Page<Product> findBestSellersProducts(
            @Param("deliveryCountry") String deliveryCountry,
            @Param("deliveryLat") Double deliveryLat,
            @Param("deliveryLng") Double deliveryLng,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);

    // ── By category ───────────────────────────────────────────────────────────
    @Query(
            value = "SELECT p.* FROM products p " +
                    "WHERE p.active = true AND p.category_id = :categoryId AND " + COUNTRY_FILTER_EXPR + " " +
                    "ORDER BY " + RANK_ORDER + ", p.rating DESC",
            countQuery = "SELECT COUNT(*) FROM products p " +
                    "WHERE p.active = true AND p.category_id = :categoryId AND " + COUNTRY_FILTER_EXPR,
            nativeQuery = true
    )
    Page<Product> findByCategoryProducts(
            @Param("categoryId") Long categoryId,
            @Param("deliveryCountry") String deliveryCountry,
            @Param("deliveryLat") Double deliveryLat,
            @Param("deliveryLng") Double deliveryLng,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);

    // ── Similar products (same category, excluding the product itself) ──────────
    @Query(
            value = "SELECT p.* FROM products p " +
                    "WHERE p.active = true AND p.category_id = :categoryId AND p.id <> :excludeProductId " +
                    "AND " + COUNTRY_FILTER_EXPR + " " +
                    "ORDER BY " + RANK_ORDER + ", p.rating DESC",
            countQuery = "SELECT COUNT(*) FROM products p " +
                    "WHERE p.active = true AND p.category_id = :categoryId AND p.id <> :excludeProductId " +
                    "AND " + COUNTRY_FILTER_EXPR,
            nativeQuery = true
    )
    Page<Product> findSimilarProducts(
            @Param("categoryId") Long categoryId,
            @Param("excludeProductId") Long excludeProductId,
            @Param("deliveryCountry") String deliveryCountry,
            @Param("deliveryLat") Double deliveryLat,
            @Param("deliveryLng") Double deliveryLng,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);

    // ── Keyword search ────────────────────────────────────────────────────────
    @Query(
            value = "SELECT p.* FROM products p " +
                    "WHERE p.active = true AND (" +
                    "LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) " +
                    "OR LOWER(p.short_description) LIKE LOWER(CONCAT('%', :query, '%')) " +
                    "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%'))) " +
                    "AND " + COUNTRY_FILTER_EXPR + " " +
                    "ORDER BY " + RANK_ORDER + ", p.rating DESC, p.total_sold DESC",
            countQuery = "SELECT COUNT(*) FROM products p " +
                    "WHERE p.active = true AND (" +
                    "LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) " +
                    "OR LOWER(p.short_description) LIKE LOWER(CONCAT('%', :query, '%')) " +
                    "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%'))) " +
                    "AND " + COUNTRY_FILTER_EXPR,
            nativeQuery = true
    )
    Page<Product> searchNearUser(
            @Param("query") String query,
            @Param("deliveryCountry") String deliveryCountry,
            @Param("deliveryLat") Double deliveryLat,
            @Param("deliveryLng") Double deliveryLng,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);
}
