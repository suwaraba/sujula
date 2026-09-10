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

    private String couponCode;

    private List<Line> lines;

    private LocalDateTime placedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime cancelledAt;

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
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}
