package com.sujula.repository.order;

import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.VendorOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface VendorOrderRepository extends JpaRepository<VendorOrder, Long> {

    List<VendorOrder> findByOrderId(Long orderId);

    /** Whether this vendor has anything in this order — the basis of every vendor-scoped check. */
    boolean existsByOrderIdAndVendorId(Long orderId, Long vendorId);

    Page<VendorOrder> findByVendorId(Long vendorId, Pageable pageable);

    Page<VendorOrder> findByVendorIdAndStatus(Long vendorId, VendorOrderStatus status, Pageable pageable);

    @Query(value = "SELECT vo FROM VendorOrder vo LEFT JOIN FETCH vo.order o WHERE vo.vendor.id = :vendorId",
           countQuery = "SELECT COUNT(vo) FROM VendorOrder vo WHERE vo.vendor.id = :vendorId")
    Page<VendorOrder> findByVendorIdFetchOrder(@Param("vendorId") Long vendorId, Pageable pageable);

    long countByVendorIdAndStatus(Long vendorId, VendorOrderStatus status);

    /**
     * One vendor's slice, by its own id, refusing every other vendor's.
     *
     * <p>The vendor id is part of the lookup rather than checked afterwards, so a
     * slice belonging to someone else is simply not found — there is no moment
     * where the row is in hand and the check has still to be made.
     */
    Optional<VendorOrder> findByIdAndVendorId(Long id, Long vendorId);

    /**
     * The fulfilment queue, oldest first — the order a seller should work in.
     * Joins the order only for its number; nothing else on it is read.
     */
    @Query(value = "SELECT vo FROM VendorOrder vo JOIN FETCH vo.order WHERE vo.vendor.id = :vendorId "
                 + "ORDER BY vo.createdAt ASC",
           countQuery = "SELECT COUNT(vo) FROM VendorOrder vo WHERE vo.vendor.id = :vendorId")
    Page<VendorOrder> findQueueByVendorId(@Param("vendorId") Long vendorId, Pageable pageable);

    @Query(value = "SELECT vo FROM VendorOrder vo JOIN FETCH vo.order "
                 + "WHERE vo.vendor.id = :vendorId AND vo.status = :status ORDER BY vo.createdAt ASC",
           countQuery = "SELECT COUNT(vo) FROM VendorOrder vo "
                      + "WHERE vo.vendor.id = :vendorId AND vo.status = :status")
    Page<VendorOrder> findQueueByVendorIdAndStatus(@Param("vendorId") Long vendorId,
                                                   @Param("status") VendorOrderStatus status,
                                                   Pageable pageable);

    /** Order counts per status in one query, rather than one count per status. */
    @Query("SELECT vo.status, COUNT(vo) FROM VendorOrder vo WHERE vo.vendor.id = :vendorId GROUP BY vo.status")
    List<Object[]> countByStatusForVendor(@Param("vendorId") Long vendorId);

    /**
     * Payout summed over the statuses given, in the vendor's own currency.
     * Null when the vendor has no such orders.
     */
    @Query("SELECT SUM(vo.payoutNative) FROM VendorOrder vo "
         + "WHERE vo.vendor.id = :vendorId AND vo.status IN :statuses")
    BigDecimal sumPayoutNative(@Param("vendorId") Long vendorId,
                               @Param("statuses") Collection<VendorOrderStatus> statuses);
}
