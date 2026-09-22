package com.sujula.model.aftersales;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.DisputeOutcome;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Two people who cannot agree, and the money held still while somebody decides.
 *
 * <p><strong>Opening one freezes the seller's money on this sub-order and
 * nothing else (C3).</strong> A buyer disputing the phone they bought from Kombo
 * Electronics has no quarrel with Teranga Mobile, whose charger arrived on the
 * same payment and whose payout must not wait on somebody else's argument. The
 * freeze is two things working together and both are needed: escrow on this
 * slice stops releasing, and — when it has already released — a hold entry goes
 * on the ledger so the money leaves the available balance.
 *
 * <p>The freeze is recorded on {@link VendorOrder#getDisputeFrozenAt()}, which
 * only this dispute's writer sets. That is what {@code MoneyLedger} reads before
 * releasing escrow, so a frozen slice cannot be released by any path at all —
 * including a delivery that happens after the dispute was raised.
 */
@Entity
@Table(name = "disputes",
       indexes = {
           @Index(name = "idx_dispute_vendor_order", columnList = "vendor_order_id"),
           @Index(name = "idx_dispute_raised_by",    columnList = "raised_by_user_id"),
           @Index(name = "idx_dispute_status",       columnList = "status"),
           @Index(name = "idx_dispute_reference",    columnList = "reference", unique = true)
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 24)
    private String reference;

    /** The slice being argued over. One seller's line, never the whole payment. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_order_id", nullable = false)
    private VendorOrder vendorOrder;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raised_by_user_id")
    private User raisedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DisputeStatus status = DisputeStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DisputeReason reason;

    @Column(length = 2000)
    private String description;

    /** The return this grew out of, when it grew out of one. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_request_id")
    private ReturnRequest returnRequest;

    // ── What is at stake ─────────────────────────────────────────────────────

    /** What the buyer says they are owed, in what they paid. */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    /** The same in the seller's currency, at the order's own frozen rate. */
    @Column(precision = 12, scale = 2)
    private BigDecimal amountNative;

    @Embedded
    private FxSnapshot fx;

    // ── The freeze ───────────────────────────────────────────────────────────

    private LocalDateTime frozenAt;

    private LocalDateTime unfrozenAt;

    /**
     * The ledger reference of the hold, when one was actually posted.
     *
     * <p>Null when the sale was still in escrow at the moment the dispute was
     * raised: there was nothing available to hold, and posting a negative entry
     * against money that had never been made payable would have taken the
     * balance down twice for one sale.
     */
    @Column(length = 40)
    private String holdReference;

    // ── The answer ───────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DisputeOutcome outcome;

    /**
     * What goes back to the buyer, where the answer is "some of it".
     *
     * <p>Its own column rather than reusing {@link #amount}, because the two say
     * different things: the amount is what was claimed and this is what was
     * decided, and a system that overwrote the first with the second would erase
     * the claim it answered.
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal awardedToBuyer;

    @Column(precision = 12, scale = 2)
    private BigDecimal awardedToBuyerNative;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private User resolvedBy;

    private LocalDateTime resolvedAt;

    @Column(length = 2000)
    private String resolutionNote;

    private LocalDateTime withdrawnAt;

    // ── The queue ────────────────────────────────────────────────────────────

    /**
     * The agent this is somebody's job.
     *
     * <p>Null means nobody has picked it up, which is not the same as nobody
     * having looked — a queue where "unassigned" and "untouched" are the same
     * field is one where two agents work the same dispute and neither knows.
     */
    private Long assignedToUserId;

    private LocalDateTime assignedAt;

    /**
     * When this should have been answered by, frozen when it was raised.
     *
     * <p>Frozen rather than computed from today's policy, for the same reason a
     * moderation case's is: a queue sorted by a deadline that moves with policy
     * is one where the oldest dispute is never the most urgent. Both parties
     * have money tied up behind this — the buyer's payment and the seller's
     * balance — and the deadline is the promise about how long that lasts.
     */
    private LocalDateTime dueBy;

    /**
     * Set when somebody asked for a person to telephone them.
     *
     * <p>Some disputes cannot be settled in writing. A recipient in Serrekunda
     * whose parcel arrived open, describing it in a second language through a
     * form, is a case that a two-minute call resolves and a week of messages
     * does not.
     */
    private LocalDateTime callbackRequestedAt;

    /** The refund this ended in, when the buyer was owed something. */
    @Column(length = 40)
    private String refundReference;

    // ── The case file ────────────────────────────────────────────────────────

    @JsonIgnore
    @OneToMany(mappedBy = "dispute", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC, id ASC")
    @Builder.Default
    private List<DisputeMessage> messages = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "dispute", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC, id ASC")
    @Builder.Default
    private List<DisputeEvidence> evidence = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Whether the money on this slice is still held still. */
    public boolean freezesMoney() {
        return status != null && status.freezesMoney();
    }
}
