package com.sujula.service;

import com.sujula.model.Payment;
import com.sujula.model.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;

/**
 * Gateway-agnostic payment service.
 *
 * The design intentionally avoids coupling to any specific provider.
 * Payment initiation returns a provider-specific redirect URL (or a COD
 * confirmation object) — the client decides how to render it.
 *
 * Supported methods at launch:
 *   COD   — Cash on Delivery. Payment is created as PENDING; marked PAID
 *            when the delivery driver records the handover.
 *   WAVE  — Wave Mobile Money (Senegal / Gambia).  Webhook-driven.
 *   ORANGE— Orange Money. Webhook-driven.
 *   (Stripe/PayPal can be wired in as additional cases without changing this interface.)
 */
public interface PaymentService {

    /**
     * Create (or return an existing) Payment record for an order.
     *
     * @param orderId       the order to pay for
     * @param method        e.g. "COD", "WAVE", "ORANGE", "STRIPE"
     * @param currency      ISO-4217 currency code (e.g. "GMD", "USD")
     * @return Payment object; for gateway methods the {@code gatewayResponse}
     *         field will contain the provider's redirect URL or reference
     */
    Payment initiate(Long orderId, String method, String currency);

    /**
     * Record a confirmed payment from a provider webhook or admin action.
     *
     * @param transactionId  the provider's unique reference
     * @param newStatus      PAID, FAILED, or REFUNDED
     * @param gatewayPayload raw JSON / text from the provider (stored for audit)
     * @return the updated Payment
     */
    Payment recordWebhook(String transactionId, PaymentStatus newStatus, String gatewayPayload);

    /**
     * Mark a COD order as collected by the driver at delivery time.
     * Only valid for COD payments in PENDING status.
     *
     * @param orderId the delivered order
     * @return the updated Payment (now PAID)
     */
    Payment confirmCod(Long orderId);

    /** Get the payment for an order (admin / customer use). */
    Payment findByOrderId(Long orderId);

    /** Paginated list of all payments (admin). */
    Page<Payment> findAll(Pageable pageable);

    /** Paginated list filtered by status (admin). */
    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);
}
