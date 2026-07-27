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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
//
//    Page<Product> findByVendorIdAndActiveTrue(Long vendorId, Pageable pageable);
//    long countByVendorId(Long vendorId);
//    long countByVendorIdAndActiveTrue(Long vendorId);
//    Page<Product> findByCategoryIdAndActiveTrue(Long categoryId, Pageable pageable);
//    Page<Product> findByActiveTrue(Pageable pageable);
//    long countByActiveTrue();
//    Page<Product> findByFeaturedTrueAndActiveTrue(Pageable pageable);
    boolean existsBySlug(String slug);

    boolean existsByVendorIdAndNameIgnoreCase(Long vendorId, String productName);
//
//    // ── Admin queries — eagerly fetch vendor/category to avoid LazyInitializationException ──
//
//    /**
//     * Fetch all products (including inactive) with vendor and category eagerly loaded.
//     * Used by admin product list — avoids LazyInitializationException when
//     * spring.jpa.open-in-view=false.
//     */
//    @Query(value = "SELECT p FROM Product p LEFT JOIN FETCH p.vendor LEFT JOIN FETCH p.category",
//           countQuery = "SELECT count(p) FROM Product p")
//    Page<Product> findAllFetchVendorAndCategory(Pageable pageable);
//
//    /**
//     * Fetch only active products with vendor and category eagerly loaded.
//     */
//    @Query(value = "SELECT p FROM Product p LEFT JOIN FETCH p.vendor LEFT JOIN FETCH p.category WHERE p.active = true",
//           countQuery = "SELECT count(p) FROM Product p WHERE p.active = true")
//    Page<Product> findByActiveTrueFetchVendorAndCategory(Pageable pageable);
//
//    /**
//     * Admin filtered product list — supports optional category, vendor, and active filters.
//     * All three are optional (null = no restriction for that dimension).
//     * JOIN FETCH vendor and category to avoid LazyInitializationException.
//     */
//    @Query(value = """
//        SELECT DISTINCT p FROM Product p
//        LEFT JOIN FETCH p.vendor v
//        LEFT JOIN FETCH p.category c
//        WHERE (:categoryId IS NULL OR c.id = :categoryId)
//          AND (:vendorId   IS NULL OR v.id = :vendorId)
//          AND (:active     IS NULL OR p.active = :active)
//        """,
//        countQuery = """
//        SELECT count(p) FROM Product p
//        LEFT JOIN p.vendor v
//        LEFT JOIN p.category c
//        WHERE (:categoryId IS NULL OR c.id = :categoryId)
//          AND (:vendorId   IS NULL OR v.id = :vendorId)
//          AND (:active     IS NULL OR p.active = :active)
//        """)
//    Page<Product> findFiltered(
//            @Param("categoryId") Long categoryId,
//            @Param("vendorId")   Long vendorId,
//            @Param("active")     Boolean active,
//            Pageable pageable);
//
//    // ── Basic keyword search (used by simple product-list endpoints) ──────────
//    @Query("""
//        SELECT p FROM Product p WHERE p.active = true AND (
//            LOWER(p.name)             LIKE LOWER(CONCAT('%', :q, '%')) OR
//            LOWER(p.shortDescription) LIKE LOWER(CONCAT('%', :q, '%')) OR
//            LOWER(p.description)      LIKE LOWER(CONCAT('%', :q, '%'))
//        )
//    """)
//    Page<Product> searchProducts(@Param("q") String query, Pageable pageable);
//
//    // ── Legacy paginated search (ProductController simple endpoints) ──────────
//    @Query("""
//        SELECT p FROM Product p
//        LEFT JOIN p.category   cat
//        LEFT JOIN p.brand      br
//        LEFT JOIN p.vendor     v
//        WHERE p.active = true
//          AND (:q = ''
//               OR LOWER(p.name)             LIKE LOWER(CONCAT('%', :q, '%'))
//               OR LOWER(p.shortDescription) LIKE LOWER(CONCAT('%', :q, '%'))
//               OR LOWER(p.description)      LIKE LOWER(CONCAT('%', :q, '%')))
//          AND (:categoryId IS NULL OR cat.id = :categoryId)
//          AND (:brandId    IS NULL OR br.id  = :brandId)
//          AND (:vendorId   IS NULL OR v.id   = :vendorId)
//          AND (:minPrice   IS NULL OR p.price >= :minPrice)
//          AND (:maxPrice   IS NULL OR p.price <= :maxPrice)
//          AND (:minRating  IS NULL OR p.rating >= :minRating)
//          AND (:countryCode = ''
//               OR p.countryCode = :countryCode
//               OR p.deliveryScope = 'GLOBAL')
//          AND (:inStockOnly = false OR p.stockQuantity > 0)
//    """)
//    Page<Product> advancedSearch(
//            @Param("q")           String q,
//            @Param("categoryId")  Long categoryId,
//            @Param("brandId")     Long brandId,
//            @Param("vendorId")    Long vendorId,
//            @Param("minPrice")    BigDecimal minPrice,
//            @Param("maxPrice")    BigDecimal maxPrice,
//            @Param("minRating")   BigDecimal minRating,
//            @Param("countryCode") String countryCode,
//            @Param("inStockOnly") boolean inStockOnly,
//            Pageable pageable
//    );
//
//    /**
//     * All products (active and inactive) for a single vendor, with category eagerly fetched.
//     * Used by the admin vendor-profile Products tab.
//     */
//    @Query(value      = "SELECT p FROM Product p LEFT JOIN FETCH p.category WHERE p.vendor.id = :vendorId",
//           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.vendor.id = :vendorId")
//    Page<Product> findByVendorIdFetchCategory(@Param("vendorId") Long vendorId, Pageable pageable);
//
//    /**
//     * Returns all active products for a vendor that are at or below their low-stock
//     * threshold. Filtering is done in the DB — avoids the previous
//     * {@code Integer.MAX_VALUE} page fetch that loaded the entire product table.
//     */
//    @Query("""
//        SELECT p FROM Product p
//        WHERE p.vendor.id = :vendorId
//          AND p.active = true
//          AND p.lowStockThreshold IS NOT NULL
//          AND p.stockQuantity <= p.lowStockThreshold
//        ORDER BY p.stockQuantity ASC
//        """)
//    List<Product> findLowStockByVendorId(@Param("vendorId") Long vendorId);
//
//    // ── Revenue / analytics helpers ───────────────────────────────────────────
//
//    /**
//     * Count active products grouped by priceCurrency.
//     * Returns rows: [priceCurrency (String), count (Long)].
//     * Used by admin Revenue analytics to show vendor product-currency distribution.
//     */
//    @Query("SELECT p.priceCurrency, COUNT(p) FROM Product p WHERE p.active = true GROUP BY p.priceCurrency ORDER BY COUNT(p) DESC")
//    List<Object[]> countActiveByPriceCurrency();
//
//    // ── Unpaginated candidates for distance-scored listing ────────────────────
//    // JOIN FETCH p.vendor is required: ProductScoringService accesses vendor
//    // coordinates during scoring (outside the original transaction boundary).
//
//    /** All active products for distance scoring. Vendor and images eagerly loaded. */
//    @Query("""
//        SELECT DISTINCT p FROM Product p
//        JOIN FETCH p.vendor v
//        LEFT JOIN FETCH p.images
//        WHERE p.active = true
//          AND (:cc = '' OR p.deliveryScope = 'GLOBAL' OR p.countryCode = :cc)
//        """)
//    List<Product> findAllCandidatesForCountry(@Param("cc") String cc);
//
//    /** Featured active products for distance scoring. Vendor and images eagerly loaded. */
//    @Query("""
//        SELECT DISTINCT p FROM Product p
//        JOIN FETCH p.vendor v
//        LEFT JOIN FETCH p.images
//        WHERE p.active = true AND p.featured = true
//          AND (:cc = '' OR p.deliveryScope = 'GLOBAL' OR p.countryCode = :cc)
//        """)
//    List<Product> findFeaturedCandidatesForCountry(@Param("cc") String cc);
//
//    /** Active products in a category for distance scoring. Vendor and images eagerly loaded. */
//    @Query("""
//        SELECT DISTINCT p FROM Product p
//        JOIN FETCH p.vendor v
//        LEFT JOIN FETCH p.images
//        WHERE p.active = true AND p.category.id = :catId
//          AND (:cc = '' OR p.deliveryScope = 'GLOBAL' OR p.countryCode = :cc)
//        """)
//    List<Product> findCategoryCandidatesForCountry(
//            @Param("catId") Long catId,
//            @Param("cc")    String cc);
//
//    // ── Delivery-country filtered browse (used by dedicated list endpoints) ────
//
//    /**
//     * All active products filtered by delivery country.
//     * Pass {@code cc = ""} to disable country filtering (returns all active).
//     * A product matches when:
//     *   • deliveryScope = 'GLOBAL' (available everywhere), OR
//     *   • countryCode = :cc (vendor's country matches user's country)
//     */
//    @Query("""
//        SELECT p FROM Product p WHERE p.active = true
//          AND (:cc = '' OR p.deliveryScope = 'GLOBAL' OR p.countryCode = :cc)
//        """)
//    Page<Product> findAllForCountry(@Param("cc") String cc, Pageable pageable);
//
//    @Query("""
//        SELECT p FROM Product p WHERE p.active = true AND p.featured = true
//          AND (:cc = '' OR p.deliveryScope = 'GLOBAL' OR p.countryCode = :cc)
//        """)
//    Page<Product> findFeaturedForCountry(@Param("cc") String cc, Pageable pageable);
//
//    @Query("""
//        SELECT p FROM Product p WHERE p.active = true AND p.category.id = :catId
//          AND (:cc = '' OR p.deliveryScope = 'GLOBAL' OR p.countryCode = :cc)
//        """)
//    Page<Product> findByCategoryForCountry(
//            @Param("catId") Long catId,
//            @Param("cc")    String cc,
//            Pageable pageable);
//
//    // ── Scored search candidate pool ──────────────────────────────────────────
//    /**
//     * Returns all products that pass the hard filters.
//     * No sorting is applied here — scoring and ranking is done in Java
//     * by {@code ProductScoringService}, which then paginates the result.
//     *
//     * Country/global logic:
//     *   If deliveryCountry is provided, include products where:
//     *     • the vendor is in that country  (vendor.addressCountryCode = deliveryCountry)
//     *     • OR the product has GLOBAL scope (always visible everywhere)
//     *   If deliveryCountry is null → no country restriction.
//     *
//     * Bounding-box pre-filter (applied only when latMin is not null):
//     *   Uses the product's own coordinates first, falling back to the vendor's.
//     *   Products/vendors with no coordinates always pass the bounding box.
//     */
//    @Query("""
//        SELECT DISTINCT p FROM Product p
//        JOIN FETCH p.vendor v
//        LEFT JOIN FETCH p.images
//        LEFT JOIN FETCH p.category cat
//        LEFT JOIN FETCH p.brand    br
//        WHERE p.active = true
//          AND (:q = ''
//               OR LOWER(p.name)             LIKE LOWER(CONCAT('%', :q, '%'))
//               OR LOWER(p.shortDescription) LIKE LOWER(CONCAT('%', :q, '%'))
//               OR LOWER(p.description)      LIKE LOWER(CONCAT('%', :q, '%')))
//          AND (:categoryId IS NULL OR cat.id = :categoryId)
//          AND (:brandId    IS NULL OR br.id  = :brandId)
//          AND (:vendorId   IS NULL OR v.id   = :vendorId)
//          AND (:minPrice IS NULL OR p.price >= :minPrice)
//          AND (:maxPrice IS NULL OR p.price <= :maxPrice)
//          AND (:minRating IS NULL OR p.rating >= :minRating)
//          AND (:inStockOnly = false OR p.stockQuantity > 0)
//          AND (:deliveryCountry = ''
//               OR v.addressCountryCode = :deliveryCountry
//               OR p.deliveryScope      = 'GLOBAL')
//          AND (:latMin IS NULL
//               OR COALESCE(p.latitude,  v.latitude)  IS NULL
//               OR (COALESCE(p.latitude,  v.latitude)  >= :latMin
//               AND COALESCE(p.latitude,  v.latitude)  <= :latMax
//               AND COALESCE(p.longitude, v.longitude) >= :lngMin
//               AND COALESCE(p.longitude, v.longitude) <= :lngMax))
//    """)
//    List<Product> findCandidates(
//            @Param("q")               String     q,
//            @Param("categoryId")      Long       categoryId,
//            @Param("brandId")         Long       brandId,
//            @Param("vendorId")        Long       vendorId,
//            @Param("minPrice")        BigDecimal minPrice,
//            @Param("maxPrice")        BigDecimal maxPrice,
//            @Param("minRating")       BigDecimal minRating,
//            @Param("inStockOnly")     boolean    inStockOnly,
//            @Param("deliveryCountry") String     deliveryCountry,
//            @Param("latMin")          Double     latMin,
//            @Param("latMax")          Double     latMax,
//            @Param("lngMin")          Double     lngMin,
//            @Param("lngMax")          Double     lngMax
//    );
//
//    // ── Analytics queries ─────────────────────────────────────────────────────
//
//    /**
//     * Dead-stock: active products with zero sales in the given period.
//     * Fetches vendor eagerly to avoid lazy-load when building the response.
//     */
//    @Query(value = "SELECT p FROM Product p LEFT JOIN FETCH p.vendor WHERE p.active = true AND p.id NOT IN (SELECT DISTINCT oi.product.id FROM OrderItem oi WHERE oi.order.createdAt >= :since AND oi.order.status <> com.sujula.model.enums.OrderStatus.CANCELLED)",
//           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.active = true AND p.id NOT IN (SELECT DISTINCT oi.product.id FROM OrderItem oi WHERE oi.order.createdAt >= :since AND oi.order.status <> com.sujula.model.enums.OrderStatus.CANCELLED)")
//    Page<Product> findDeadStock(@Param("since") java.time.LocalDateTime since, Pageable pageable);
//
//    /**
//     * Acquire a row-level exclusive lock on the product row before reading.
//     * Used during order placement to prevent concurrent stock deductions from
//     * overselling the same product (eliminates the check-then-act TOCTOU race).
//     * Must be called inside an active @Transactional context.
//     */
//    @Lock(LockModeType.PESSIMISTIC_WRITE)
//    @Query("SELECT p FROM Product p WHERE p.id = :id")
//    Optional<Product> findByIdForUpdate(@Param("id") Long id);
//
//
//    @Query(
//    value = """
//        SELECT p.*
//        FROM products p
//        WHERE p.active = true
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//        ORDER BY
//          p.created_at DESC,
//
//          CASE
//            WHEN p.delivery_scope = 'LOCAL'
//                 AND :userLat IS NOT NULL
//                 AND :userLng IS NOT NULL
//                 AND p.latitude IS NOT NULL
//                 AND p.longitude IS NOT NULL
//            THEN
//              6371 * acos(
//                least(1, greatest(-1,
//                  cos(radians(:userLat)) *
//                  cos(radians(p.latitude)) *
//                  cos(radians(p.longitude) - radians(:userLng)) +
//                  sin(radians(:userLat)) *
//                  sin(radians(p.latitude))
//                ))
//              )
//            ELSE NULL
//          END ASC
//    """,
//    countQuery = """
//        SELECT COUNT(*)
//        FROM products p
//        WHERE p.active = true
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//    """,
//    nativeQuery = true
//)
//Page<Product> findNewArrivalsProducts(
//        @Param("deliveryCountry") String deliveryCountry,
//        @Param("userLat") Double userLat,
//        @Param("userLng") Double userLng,
//        Pageable pageable
//);
//
//
//@Query(
//    value = """
//        SELECT p.*
//        FROM products p
//        JOIN vendors v ON v.id = p.vendor_id
//        WHERE p.active = true
//          AND (:featured IS NULL OR p.featured = :featured)
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//        ORDER BY
//          CASE
//            WHEN p.delivery_scope = 'LOCAL'
//                 AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//            THEN 0
//            ELSE 1
//          END ASC,
//
//          CASE
//            WHEN :hasLocation = true
//                 AND p.delivery_scope = 'LOCAL'
//                 AND p.latitude IS NOT NULL
//                 AND p.longitude IS NOT NULL
//            THEN
//              6371 * acos(
//                least(1, greatest(-1,
//                  cos(radians(CAST(:userLat AS double precision))) *
//                  cos(radians(p.latitude)) *
//                  cos(radians(p.longitude) - radians(CAST(:userLng AS double precision))) +
//                  sin(radians(CAST(:userLat AS double precision))) *
//                  sin(radians(p.latitude))
//                ))
//              )
//            ELSE 999999
//          END ASC,
//
//          p.created_at DESC
//    """,
//    countQuery = """
//        SELECT COUNT(*)
//        FROM products p
//        WHERE p.active = true
//          AND (:featured IS NULL OR p.featured = :featured)
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//    """,
//    nativeQuery = true
//)
//Page<Product> findFeaturedProductsNearUser(
//        @Param("featured") Boolean featured,
//        @Param("deliveryCountry") String deliveryCountry,
//        @Param("userLat") Double userLat,
//        @Param("userLng") Double userLng,
//        @Param("hasLocation") Boolean hasLocation,
//        Pageable pageable
//);
//
//@Query(
//    value = """
//        SELECT p.*
//        FROM products p
//        WHERE p.active = true
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//        ORDER BY
//          p.total_sold DESC,
//
//          CASE
//            WHEN p.delivery_scope = 'LOCAL'
//                 AND :userLat IS NOT NULL
//                 AND :userLng IS NOT NULL
//                 AND p.latitude IS NOT NULL
//                 AND p.longitude IS NOT NULL
//            THEN
//              6371 * acos(
//                least(1, greatest(-1,
//                  cos(radians(:userLat)) *
//                  cos(radians(p.latitude)) *
//                  cos(radians(p.longitude) - radians(:userLng)) +
//                  sin(radians(:userLat)) *
//                  sin(radians(p.latitude))
//                ))
//              )
//            ELSE NULL
//          END ASC,
//
//          p.created_at DESC
//    """,
//    countQuery = """
//        SELECT COUNT(*)
//        FROM products p
//        WHERE p.active = true
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//    """,
//    nativeQuery = true
//)
//Page<Product> findBestSellersProducts(
//        @Param("deliveryCountry") String deliveryCountry,
//        @Param("userLat") Double userLat,
//        @Param("userLng") Double userLng,
//        Pageable pageable
//);
//
//@Query(
//    value = """
//        SELECT p.*
//        FROM products p
//        WHERE p.active = true
//          AND p.category_id = :categoryId
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//        ORDER BY
//          CASE
//            WHEN p.delivery_scope = 'LOCAL'
//                 AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//            THEN 0
//            ELSE 1
//          END ASC,
//
//          CASE
//            WHEN :hasLocation = true
//                 AND p.delivery_scope = 'LOCAL'
//                 AND p.latitude IS NOT NULL
//                 AND p.longitude IS NOT NULL
//            THEN
//              6371 * acos(
//                least(1, greatest(-1,
//                  cos(radians(CAST(:userLat AS double precision))) *
//                  cos(radians(p.latitude)) *
//                  cos(radians(p.longitude) - radians(CAST(:userLng AS double precision))) +
//                  sin(radians(CAST(:userLat AS double precision))) *
//                  sin(radians(p.latitude))
//                ))
//              )
//            ELSE 999999
//          END ASC,
//
//          p.created_at DESC
//    """,
//    countQuery = """
//        SELECT COUNT(*)
//        FROM products p
//        WHERE p.active = true
//          AND p.category_id = :categoryId
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//    """,
//    nativeQuery = true
//)
//Page<Product> findByCategoryNearUser(
//        @Param("categoryId")      Long    categoryId,
//        @Param("deliveryCountry") String  deliveryCountry,
//        @Param("userLat")         Double  userLat,
//        @Param("userLng")         Double  userLng,
//        @Param("hasLocation")     Boolean hasLocation,
//        Pageable pageable
//);
//
//@Query(
//    value = """
//        SELECT p.*
//        FROM products p
//        WHERE p.active = true
//          AND (:query = ''
//               OR LOWER(p.name)              LIKE LOWER(CONCAT('%', :query, '%'))
//               OR LOWER(p.short_description) LIKE LOWER(CONCAT('%', :query, '%'))
//               OR LOWER(p.description)       LIKE LOWER(CONCAT('%', :query, '%')))
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//        ORDER BY
//          CASE
//            WHEN p.delivery_scope = 'LOCAL'
//                 AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//            THEN 0
//            ELSE 1
//          END ASC,
//
//          CASE
//            WHEN :hasLocation = true
//                 AND p.delivery_scope = 'LOCAL'
//                 AND p.latitude IS NOT NULL
//                 AND p.longitude IS NOT NULL
//            THEN
//              6371 * acos(
//                least(1, greatest(-1,
//                  cos(radians(CAST(:userLat AS double precision))) *
//                  cos(radians(p.latitude)) *
//                  cos(radians(p.longitude) - radians(CAST(:userLng AS double precision))) +
//                  sin(radians(CAST(:userLat AS double precision))) *
//                  sin(radians(p.latitude))
//                ))
//              )
//            ELSE 999999
//          END ASC,
//
//          p.rating DESC,
//          p.total_sold DESC
//    """,
//    countQuery = """
//        SELECT COUNT(*)
//        FROM products p
//        WHERE p.active = true
//          AND (:query = ''
//               OR LOWER(p.name)              LIKE LOWER(CONCAT('%', :query, '%'))
//               OR LOWER(p.short_description) LIKE LOWER(CONCAT('%', :query, '%'))
//               OR LOWER(p.description)       LIKE LOWER(CONCAT('%', :query, '%')))
//          AND (
//                p.delivery_scope = 'GLOBAL'
//                OR (
//                    p.delivery_scope = 'LOCAL'
//                    AND LOWER(p.country_code) = LOWER(:deliveryCountry)
//                )
//              )
//    """,
//    nativeQuery = true
//)
//Page<Product> searchNearUser(
//        @Param("query")           String  query,
//        @Param("deliveryCountry") String  deliveryCountry,
//        @Param("userLat")         Double  userLat,
//        @Param("userLng")         Double  userLng,
//        @Param("hasLocation")     Boolean hasLocation,
//        Pageable pageable
//);
}
