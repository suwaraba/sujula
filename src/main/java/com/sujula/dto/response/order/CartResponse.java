package com.sujula.dto.response.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.CartIssueType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A multivendor, multicurrency cart.
 *
 * <p>Items are grouped by vendor because everything downstream of the cart is
 * per-vendor: shipping, minimum order value, fulfilment SLA and payout. Each
 * group carries both its native (vendor listing) currency amounts and the
 * amounts converted into the shopper's display currency, so the client never
 * has to guess which currency a number is in.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CartResponse {

    private Long cartId;

    /** Populated for guest carts only. */
    private String sessionId;

    /** True when this cart has no owning user account. */
    private Boolean guest;

    /** Currency every {@code *Converted} amount below is denominated in. */
    private String displayCurrency;

    /** When this quote was computed. Converted amounts are indicative until checkout pins a rate. */
    private LocalDateTime pricedAt;

    /**
     * False when at least one vendor group could not be converted into the
     * display currency. The grand totals then cover only the convertible groups
     * and must not be presented as the amount payable.
     */
    private boolean totalsComplete;

    private List<VendorGroup> vendors;

    // ── Grand totals, in displayCurrency ──────────────────────────────────────

    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;

    private int itemCount;
    private int lineCount;

    /** Applied platform-scoped coupon, if any. */
    private String platformCouponCode;

    /** Cart-level problems found during revalidation. */
    private List<CartIssue> issues;

    // ── Nested types ──────────────────────────────────────────────────────────

    /** One vendor's slice of the cart. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VendorGroup {

        private Long vendorId;
        private String storeName;
        private String storeSlug;
        private String logoUrl;

        /** The vendor's own listing currency. */
        private String nativeCurrency;

        /**
         * Rate applied to convert {@code nativeCurrency} into the cart's display
         * currency. Null when the two are the same or no rate was found.
         */
        private BigDecimal exchangeRate;

        /** False when no rate was available; the converted amounts are then null. */
        private boolean convertible;

        private List<CartItemResponse> items;

        // Amounts in the vendor's native currency
        private BigDecimal subtotalNative;
        private BigDecimal discountNative;
        private BigDecimal totalNative;

        // The same amounts in the cart's display currency
        private BigDecimal subtotal;
        private BigDecimal discount;
        private BigDecimal total;

        /** Vendor-scoped coupon applied to this group, if any. */
        private String vendorCouponCode;

        /**
         * This group's share of a platform-funded coupon, in display currency.
         * Kept separate from {@code discount} so payout logic can tell which
         * part of the markdown the platform absorbs and which the vendor does.
         */
        private BigDecimal platformDiscountShare;

        /** True when every line in the group is purchasable right now. */
        private boolean checkoutable;

        private List<CartIssue> issues;
    }

    /** A single cart line. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CartItemResponse {

        private Long itemId;
        private Long productId;
        private String productName;
        private String productSlug;
        private String imageUrl;

        private Long vendorId;

        private Long variantId;
        private String variantSku;
        /** Human-readable variant summary, e.g. "Size: Large, Colour: Red". */
        private String variantLabel;

        private Integer quantity;
        private int availableStock;

        /** Listing currency for {@code unitPriceNative} / {@code lineTotalNative}. */
        private String nativeCurrency;
        private BigDecimal unitPriceNative;
        private BigDecimal lineTotalNative;

        /** Same amounts in the cart's display currency; null if unconvertible. */
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;

        /**
         * Previous unit price in native currency, present only when the vendor
         * moved the price since this line was added.
         */
        private BigDecimal previousUnitPriceNative;

        /** True when this line can be checked out as-is. */
        private boolean purchasable;

        private List<CartIssue> issues;
    }

    /** A machine-readable explanation of something the cart changed or blocked. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CartIssue {

        private CartIssueType type;

        /** Cart line the issue relates to; null for cart- or vendor-level issues. */
        private Long itemId;

        /** Vendor the issue relates to; null for cart-level issues. */
        private Long vendorId;

        private String message;

        public static CartIssue of(CartIssueType type, String message) {
            return CartIssue.builder().type(type).message(message).build();
        }
    }
}
