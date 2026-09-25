package com.sujula.model.webhook;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.sujula.model.constant.WebhookKind;
import com.sujula.model.constant.WebhookStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Something a provider told us, and what we did about it.
 *
 * <p>The row is written before the event is acted on, and that ordering is the
 * whole design. A payment provider saying "this succeeded" is the only record
 * that it did; acting on it first and storing it afterwards means a crash
 * between the two loses the fact that a buyer paid.
 *
 * <p><b>Replay protection is the unique constraint.</b> Providers retry — that
 * is how they are supposed to work — and the same event arriving five times must
 * credit an order once. {@code (provider, eventId)} is unique, so the second
 * arrival fails to insert and is answered 200 without being acted on. A flag
 * somebody checks in code would be a race; a constraint is not.
 *
 * <p>The payload is kept in full. When a figure on an order disagrees with a
 * provider's dashboard six months later, the thing that settles it is what they
 * actually sent, not our reading of it.
 */
@Entity
@Table(name = "webhook_events",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_webhook_provider_event", columnNames = { "provider", "eventId" }),
       indexes = {
           @Index(name = "idx_webhook_status",   columnList = "status"),
           @Index(name = "idx_webhook_kind",     columnList = "kind, receivedAt"),
           @Index(name = "idx_webhook_received", columnList = "receivedAt")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which surface it came in on: payments, messaging, identity. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookKind kind;

    /** {@code stripe}, {@code wave}, {@code ses}. Lower case, from the path. */
    @Column(nullable = false, length = 40)
    private String provider;

    /**
     * The provider's own id for this event.
     *
     * <p>Theirs, not ours, because deduplication has to survive their retry
     * logic rather than ours. A provider that sends no id gets one derived from
     * a hash of the body — which deduplicates an identical retry and is the best
     * available answer.
     */
    @Column(nullable = false, length = 120)
    private String eventId;

    /** Their word for what happened: {@code payment_intent.succeeded}. */
    @Column(length = 120)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WebhookStatus status = WebhookStatus.RECEIVED;

    /**
     * Exactly what they sent, byte for byte as text.
     *
     * <p>Not a parsed structure. The signature was computed over these bytes, so
     * anything re-serialised cannot be checked again — and a dispute about what a
     * provider said is settled by what they said.
     */
    // LONGTEXT on MySQL, text on PostgreSQL: the type, not a vendor keyword.
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String payload;

    /**
     * Whether the signature checked out.
     *
     * <p>Stored even though an unsigned request is refused, because the refusal
     * is itself worth keeping: a burst of them is somebody probing, and a
     * platform that discarded them has no way to see that.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean signatureValid = false;

    /** What went wrong with the signature or the timestamp, when something did. */
    @Column(length = 300)
    private String rejectionReason;

    /** The timestamp the provider signed, as they sent it. */
    private LocalDateTime providerTimestamp;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    private LocalDateTime processedAt;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(length = 2000)
    private String failureReason;

    /** What it turned out to be about: an order id, a payout reference. */
    @Column(length = 120)
    private String subjectReference;

    /** What we did, in words, for somebody reading this months later. */
    @Column(length = 500)
    private String outcome;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public boolean isPending() {
        return status == WebhookStatus.RECEIVED || status == WebhookStatus.RETRYING;
    }
}
