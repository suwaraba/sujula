package com.sujula.repository.product;


import com.sujula.model.products.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySlug(String slug);

    boolean existsByVendorIdAndNameIgnoreCase(Long vendorId, String productName);

    boolean existsByVendorIdAndNameIgnoreCaseAndIdNot(Long vendorId, String productName, Long productId);

    // ── Proximity-ranked browse queries ──────────────────────────────────────
    // Every query below is a native query (MySQL — no PostGIS/spatial extension
    // available) that great-circle-distances each product from (userLat, userLng)
    // using the Haversine formula. Products within `radiusKm` sort ahead of
    // everything else (bucket 0 vs 1); within a bucket, products on promotion
    // (compareAtPrice > price) sort first, then by `score` descending. Distance,
    // rating, etc. are only used as later tiebreakers. Passing null lat/lng simply
    // puts every product in bucket 1, so ordering falls back to promotion + score.

    String DISTANCE_KM_EXPR =
            "(6371 * acos(least(1.0, greatest(-1.0, " +
                    "cos(radians(:userLat)) * cos(radians(p.latitude)) * cos(radians(p.longitude) - radians(:userLng)) " +
                    "+ sin(radians(:userLat)) * sin(radians(p.latitude))))))";

    String PROXIMITY_BUCKET_EXPR =
            "(CASE WHEN :userLat IS NOT NULL AND :userLng IS NOT NULL " +
                    "AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL " +
                    "AND " + DISTANCE_KM_EXPR + " <= :radiusKm THEN 0 ELSE 1 END)";

    String PROMOTION_RANK_EXPR =
            "(CASE WHEN p.compare_at_price IS NOT NULL AND p.compare_at_price > p.price THEN 0 ELSE 1 END)";

    String SCORE_EXPR = "COALESCE(p.score, 0)";

    String COUNTRY_FILTER_EXPR =
            "(:deliveryCountry IS NULL OR :deliveryCountry = '' " +
                    "OR p.delivery_scope = 'GLOBAL' OR LOWER(p.country) = LOWER(:deliveryCountry))";

    String RANK_ORDER = PROXIMITY_BUCKET_EXPR + " ASC, " + PROMOTION_RANK_EXPR + " ASC, " + SCORE_EXPR + " DESC";

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
            @Param("userLat") Double userLat,
            @Param("userLng") Double userLng,
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
            @Param("userLat") Double userLat,
            @Param("userLng") Double userLng,
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
            @Param("userLat") Double userLat,
            @Param("userLng") Double userLng,
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
            @Param("userLat") Double userLat,
            @Param("userLng") Double userLng,
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
            @Param("userLat") Double userLat,
            @Param("userLng") Double userLng,
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
            @Param("userLat") Double userLat,
            @Param("userLng") Double userLng,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);
}
