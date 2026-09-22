package com.sujula.dto.response.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.CartIssueType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
// Both constructors, because this class is not only serialised.
//
// @Builder suppresses the default constructor, so Jackson could write a
// CartResponse and not read one back. That matters in exactly one place and it
// is a place that matters: the idempotency records store a serialised response
// and replay it on a retry, and POST /carts/{token}/items is idempotent by
// design — its own description says "send an Idempotency-Key so a retried tap
// does not add the item twice". The retry the key exists for answered 500.
//
// Every other idempotent response on this platform is a record, which Jackson
// constructs from its components. This was the only class among them, so it was
// the only one that broke.
@NoArgsConstructor
// Private, and that is load-bearing. @Builder needs an all-args constructor and
// @NoArgsConstructor suppresses the implicit one, so both have to be declared —
// but a PUBLIC all-args constructor is picked up by Jackson's parameter-names
// module as a properties-based creator, and it then maps every absent field to
// null and fails on the first primitive. Private leaves the builder working and
// leaves Jackson with the default constructor and the setters, which is the
// binding that actually reads a stored response back.
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CartResponse {

    private Long cartId;

    /**
     * How every later request addresses this cart.
     *
     * <p>256 bits of secure random, and the whole credential: a cart holds a
     * destination, a list of what somebody is buying and for whom, and what they
     * are about to spend. It is returned because it has to be — without it a
     * guest who opens a basket cannot add a line to it, price it or pay for it,
     * and this surface is addressed by a token rather than reached through a
     * cookie precisely so that a native client with no cookie jar can use it.
     *
     * <p>It was missing, and POST /carts was unusable for a new guest as a
     * result: the endpoint's own description said "returns the cart with its
     * token" and the response carried a numeric id that GET /carts/&#123;token&#125;
     * does not resolve. Nothing noticed because every test and every seeded
     * cart used a token written by hand.
     */
    private String token;

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

    /**
     * Sum of the per-line delivery legs, in the display currency.
     *
     * <p>Zero until the shopper says where the goods are going. Delivery is
     * priced from the distance a parcel travels and the weight it carries, and
     * neither is knowable before there is a destination — so an unpriced cart
     * shows no shipping rather than a guess.
     */
    private BigDecimal shipping;

    private BigDecimal total;

    /**
     * The delivery context this cart is priced against.
     *
     * <p>Echoed back so a client can show "delivering to Serrekunda" and so the
     * separation from {@code displayCurrency} is visible in the payload: they
     * are two fields because they answer two questions.
     */
    private String deliveryContextId;

    /** False when some line cannot reach the destination. */
    private Boolean deliverable;

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
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
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

        /**
         * What it costs to get this seller's goods to the destination.
         *
         * <p>Per group because that is how it is incurred: a multivendor basket
         * has no single origin, and two sellers in two towns ship two parcels.
         * Null until the cart has somewhere to deliver to.
         */
        private BigDecimal shipping;

        private BigDecimal total;

        /** False when something from this seller cannot reach the destination. */
        private Boolean deliverable;

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
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
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

        /**
         * This line's own delivery leg, in the display currency.
         *
         * <p>Priced per product rather than apportioned from an order total,
         * because each travels its own distance carrying its own weight.
         */
        private BigDecimal deliveryCost;

        /** How far this product travels to the destination. */
        private BigDecimal distanceKm;

        /**
         * Whether this specific product can reach the destination.
         *
         * <p>Per line, because on a multivendor cart the answer differs between
         * lines and a shopper needs to know which item is the problem — not
         * merely that "the cart" cannot be delivered.
         */
        private Boolean deliverable;

        /** True when this line can be checked out as-is. */
        private boolean purchasable;

        private List<CartIssue> issues;
    }

    /** A machine-readable explanation of something the cart changed or blocked. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
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
