package com.sujula.service.payment;

import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.order.Payment;

import java.math.BigDecimal;

/**
 * Provider adapter for the online methods (card, PayPal).
 *
 * <p>{@code PaymentServiceImpl} owns the money rules — what may be paid, by
 * whom, when, and what a confirmation does to the order — and knows nothing
 * about any provider. A provider integration implements this interface, is
 * registered as a Spring bean, and is picked up automatically for the methods
 * it says it supports. With no bean registered for a method, that method is
 * reported as unavailable at checkout rather than failing halfway through one.
 *
 * <p>Implementations are responsible for verifying webhook signatures and
 * translating provider payloads into a {@code PaymentCallbackRequest} before
 * handing them to the service.
 */
public interface PaymentGateway {

    /** Which method this adapter handles. */
    boolean supports(PaymentMethod method);

    /** Provider name for logs and audit, e.g. "stripe". */
    String name();

    /**
     * Opens a checkout for an already-persisted, still-unpaid payment.
     *
     * @param returnUrl where the provider should send the buyer afterwards; may be null
     */
    GatewayCheckout createCheckout(Payment payment, String returnUrl);

    /**
     * Returns money through the provider.
     *
     * @throws UnsupportedOperationException when the provider cannot refund programmatically
     */
    default GatewayRefund refund(Payment payment, BigDecimal amount, String reason) {
        throw new UnsupportedOperationException(
                name() + " does not support programmatic refunds; refund it in the provider dashboard "
                        + "and record it here afterwards");
    }

    /**
     * An opened checkout.
     *
     * @param transactionId provider reference; stored so a later callback can find this payment
     * @param checkoutUrl   hosted page to redirect the buyer to, or null for an inline form
     * @param clientSecret  secret the provider's client SDK needs for an inline form, or null
     * @param rawResponse   provider payload, stored for audit
     */
    record GatewayCheckout(String transactionId, String checkoutUrl, String clientSecret, String rawResponse) {}

    /**
     * A completed refund.
     *
     * @param refundId    provider's refund reference
     * @param amount      amount actually returned
     * @param rawResponse provider payload, stored for audit
     */
    record GatewayRefund(String refundId, BigDecimal amount, String rawResponse) {}
}
