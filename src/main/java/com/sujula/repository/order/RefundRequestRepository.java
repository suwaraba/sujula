package com.sujula.repository.order;

import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.order.RefundRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    /** The queue an administrator works through. */
    @Query("SELECT r FROM RefundRequest r JOIN FETCH r.order JOIN FETCH r.vendorOrder "
         + "WHERE r.status = :status ORDER BY r.createdAt ASC")
    Page<RefundRequest> findByStatus(@Param("status") RefundRequestStatus status, Pageable pageable);

    boolean existsByReference(String reference);
}
