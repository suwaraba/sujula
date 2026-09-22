package com.sujula.model.shipment;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.RecipientInstructionType;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Something the recipient asked for, and the proof she was the one asking.
 *
 * <p><strong>Append-only, like every other record in this system that somebody
 * later has to be able to explain.</strong> Changing your mind writes a second
 * row and marks the first superseded; it does not edit the first. A driver who
 * went to a counter that the recipient had since changed her mind about needs to
 * be able to show that the counter was the instruction when he set off, and a
 * mutable column cannot show that.
 *
 * <p>Each row carries {@link #verifiedByCodeId} — which access code was
 * presented — so a safe-drop authorisation is not a checkbox somebody ticked but
 * a statement made by whoever held the code, at a time, with the words they
 * used. That matters most for exactly that instruction, because it is the one
 * that substitutes for the recipient being at the door.
 */
@Entity
@Table(name = "recipient_instructions",
       indexes = {
           @Index(name = "idx_ri_shipment",      columnList = "shipment_id"),
           @Index(name = "idx_ri_shipment_type", columnList = "shipment_id, type")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipientInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RecipientInstructionType type;

    // ── CHOOSE_PICKUP_POINT ──────────────────────────────────────────────────

    /**
     * The counter she asked for.
     *
     * <p>A reference rather than a name and a street, because the driver has to
     * navigate to it and a point that later moves must not leave a parcel headed
     * for where it used to be.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pickup_point_id")
    private PickupPoint pickupPoint;

    // ── RESCHEDULE ───────────────────────────────────────────────────────────

    /** Not before. */
    private LocalDateTime windowFrom;

    /** Not after. */
    private LocalDateTime windowUntil;

    // ── AUTHORISE_SAFE_DROP ──────────────────────────────────────────────────

    /**
     * Where exactly, in her words.
     *
     * <p>Kept verbatim and never normalised. "With Ndey at the pharmacy next
     * door" is an address in this market, and a system that tried to parse it
     * into a street and a number would lose the only part a driver can act on.
     */
    @Column(length = 300)
    private String safeDropLocation;

    /**
     * Who may take it, if it is a person rather than a place.
     *
     * <p>A first name and no more. The driver needs enough to ask for somebody;
     * a full name and a phone number would be a second person's details sitting
     * on a parcel record that half the delivery network can read.
     */
    @Column(length = 120)
    private String safeDropPerson;

    // ── Proof ────────────────────────────────────────────────────────────────

    /**
     * The access code that was presented when this was given.
     *
     * <p>An id rather than an association, on purpose: the code row may be
     * invalidated or cleaned up, and the instruction must keep saying which one
     * it was regardless. Nothing reads it to authorise anything — it is evidence
     * after the fact, which is the only kind of evidence that survives the code
     * being rotated.
     */
    @Column(nullable = false)
    private Long verifiedByCodeId;

    @Column(nullable = false)
    private LocalDateTime verifiedAt;

    /**
     * Set when a later instruction of the same kind replaces this one.
     *
     * <p>The reason nothing here is deleted. A superseded safe-drop
     * authorisation is how you find out that the parcel left behind the shop was
     * authorised at eleven and withdrawn at noon.
     */
    private LocalDateTime supersededAt;

    /** What the recipient will be shown on the page afterwards. */
    @Column(length = 300)
    private String summary;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Whether this is the one currently in force. */
    public boolean isInForce() {
        return supersededAt == null;
    }
}
