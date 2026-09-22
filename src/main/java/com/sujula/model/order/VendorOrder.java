package com.sujula.model.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.products.Coupon;
import com.sujula.model.user.Vendor;

import com.sujula.model.money.FxSnapshot;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One vendor's slice of a multivendor {@link Order}.
 *
 * <p>Split out from {@code Order} because fulfilment, cancellation and payout
 * all happen per vendor — a buyer's single checkout can leave one vendor's
 * items shipped while another's are still pending, and a cancellation must be
 * able to touch just one vendor's slice without disturbing the rest.
 *
 * <p>Carries amounts twice: {@code *Native} in the vendor's own settlement
 * currency (what the vendor is actually owed — never converted) and the plain
 * fields in the order's display currency (what the buyer saw and paid),
 * mirroring how {@code CartResponse.VendorGroup} prices a still-open cart.
 * {@code nativeCurrency} and the {@code *Native} amounts are null when this
 * vendor's lines spanned more than one listing currency at checkout — payout
 * accounting then falls back to the per-line native amounts on each
 * {@link OrderItem}.
 */
@Entity
@Table(name = "vendor_orders",
       indexes = {
           @Index(name = "idx_vendor_order_order",  columnList = "order_id"),
           @Index(name = "idx_vendor_order_vendor", columnList = "vendor_id"),
           @Index(name = "idx_vendor_order_status", columnList = "status")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class VendorOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VendorOrderStatus status = VendorOrderStatus.PENDING;

    /** The vendor's own settlement currency; null when this slice spanned several. */
    @Column(length = 3)
    private String nativeCurrency;

    @Column(precision = 12, scale = 2)
    private BigDecimal subtotalNative;

    @Column(precision = 12, scale = 2)
    private BigDecimal discountNative;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalNative;

    // ── Same amounts, in the order's display currency (what the buyer paid) ───

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    // ── Settlement, frozen at checkout ───────────────────────────────────────
    // All in nativeCurrency. Frozen rather than computed on demand: the platform's
    // commission rate changes, exchange rates change daily, and neither may
    // restate what a vendor was owed for an order already placed.

    /** Platform commission percentage applied to this slice, as it stood at checkout. */
    @Column(precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Column(precision = 12, scale = 2)
    private BigDecimal commissionNative;

    /**
     * Delivery on this vendor's lines, converted at the same rate the goods were.
     *
     * <p>Recorded for the vendor's own books, not paid to them: the platform
     * arranges and keeps delivery, so it is deliberately absent from
     * {@link #payoutNative}.
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal deliveryNative;

    /** What the vendor is owed: {@code totalNative - commissionNative}. */
    @Column(precision = 12, scale = 2)
    private BigDecimal payoutNative;

    /**
     * The rate every {@code *Native} figure above was converted at, and when
     * that rate was taken.
     *
     * <p>This slice is where the rate belongs, not the order: an order spanning
     * two vendors in two listing currencies was priced at two rates, and a single
     * order-level rate could only ever record one of them. One vendor, one
     * currency pair, one rate.
     *
     * <p>Without it the frozen amounts above are unexplainable. They are the
     * right numbers — but a vendor asking why their payout was what it was gets
     * only the figure back, and nobody can reconstruct the arithmetic once the
     * rate table has moved on, which it does daily.
     *
     * <p>Null when this vendor's lines spanned more than one listing currency,
     * the same case in which the native totals are null: there is no single rate
     * to record because there was no single conversion.
     */
    @Embedded
    private FxSnapshot fx;

    /**
     * When the buyer said they had received this seller's goods.
     *
     * <p>Optional and early. Money is held until delivery is proven, and proof
     * normally comes from the custody chain — a code handed over, a signature, a
     * photograph. This is the buyer short-circuiting that: they have the parcel,
     * they say so, and the seller is paid without waiting on the paperwork.
     *
     * <p>Per vendor, because a basket from two sellers arrives as two parcels on
     * two days. Confirming one must not pay the other.
     */
    private LocalDateTime receiptConfirmedAt;

    /**
     * When this slice's funds became releasable to the vendor.
     *
     * <p>Set by receipt confirmation or by proven delivery, never by the vendor
     * and never by time alone. It is the difference between "the goods arrived"
     * and "we assume they did".
     */
    private LocalDateTime escrowReleasedAt;

    /**
     * Set while a dispute is holding this slice's money still.
     *
     * <p>Written only by the dispute writer and read by {@code MoneyLedger}
     * before it releases escrow — which is what makes the freeze structural
     * rather than remembered. A delivery that happens after a dispute is raised
     * would otherwise release the money through a path that knows nothing about
     * disputes, and the parcel arriving is exactly what the argument is usually
     * about.
     *
     * <p>Per slice, never per order (C3). One seller's disputed line must not
     * hold another seller's payout on the same payment.
     */
    private LocalDateTime disputeFrozenAt;

    /** Whether the buyer has confirmed they have these goods. */
    public boolean isReceiptConfirmed() {
        return receiptConfirmedAt != null;
    }

    /**
     * Whether this slice can still be stopped without anything being recalled.
     *
     * <p>Pre-dispatch: nothing has left the seller, so cancelling costs a
     * restock and nothing else. Once it has shipped, stopping it is a return,
     * which is a different conversation with different money in it.
     */
    public boolean isPreDispatch() {
        return status == com.sujula.model.constant.VendorOrderStatus.PENDING
                || status == com.sujula.model.constant.VendorOrderStatus.PREPARING
                || status == com.sujula.model.constant.VendorOrderStatus.READY_FOR_PICKUP;
    }

    /** Vendor-scoped coupon applied to this slice, if any. Snapshot survives coupon deletion. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    @JsonIgnore
    private Coupon coupon;

    @Column(length = 50)
    private String couponCode;

    @OneToMany(mappedBy = "vendorOrder", fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    private LocalDateTime cancelledAt;

    // ── Fulfilment, as the seller works through it ───────────────────────────

    /** When the seller accepted the order and started packing. */
    private LocalDateTime acceptedAt;

    /** When it was packed and waiting for a driver. */
    private LocalDateTime readyAt;

    /** When a driver collected it, against a release code. */
    private LocalDateTime collectedAt;

    /**
     * Why the seller refused it.
     *
     * <p>Required when they do. A rejection with no reason leaves the buyer with
     * a cancelled order, a pending refund and nothing to tell the person waiting
     * for the parcel - which on this marketplace is somebody else entirely, in
     * another country, who was told a gift was coming.
     */
    @Column(length = 400)
    private String rejectionReason;

    /**
     * How many times the release code has been reissued.
     *
     * <p>Counted because reissuing is how a code that has leaked is replaced,
     * and because doing it constantly is how a seller brute-forces nothing in
     * particular but does exhaust the driver's patience. The rate limit reads
     * this.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer releaseCodeIssueCount = 0;

    private LocalDateTime releaseCodeIssuedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
