package com.sujula.dto.response.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.PaymentChannel;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Payment;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What a client needs to act on a payment: where it stands, what is still owed,
 * and — for a method that needs the buyer to do something — where to go or what
 * to quote.
 *
 * <p>The raw gateway payload is deliberately absent: it is audit material, not
 * something any client should see.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    private Long paymentId;
    private Long orderId;
    private String orderNumber;

    private String reference;
    private PaymentStatus status;
    private PaymentMethod method;
    private PaymentChannel channel;

    private BigDecimal amount;
    private BigDecimal amountRefunded;
    /** What the buyer still owes: the full amount until it settles, then nothing. */
    private BigDecimal amountOutstanding;
    private String currency;

    /** True while the buyer still has something to do (pay online, transfer, or hand over cash). */
    private boolean actionRequired;

    /** Hosted checkout URL, when the provider uses one. */
    private String checkoutUrl;

    /** Client-side secret for an inline provider form. */
    private String clientSecret;

    /** Human-readable instructions for an offline or in-person method. */
    private String instructions;

    private String transactionId;
    private String collectionReference;
    private String failureReason;
    private String note;

    private LocalDateTime paidAt;
    private LocalDateTime refundedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;

    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrder() != null ? payment.getOrder().getId() : null)
                .orderNumber(payment.getOrder() != null ? payment.getOrder().getOrderNumber() : null)
                .reference(payment.getReference())
                .status(payment.getStatus())
                .method(payment.getMethod())
                .channel(payment.getChannel())
                .amount(payment.getAmount())
                .amountRefunded(payment.getAmountRefunded())
                .amountOutstanding(payment.getStatus() != null && payment.getStatus().isOutstanding()
                        ? payment.getAmount() : BigDecimal.ZERO)
                .currency(payment.getCurrency())
                .actionRequired(payment.getStatus() != null && payment.getStatus().isOutstanding())
                .checkoutUrl(payment.getCheckoutUrl())
                .clientSecret(payment.getClientSecret())
                .instructions(payment.getInstructions())
                .transactionId(payment.getTransactionId())
                .collectionReference(payment.getCollectionReference())
                .failureReason(payment.getFailureReason())
                .note(payment.getNote())
                .paidAt(payment.getPaidAt())
                .refundedAt(payment.getRefundedAt())
                .cancelledAt(payment.getCancelledAt())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
