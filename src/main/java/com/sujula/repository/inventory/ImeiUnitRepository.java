package com.sujula.repository.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.inventory.ImeiUnit;

@Repository
public interface ImeiUnitRepository extends JpaRepository<ImeiUnit, Long> {

    /**
     * Whether this handset is already registered anywhere on the platform.
     *
     * <p>Deliberately not scoped to a vendor. A phone cannot be on two shelves,
     * and the same IMEI appearing in a second shop is either a typo or a handset
     * that has been sold twice.
     */
    Optional<ImeiUnit> findByImei(String imei);

    boolean existsByImei(String imei);

    @Query("SELECT u FROM ImeiUnit u WHERE u.id = :id AND u.vendor.id = :vendorId")
    Optional<ImeiUnit> findByIdAndVendorId(@Param("id") Long id, @Param("vendorId") Long vendorId);

    @Query(value      = "SELECT u FROM ImeiUnit u WHERE u.vendor.id = :vendorId "
                      + "AND (:variantId IS NULL OR u.variant.id = :variantId) "
                      + "AND (:status IS NULL OR u.status = :status) "
                      + "ORDER BY u.createdAt DESC",
           countQuery = "SELECT COUNT(u) FROM ImeiUnit u WHERE u.vendor.id = :vendorId "
                      + "AND (:variantId IS NULL OR u.variant.id = :variantId) "
                      + "AND (:status IS NULL OR u.status = :status)")
    Page<ImeiUnit> findForVendor(@Param("vendorId") Long vendorId,
                                 @Param("variantId") Long variantId,
                                 @Param("status") ImeiStatus status, Pageable pageable);

    /**
     * How many sellable handsets a variant has.
     *
     * <p>The authority for a serialised variant's stock. Where individual units
     * are tracked, a separate count is a second opinion about the same shelf,
     * and the two will disagree the first time a unit is written off without
     * somebody remembering to decrement.
     */
    @Query("SELECT COUNT(u) FROM ImeiUnit u WHERE u.variant.id = :variantId "
         + "AND u.status = com.sujula.model.constant.ImeiStatus.IN_STOCK")
    long countSellableForVariant(@Param("variantId") Long variantId);

    @Query("SELECT COUNT(u) FROM ImeiUnit u WHERE u.product.id = :productId "
         + "AND u.status = com.sujula.model.constant.ImeiStatus.IN_STOCK")
    long countSellableForProduct(@Param("productId") Long productId);

    /**
     * Sellable counts for a page of variants, in one query.
     *
     * <p>The inventory list has to say which rows are tracked handset by
     * handset, and asking per row is a query per line on the screen a seller
     * keeps open all day.
     */
    @Query("SELECT u.variant.id, COUNT(u) FROM ImeiUnit u WHERE u.variant.id IN :variantIds "
         + "AND u.status = com.sujula.model.constant.ImeiStatus.IN_STOCK GROUP BY u.variant.id")
    List<Object[]> countSellableForVariants(@Param("variantIds") java.util.Collection<Long> variantIds);

    /** Whether this variant is serialised at all - that is, has any units. */
    boolean existsByVariantId(Long variantId);

    List<ImeiUnit> findByVariantIdAndStatus(Long variantId, ImeiStatus status);
}
