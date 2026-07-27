package com.sujula.repository.product;

import com.sujula.model.products.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    boolean existsBySku(String sku);

    Optional<ProductVariant> findBySku(String sku);

    List<ProductVariant> findByProductId(Long productId);

    List<ProductVariant> findByProductIdAndActiveTrue(Long productId);

//    List<ProductVariant> findByProductIdAndAvailableTrue(Long productId);
//    List<ProductVariant> findByProductId(Long productId);
//    Optional<ProductVariant> findBySku(String sku);
//    boolean existsBySku(String sku);
//
//    /**
//     * Acquire a row-level exclusive lock on the variant row before reading.
//     * Used during order placement to prevent concurrent stock deductions from
//     * overselling the same variant (eliminates the check-then-act TOCTOU race).
//     * Must be called inside an active @Transactional context.
//     */
//    @Lock(LockModeType.PESSIMISTIC_WRITE)
//    @Query("SELECT v FROM ProductVariant v WHERE v.id = :id")
//    Optional<ProductVariant> findByIdForUpdate(@Param("id") Long id);
}
