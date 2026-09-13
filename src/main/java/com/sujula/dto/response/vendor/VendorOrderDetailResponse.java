package com.sujula.dto.response.vendor;

import com.sujula.model.constant.VendorOrderStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * What a vendor needs to pack one order: which products, how many, and what
 * they will be paid.
 *
 * <p>Same rule as the queue row it expands — nothing about the buyer, nothing in
 * the buyer's currency, and no side-by-side of the two. The lines carry product
 * ids and the vendor's own listing prices, which is what the seller set in the
 * first place.
 */
@Data
@Builder
public class VendorOrderDetailResponse {

    private Long id;
    private String orderNumber;
    private VendorOrderStatus status;
    private Set<VendorOrderStatus> allowedNextStatuses;

    /** The vendor's settlement currency. Every amount on this response is in it. */
    private String currency;

    private BigDecimal goodsSubtotal;
    private BigDecimal discount;
    private BigDecimal goodsTotal;

    private BigDecimal commissionRate;
    private BigDecimal commission;

    /**
     * Delivery on this order's lines, for the seller's records. The platform
     * arranges delivery and keeps this, so it is not part of {@link #payout}.
     */
    private BigDecimal delivery;

    private BigDecimal payout;

    /**
     * How the buyer's currency became this vendor's, and when.
     *
     * <p>Present only when a conversion actually happened. Every figure above is
     * in the vendor's own currency and was converted from what the buyer paid;
     * without this the vendor can see what they are owed but not why it is that
     * number, and once the rate table has moved on nobody can reconstruct it.
     */
    private Fx fx;

    private String couponCode;

    private List<Line> lines;

    private LocalDateTime placedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime cancelledAt;

    private LocalDateTime acceptedAt;
    private LocalDateTime readyAt;
    private LocalDateTime collectedAt;

    /** Why the seller turned it down, when they did. */
    private String rejectionReason;

    /**
     * Where the parcel goes, limited to what packing needs.
     *
     * <p>The only buyer-derived block on this response, and it comes from the
     * delivery context rather than the payer's (C1). A seller never learns who
     * paid, from where, in what currency, or how much.
     */
    private com.sujula.dto.response.fulfilment.FulfilmentResponses.Shipping shipping;

    /**
     * Whether every serialised line has its handsets bound.
     *
     * <p>What decides whether {@code /ready} will be accepted. Returned so the
     * seller's screen can say so before they try.
     */
    private boolean readyToPack;

    /** The rate a payout was struck at, as the vendor sees it. */
    @Data
    @Builder
    public static class Fx {
        /** What the buyer was charged in. */
        private String paidIn;
        /** This vendor's own currency — what the figures above are in. */
        private String settledIn;
        /** Units of paidIn per one unit of settledIn. */
        private BigDecimal rate;
        /** When the rate was published — not when the order was placed. */
        private LocalDateTime rateAt;
        /** Where the rate came from: a published rate, or one held for the buyer. */
        private String source;
    }

    /** One product to pick, priced as the vendor listed it. */
    @Data
    @Builder
    public static class Line {
        private Long productId;
        private Long variantId;
        private String productName;
        private String sku;
        private String variantSku;
        private String selectedOptions;
        private String imageUrl;
        private Long lineId;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;

        /**
         * Whether this line is tracked handset by handset.
         *
         * <p>True when the variant has IMEI units behind it. A serialised line
         * cannot be packed until a handset is bound to each unit of it, which is
         * the only thing standing between an order and a parcel containing a
         * phone nobody can identify afterwards.
         */
        private boolean serialised;

        /** The handsets bound so far, in the order they were scanned. */
        private List<String> assignedImeis;

        /** How many still need binding before this line can be packed. */
        private int handsetsOutstanding;
    }
}
