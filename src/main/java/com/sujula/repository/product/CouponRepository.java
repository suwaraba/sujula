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
}
