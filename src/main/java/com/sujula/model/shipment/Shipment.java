package com.sujula.model.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.order.VendorOrder;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One physical parcel, on its way.
 *
 * <p><strong>One per vendor order, because that is how many boxes there are.</strong>
 * A seller packs their whole slice into one parcel and hands over one release
 * code; a shipment is that box. Keying this to an order line instead would give
 * a two-line slice two shipments with one code between them, which is a chain
 * whose first link cannot be verified twice.
 *
 * <p><strong>The status is derived and has no public setter.</strong> It is
 * recomputed from {@link CustodyEvent} every time one is appended. That is C4
 * made structural rather than remembered: there is no method anywhere that sets
 * a shipment to DELIVERED, so no parcel can reach DELIVERED without an event
 * saying who handed it to whom, where, and with what proof.
 */
@Entity
@Table(name = "shipments",
       indexes = {
           @Index(name = "idx_shipment_vendor_order", columnList = "vendor_order_id", unique = true),
           @Index(name = "idx_shipment_status",       columnList = "status"),
           @Index(name = "idx_shipment_reference",    columnList = "reference", unique = true)
       })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter
    private Long id;

    /**
     * Guards against two drivers acting on the same parcel at once.
     *
     * <p>Real on this platform rather than theoretical: a transfer between
     * drivers has both of them writing to the same shipment within seconds of
     * each other, standing next to each other.
     */
    @Version
    private Long version;

    /** What a driver and a support agent quote at each other. Never the id. */
    @Column(nullable = false, unique = true, length = 24)
    @Setter
    private String reference;

    /** The slice this parcel holds. One box, one seller, one release code. */
    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_order_id", nullable = false, unique = true)
    @Setter
    private VendorOrder vendorOrder;

    /**
     * Where the parcel is, computed from the events below.
     *
     * <p>Deliberately without a public setter. {@code CustodyChain} is the only
     * thing that may change it, and it does so by re-deriving it from the chain
     * rather than by being told a new value — so this field cannot disagree with
     * the events, because it is a function of them.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    @Builder.Default
    private ShipmentStatus status = ShipmentStatus.AWAITING_COLLECTION;

    /**
     * The legs, in the order they are to be travelled.
     *
     * <p>A parcel may go straight to a door or through two hubs, so this is a
     * list rather than a pair of columns.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "shipment", fetch = FetchType.LAZY)
    @OrderBy("sequence ASC")
    @Builder.Default
    private List<ShipmentLeg> legs = new ArrayList<>();

    /** Everything that has happened to it, oldest first. Append-only. */
    @JsonIgnore
    @OneToMany(mappedBy = "shipment", fetch = FetchType.LAZY)
    @OrderBy("occurredAt ASC, id ASC")
    @Builder.Default
    private List<CustodyEvent> events = new ArrayList<>();

    // ── Where it is going ────────────────────────────────────────────────────
    //
    // Snapshotted from the order, for the same reason the order snapshots it
    // from the address book: a parcel in transit must not change destination
    // because somebody edited a saved address.

    @Column(length = 200)
    @Setter
    private String recipientName;

    @Column(length = 30)
    @Setter
    private String recipientPhone;

    @Column(length = 300)
    @Setter
    private String destinationStreet;

    @Column(length = 120)
    @Setter
    private String destinationCity;

    @Column(length = 2)
    @Setter
    private String destinationCountry;

    /**
     * Where the parcel actually goes, as a point.
     *
     * <p>The courier needs a position, not a street name, in a country where
     * most addresses do not resolve to one. Also what the delivery geofence is
     * measured against.
     */
    @Setter
    private Double destinationLatitude;

    @Setter
    private Double destinationLongitude;

    /** Where it starts: the seller's counter. */
    @Setter
    private Double originLatitude;

    @Setter
    private Double originLongitude;

    @Column(length = 300)
    @Setter
    private String originAddress;

    // ── Money and counting ───────────────────────────────────────────────────

    /** How many things are in the box, for a driver to check against what they were handed. */
    @Column(nullable = false)
    @Builder.Default
    @Setter
    private Integer parcelCount = 1;

    /** What the drivers on this shipment are paid between them, in the platform's own currency. */
    @Column(precision = 12, scale = 2)
    @Setter
    private BigDecimal deliveryFee;

    @Column(length = 3)
    @Setter
    private String feeCurrency;

    // ── Attempts ─────────────────────────────────────────────────────────────

    /**
     * How many delivery attempts have failed.
     *
     * <p>Derived from the FAILED_ATTEMPT events rather than incremented
     * independently, and kept here so the retry rule does not need to walk the
     * chain on every read.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer failedAttempts = 0;

    @Setter
    private LocalDateTime nextAttemptAfter;

    // ── Sitting at a counter ─────────────────────────────────────────────────

    /**
     * The pickup point currently holding this parcel.
     *
     * <p>Null whenever it is moving or finished. Set when a point accepts it and
     * cleared when it leaves, so "what is on my shelf" is one indexed read
     * rather than a walk through the custody chain for every parcel in the
     * country.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @jakarta.persistence.ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "held_at_pickup_point_id")
    @Setter
    private com.sujula.model.delivery.PickupPoint heldAtPickupPoint;

    /**
     * Where on the shelf it is.
     *
     * <p>A location label, not a credential. An operator with two hundred
     * parcels behind a counter needs to be able to walk to the right one, and
     * "the Galaxy for the lady from Serrekunda" is not a filing system. Anyone
     * who can see this can already see the parcel; what they still cannot do is
     * release it, because that needs the recipient's code.
     */
    @Column(length = 12)
    @Setter
    private String shelfCode;

    @Setter
    private LocalDateTime storedAt;

    /**
     * When it stops being stored and starts going back.
     *
     * <p>Computed from the point's own storage window when it is accepted, and
     * frozen: an operator who later shortens their window must not retroactively
     * make somebody's parcel overdue.
     */
    @Setter
    private LocalDateTime storageDeadline;

    /** What the operator earns for this one, frozen when they accepted it. */
    @Column(precision = 12, scale = 2)
    @Setter
    private BigDecimal pickupCommission;

    @Column(length = 3)
    @Setter
    private String pickupCommissionCurrency;

    /** Whether this parcel is past the day it should have been collected. */
    public boolean isOverdue(LocalDateTime now) {
        return storageDeadline != null && heldAtPickupPoint != null
                && storageDeadline.isBefore(now);
    }

    // ── Timestamps, each one a consequence of an event ───────────────────────

    private LocalDateTime collectedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime returnedAt;

    @Setter
    private LocalDateTime cancelledAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // ── What CustodyChain alone may change ───────────────────────────────────

    /**
     * Package-private on purpose.
     *
     * <p>Only {@code CustodyChain}, which lives in a different package, reaches
     * this through {@link #applyDerivedState}. Nothing else in the codebase can
     * name it, which is what stops a status being assigned rather than earned.
     */
    public void applyDerivedState(ShipmentStatus derived, int failedAttempts,
                                  LocalDateTime collectedAt, LocalDateTime deliveredAt,
                                  LocalDateTime returnedAt) {
        this.status = derived;
        this.failedAttempts = failedAttempts;
        this.collectedAt = collectedAt;
        this.deliveredAt = deliveredAt;
        this.returnedAt = returnedAt;
    }

    /** Whether somebody on this platform is holding the goods right now. */
    public boolean isCustodyActive() {
        return status != null && status.isCustodyActive();
    }
}
