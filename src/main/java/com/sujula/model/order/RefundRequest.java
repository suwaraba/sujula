package com.sujula.model.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A buyer asking for money back on one seller's goods.
 *
 * <p><strong>Attached to a vendor order, not an order.</strong> That is the
 * whole reason this entity exists. A basket from two sellers is one payment and
 * two shipments, and when one seller cancels, the buyer is owed that seller's
 * portion — not a proportion of the order. Until now a refund could only be
 * expressed as an amount against the whole order, which made "refund the wax
 * print but not the phone" impossible to say.
 *
 * <p><strong>Nothing moves automatically.</strong> The buyer asks and an
 * administrator decides. Money leaving the platform is the one action no
 * subsequent API call can undo, and on a marketplace where a shipment may
 * already be halfway to another country, an automatic refund on request is an
 * invitation to lose both the goods and the money.
 */
@Entity
@Table(name = "refund_requests",
       indexes = {
           @Index(name = "idx_refund_vendor_order", columnList = "vendor_order_id"),
           @Index(name = "idx_refund_order",        columnList = "order_id"),
           @Index(name = "idx_refund_status",       columnList = "status")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable, for the buyer and for support to quote at each other. */
    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /**
     * The slice this refund is for.
     *
     * <p>Required. A refund with no vendor order is the order-level refund this
     * entity exists to replace.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_order_id", nullable = false)
    private VendorOrder vendorOrder;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RefundRequestStatus status = RefundRequestStatus.REQUESTED;

    /**
     * What the buyer is owed, in what they paid in.
     *
     * <p>The buyer's currency, because that is what left their card. What the
     * platform then recovers from the vendor is a separate figure in the
     * vendor's own currency, which is why the snapshot below travels with it.
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * The same amount in the vendor's settlement currency.
     *
     * <p>What is clawed back from their payout. Kept beside the buyer's figure
     * rather than derived later, because by the time a refund is settled the rate
     * has moved and re-deriving it would make the two sides disagree.
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal amountNative;

    /** The rate the two figures above were reconciled at. */
    @Embedded
    private FxSnapshot fx;

    @Column(length = 300)
    private String reason;

    // ── The decision ─────────────────────────────────────────────────────────

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_user_id")
    private User decidedBy;

    private LocalDateTime decidedAt;

    /** Why it was refused, in words the buyer can read. */
    @Column(length = 300)
    private String decisionNote;

    /** The payment movement that actually returned the money, once one exists. */
    private Long paymentId;

    private LocalDateTime completedAt;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    public boolean isOpen() {
        return status != null && status.isOpen();
    }
}
