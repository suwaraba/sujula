package com.sujula.model.logistics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.DeliveryMode;

import jakarta.persistence.Column;
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
 * What delivery costs, from a given day, in a given place.
 *
 * <p>Effective-dated for the same reason commission is: an order priced on the
 * fifteenth was priced under the card in force on the fifteenth, and a new card
 * written today cannot reach back and make that figure something else. Rows are
 * never edited into the past — {@code AdminLogisticsService} refuses a start date
 * that has already gone by, and supersedes rather than overwrites.
 *
 * <p>Scoped by zone and by mode. Null zone is the platform's own default for its
 * country; null mode applies to every mode. Resolution is most specific first,
 * so a card for PICKUP_POINT inside Kanifing wins over a national card for every
 * mode, which in turn wins over {@link com.sujula.service.delivery.DeliveryPricingProperties}.
 *
 * <p>Every amount is in {@link #currency}. That is deliberately <em>not</em> the
 * buyer's currency and not the vendor's: it is the currency the platform prices
 * carriage in, converted once at a snapshotted rate like every other figure a
 * buyer is charged.
 */
@Entity
@Table(name = "delivery_rate_cards",
       indexes = {
           @Index(name = "idx_rate_card_scope",  columnList = "zone_id, mode"),
           @Index(name = "idx_rate_card_from",   columnList = "effectiveFrom"),
           @Index(name = "idx_rate_card_country", columnList = "countryCode")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryRateCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** Null means "wherever this country's default applies". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private DeliveryZone zone;

    /**
     * ISO 3166-1 alpha-2 this card covers when it has no zone.
     *
     * <p>A country-level card is how a platform prices a place before anybody
     * has drawn a polygon for it, which is most places on the day they open.
     */
    @Column(length = 2)
    private String countryCode;

    /** Null applies to every mode. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DeliveryMode mode;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "GMD";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal baseFee;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal includedKm = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal perKm = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal includedKg = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal perKg = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal minFee;

    /** Null leaves a leg uncapped. */
    @Column(precision = 12, scale = 2)
    private BigDecimal maxFee;

    /** Waive carriage above this basket value with one vendor; null disables it. */
    @Column(precision = 12, scale = 2)
    private BigDecimal freeAbove;

    /** First day this card prices anything. */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    /**
     * Last day it does, inclusive. Null means "until something supersedes it".
     *
     * <p>Written by the service when a later card is created for the same scope,
     * so the timeline has no gap and no overlap. Two cards live on the same day
     * for the same scope is not a tie to be broken at read time — it is a
     * mistake, and closing the old one at write time is what prevents it.
     */
    private LocalDate effectiveUntil;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private Long createdByUserId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Whether this card prices a leg travelling on {@code day}. */
    public boolean appliesOn(LocalDate day) {
        return active
                && effectiveFrom != null && !day.isBefore(effectiveFrom)
                && (effectiveUntil == null || !day.isAfter(effectiveUntil));
    }

    /**
     * How specific this card is, for choosing between two that both apply.
     *
     * <p>A zone beats a country, and a card naming a mode beats one that covers
     * all of them. Stated as a number rather than left to query order, because
     * "whichever row came back first" is a pricing rule nobody can reproduce.
     */
    public int specificity() {
        return (zone != null ? 2 : 0) + (mode != null ? 1 : 0);
    }
}
