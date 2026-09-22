package com.sujula.dto.response.checkout;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** What checkout returns. */
public final class CheckoutResponses {

    private CheckoutResponses() {}

    /**
     * An order, its per-vendor slices, and how to pay for it.
     *
     * @param vendorOrders the sub-orders this payment split into. Each ships,
     *                     cancels, refunds and pays out independently, so a
     *                     client that renders one order with one status will
     *                     eventually be wrong about half of it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Placed(
            Long orderId,
            String orderNumber,
            OrderStatus status,
            String currency,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal shipping,
            BigDecimal total,
            List<VendorSlice> vendorOrders,
            PaymentIntent payment,
            LocalDateTime placedAt) {}

    /**
     * One seller's slice of the payment.
     *
     * @param payoutNative what this vendor is owed, in their own currency,
     *                     frozen at the rate on the day the order was placed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VendorSlice(
            Long vendorOrderId,
            Long vendorId,
            String storeName,
            String status,
            BigDecimal total,
            String listingCurrency,
            BigDecimal totalNative,
            BigDecimal payoutNative,
            BigDecimal fxRate) {}

    /**
     * How to pay.
     *
     * @param checkoutUrl  where to send the buyer, for methods that redirect
     * @param clientSecret for client-side confirmation, when the gateway uses one
     * @param instructions what to tell the buyer, for bank transfer or cash
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentIntent(
            Long paymentId,
            String reference,
            PaymentMethod method,
            PaymentStatus status,
            BigDecimal amount,
            String currency,
            String checkoutUrl,
            String clientSecret,
            String instructions) {}

    /**
     * What a client polls while waiting for the gateway.
     *
     * @param settled true once the payment is confirmed — by a webhook, not by
     *                the client's own optimism
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(
            Long orderId,
            String orderNumber,
            OrderStatus orderStatus,
            PaymentStatus paymentStatus,
            boolean settled,
            boolean retryable,
            BigDecimal amount,
            String currency,
            LocalDateTime paidAt,
            String failureReason) {}
}
