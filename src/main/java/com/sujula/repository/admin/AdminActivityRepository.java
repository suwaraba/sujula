package com.sujula.repository.admin;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.user.User;

/**
 * What one account has actually done, for the profile an agent reads.
 *
 * <p>Hung off {@code User} rather than given its own entity because it owns no
 * rows: every query here counts somebody else's table. Gathering them in one
 * place is the point — an agent deciding whether somebody is a fraud or a
 * first-time buyer with a bad connection needs the shape of a history, and
 * fetching it from six endpoints is how they end up deciding on one of them.
 *
 * <p>Money is grouped by currency and never summed. A buyer who has spent 400
 * EUR and 12,000 GMD has not spent 12,400 of anything (C2).
 */
@Repository
public interface AdminActivityRepository extends JpaRepository<User, Long> {

    @Query("SELECT COUNT(o) FROM Order o WHERE o.customer.id = :userId")
    int countOrders(@Param("userId") Long userId);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.customer.id = :userId "
         + "AND o.status = com.sujula.model.constant.OrderStatus.CANCELLED")
    int countCancelledOrders(@Param("userId") Long userId);

    /**
     * What they have paid, per currency, counting only orders that were paid.
     *
     * <p>Pending and cancelled orders are left out deliberately: "has spent" is
     * a different claim from "has ordered", and the first is the one an agent is
     * relying on when they decide how much benefit of the doubt somebody gets.
     */
    @Query("SELECT o.currency, COALESCE(SUM(o.total), 0), COUNT(o) FROM Order o "
         + "WHERE o.customer.id = :userId "
         + "AND o.paymentStatus IN (com.sujula.model.constant.PaymentStatus.PAID, "
         + "                        com.sujula.model.constant.PaymentStatus.PARTIALLY_REFUNDED) "
         + "GROUP BY o.currency ORDER BY o.currency")
    List<Object[]> spendByCurrency(@Param("userId") Long userId);

    @Query("SELECT MIN(o.createdAt), MAX(o.createdAt) FROM Order o WHERE o.customer.id = :userId")
    List<Object[]> orderWindow(@Param("userId") Long userId);

    @Query("SELECT COUNT(r) FROM ReturnRequest r WHERE r.requestedBy.id = :userId")
    int countReturns(@Param("userId") Long userId);

    /** Disputes at either end: raised by them, or against their store. */
    @Query("SELECT COUNT(d) FROM Dispute d WHERE d.raisedBy.id = :userId "
         + "OR d.vendorOrder.vendor.user.id = :userId")
    int countDisputes(@Param("userId") Long userId);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.user.id = :userId AND r.deletedAt IS NULL")
    int countReviews(@Param("userId") Long userId);

    /**
     * Reviews of theirs that somebody has reported.
     *
     * <p>Counted rather than listed on this screen. It is a signal about the
     * account, and a high number is a reason to look at the reviews rather than
     * a verdict — a seller with two accounts can report the same person twice.
     */
    @Query("SELECT COUNT(DISTINCT p.review.id) FROM ReviewReport p WHERE p.review.user.id = :userId")
    int countReportedReviews(@Param("userId") Long userId);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.vendor.user.id = :userId")
    int countProductsListed(@Param("userId") Long userId);

    /**
     * Parcels this account has actually handed over.
     *
     * <p>Counted from the custody chain rather than from a status column, for
     * the same reason everything else on this platform is: a delivery is an
     * event somebody recorded with proof, and a driver's record should be the
     * sum of those rather than of statuses anybody could have set (C4).
     */
    @Query("SELECT COUNT(e) FROM CustodyEvent e WHERE e.recordedByUserId = :userId "
         + "AND e.type = com.sujula.model.constant.CustodyEventType.RELEASED")
    int countDeliveriesCompleted(@Param("userId") Long userId);

    @Query("SELECT COUNT(s) FROM UserSession s WHERE s.user.id = :userId "
         + "AND s.revokedAt IS NULL AND s.expiresAt > :now")
    int countLiveSessions(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
