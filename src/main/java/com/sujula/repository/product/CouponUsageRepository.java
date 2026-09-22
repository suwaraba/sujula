package com.sujula.repository.product;

import com.sujula.model.products.CouponUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponUsageRepository extends JpaRepository<CouponUsage, Long> {

    long countByCouponIdAndUserId(Long couponId, Long userId);

    long countByCouponId(Long couponId);

    /**
     * Who redeemed a coupon, newest first.
     *
     * <p>Scoped through the coupon's vendor rather than by coupon id alone: the
     * list names customers, and one seller reading another's redemptions would
     * be reading their customer list.
     */
    @org.springframework.data.jpa.repository.Query(
            value      = "SELECT u FROM CouponUsage u LEFT JOIN FETCH u.user "
                       + "WHERE u.coupon.id = :couponId AND u.coupon.vendor.id = :vendorId "
                       + "ORDER BY u.usedAt DESC",
            countQuery = "SELECT COUNT(u) FROM CouponUsage u "
                       + "WHERE u.coupon.id = :couponId AND u.coupon.vendor.id = :vendorId")
    org.springframework.data.domain.Page<com.sujula.model.products.CouponUsage> findForCoupon(
            @org.springframework.data.repository.query.Param("couponId") Long couponId,
            @org.springframework.data.repository.query.Param("vendorId") Long vendorId,
            org.springframework.data.domain.Pageable pageable);
}
