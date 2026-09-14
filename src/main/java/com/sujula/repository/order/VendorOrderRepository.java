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
import java.time.LocalDateTime;
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

    // ── Analytics ────────────────────────────────────────────────────────────
    //
    // Every aggregate below groups by native_currency. That is the C2 rule made
    // structural: a caller cannot add dalasi to CFA by calling the wrong method,
    // because no method returns a single cross-currency total.
    //
    // "Counting" statuses are the ones where goods actually changed hands.
    // Cancelled and refunded slices are counted separately rather than netted
    // in, because a seller whose revenue fell wants to know which of the two it
    // was.

    /** Revenue, commission, order and unit counts in a window, per currency. */
    @Query("SELECT vo.nativeCurrency, "
         + "       COALESCE(SUM(vo.totalNative), 0), "
         + "       COALESCE(SUM(vo.commissionNative), 0), "
         + "       COUNT(vo) "
         + "FROM VendorOrder vo WHERE vo.vendor.id = :vendorId "
         + "AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED) "
         + "GROUP BY vo.nativeCurrency")
    List<Object[]> revenueByCurrency(@Param("vendorId") Long vendorId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /** The same, bucketed by day, for the time series. */
    @Query("SELECT vo.nativeCurrency, CAST(vo.createdAt AS date), "
         + "       COALESCE(SUM(vo.totalNative), 0), "
         + "       COALESCE(SUM(vo.commissionNative), 0), "
         + "       COUNT(vo) "
         + "FROM VendorOrder vo WHERE vo.vendor.id = :vendorId "
         + "AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED) "
         + "GROUP BY vo.nativeCurrency, CAST(vo.createdAt AS date) "
         + "ORDER BY CAST(vo.createdAt AS date)")
    List<Object[]> dailyRevenueByCurrency(@Param("vendorId") Long vendorId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** Units shipped per day per currency, kept apart from the money. */
    @Query("SELECT vo.nativeCurrency, CAST(vo.createdAt AS date), COALESCE(SUM(oi.quantity), 0) "
         + "FROM VendorOrder vo JOIN vo.items oi WHERE vo.vendor.id = :vendorId "
         + "AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED) "
         + "GROUP BY vo.nativeCurrency, CAST(vo.createdAt AS date)")
    List<Object[]> dailyUnitsByCurrency(@Param("vendorId") Long vendorId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(oi.quantity), 0) FROM VendorOrder vo JOIN vo.items oi "
         + "WHERE vo.vendor.id = :vendorId AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED)")
    long unitsSold(@Param("vendorId") Long vendorId,
                   @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(vo) FROM VendorOrder vo WHERE vo.vendor.id = :vendorId "
         + "AND vo.createdAt >= :from AND vo.createdAt < :to AND vo.status = :status")
    long countInWindowWithStatus(@Param("vendorId") Long vendorId,
                                 @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                 @Param("status") VendorOrderStatus status);

    /** Refunded money in a window, per currency, from decided refunds only. */
    @Query("SELECT vo.nativeCurrency, COALESCE(SUM(r.amountNative), 0) "
         + "FROM RefundRequest r JOIN r.vendorOrder vo WHERE vo.vendor.id = :vendorId "
         + "AND r.createdAt >= :from AND r.createdAt < :to "
         + "AND r.status = com.sujula.model.constant.RefundRequestStatus.COMPLETED "
         + "GROUP BY vo.nativeCurrency")
    List<Object[]> refundedByCurrency(@Param("vendorId") Long vendorId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    // ── Products ─────────────────────────────────────────────────────────────

    /** Units and revenue per product in a window, carrying each row's currency. */
    @Query("SELECT oi.product.id, MIN(oi.productName), MIN(oi.productSku), "
         + "       COALESCE(SUM(oi.quantity), 0), COALESCE(SUM(oi.totalPrice), 0), "
         + "       MIN(vo.nativeCurrency) "
         + "FROM VendorOrder vo JOIN vo.items oi WHERE vo.vendor.id = :vendorId "
         + "AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED) "
         + "AND oi.product IS NOT NULL "
         + "GROUP BY oi.product.id ORDER BY COALESCE(SUM(oi.quantity), 0) DESC")
    List<Object[]> productPerformance(@Param("vendorId") Long vendorId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /** Orders that contained at least one of this seller's products. */
    @Query("SELECT COUNT(DISTINCT vo.id) FROM VendorOrder vo JOIN vo.items oi "
         + "WHERE vo.vendor.id = :vendorId AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED)")
    long ordersContainingAProduct(@Param("vendorId") Long vendorId,
                                  @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Customers ────────────────────────────────────────────────────────────

    /**
     * Distinct buyers, and the first time each of them bought from this seller.
     *
     * <p>Returns identifiers only so the service can classify new against
     * returning and then throw them away. Nothing identifying reaches a
     * response - a seller learns how many, never who.
     */
    @Query("SELECT vo.order.customer.id, MIN(vo.createdAt) FROM VendorOrder vo "
         + "WHERE vo.vendor.id = :vendorId AND vo.order.customer IS NOT NULL "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED) "
         + "GROUP BY vo.order.customer.id")
    List<Object[]> buyerFirstPurchase(@Param("vendorId") Long vendorId);

    /** Buyers active in a window, as ids for classification only. */
    @Query("SELECT DISTINCT vo.order.customer.id FROM VendorOrder vo "
         + "WHERE vo.vendor.id = :vendorId AND vo.order.customer IS NOT NULL "
         + "AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED)")
    List<Long> buyersInWindow(@Param("vendorId") Long vendorId,
                              @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Where the parcels went, by delivery country.
     *
     * <p>The DELIVERY country, never the payer's. A seller in Banjul learns that
     * they ship to Gambia and Senegal; they do not learn that the money comes
     * from Spain (C1).
     */
    @Query("SELECT vo.order.shippingCountry, COUNT(DISTINCT vo.id), COALESCE(SUM(oi.quantity), 0) "
         + "FROM VendorOrder vo JOIN vo.items oi WHERE vo.vendor.id = :vendorId "
         + "AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.status NOT IN (com.sujula.model.constant.VendorOrderStatus.CANCELLED) "
         + "GROUP BY vo.order.shippingCountry ORDER BY COUNT(DISTINCT vo.id) DESC")
    List<Object[]> destinationCountries(@Param("vendorId") Long vendorId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    // ── Delivery ─────────────────────────────────────────────────────────────

    /** How long acceptance to packed took, in hours, per slice. */
    @Query("SELECT vo.acceptedAt, vo.readyAt, vo.collectedAt FROM VendorOrder vo "
         + "WHERE vo.vendor.id = :vendorId AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND vo.acceptedAt IS NOT NULL")
    List<Object[]> fulfilmentTimestamps(@Param("vendorId") Long vendorId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    /** Parcels by delivery status and destination town, for the failure map. */
    @Query("SELECT d.status, vo.order.shippingCity, vo.order.shippingCountry, COUNT(d) "
         + "FROM Delivery d JOIN d.orderItem oi JOIN oi.vendorOrder vo "
         + "WHERE vo.vendor.id = :vendorId AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "GROUP BY d.status, vo.order.shippingCity, vo.order.shippingCountry")
    List<Object[]> parcelsByStatusAndZone(@Param("vendorId") Long vendorId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** Delivered timestamps against their order, for the time-to-delivery average. */
    @Query("SELECT vo.readyAt, d.deliveredAt FROM Delivery d JOIN d.orderItem oi "
         + "JOIN oi.vendorOrder vo WHERE vo.vendor.id = :vendorId "
         + "AND vo.createdAt >= :from AND vo.createdAt < :to "
         + "AND d.deliveredAt IS NOT NULL AND vo.readyAt IS NOT NULL")
    List<Object[]> readyToDelivered(@Param("vendorId") Long vendorId,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    /**
     * Sub-orders placed in a window, for the revenue report's FX margin.
     *
     * <p>Reads each slice's own snapshotted rate — which is why this exists at
     * all rather than a SUM in the database. The margin on a converted order
     * depends on the spread that was in force when it was placed, and that is a
     * join no aggregate query here can make.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT v FROM VendorOrder v WHERE v.createdAt >= :from AND v.createdAt < :to "
          + "AND (:vendorId IS NULL OR v.vendor.id = :vendorId) "
          + "AND v.cancelledAt IS NULL "
          + "ORDER BY v.createdAt ASC")
    java.util.List<VendorOrder> findPlacedBetween(
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to,
            @org.springframework.data.repository.query.Param("vendorId") Long vendorId);
}