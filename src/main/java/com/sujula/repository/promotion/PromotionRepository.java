package com.sujula.repository.promotion;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.PromotionStatus;
import com.sujula.model.promotion.Promotion;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    @Query("SELECT p FROM Promotion p WHERE p.id = :id AND p.vendor.id = :vendorId")
    Optional<Promotion> findByIdAndVendorId(@Param("id") Long id, @Param("vendorId") Long vendorId);

    @Query(value      = "SELECT p FROM Promotion p WHERE p.vendor.id = :vendorId "
                      + "AND (:status IS NULL OR p.status = :status) ORDER BY p.createdAt DESC",
           countQuery = "SELECT COUNT(p) FROM Promotion p WHERE p.vendor.id = :vendorId "
                      + "AND (:status IS NULL OR p.status = :status)")
    Page<Promotion> findForVendor(@Param("vendorId") Long vendorId,
                                  @Param("status") PromotionStatus status, Pageable pageable);

    /**
     * This seller's other live promotions whose window touches the given one.
     *
     * <p>The set an activation is checked against. Two overlapping discounts on
     * the same goods do not compound into a sensible price - they compound into
     * whichever the pricing code happens to apply first, which is a different
     * answer on different days.
     *
     * <p>A null end is an open-ended window and overlaps anything after its
     * start, which is why the comparison is written this way round rather than
     * with BETWEEN.
     */
    @Query("SELECT p FROM Promotion p WHERE p.vendor.id = :vendorId "
         + "AND p.status = com.sujula.model.constant.PromotionStatus.ACTIVE "
         + "AND p.id <> :exceptId "
         + "AND (:endsAt IS NULL OR p.startsAt IS NULL OR p.startsAt < :endsAt) "
         + "AND (p.endsAt IS NULL OR :startsAt IS NULL OR p.endsAt > :startsAt)")
    List<Promotion> findOverlapping(@Param("vendorId") Long vendorId,
                                    @Param("exceptId") Long exceptId,
                                    @Param("startsAt") LocalDateTime startsAt,
                                    @Param("endsAt") LocalDateTime endsAt);

    /** Everything running for this seller right now, for the storefront. */
    @Query("SELECT p FROM Promotion p WHERE p.vendor.id = :vendorId "
         + "AND p.status = com.sujula.model.constant.PromotionStatus.ACTIVE "
         + "AND (p.startsAt IS NULL OR p.startsAt <= :now) "
         + "AND (p.endsAt IS NULL OR p.endsAt > :now)")
    List<Promotion> findRunning(@Param("vendorId") Long vendorId, @Param("now") LocalDateTime now);

    long countByVendorIdAndStatus(Long vendorId, PromotionStatus status);
}
