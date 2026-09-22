package com.sujula.repository.inventory;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.inventory.StockMovement;

/**
 * The stock ledger.
 *
 * <p>Read-only by design: there is no update and no delete here. A wrong
 * movement is corrected by another movement, because the history of the mistake
 * is usually the part somebody needs.
 */
@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /**
     * One variant's history, newest first, and only if it is this seller's.
     *
     * <p>Ownership is in the query even though a variant id looks harmless: a
     * movement names quantities, costs and order references, which tell a
     * competitor how fast a shop turns over.
     */
    @Query(value      = "SELECT m FROM StockMovement m WHERE m.variant.id = :variantId "
                      + "AND m.vendor.id = :vendorId ORDER BY m.recordedAt DESC, m.id DESC",
           countQuery = "SELECT COUNT(m) FROM StockMovement m WHERE m.variant.id = :variantId "
                      + "AND m.vendor.id = :vendorId")
    Page<StockMovement> findForVariant(@Param("variantId") Long variantId,
                                       @Param("vendorId") Long vendorId, Pageable pageable);

    /** The same for a product with no variants, where the stock lives on the product. */
    @Query(value      = "SELECT m FROM StockMovement m WHERE m.product.id = :productId "
                      + "AND m.variant IS NULL AND m.vendor.id = :vendorId "
                      + "ORDER BY m.recordedAt DESC, m.id DESC",
           countQuery = "SELECT COUNT(m) FROM StockMovement m WHERE m.product.id = :productId "
                      + "AND m.variant IS NULL AND m.vendor.id = :vendorId")
    Page<StockMovement> findForProduct(@Param("productId") Long productId,
                                       @Param("vendorId") Long vendorId, Pageable pageable);

    /** Everything this seller's stock did in a window, for a reconciliation. */
    @Query("SELECT m FROM StockMovement m WHERE m.vendor.id = :vendorId "
         + "AND m.recordedAt BETWEEN :from AND :to ORDER BY m.recordedAt ASC")
    List<StockMovement> findForVendorBetween(@Param("vendorId") Long vendorId,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);

    /**
     * When each of these items last moved, in one query.
     *
     * <p>The inventory list shows it, and asking per row would be a query per
     * line on the screen a seller keeps open all day. Without this the column
     * was simply always empty, which is worse than not having it: a client
     * renders a blank and nobody knows whether the item has never moved or the
     * server forgot to say.
     */
    @Query("SELECT m.variant.id, MAX(m.recordedAt) FROM StockMovement m "
         + "WHERE m.variant.id IN :variantIds GROUP BY m.variant.id")
    List<Object[]> lastMovementForVariants(@Param("variantIds") java.util.Collection<Long> variantIds);

    long countByVendorId(Long vendorId);
}
