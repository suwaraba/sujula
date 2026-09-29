package com.sujula.service.idempotency;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.idempotency.IdempotencyRecord;
import com.sujula.repository.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit coverage for replay and the clean-context contender path. */
class IdempotencyServiceTest {

    private record Body(String label, int quantity) {
    }

    private record Result(Long id, String label) {
    }

    private IdempotencyRecordRepository records;
    private IdempotencyAttemptService attempts;
    private ObjectMapper objectMapper;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        records = mock(IdempotencyRecordRepository.class);
        attempts = mock(IdempotencyAttemptService.class);
        objectMapper = new ObjectMapper();
        service = new IdempotencyService(records, attempts, objectMapper);
    }

    @Test
    void firstRequestDelegatesTheProtectedOperationToTheTransactionalAttempt() {
        Body body = new Body("Home", 1);
        AtomicInteger runs = new AtomicInteger();

        when(records.findLive("user:4:checkout", "key-1")).thenReturn(Optional.empty());
        when(attempts.attempt(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(call -> {
                    Supplier<Result> operation = call.getArgument(4);
                    return operation.get();
                });

        Result result = service.execute(
                "user:4:checkout", "key-1", body, 201, Result.class,
                () -> new Result((long) runs.incrementAndGet(), body.label()));

        assertEquals(new Result(1L, "Home"), result);
        assertEquals(1, runs.get());
        verify(attempts).attempt(eq("user:4:checkout"), eq("key-1"), anyString(),
                eq(201), any());
    }

    @Test
    void aCompletedMatchingKeyReplaysWithoutInvokingTheOperation() {
        Body body = new Body("Home", 1);
        Result winner = new Result(7L, "Home");
        IdempotencyRecord stored = completed("user:4:checkout", "key-1", body, winner);
        when(records.findLive("user:4:checkout", "key-1")).thenReturn(Optional.of(stored));

        Result replayed = service.execute(
                "user:4:checkout", "key-1", body, 201, Result.class,
                () -> {
                    throw new AssertionError("a replay must not execute checkout");
                });

        assertEquals(winner, replayed);
        verify(attempts, never()).attempt(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void aKeyReusedForDifferentContentIsRejectedWithoutExecutingCheckout() {
        Body winnerBody = new Body("Home", 1);
        Body differentBody = new Body("Work", 1);
        when(records.findLive("user:4:checkout", "key-1"))
                .thenReturn(Optional.of(completed(
                        "user:4:checkout", "key-1", winnerBody, new Result(7L, "Home"))));

        assertThrows(BadRequestException.class, () -> service.execute(
                "user:4:checkout", "key-1", differentBody, 201, Result.class,
                () -> {
                    throw new AssertionError("a fingerprint mismatch must not execute checkout");
                }));

        verify(attempts, never()).attempt(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void aClaimConflictReplaysTheWinnerFromACleanReadContext() {
        Body body = new Body("Home", 1);
        Result winner = new Result(7L, "Home");
        IdempotencyRecord stored = completed("user:4:checkout", "key-1", body, winner);

        when(records.findLive("user:4:checkout", "key-1"))
                .thenReturn(Optional.empty(), Optional.of(stored));
        when(attempts.attempt(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenThrow(new IdempotencyAttemptService.ClaimConflictException(
                        new DataIntegrityViolationException("uk_idempotency_scope_key")));

        Result replayed = service.execute(
                "user:4:checkout", "key-1", body, 201, Result.class,
                () -> {
                    throw new AssertionError("a contender must not execute checkout");
                });

        assertEquals(winner, replayed);
    }

    @Test
    void rejectsAnOverlongKeyBeforeStartingAnAttempt() {
        assertThrows(BadRequestException.class, () -> service.execute(
                "user:4:checkout", "k".repeat(101), new Body("Home", 1), 201, Result.class,
                () -> new Result(1L, "Home")));

        verify(attempts, never()).attempt(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void scopesNameBothThePersonAndTheOperation() {
        assertEquals("user:4:addresses", IdempotencyService.scopeFor(4L, "addresses"));
        assertEquals("anon:addresses", IdempotencyService.anonymousScope("addresses"));
    }

    private IdempotencyRecord completed(String scope, String key, Body body, Result result) {
        try {
            return IdempotencyRecord.builder()
                    .scope(scope)
                    .idempotencyKey(key)
                    .requestFingerprint(fingerprint(body))
                    .responseStatus(201)
                    .responseBody(objectMapper.writeValueAsString(result))
                    .createdAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String fingerprint(Body body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(body)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
