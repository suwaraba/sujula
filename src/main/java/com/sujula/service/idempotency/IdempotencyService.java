package com.sujula.service.idempotency;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.idempotency.IdempotencyRecord;
import com.sujula.repository.idempotency.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a repeated request execute the protected operation once.
 *
 * <p>The transactional work lives in {@link IdempotencyAttemptService}. This
 * non-transactional facade is intentional: a contender whose claim fails has a
 * rolled-back transaction, so it reads the winner from a clean context instead
 * of trying to continue inside the failed transaction.
 */
@Service
public class IdempotencyService {

    private static final int MAX_CLAIM_ATTEMPTS = 3;
    private static final int WINNER_READ_ATTEMPTS = 20;
    private static final long WINNER_READ_WAIT_MILLIS = 25L;

    private final IdempotencyRecordRepository records;
    private final IdempotencyAttemptService attempts;
    private final ObjectMapper objectMapper;

    public IdempotencyService(
            IdempotencyRecordRepository records,
            IdempotencyAttemptService attempts,
            ObjectMapper objectMapper) {

        this.records = records;
        this.attempts = attempts;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs {@code operation} once for this key, or replays the stored response.
     *
     * <p>A null or blank key means the caller has not requested idempotency and
     * the operation is executed normally. Endpoints that require idempotency,
     * such as checkout, reject a missing key before calling this service.
     */
    public <T> T execute(
            String scope,
            String key,
            Object request,
            int status,
            Class<T> type,
            Supplier<T> operation) {

        if (key == null || key.isBlank()) {
            return operation.get();
        }

        String trimmedKey = key.trim();
        if (trimmedKey.length() > 100) {
            throw new BadRequestException("Idempotency-Key must be 100 characters or fewer.");
        }

        String fingerprint = fingerprint(request);

        for (int claimAttempt = 0; claimAttempt < MAX_CLAIM_ATTEMPTS; claimAttempt++) {
            Optional<IdempotencyRecord> existing = records.findLive(scope, trimmedKey);
            if (existing.isPresent()) {
                Optional<IdempotencyRecord> completed = awaitCompleted(
                        scope, trimmedKey, fingerprint, existing.get());
                if (completed.isPresent()) {
                    return replay(completed.get(), fingerprint, type);
                }
                continue;
            }

            try {
                return attempts.attempt(scope, trimmedKey, fingerprint, status, operation);
            } catch (IdempotencyAttemptService.ClaimConflictException conflict) {
                Optional<IdempotencyRecord> completed = awaitCompleted(
                        scope, trimmedKey, fingerprint, null);
                if (completed.isPresent()) {
                    return replay(completed.get(), fingerprint, type);
                }
            }
        }

        throw new IllegalStateException(
                "The concurrent request did not complete idempotency processing in time.");
    }

    private Optional<IdempotencyRecord> awaitCompleted(
            String scope,
            String key,
            String fingerprint,
            IdempotencyRecord firstRecord) {

        IdempotencyRecord record = firstRecord;
        for (int readAttempt = 0; readAttempt < WINNER_READ_ATTEMPTS; readAttempt++) {
            if (record == null) {
                record = records.findLive(scope, key).orElse(null);
            }
            if (record == null) {
                return Optional.empty();
            }
            verifyFingerprint(record, fingerprint);
            if (record.hasResponse()) {
                return Optional.of(record);
            }

            waitForWinner();
            record = null;
        }
        return Optional.empty();
    }

    private <T> T replay(IdempotencyRecord record, String fingerprint, Class<T> type) {
        verifyFingerprint(record, fingerprint);
        try {
            return objectMapper.readValue(record.getResponseBody(), type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "A stored idempotent response could not be replayed. "
                            + "The key is still within its retention window but "
                            + "the response format has changed.",
                    e);
        }
    }

    private void verifyFingerprint(IdempotencyRecord record, String fingerprint) {
        if (!record.matches(fingerprint)) {
            throw new BadRequestException(
                    "This Idempotency-Key was already used for a different request. "
                            + "Use a new key for each distinct operation — reusing one "
                            + "would return the earlier request's result and silently "
                            + "discard this one.");
        }
    }

    private void waitForWinner() {
        try {
            Thread.sleep(WINNER_READ_WAIT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for the idempotent request to finish.",
                    interrupted);
        }
    }

    /** Creates a stable SHA-256 fingerprint of the request JSON. */
    private String fingerprint(Object request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request == null ? "" : request);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint a request for idempotency", e);
        }
    }

    /** Scope for an authenticated caller. */
    public static String scopeFor(Long userId, String operation) {
        return "user:" + userId + ":" + operation;
    }

    /** Scope for callers without an account. */
    public static String anonymousScope(String operation) {
        return "anon:" + operation;
    }
}
