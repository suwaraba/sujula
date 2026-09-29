package com.sujula.dto.response.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Deliberately constrained view for the temporary historic guest-order lookup.
 *
 * <p>The legacy order-number/email selector is not strong authentication. This
 * response therefore contains only enough persisted buyer-facing information
 * for someone to recognise an order and decide whether payment is outstanding.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuestOrderLookupResponse(
        String orderNumber,
        OrderStatus status,
        PaymentStatus paymentStatus,
        String currency,
        BigDecimal total,
        List<Line> items,
        Destination destination,
        LocalDateTime createdAt) {

    /** A recognition-only line: no product, vendor, inventory, or payment identifiers. */
    public record Line(String productName, int quantity) {}

    /** Broad delivery destination only; never a deliverable address or coordinates. */
    public record Destination(String city, String country) {}
}
