package com.sujula.model.finance;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.user.Payout;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A run of transfers, prepared by one person and approved by another.
 *
 * <p>The two people are the point. Money leaving is the one action no later API
 * call can undo — there is no unsend — so the person who assembles a run is not
 * the person who releases it, and the entity refuses that rather than leaving it
 * to a procedure somebody follows when they are not in a hurry.
 *
 * <p>A batch is per currency, always. Sixteen vendors settling in dalasi and
 * four in CFA are two runs, not one run with a mixed total, because a total that
 * adds GMD to XOF is a number with no meaning that somebody will nonetheless
 * reconcile against a bank statement (C2).
 *
 * <p>The items are {@link Payout} rows — the same table a seller's own request
 * lands in, so a payout made by a batch and a payout a seller asked for settle,
 * fail and reverse through exactly one code path. A second table for "batch
 * payouts" would be a second way for money to leave.
 */
@Entity
@Table(name = "payout_batches",
       indexes = {
           @Index(name = "idx_batch_reference", columnList = "reference", unique = true),
           @Index(name = "idx_batch_status",    columnList = "status"),
           @Index(name = "idx_batch_currency",  columnList = "currency")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayoutBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    /** What an administrator quotes to the bank and to each other. */
    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PayoutBatchStatus status = PayoutBatchStatus.DRAFT;

    /** ISO 4217. Every item in the batch settles in it. */
    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * The sum of the items, frozen when the batch was assembled.
     *
     * <p>Stored rather than summed at read time, and the two are checked against
     * each other before release. A batch whose stored total and item sum disagree
     * is a batch somebody edited underneath, and releasing it would move an
     * amount nobody approved — which is precisely what the second approver
     * believes they are preventing.
     */
    @Setter(AccessLevel.NONE)
    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Setter(AccessLevel.NONE)
    @Column(nullable = false)
    @Builder.Default
    private int itemCount = 0;

    @Column(nullable = false)
    private Long preparedByUserId;

    @Column(nullable = false)
    private LocalDateTime preparedAt;

    /**
     * Who released it. Never the same person who prepared it.
     *
     * <p>Enforced in the service and again by {@link #canBeApprovedBy}, because
     * a rule that lives only in a service method is a rule that the next caller
     * does not know about.
     */
    private Long approvedByUserId;

    private LocalDateTime approvedAt;

    @Column(length = 500)
    private String note;

    /** Why it was abandoned, when it was. */
    @Column(length = 500)
    private String cancelledReason;

    private LocalDateTime cancelledAt;

    /**
     * What was excluded when the batch was assembled, and why.
     *
     * <p>A held store's money is not in this run, and the person approving it
     * should be told that rather than left to notice a vendor missing. Written
     * once at assembly: it describes what the batch is, not what is true now.
     */
    @Column(columnDefinition = "TEXT")
    private String exclusions;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Payout> items = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Set by the service after counting the items it just attached. */
    public void applyTotals(BigDecimal total, int itemCount) {
        this.total = total;
        this.itemCount = itemCount;
    }

    /**
     * Whether this person may release this batch.
     *
     * <p>False for the preparer, always. Four eyes on money leaving is not a
     * convention here; it is the only control between a compromised admin session
     * and every vendor balance on the platform.
     */
    public boolean canBeApprovedBy(Long userId) {
        return status == PayoutBatchStatus.AWAITING_APPROVAL
                && userId != null
                && !userId.equals(preparedByUserId);
    }
}
