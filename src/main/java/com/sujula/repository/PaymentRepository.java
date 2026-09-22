package com.sujula.repository;

import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByOrderOrderNumber(String orderNumber);

    Optional<Payment> findByReference(String reference);

    Optional<Payment> findByTransactionId(String transactionId);

    boolean existsByReference(String reference);

    /**
     * Locks the payment row for the duration of the transaction.
     *
     * <p>Confirmation can arrive from two directions at once — a provider
     * callback and a human pressing "mark as paid" — and both then read, decide
     * and write. Serialising them here keeps the second one from re-applying a
     * transition the first already made.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.order.id = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.transactionId = :transactionId")
    Optional<Payment> findByTransactionIdForUpdate(@Param("transactionId") String transactionId);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    Page<Payment> findByMethod(PaymentMethod method, Pageable pageable);

    Page<Payment> findByStatusAndMethod(PaymentStatus status, PaymentMethod method, Pageable pageable);

    /**
     * Payments for orders containing at least one item from a given vendor.
     * A multivendor order has one payment covering every vendor in it, so the
     * same payment legitimately appears for each of those vendors.
     */
    @Query(value      = "SELECT p FROM Payment p " +
                        "WHERE p.order.id IN (SELECT vo.order.id FROM VendorOrder vo WHERE vo.vendor.id = :vendorId)",
           countQuery = "SELECT COUNT(p) FROM Payment p " +
                        "WHERE p.order.id IN (SELECT vo.order.id FROM VendorOrder vo WHERE vo.vendor.id = :vendorId)")
    Page<Payment> findByVendorId(@Param("vendorId") Long vendorId, Pageable pageable);

    @Query(value      = "SELECT p FROM Payment p " +
                        "WHERE p.status = :status " +
                        "  AND p.order.id IN (SELECT vo.order.id FROM VendorOrder vo WHERE vo.vendor.id = :vendorId)",
           countQuery = "SELECT COUNT(p) FROM Payment p " +
                        "WHERE p.status = :status " +
                        "  AND p.order.id IN (SELECT vo.order.id FROM VendorOrder vo WHERE vo.vendor.id = :vendorId)")
    Page<Payment> findByVendorIdAndStatus(@Param("vendorId") Long vendorId,
                                          @Param("status")   PaymentStatus status,
                                          Pageable pageable);

    /**
     * Total received in a currency, for admin reporting. Returns null over an
     * empty set — callers must guard, as {@code COALESCE(SUM(...), 0)} would
     * hand Hibernate an Integer literal it cannot coerce to BigDecimal.
     */
    @Query("SELECT SUM(p.amount - p.amountRefunded) FROM Payment p " +
           "WHERE p.currency = :currency AND p.status IN (com.sujula.model.constant.PaymentStatus.PAID, " +
           "com.sujula.model.constant.PaymentStatus.PARTIALLY_REFUNDED)")
    java.math.BigDecimal totalCollected(@Param("currency") String currency);

    long countByStatus(PaymentStatus status);

    /**
     * The back office's payment search.
     *
     * <p>{@code transactionId} matches the provider's own id, which is what a
     * PSP dashboard is searched by — an agent with a reference from a bank's
     * screen and no order number has nothing else to go on. It is matched
     * exactly rather than by prefix, because a partial match against provider
     * ids returns other people's payments.
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT p FROM Payment p JOIN p.order o "
          + "WHERE (:q IS NULL OR LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(p.reference) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(o.shippingFullName) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(o.billingFullName) LIKE LOWER(CONCAT('%', :q, '%'))) "
          + "AND (:status IS NULL OR p.status = :status) "
          + "AND (:currency IS NULL OR UPPER(p.currency) = UPPER(:currency)) "
          + "AND (:transactionId IS NULL OR p.transactionId = :transactionId) "
          + "AND (:from IS NULL OR p.createdAt >= :from) "
          + "AND (:to IS NULL OR p.createdAt < :to) "
          + "ORDER BY p.createdAt DESC, p.id DESC",
            countQuery = "SELECT COUNT(p) FROM Payment p JOIN p.order o "
          + "WHERE (:q IS NULL OR LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(p.reference) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(o.shippingFullName) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(o.billingFullName) LIKE LOWER(CONCAT('%', :q, '%'))) "
          + "AND (:status IS NULL OR p.status = :status) "
          + "AND (:currency IS NULL OR UPPER(p.currency) = UPPER(:currency)) "
          + "AND (:transactionId IS NULL OR p.transactionId = :transactionId) "
          + "AND (:from IS NULL OR p.createdAt >= :from) "
          + "AND (:to IS NULL OR p.createdAt < :to)")
    Page<Payment> adminSearch(
            @Param("q") String q,
            @Param("status") PaymentStatus status,
            @Param("currency") String currency,
            @Param("transactionId") String transactionId,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            Pageable pageable);

    /**
     * What buyers actually paid, less what has gone back, by currency.
     *
     * <p>The payment side of the reconciliation. Denominated in the currency the
     * BUYER was charged in, which for a cross-border order is not the currency
     * the vendor's ledger is in — the report says so rather than subtracting two
     * figures that are not comparable (C2).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT p.currency, COALESCE(SUM(p.amount), 0), COALESCE(SUM(p.amountRefunded), 0) "
          + "FROM Payment p WHERE p.status IN ("
          + "  com.sujula.model.constant.PaymentStatus.PAID, "
          + "  com.sujula.model.constant.PaymentStatus.PARTIALLY_REFUNDED) "
          + "GROUP BY p.currency ORDER BY p.currency ASC")
    java.util.List<Object[]> takenByCurrency();
}