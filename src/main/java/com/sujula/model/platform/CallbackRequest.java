package com.sujula.model.platform;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.CallbackOutcome;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Somebody is going to telephone somebody, and what came of it.
 *
 * <p>Some disputes cannot be settled in writing. A recipient in Serrekunda
 * whose parcel arrived open, describing it through a form in her second
 * language, is a case a two-minute call settles and a week of messages does
 * not. This platform is built for people who may have a phone and nothing else
 * (C5), so a callback is a first-class record rather than a note somebody types.
 *
 * <p>The intent and the outcome are separate columns because they are separate
 * facts. "We said we would call" and "we called and she did not answer" and "we
 * called and she said the seal was cut" are three different states, and a
 * platform that recorded only the first cannot tell a supervisor which
 * callbacks are still owed.
 */
@Entity
@Table(name = "callback_requests",
       indexes = {
           @Index(name = "idx_callback_dispute", columnList = "disputeId"),
           @Index(name = "idx_callback_outcome", columnList = "outcome"),
           @Index(name = "idx_callback_due",     columnList = "callBy")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallbackRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What this is about. Null for a call that is not about a dispute. */
    private Long disputeId;

    /**
     * The number to ring.
     *
     * <p>Held here rather than read from an account, because the person who
     * needs the call may not have one. A recipient has a phone number and
     * nothing else, and that number is on the shipment rather than on a user.
     */
    @Column(nullable = false, length = 30)
    private String phone;

    /** Who to ask for, in the words the caller will use. */
    @Column(length = 120)
    private String contactName;

    /**
     * Which language to open in.
     *
     * <p>Worth a column. A caller who opens in English to somebody who speaks
     * Wolof has already lost the call, and "she did not want to talk" is what
     * gets written down instead.
     */
    @Column(length = 40)
    private String preferredLanguage;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private Long requestedByUserId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime requestedAt;

    /** When it should have happened by. */
    private LocalDateTime callBy;

    /** Null while the call is still owed. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CallbackOutcome outcome;

    private Long calledByUserId;
    private LocalDateTime calledAt;

    /** What was said, in the caller's own words. */
    @Column(length = 2000)
    private String notes;

    /** How many times somebody has tried. */
    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Whether somebody still owes this person a call. */
    public boolean isOutstanding() {
        return outcome == null || outcome.needsAnotherTry();
    }

    public boolean isOverdue() {
        return isOutstanding() && callBy != null && callBy.isBefore(LocalDateTime.now());
    }
}
