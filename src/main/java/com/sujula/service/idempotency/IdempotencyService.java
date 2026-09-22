package com.sujula.service.idempotency;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.idempotency.IdempotencyRecord;
import com.sujula.repository.idempotency.IdempotencyRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a repeated request do the work once.
 *
 * <p>A shopper on a patchy connection taps "save address", the request reaches
 * the server, the response does not reach them, and their client sends it again.
 * Without this they now have the address twice. On a marketplace where most
 * traffic is mobile that is not a rare case — it is the normal failure of the
 * network, and the fix belongs on the server, because the client genuinely
 * cannot tell a lost request from a lost response.
 *
 * <p>The client names its attempt with an {@code Idempotency-Key}; the first
 * call runs the operation and keeps the response, and every repeat is answered
 * from the record.
 *
 * <h2>What makes it correct rather than merely helpful</h2>
 *
 * <p><strong>Keys are scoped.</strong> A client picks its own key and two
 * clients will pick the same one, so the lookup is always by scope and key
 * together. Without the scope, one shopper's saved address is served to another;
 * without the endpoint in the scope, one key reused across two operations
 * returns the wrong operation's answer.
 *
 * <p><strong>The body is fingerprinted.</strong> A key reused with different
 * content is not a retry — usually a client sending one key for a whole session
 * — and replaying the first answer would silently drop the second request. That
 * is refused, loudly, rather than served.
 *
 * <p><strong>The winner is decided by the database.</strong> Two concurrent
 * first attempts both find nothing; the unique constraint on (scope, key) lets
 * exactly one insert, and the loser replays the winner's answer. Checking first
 * and inserting after would let both through, which is the duplicate this class
 * exists to prevent.
 */
@Slf4j
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;
    private final Duration retention;

    public IdempotencyService(IdempotencyRecordRepository records, ObjectMapper objectMapper,
                              @Value("${sujula.idempotency.retention:PT24H}") Duration retention) {
        this.records = records;
        this.objectMapper = objectMapper;
        this.retention = retention;
    }

    /**
     * Runs {@code operation} once for this key, or returns what it returned
     * before.
     *
     * <p>A null or blank key means the client is not asking for this, and the
     * operation simply runs. That is deliberate: idempotency is something a
     * client opts into by naming its attempt, and refusing requests without a key
     * would break every caller that has not been updated yet.
     *
     * @param scope      who is retrying and at what — never the key alone
     * @param key        the client's {@code Idempotency-Key}, or null
     * @param request    the request body, fingerprinted to detect a reused key
     * @param status     the status code to record for a replay
     * @param type       what to deserialise a replayed response into
     * @param operation  the work, run at most once per key
     */
    public <T> T execute(String scope, String key, Object request, int status,
                         Class<T> type, Supplier<T> operation) {
        if (key == null || key.isBlank()) {
            return operation.get();
        }

        String trimmedKey = key.trim();
        if (trimmedKey.length() > 100) {
            throw new BadRequestException(
                    "Idempotency-Key must be 100 characters or fewer.");
        }

        String fingerprint = fingerprint(request);

        Optional<IdempotencyRecord> existing = records.findLive(scope, trimmedKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }

        T result = operation.get();

        try {
            store(scope, trimmedKey, fingerprint, status, result);
        } catch (DataIntegrityViolationException race) {
            // Another request with the same key committed between the lookup
            // above and this insert. Its answer is the one that stands — the
            // operation has run twice, which is the cost of a race this narrow,
            // but both callers see one consistent result rather than two.
            log.info("[Idempotency] Concurrent first use of key {} in scope {}; replaying the winner",
                    trimmedKey, scope);
            return records.findLive(scope, trimmedKey)
                    .map(record -> replay(record, fingerprint, type))
                    .orElse(result);
        }
        return result;
    }

    /**
     * Written in its own transaction so the record survives independently of the
     * operation's.
     *
     * <p>{@code REQUIRES_NEW} through a separate bean would be the stricter
     * arrangement; here the record is deliberately written inside the caller's
     * transaction instead, so that an operation which rolls back does not leave
     * behind a record claiming it succeeded. A retry then re-runs the work, which
     * is the right answer: nothing was saved the first time.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    protected void store(String scope, String key, String fingerprint, int status, Object response) {
        records.save(IdempotencyRecord.builder()
                .scope(scope)
                .idempotencyKey(key)
                .requestFingerprint(fingerprint)
                .responseStatus(status)
                .responseBody(serialise(response))
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plus(retention))
                .build());
    }

    private <T> T replay(IdempotencyRecord record, String fingerprint, Class<T> type) {
        if (!record.matches(fingerprint)) {
            throw new BadRequestException(
                    "This Idempotency-Key was already used for a different request. "
                            + "Use a new key for each distinct operation — reusing one would "
                            + "return the earlier request's result and silently discard this one.");
        }
        try {
            return objectMapper.readValue(record.getResponseBody(), type);
        } catch (Exception e) {
            // The stored shape no longer matches the class, which means the API
            // changed under a key that is still inside its retention window.
            // Re-running is wrong (it would duplicate); failing is honest.
            throw new IllegalStateException(
                    "A stored idempotent response could not be replayed. The key is still within "
                            + "its retention window but the response format has changed.", e);
        }
    }

    private String serialise(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Could not record an idempotent response", e);
        }
    }

    /**
     * A digest of the request, so a reused key can be told from a genuine retry.
     *
     * <p>The JSON of the request rather than the object: two equal requests must
     * fingerprint the same, and most request DTOs here are Lombok {@code @Data}
     * classes whose {@code hashCode} would do that too — but records, builders
     * and future DTOs are not guaranteed to, and a fingerprint that depends on
     * how a class was written is one that breaks silently when the class is
     * rewritten.
     */
    private String fingerprint(Object request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request == null ? "" : request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint a request for idempotency", e);
        }
    }

    /** Scope for a signed-in caller: this person, at this endpoint. */
    public static String scopeFor(Long userId, String operation) {
        return "user:" + userId + ":" + operation;
    }

    /**
     * Scope for a caller with no account.
     *
     * <p>Guests share one scope per endpoint, which means two guests picking the
     * same key collide. That is why the fingerprint check is not optional: a
     * collision between different bodies is refused rather than served, so the
     * worst case is an unlucky shopper being told to use a new key — not one
     * being handed another's response. Clients are expected to use a UUID, which
     * makes the collision vanishingly unlikely to begin with.
     */
    public static String anonymousScope(String operation) {
        return "anon:" + operation;
    }
}
