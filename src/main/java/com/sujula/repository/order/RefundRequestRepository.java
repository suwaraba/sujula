package com.sujula.repository.order;

import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.order.RefundRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    /**
     * An open request against this slice, if there is one.
     *
     * <p>What makes asking twice idempotent. A buyer who taps cancel, sees
     * nothing happen on a slow connection and taps again should not end up with
     * two refunds queued against the same goods — one of which an administrator
     * might approve after the other has already paid out.
     */
    @Query("SELECT r FROM RefundRequest r WHERE r.vendorOrder.id = :vendorOrderId "
         + "AND r.status IN :open ORDER BY r.createdAt DESC LIMIT 1")
    Optional<RefundRequest> findOpenForVendorOrder(@Param("vendorOrderId") Long vendorOrderId,
                                                   @Param("open") List<RefundRequestStatus> open);

    List<RefundRequest> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /** A requested, approved or completed refund makes escrow release unsafe. */
    boolean existsByVendorOrderIdAndStatusIn(Long vendorOrderId,
                                             List<RefundRequestStatus> statuses);

    /** The queue an administrator works through. */
    @Query("SELECT r FROM RefundRequest r JOIN FETCH r.order JOIN FETCH r.vendorOrder "
         + "WHERE r.status = :status ORDER BY r.createdAt ASC")
    Page<RefundRequest> findByStatus(@Param("status") RefundRequestStatus status, Pageable pageable);

    boolean existsByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefundRequest r WHERE r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT r.order.id FROM RefundRequest r WHERE r.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefundRequest> findFirstByVendorOrderIdAndStatusInOrderByCreatedAtAsc(
            Long vendorOrderId, List<RefundRequestStatus> statuses);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RefundRequest r "
         + "WHERE r.order.id = :orderId AND r.status IN :statuses")
    java.math.BigDecimal sumDisplayByOrderAndStatusIn(
            @Param("orderId") Long orderId,
            @Param("statuses") List<RefundRequestStatus> statuses);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RefundRequest r "
         + "WHERE r.vendorOrder.id = :vendorOrderId AND r.status IN :statuses")
    java.math.BigDecimal sumDisplayByVendorOrderAndStatusIn(
            @Param("vendorOrderId") Long vendorOrderId,
            @Param("statuses") List<RefundRequestStatus> statuses);

    @Query("SELECT COALESCE(SUM(r.amountNative), 0) FROM RefundRequest r "
         + "WHERE r.vendorOrder.id = :vendorOrderId AND r.status IN :statuses")
    java.math.BigDecimal sumNativeByVendorOrderAndStatusIn(
            @Param("vendorOrderId") Long vendorOrderId,
            @Param("statuses") List<RefundRequestStatus> statuses);
}
