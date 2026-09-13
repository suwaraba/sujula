package com.sujula.model.money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.Payout;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing that happened to a vendor's money.
 *
 * <p><strong>The rows are the truth and the balance is a sum of them.</strong>
 * There is no stored balance anywhere that this has to be kept in step with,
 * because a stored balance is a second opinion about the same money and the two
 * will disagree the first time something is written without remembering to
 * update it. A seller who disputes their balance can be shown the rows that
 * make it, which is the only answer worth giving.
 *
 * <p><strong>Append-only.</strong> Nothing here is edited or deleted. A refund
 * is a new negative row, not a smaller sale; a failed payout is a reversal, not
 * an undone transfer. What actually happened, in the order it happened, stays
 * readable afterwards.
 *
 * <h2>Currency</h2>
 *
 * <p>Every entry is in the vendor's own currency, frozen at the rate the order
 * carried, and every entry states which currency that was (C2). Rows are never
 * summed across currencies and never re-converted: a vendor who has traded in
 * two currencies has two balances, and inventing a single figure would require
 * a rate nobody agreed to at a moment nobody can name.
 *
 * <p>The {@link #fx} snapshot travels with the row so it can explain itself
 * years later — what the buyer paid, in what, and at what rate it became this.
 *
 * <h2>When money becomes available</h2>
 *
 * <p>A sale posts immediately and is <em>held</em>: {@link #availableFrom} is
 * null until the slice's escrow releases, which happens when delivery is proven
 * or the buyer confirms receipt. That is the whole point of escrow on a
 * marketplace where the goods may be crossing a border — money must not be
 * payable before anyone knows the parcel arrived.
 */
@Entity
@Table(name = "vendor_ledger_entries",
       indexes = {
           @Index(name = "idx_ledger_vendor",           columnList = "vendor_id"),
           @Index(name = "idx_ledger_vendor_currency",  columnList = "vendor_id, currency"),
           @Index(name = "idx_ledger_vendor_occurred",  columnList = "vendor_id, occurredAt"),
           @Index(name = "idx_ledger_vendor_order",     columnList = "vendor_order_id"),
           @Index(name = "idx_ledger_payout",           columnList = "payout_id")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private LedgerEntryType type;

    /**
     * Signed, always.
     *
     * <p>Positive is owed to the vendor, negative is taken back or paid out. A
     * ledger of absolute values with a separate direction column is one where
     * the sum is wrong the first time somebody forgets to read the direction.
     */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** The vendor's own currency at the time. Never re-derived from the vendor row. */
    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * The slice this entry came off, where one did.
     *
     * <p>Null for payouts and hand-made adjustments, which belong to the vendor
     * rather than to any one order.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_order_id")
    private VendorOrder vendorOrder;

    /** The transfer this entry was part of, once it has been paid out. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_id")
    private Payout payout;

    /**
     * When this money became payable, or null while it is still held.
     *
     * <p>Null is not "unknown" — it is escrow. A sale posts held and this is
     * stamped when the slice's escrow releases, so "what am I owed today" and
     * "what will I be owed once the parcels arrive" are two sums over the same
     * rows rather than two tables.
     *
     * <p>Entries that are never held — a commission, a payout, an adjustment —
     * carry the moment they occurred, because there is nothing to wait for.
     */
    private LocalDateTime availableFrom;

    /**
     * When the thing this row describes happened.
     *
     * <p>Distinct from {@link #createdAt}, which is when it was written down.
     * They differ whenever something is posted late, and a statement for March
     * has to be built from when the money moved rather than when somebody got
     * round to recording it.
     */
    @Column(nullable = false)
    private LocalDateTime occurredAt;

    /** In the seller's words, not the system's. Shown as-is in the ledger view. */
    @Column(nullable = false, length = 300)
    private String description;

    /**
     * The order number, refund reference or payout reference behind this row.
     *
     * <p>What a seller quotes at support. Denormalised on purpose: a reference
     * that stopped resolving because a row was archived is still the thing they
     * wrote on a piece of paper.
     */
    @Column(length = 40)
    private String reference;

    /**
     * The rate the order behind this entry was struck at.
     *
     * <p>Copied from the slice rather than looked up. By the time anybody reads
     * a ledger the published rate has moved, and re-deriving would make the
     * row's own figures stop agreeing with the order it came from.
     */
    @Embedded
    private FxSnapshot fx;

    /** Who made it, for the one type a person may write. Null for everything else. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Whether this money is payable as at {@code when}. */
    public boolean isAvailableAt(LocalDateTime when) {
        return availableFrom != null && !availableFrom.isAfter(when);
    }

    /** Whether this money is still waiting on a parcel arriving. */
    public boolean isHeld() {
        return availableFrom == null;
    }
}
