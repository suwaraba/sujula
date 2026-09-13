package com.sujula.repository.product;

import com.sujula.model.products.ProductVariant;
import jakarta.persistence.LockModeType;
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
