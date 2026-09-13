package com.sujula.model.shipment;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.CustodyEventType;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A parcel changed hands, and here is the proof.
 *
 * <p>This table is the custody chain. Everything else about where a parcel is
 * — the shipment's status, its timestamps, whether a seller gets paid — is
 * derived from these rows, and they are never edited or deleted. A correction is
 * a new event, because "the driver said they delivered it and then said they
 * had not" is a fact worth keeping rather than a mistake to erase.
 *
 * <h2>What makes a row worth believing</h2>
 *
 * <p><strong>A code somebody else held.</strong> Every transfer carries the code
 * presented by the party receiving the goods, which they could only have got
 * from the party giving them up. That is the only thing a code proves, and it is
 * the thing that matters: two people were in the same place at the same time.
 *
 * <p><strong>A position, with its accuracy.</strong> Stored alongside where the
 * event was supposed to happen, and the distance between them. Accuracy is kept
 * because a 2km fix and a 5m fix are different evidence, and a chain that
 * recorded only the coordinates would treat them the same.
 *
 * <p><strong>Two clocks.</strong> {@link #occurredAt} is when the driver's phone
 * says it happened and {@link #recordedAt} is when the server heard about it.
 * They differ by hours for events captured with no signal, which is normal here
 * rather than suspicious — but the gap is visible rather than collapsed, because
 * a device clock is something the holder can set.
 */
@Entity
@Table(name = "custody_events",
       uniqueConstraints = @UniqueConstraint(name = "uq_custody_client_event",
                                             columnNames = {"recordedByUserId", "clientEventId"}),
       indexes = {
           @Index(name = "idx_custody_shipment",  columnList = "shipment_id, occurredAt"),
           @Index(name = "idx_custody_leg",       columnList = "leg_id"),
           @Index(name = "idx_custody_type",      columnList = "type")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustodyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    /** The leg this happened on. Null only for events that belong to no leg. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leg_id")
    private ShipmentLeg leg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private CustodyEventType type;

    // ── Who ──────────────────────────────────────────────────────────────────

    /**
     * The person whose action this was.
     *
     * <p>A plain id rather than an association, and deliberately so. It is half
     * of the unique constraint that makes an offline upload idempotent, and a
     * custody row has to stay readable for years — longer than a user row is
     * guaranteed to be joinable, since an account can be closed and anonymised
     * while the question of who handed over a parcel stays answerable.
     */
    @Column(nullable = false)
    private Long recordedByUserId;

    /**
     * The other party, where an event has one.
     *
     * <p>Set on a driver-to-driver transfer, which is the one link that two
     * people both attest to. A transfer recorded by only one of them would be a
     * link nobody can corroborate.
     */
    private Long counterpartyUserId;

    // ── The proof ────────────────────────────────────────────────────────────

    /**
     * The code the receiving party presented.
     *
     * <p>Stored so a disputed handover can be checked afterwards. It is spent by
     * the time it lands here — the code row is marked used in the same
     * transaction — so a copy is a record rather than a credential.
     */
    @Column(length = 12)
    private String codePresented;

    /** Which handover code row was burned, for the audit to join back to. */
    private Long handoverCodeId;

    private Double latitude;
    private Double longitude;

    /** Metres of uncertainty the device reported. A 2km fix is not a 5m fix. */
    @Column(precision = 9, scale = 2)
    private java.math.BigDecimal accuracyMetres;

    /**
     * How far the driver was from where this was meant to happen.
     *
     * <p>Computed and stored rather than checked and forgotten. An event that
     * was allowed through at 180 metres is different evidence from one at 4
     * metres, and whoever reads this chain later deserves to know which it was.
     */
    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal metresFromExpected;

    /** Whether the position was inside the geofence when this was recorded. */
    @Column(nullable = false)
    @Builder.Default
    private boolean withinGeofence = false;

    @Column(length = 500)
    private String photoUrl;

    @Column(length = 500)
    private String signatureUrl;

    /** A reason code for a failure, and the words that go with it. */
    @Column(length = 40)
    private String reasonCode;

    @Column(length = 400)
    private String note;

    // ── The two clocks ───────────────────────────────────────────────────────

    /**
     * When the driver's device says it happened.
     *
     * <p>What the chain is ordered by, because that is the real sequence of
     * events. Trusted within limits: the service refuses a time in the future or
     * implausibly far in the past, since a device clock is something its holder
     * can set.
     */
    @Column(nullable = false)
    private LocalDateTime occurredAt;

    /** When the server heard about it. Differs by hours for an offline capture. */
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime recordedAt;

    /**
     * Whether this was captured with no signal and uploaded later.
     *
     * <p>Flagged rather than inferred from the gap between the clocks, because
     * the gap can also be a slow request, and the two deserve to be told apart
     * by somebody investigating a disputed delivery.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean capturedOffline = false;

    /**
     * The id the driver's app gave this event before it had one from us.
     *
     * <p>What makes an offline upload safe to retry. A phone that syncs, loses
     * signal mid-response and syncs again must not record two collections of the
     * same parcel — and it cannot know whether the first attempt landed. The
     * unique constraint on (user, clientEventId) decides, in the database,
     * rather than in a check that could be forgotten.
     */
    @Column(length = 64)
    private String clientEventId;
}
