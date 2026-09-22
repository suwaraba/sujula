package com.sujula.repository.product;

import com.sujula.model.products.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderBySortOrderAsc(Long productId);

    Optional<ProductImage> findByProductIdAndIsDefaultTrue(Long productId);

    long countByProductId(Long productId);

    /**
     * The card images for a whole page of listings, in one query.
     *
     * <p>A seller's back office renders twenty rows at a time and each row wants
     * a thumbnail. Reading {@code product.getImages()} per row is twenty lazy
     * loads for one screen, and it is invisible until the catalogue is big
     * enough for it to hurt.
     */
    @Query("SELECT i FROM ProductImage i WHERE i.product.id IN :productIds "
         + "AND i.status = com.sujula.model.constant.MediaStatus.READY "
         + "ORDER BY i.product.id ASC, i.isDefault DESC, i.sortOrder ASC")
    List<ProductImage> findReadyForProducts(@Param("productIds") java.util.Collection<Long> productIds);

    @Query("SELECT i.product.id, COUNT(i) FROM ProductImage i "
         + "WHERE i.product.id IN :productIds GROUP BY i.product.id")
    List<Object[]> countByProductIds(@Param("productIds") java.util.Collection<Long> productIds);

    // clearAutomatically=true flushes pending changes and clears the L1 cache before
    // executing the bulk UPDATE, preventing stale in-memory entities from overwriting
    // the new state on subsequent saves within the same transaction.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductImage i SET i.isDefault = false WHERE i.product.id = :productId")
    void clearDefaultsByProductId(@Param("productId") Long productId);
}
