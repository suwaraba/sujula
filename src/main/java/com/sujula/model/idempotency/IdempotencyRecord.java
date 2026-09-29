package com.sujula.model.idempotency;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** The answer already given to a request that is being made again. */
@Entity
@Table(
        name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_idempotency_scope_key",
                columnNames = {"scope", "idempotency_key"}
        ),
        indexes = @Index(
                name = "idx_idempotency_expires",
                columnList = "expiresAt"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    /**
     * A reserved response pair used while a transaction owns the request key.
     * The existing database columns remain non-null throughout the transaction.
     */
    public static final int PROCESSING_RESPONSE_STATUS = 102;
    public static final String PROCESSING_RESPONSE_BODY = "{}";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Who is retrying, and at which endpoint.
     *
     * <p>Both are required because either alone is unsafe: without the user,
     * two shoppers can collide on a common key; without the operation, one
     * key reused across different endpoints can return the wrong response.
     */
    @Column(name = "scope", nullable = false, length = 120)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /**
     * SHA-256 digest of the request body.
     *
     * <p>The request body itself is deliberately not retained because requests
     * can contain names, phone numbers, addresses and coordinates.
     */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    /** Serialized response body. */
    @Lob
    @Column(nullable = false)
    private String responseBody;

    /** HTTP status associated with the stored response. */
    @Column(nullable = false)
    private int responseStatus;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    /**
     * When this record stops being replayable.
     *
     * <p>The retention window is deliberately finite. It is a retry window,
     * not a permanent request log.
     */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public boolean matches(String fingerprint) {
        return requestFingerprint != null
                && requestFingerprint.equals(fingerprint);
    }

    public boolean isProcessing() {
        return responseStatus == PROCESSING_RESPONSE_STATUS
                && PROCESSING_RESPONSE_BODY.equals(responseBody);
    }

    public boolean hasResponse() {
        return !isProcessing();
    }
}
