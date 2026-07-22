package com.sujula.repository;

import com.sujula.model.Payment;
import com.sujula.model.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    Optional<Payment> findByTransactionId(String transactionId);
    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    /**
     * Payments for orders that contain at least one item from a given vendor.
     * Used by admin vendor-profile Payments tab.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT p FROM Payment p LEFT JOIN FETCH p.order " +
            "WHERE p.order.id IN (" +
            "  SELECT vo.order.id FROM VendorOrder vo WHERE vo.vendor.id = :vendorId" +
            ")")
    Page<Payment> findByVendorId(
            @org.springframework.data.repository.query.Param("vendorId") Long vendorId,
            Pageable pageable);

    /** Payments for a vendor filtered by payment status. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT p FROM Payment p LEFT JOIN FETCH p.order " +
            "WHERE p.status = :status AND p.order.id IN (" +
            "  SELECT vo.order.id FROM VendorOrder vo WHERE vo.vendor.id = :vendorId" +
            ")")
    Page<Payment> findByVendorIdAndStatus(
            @org.springframework.data.repository.query.Param("vendorId") Long vendorId,
            @org.springframework.data.repository.query.Param("status")   PaymentStatus status,
            Pageable pageable);
}
