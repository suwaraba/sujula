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

    // clearAutomatically=true flushes pending changes and clears the L1 cache before
    // executing the bulk UPDATE, preventing stale in-memory entities from overwriting
    // the new state on subsequent saves within the same transaction.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductImage i SET i.isDefault = false WHERE i.product.id = :productId")
    void clearDefaultsByProductId(@Param("productId") Long productId);
}
