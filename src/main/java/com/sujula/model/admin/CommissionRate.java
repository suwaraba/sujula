package com.sujula.model.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the platform takes from a seller, and from when.
 *
 * <p><strong>Effective-dated, and never applied backwards.</strong> That is the
 * whole reason this is a table rather than a column. A commission is agreed
 * between the platform and a seller, and an order priced under one rate is an
 * order settled under it — changing the number on the vendor row would silently
 * re-price every sale that had already happened, including ones already paid
 * out, and the seller would find out from a statement that no longer matched
 * the one they were sent last month.
 *
 * <p>So the rate that matters is the one in force on the day an order was
 * placed, and {@code VendorOrder} snapshots it at checkout exactly as it
 * snapshots the exchange rate (C2). These rows are what that snapshot is taken
 * from, and what a seller is shown when they ask what they are being charged
 * from next month.
 *
 * <p>Append-only. A change writes a new row with a later {@code effectiveFrom};
 * the previous one is closed rather than edited, so "what was I paying in
 * September" has an answer.
 */
@Entity
@Table(name = "commission_rates",
       indexes = {
           @Index(name = "idx_commission_vendor", columnList = "vendor_id, effective_from"),
           @Index(name = "idx_commission_live",   columnList = "vendor_id, effective_until")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommissionRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The seller this applies to.
     *
     * <p>Nullable, and that is the platform-wide default: a row with no vendor
     * is what a new store gets until somebody negotiates otherwise. Keeping the
     * default in the same table means the question "what rate applies here" has
     * one answer rather than two places to look.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    /** Percent, not a fraction. 12.50 means twelve and a half percent. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    /**
     * The category this rate is for, where it is not the seller's whole shop.
     *
     * <p>Phones and cloth do not carry the same margin, and a platform that
     * charged one rate across both would be overcharging on one of them. Null
     * means every category.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private com.sujula.model.products.Category category;

    /**
     * From when.
     *
     * <p>Never in the past on a new row, which the service enforces. A rate
     * backdated to last month would re-price orders that have already been paid
     * out, and the money to claw back would have to come from somewhere.
     */
    @Column(nullable = false)
    private LocalDateTime effectiveFrom;

    /**
     * Until when, set when a later rate supersedes this one.
     *
     * <p>Null means it is the one in force. Closed rather than deleted, so a
     * statement from September can still be explained in March.
     */
    private LocalDateTime effectiveUntil;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "set_by_user_id")
    private User setBy;

    /** Why it changed. A seller will ask, and "an administrator changed it" is not an answer. */
    @Column(length = 500)
    private String note;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Whether this rate applies at a given moment. */
    public boolean appliesAt(LocalDateTime when) {
        if (effectiveFrom != null && effectiveFrom.isAfter(when)) {
            return false;
        }
        return effectiveUntil == null || effectiveUntil.isAfter(when);
    }
}
