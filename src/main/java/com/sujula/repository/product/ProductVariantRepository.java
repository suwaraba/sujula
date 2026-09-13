package com.sujula.repository.product;

import com.sujula.model.products.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    boolean existsBySku(String sku);

    Optional<ProductVariant> findBySku(String sku);

    List<ProductVariant> findByProductId(Long productId);

    // ── The seller's inventory screen ────────────────────────────────────────

    /**
     * One item, but only if this seller's listing owns it.
     *
     * <p>Ownership is the query, reached through the product. A variant id is a
     * small integer and what it unlocks is a stock figure and a cost price.
     */
    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product p "
         + "WHERE v.id = :id AND p.vendor.id = :vendorId")
    Optional<ProductVariant> findByIdAndVendorId(@Param("id") Long id,
                                                 @Param("vendorId") Long vendorId);

    /**
     * The inventory list, with the filters a seller actually uses.
     *
     * <p>Archived listings are excluded: a seller reconciling a shelf is not
     * counting things they finished with. Drafts are not, because stock arrives
     * before a listing goes live and a shop that could not receive it until
     * approval would be counting in a notebook.
     */
    @Query(value      = "SELECT v FROM ProductVariant v JOIN FETCH v.product p "
                      + "WHERE p.vendor.id = :vendorId "
                      + "AND p.status <> com.sujula.model.constant.ProductStatus.ARCHIVED "
                      + "AND (:lowStockOnly = FALSE OR v.stock <= COALESCE(p.lowStockThreshold, 0)) "
                      + "AND (:outOfStockOnly = FALSE OR v.stock = 0) "
                      + "AND (:search IS NULL OR LOWER(v.sku) LIKE LOWER(CONCAT('%', :search, '%')) "
                      + "     OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))) "
                      + "ORDER BY v.stock ASC, p.name ASC",
           countQuery = "SELECT COUNT(v) FROM ProductVariant v JOIN v.product p "
                      + "WHERE p.vendor.id = :vendorId "
                      + "AND p.status <> com.sujula.model.constant.ProductStatus.ARCHIVED "
                      + "AND (:lowStockOnly = FALSE OR v.stock <= COALESCE(p.lowStockThreshold, 0)) "
                      + "AND (:outOfStockOnly = FALSE OR v.stock = 0) "
                      + "AND (:search IS NULL OR LOWER(v.sku) LIKE LOWER(CONCAT('%', :search, '%')) "
                      + "     OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<ProductVariant> findForVendorInventory(@Param("vendorId") Long vendorId,
                                                @Param("lowStockOnly") boolean lowStockOnly,
                                                @Param("outOfStockOnly") boolean outOfStockOnly,
                                                @Param("search") String search,
                                                Pageable pageable);

    /**
     * How many of this seller's items need restocking, across the catalogue.
     *
     * <p>Across all of it rather than the page, because the number a seller
     * wants on the screen is "how many things am I about to run out of", not
     * "how many on this page".
     */
    @Query("SELECT COUNT(v) FROM ProductVariant v JOIN v.product p WHERE p.vendor.id = :vendorId "
         + "AND p.status <> com.sujula.model.constant.ProductStatus.ARCHIVED "
         + "AND v.stock <= COALESCE(p.lowStockThreshold, 0) AND v.stock > 0")
    long countLowStockForVendor(@Param("vendorId") Long vendorId);

    @Query("SELECT COUNT(v) FROM ProductVariant v JOIN v.product p WHERE p.vendor.id = :vendorId "
         + "AND p.status <> com.sujula.model.constant.ProductStatus.ARCHIVED AND v.stock = 0")
    long countOutOfStockForVendor(@Param("vendorId") Long vendorId);

    /** Variant counts for a whole page, so a list does not lazy-load per row. */
    @Query("SELECT v.product.id, COUNT(v) FROM ProductVariant v "
         + "WHERE v.product.id IN :productIds GROUP BY v.product.id")
    List<Object[]> countByProductIds(@Param("productIds") java.util.Collection<Long> productIds);

    /**
     * Whether anybody has ever ordered this particular variant.
     *
     * <p>What decides turning a variant off from deleting it, asked of the order
     * lines rather than of the product's sold counter - a listing can have sold
     * plenty without this variant ever moving, and deactivating a variant nobody
     * bought leaves dead rows in every seller's option list.
     */
    @Query("SELECT COUNT(oi) > 0 FROM OrderItem oi WHERE oi.variant.id = :variantId")
    boolean hasBeenOrdered(@Param("variantId") Long variantId);

    List<ProductVariant> findByProductIdAndActiveTrue(Long productId);

    boolean existsBySkuAndProductIdNot(String sku, Long existingProductId);
    @Query("SELECT v.sku FROM ProductVariant v WHERE v.sku IN :skus")
    Set<String> findExistingSkus(@Param("skus") Collection<String> skus);

    @Query("SELECT v.sku FROM ProductVariant v WHERE v.sku IN :skus AND v.product.id <> :productId")
    Set<String> findExistingSkusExcludingProduct(@Param("skus") Collection<String> skus,
                                                 @Param("productId") Long productId);

    /**
     * Acquire a row-level exclusive lock on the variant row before reading.
     * Used during order placement to prevent concurrent stock deductions from
     * overselling the same variant (eliminates the check-then-act TOCTOU race).
     * Must be called inside an active @Transactional context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ProductVariant v WHERE v.id = :id")
    Optional<ProductVariant> findByIdForUpdate(@Param("id") Long id);
}
