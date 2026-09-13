package com.sujula.repository.product;

import com.sujula.model.products.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    /** Fetches the issuing vendor alongside the coupon so scope checks don't lazy-load. */
    @Query("SELECT c FROM Coupon c LEFT JOIN FETCH c.vendor WHERE c.code = :code")
    Optional<Coupon> findByCodeWithVendor(@Param("code") String code);

    boolean existsByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    // ── The seller's own coupons ─────────────────────────────────────────────

    /**
     * One coupon, but only if this seller issued it.
     *
     * <p>Ownership is the query. A coupon carries a usage count and a minimum
     * basket, which together tell a competitor what a shop's average order is
     * worth and how well a campaign is going.
     */
    @Query("SELECT c FROM Coupon c WHERE c.id = :id AND c.vendor.id = :vendorId")
    Optional<Coupon> findByIdAndVendorId(@Param("id") Long id, @Param("vendorId") Long vendorId);

    @Query(value      = "SELECT c FROM Coupon c WHERE c.vendor.id = :vendorId "
                      + "AND (:activeOnly = FALSE OR c.active = TRUE) ORDER BY c.createdAt DESC",
           countQuery = "SELECT COUNT(c) FROM Coupon c WHERE c.vendor.id = :vendorId "
                      + "AND (:activeOnly = FALSE OR c.active = TRUE)")
    org.springframework.data.domain.Page<Coupon> findForVendor(
            @Param("vendorId") Long vendorId, @Param("activeOnly") boolean activeOnly,
            org.springframework.data.domain.Pageable pageable);

    long countByVendorId(Long vendorId);
}
