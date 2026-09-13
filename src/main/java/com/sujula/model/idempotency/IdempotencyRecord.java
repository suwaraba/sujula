package com.sujula.model.idempotency;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The answer already given to a request that is being made again.
 *
 * <p>Retries are not an edge case on a marketplace reached over mobile networks:
 * a request times out on a slow connection and the client sends it again, and
 * without this the shopper ends up with two saved addresses, or two delivery
 * contexts, from one tap. The client names its attempt with an
 * {@code Idempotency-Key}; the first attempt does the work and the response is
 * kept here; every repeat of that key is answered from this row instead.
 *
 * <p>Two details make it safe rather than merely convenient.
 *
 * <p><strong>The key is scoped to the caller.</strong> Keys are chosen by
 * clients, so two shoppers will eventually pick the same one — and a globally
 * unique key would then serve one person's saved address to the other. The
 * unique constraint spans scope and key together, where scope is the
 * authenticated user or, for a guest, nothing more identifying than the endpoint
 * they called.
 *
 * <p><strong>The request is fingerprinted.</strong> A key reused with a
 * different body is not a retry, it is a mistake — usually a client that reuses
 * one key for a whole session — and replaying the first answer would silently
 * discard the second request. That case is refused rather than served.
 */
@Entity
@Table(name = "idempotency_records",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_idempotency_scope_key", columnNames = {"scope", "idempotency_key"}),
       indexes = @Index(name = "idx_idempotency_expires", columnList = "expiresAt"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Who is retrying, and at which endpoint.
     *
     * <p>Both, because either alone is wrong: without the user, two shoppers
     * collide on a common key; without the endpoint, one key used for two
     * different operations makes the second return the first's answer.
     */
    @Column(name = "scope", nullable = false, length = 120)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /**
     * A digest of the request that was served.
     *
     * <p>A digest rather than the body: request bodies here carry names, phone
     * numbers and coordinates, and keeping a second copy of all of it for a
     * retry that will probably never come is not a trade worth making.
     */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    /** The response body to replay, as it was serialised the first time. */
    @Lob
    @Column(nullable = false)
    private String responseBody;

    @Column(nullable = false)
    private int responseStatus;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    /**
     * When this stops being replayable.
     *
     * <p>A retry window, not a permanent log. Past it the key is free again,
     * which is correct: a client reusing a key a week later is not retrying
     * anything, and keeping every key ever seen would make this table the
     * largest in the database.
     */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public boolean matches(String fingerprint) {
        return requestFingerprint != null && requestFingerprint.equals(fingerprint);
    }
}
