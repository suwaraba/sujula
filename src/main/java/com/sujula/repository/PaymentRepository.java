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
}
