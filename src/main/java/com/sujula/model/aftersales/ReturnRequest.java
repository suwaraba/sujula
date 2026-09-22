package com.sujula.model.aftersales;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.ReturnReason;
import com.sujula.model.constant.ReturnStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Goods going back, which is not the same thing as money coming back.
 *
 * <p>Kept apart from {@code RefundRequest} deliberately. A refund is a payment
 * instruction; this is a parcel travelling in the opposite direction, and on
 * this route that takes days and may not happen at all — sending a cracked
 * screen protector from Serrekunda back to Banjul costs more than the protector.
 * So the ordinary resolution here is an offer of money with the goods staying
 * put, and that is a state of its own rather than a refund somebody typed a
 * smaller number into.
 *
 * <p><strong>One seller's slice, never the whole payment (C3).</strong> A buyer
 * returning a phone to Kombo Electronics has nothing to do with the charger they
 * bought from Teranga Mobile on the same card. Keying this to the order would
 * have made one seller's return everybody's problem.
 *
 * <p><strong>Two currencies, snapshotted (C2).</strong> What the buyer gets back
 * is in what they paid; what comes off the seller is in what they were paid in;
 * and the rate is the one from the day of the order, carried here rather than
 * looked up again. A return settled next month at next month's rate is a return
 * where somebody quietly lost money on the exchange.
 */
@Entity
@Table(name = "return_requests",
       indexes = {
           @Index(name = "idx_return_vendor_order", columnList = "vendor_order_id"),
           @Index(name = "idx_return_requested_by", columnList = "requested_by_user_id"),
           @Index(name = "idx_return_status",       columnList = "status"),
           @Index(name = "idx_return_reference",    columnList = "reference", unique = true)
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Guards the offer-and-accept race.
     *
     * <p>Real rather than theoretical: a seller raising an offer and a buyer
     * accepting the previous one are seconds apart, and the loser of that race
     * must be told to look again rather than silently accept a number that is
     * no longer on the table.
     */
    @Version
    private Long version;

    /** What the buyer and the seller quote at each other. Never the id. */
    @Column(nullable = false, unique = true, length = 24)
    private String reference;

    /** The slice being returned. One seller, one decision, one settlement. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_order_id", nullable = false)
    private VendorOrder vendorOrder;

    /** Who asked. The buyer who paid, not the person who received the parcel. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReturnStatus status = ReturnStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReturnReason reason;

    /** What the buyer wrote. Shown to the seller as typed. */
    @Column(length = 1000)
    private String description;

    /**
     * Photographs, one URL per line.
     *
     * <p>A cracked screen is a photograph, not a sentence, and a return with a
     * picture is one a seller can settle in a minute rather than argue about for
     * a week. Stored as text rather than a table because they are only ever read
     * as a set, with this row.
     */
    @Column(columnDefinition = "TEXT")
    private String photoUrls;

    @JsonIgnore
    @OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @Builder.Default
    private List<ReturnLine> lines = new ArrayList<>();

    // ── What is at stake, in both currencies ─────────────────────────────────

    /** What the buyer would get back, in what they paid. */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    /** The same sum in the seller's currency — what comes off their payout. */
    @Column(precision = 12, scale = 2)
    private BigDecimal amountNative;

    /**
     * The rate this was converted at, and when it was taken.
     *
     * <p>Copied from the order rather than read again. Every converted figure
     * anybody is charged, paid or owed carries its own rate, and a return is the
     * clearest case: the goods were priced on one day and are being argued about
     * on another.
     */
    @Embedded
    private FxSnapshot fx;

    // ── The seller's answer ──────────────────────────────────────────────────

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_user_id")
    private User decidedBy;

    private LocalDateTime decidedAt;

    @Column(length = 1000)
    private String decisionNote;

    /**
     * Who pays to get the goods back.
     *
     * <p>Derived from the reason when the return is opened and then frozen. A
     * seller who later disagrees argues about the reason, not about a number
     * that moved after the buyer read it.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean sellerPaysCarriage = true;

    // ── Money instead of goods ───────────────────────────────────────────────

    /** What the seller offered to settle for, in the buyer's currency. */
    @Column(precision = 12, scale = 2)
    private BigDecimal offeredAmount;

    /** And in the seller's, at the same frozen rate. */
    @Column(precision = 12, scale = 2)
    private BigDecimal offeredAmountNative;

    @Column(length = 1000)
    private String offerNote;

    private LocalDateTime offeredAt;

    private LocalDateTime offerAcceptedAt;

    // ── The goods actually coming back ───────────────────────────────────────

    private LocalDateTime receivedAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by_user_id")
    private User receivedBy;

    @Column(length = 500)
    private String receivedNote;

    // ── When it stops being theirs to settle ─────────────────────────────────

    private LocalDateTime escalatedAt;

    /**
     * The dispute this became.
     *
     * <p>A link rather than a copy. Escalating does not restate the return's
     * facts somewhere else; it hands the same row to somebody impartial and
     * points at it.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispute_id")
    private Dispute dispute;

    /** The refund this ended in, when it ended in one. */
    @Column(length = 40)
    private String refundReference;

    private LocalDateTime withdrawnAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * What is actually owed, which is the offer when one was accepted.
     *
     * <p>Computed rather than stored, so the two numbers cannot disagree. An
     * accepted offer of half replaces the full amount; nothing overwrites it,
     * because the buyer needs to be able to see what they asked for beside what
     * they settled for.
     */
    public BigDecimal settlementAmount() {
        return offerAcceptedAt != null && offeredAmount != null ? offeredAmount : amount;
    }

    /** The same figure in the seller's own currency. */
    public BigDecimal settlementAmountNative() {
        return offerAcceptedAt != null && offeredAmountNative != null
                ? offeredAmountNative : amountNative;
    }
}
