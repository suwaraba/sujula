package com.sujula.model.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The vendor-level discount contract frozen into a cart quote.
 *
 * <p>The quote lines already preserve products, quantities, prices, delivery,
 * currencies and FX. This row stores only the evidence that cannot be recovered
 * from those lines: which vendor received which display/native discount, what
 * share came from the platform, and the identity of any vendor coupon. Checkout
 * consumes these values directly; it must not repeat coupon allocation against
 * live cart state.
 */
@Entity
@Table(name = "cart_quote_vendor_snapshots",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_cart_quote_vendor_snapshot",
               columnNames = {"quote_id", "vendor_id"}),
       indexes = {
           @Index(name = "idx_quote_vendor_snapshot_quote", columnList = "quote_id"),
           @Index(name = "idx_quote_vendor_snapshot_vendor", columnList = "vendor_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartQuoteVendorSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private CartQuote quote;

    /** Scalar audit identity; no live vendor data is needed to price checkout. */
    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    /** Total vendor discount in the buyer's display currency. */
    @Column(name = "discount_display", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountDisplay = BigDecimal.ZERO;

    /** Exact frozen discount in this vendor's native/listing currency. */
    @Column(name = "discount_native", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountNative = BigDecimal.ZERO;

    /** Display-currency portion of the discount funded by a platform coupon. */
    @Column(name = "platform_discount_share_display", nullable = false,
            precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal platformDiscountShareDisplay = BigDecimal.ZERO;

    /** Immutable audit identity, deliberately not a relationship to live coupon state. */
    @Column(name = "vendor_coupon_id")
    private Long vendorCouponId;

    @Column(name = "vendor_coupon_code", length = 50)
    private String vendorCouponCode;
}
