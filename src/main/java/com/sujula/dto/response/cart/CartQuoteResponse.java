package com.sujula.dto.response.cart;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.DeliveryMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A priced cart, held.
 *
 * <p>What checkout is presented with, and what it must charge. The buyer agreed
 * to these figures; re-deriving them at capture would present one total and take
 * another.
 *
 * @param id              the handle checkout is given. Unguessable, because
 *                        holding it is what authorises paying at these figures
 * @param expiresAt       when these figures stop being honoured
 * @param complete        false when some line could not be converted. Such a
 *                        quote exists so a client can show what is wrong;
 *                        checkout refuses it
 * @param fxQuoteId       alias of {@code id}, for clients following the
 *                        currency-quote naming
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CartQuoteResponse(
        String id,
        String fxQuoteId,
        String cartToken,
        String displayCurrency,
        String deliveryContextId,
        DeliveryMode deliveryMode,
        Long pickupPointId,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal shipping,
        BigDecimal tax,
        BigDecimal total,
        boolean complete,
        boolean deliverable,
        List<Line> lines,
        List<VendorTotal> vendors,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        long expiresInSeconds,
        List<String> blockers) {

    /**
     * One product, at the price and rate it was quoted at.
     *
     * @param rate units of the buyer's currency per one of the vendor's — the
     *             number that makes "why was I charged this" answerable
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            Long productId,
            Long variantId,
            Long vendorId,
            int quantity,
            String listingCurrency,
            BigDecimal unitPriceNative,
            BigDecimal lineTotalNative,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal deliveryCost,
            BigDecimal distanceKm,
            BigDecimal billableWeightKg,
            BigDecimal rate,
            boolean deliverable,
            String issue) {}

    /** What one seller's slice comes to — the shape the order will split into. */
    public record VendorTotal(
            Long vendorId,
            String storeName,
            String listingCurrency,
            BigDecimal subtotal,
            BigDecimal shipping,
            BigDecimal total,
            boolean deliverable) {}
}
