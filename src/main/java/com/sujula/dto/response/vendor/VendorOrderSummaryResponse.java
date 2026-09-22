package com.sujula.dto.response.vendor;

import com.sujula.model.constant.VendorOrderStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of a vendor's fulfilment queue.
 *
 * <p>What is deliberately absent is the point of this class. There is no buyer,
 * no delivery address, no order total, and no amount in the buyer's currency —
 * a vendor packs goods and is paid for them, and none of that requires knowing
 * who bought them or what they were charged. Every figure here is in the
 * vendor's own settlement currency, frozen when the order was placed.
 */
@Data
@Builder
public class VendorOrderSummaryResponse {

    /** The vendor order id — what every other endpoint on this controller takes. */
    private Long id;

    /** The buyer-facing order number, so seller and support can talk about the same order. */
    private String orderNumber;

    private VendorOrderStatus status;

    /** The statuses this order may move to next, so a UI need not encode the rules. */
    private java.util.Set<VendorOrderStatus> allowedNextStatuses;

    private int itemCount;

    /** Total units across the lines — what actually has to be picked. */
    private int totalUnits;

    /** The vendor's settlement currency. Every amount below is in it. */
    private String currency;

    private BigDecimal goodsTotal;
    private BigDecimal commission;
    private BigDecimal payout;

    private LocalDateTime placedAt;
    private LocalDateTime updatedAt;
}
