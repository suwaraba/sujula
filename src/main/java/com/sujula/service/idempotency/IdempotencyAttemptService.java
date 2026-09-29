package com.sujula.service.idempotency;

import com.sujula.model.idempotency.IdempotencyRecord;
import com.sujula.repository.idempotency.IdempotencyRecordRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Executes one idempotent attempt inside the transaction that owns its claim.
 *
 * <p>This must remain a separate Spring bean. {@link IdempotencyService} calls
 * it through Spring's proxy, so this transaction covers the claim, the checkout
 * work, and the completed response rather than committing any of them alone.
 */
@Service
public class IdempotencyAttemptService {

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;
    private final Duration retention;

    public IdempotencyAttemptService(
            IdempotencyRecordRepository records,
            ObjectMapper objectMapper,
            @Value("${sujula.idempotency.retention:PT24H}") Duration retention) {

        this.records = records;
        this.objectMapper = objectMapper;
        this.retention = retention;
    }

    /**
     * Claims, executes, and completes one request in a single transaction.
     *
     * <p>{@code saveAndFlush()} deliberately performs the uniqueness check
     * before {@code operation.get()}. A contender either waits on the database
     * constraint or loses it; it never reaches the protected operation.
     */
    @Transactional
    public <T> T attempt(
            String scope,
            String key,
            String fingerprint,
            int status,
            Supplier<T> operation) {

        LocalDateTime now = LocalDateTime.now();
        records.deleteExpiredForKey(scope, key, now);

        IdempotencyRecord record;
        try {
            record = records.saveAndFlush(IdempotencyRecord.builder()
                    .scope(scope)
                    .idempotencyKey(key)
                    .requestFingerprint(fingerprint)
                    .responseStatus(IdempotencyRecord.PROCESSING_RESPONSE_STATUS)
                    .responseBody(IdempotencyRecord.PROCESSING_RESPONSE_BODY)
                    .createdAt(now)
                    .expiresAt(now.plus(retention))
                    .build());
        } catch (DataIntegrityViolationException conflict) {
            throw new ClaimConflictException(conflict);
        }

        T result = operation.get();

        record.setResponseStatus(status);
        record.setResponseBody(serialise(result));
        records.save(record);

        return result;
    }

    private String serialise(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Could not record an idempotent response", e);
        }
    }

    /**
     * Marks only the unique-key failure from the claim insert. Other database
     * failures from checkout must propagate as checkout failures, not be treated
     * as a replay race.
     */
    public static final class ClaimConflictException extends RuntimeException {

        ClaimConflictException(DataIntegrityViolationException cause) {
            super(cause);
        }
    }
}
