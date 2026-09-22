package com.sujula.model.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.delivery.Driver;
import com.sujula.model.delivery.PickupPoint;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One hop of a parcel's journey, and one driver's job.
 *
 * <p>A leg is what a driver accepts or declines. Splitting a shipment into legs
 * is not bookkeeping: the driver who can reach a shop on Kairaba Avenue is
 * frequently not the one who covers the street the parcel is going to, and a
 * model with one driver per parcel would either refuse those journeys or lose
 * track of who had the goods in the middle of them.
 *
 * <p>Each leg names where it starts and ends as coordinates, because that is
 * what the geofence on collection and delivery is measured against — and because
 * most addresses in this market do not resolve to a point on their own.
 */
@Entity
@Table(name = "shipment_legs",
       uniqueConstraints = @UniqueConstraint(name = "uq_leg_shipment_sequence",
                                             columnNames = {"shipment_id", "sequence"}),
       indexes = {
           @Index(name = "idx_leg_shipment",       columnList = "shipment_id"),
           @Index(name = "idx_leg_driver_status",  columnList = "driver_id, assignmentStatus"),
           @Index(name = "idx_leg_status",         columnList = "assignmentStatus")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipmentLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Two drivers standing next to each other during a transfer both write here. */
    @Version
    private Long version;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    /** Position in the journey, from 1. Unique within a shipment. */
    @Column(nullable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private LegType legType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LegAssignmentStatus assignmentStatus = LegAssignmentStatus.UNASSIGNED;

    /** Whose job this is, once somebody has taken it. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    /**
     * When an unanswered offer stops being one.
     *
     * <p>A deadline rather than a queue position. A driver who does not answer
     * must not hold a parcel out of circulation indefinitely — on a marketplace
     * where the recipient is waiting on a gift from abroad, an offer nobody
     * declined is worse than one somebody did.
     */
    private LocalDateTime offerExpiresAt;

    private LocalDateTime offeredAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime declinedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    /** Why a driver said no. Kept because a pattern of reasons is worth seeing. */
    @Column(length = 300)
    private String declineReason;

    // ── Where it goes, as points ─────────────────────────────────────────────

    private Double originLatitude;
    private Double originLongitude;

    @Column(length = 300)
    private String originLabel;

    private Double destinationLatitude;
    private Double destinationLongitude;

    @Column(length = 300)
    private String destinationLabel;

    /** The hub this leg starts from, when it starts at one. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_pickup_point_id")
    private PickupPoint originPickupPoint;

    /** The hub this leg ends at, when it ends at one. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_pickup_point_id")
    private PickupPoint destinationPickupPoint;

    @Column(precision = 8, scale = 3)
    private BigDecimal distanceKm;

    /**
     * What this driver earns for this leg, in the platform's own currency.
     *
     * <p>Frozen when the leg is created rather than computed at payout. A driver
     * who accepted a job for a stated amount is owed that amount, whatever the
     * fee table says by the time they are paid.
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal earning;

    @Column(length = 3)
    private String earningCurrency;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Whether this offer is still open to be answered. */
    public boolean isOfferOpen(LocalDateTime now) {
        return assignmentStatus == LegAssignmentStatus.OFFERED
                && (offerExpiresAt == null || offerExpiresAt.isAfter(now));
    }

    /** Whether this driver currently holds the goods for this leg. */
    public boolean isCarrying() {
        return assignmentStatus == LegAssignmentStatus.IN_PROGRESS;
    }
}
