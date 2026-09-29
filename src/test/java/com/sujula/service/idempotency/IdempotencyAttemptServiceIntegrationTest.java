package com.sujula.service.idempotency;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.idempotency.IdempotencyRecord;
import com.sujula.repository.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the database transaction and unique-key boundary rather than a mock
 * approximation of it. The test itself is non-transactional so each service
 * call opens and commits its own real H2 transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({IdempotencyAttemptService.class, IdempotencyAttemptServiceIntegrationTest.Json.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyAttemptServiceIntegrationTest {

    private static final String SCOPE = "user:4:checkout";

    private record Body(String label, int quantity) {
    }

    private record Result(Long id, String label) {
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Json {
        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private IdempotencyRecordRepository records;

    @org.springframework.beans.factory.annotation.Autowired
    private IdempotencyAttemptService attempts;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(records, attempts, new ObjectMapper());
    }

    @AfterEach
    void cleanUp() {
        records.deleteAll();
    }

    @Test
    void firstRequestStoresItsResponseAndASameKeyReplayDoesNotRunAgain() {
        AtomicInteger runs = new AtomicInteger();
        Body body = new Body("Home", 1);

        Result first = execute("key-1", body,
                () -> new Result((long) runs.incrementAndGet(), body.label()));
        Result replayed = execute("key-1", body,
                () -> new Result((long) runs.incrementAndGet(), body.label()));

        assertEquals(new Result(1L, "Home"), first);
        assertEquals(first, replayed);
        assertEquals(1, runs.get());

        IdempotencyRecord stored = records.findLive(SCOPE, "key-1").orElseThrow();
        assertEquals(201, stored.getResponseStatus());
        assertFalse(stored.isProcessing());
    }

    @Test
    void failedOperationRollsBackBothTheClaimAndBusinessWriteThenCanRetry() {
        AtomicInteger runs = new AtomicInteger();
        Body body = new Body("Home", 1);

        assertThrows(IllegalStateException.class, () -> execute("key-rollback", body, () -> {
            runs.incrementAndGet();
            records.save(IdempotencyRecord.builder()
                    .scope("test:business")
                    .idempotencyKey("marker")
                    .requestFingerprint("marker")
                    .responseStatus(201)
                    .responseBody("{}")
                    .createdAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build());
            throw new IllegalStateException("business failure");
        }));

        assertTrue(records.findLive(SCOPE, "key-rollback").isEmpty());
        assertTrue(records.findLive("test:business", "marker").isEmpty());

        Result retry = execute("key-rollback", body,
                () -> new Result((long) runs.incrementAndGet(), body.label()));

        assertEquals(new Result(2L, "Home"), retry);
        assertEquals(2, runs.get());
    }

    @Test
    void concurrentIdenticalRequestsExecuteOnlyOnceAndReplayTheWinner() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch winnerEnteredOperation = new CountDownLatch(1);
        CountDownLatch finishWinner = new CountDownLatch(1);
        Body body = new Body("Home", 1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Result> winner = executor.submit(() -> execute("key-concurrent", body, () -> {
                int run = runs.incrementAndGet();
                winnerEnteredOperation.countDown();
                await(finishWinner);
                return new Result((long) run, body.label());
            }));

            assertTrue(winnerEnteredOperation.await(5, TimeUnit.SECONDS));

            Future<Result> contender = executor.submit(() -> execute("key-concurrent", body,
                    () -> new Result((long) runs.incrementAndGet(), body.label())));

            assertFalse(contender.isDone(), "the contender must wait at the database claim");
            finishWinner.countDown();

            assertEquals(new Result(1L, "Home"), winner.get(5, TimeUnit.SECONDS));
            assertEquals(new Result(1L, "Home"), contender.get(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDifferentRequestsWithTheSameKeyRejectTheLoserWithoutRunningIt() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch winnerEnteredOperation = new CountDownLatch(1);
        CountDownLatch finishWinner = new CountDownLatch(1);
        Body winningBody = new Body("Home", 1);
        Body differentBody = new Body("Work", 1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Result> winner = executor.submit(() -> execute("key-fingerprint", winningBody, () -> {
                int run = runs.incrementAndGet();
                winnerEnteredOperation.countDown();
                await(finishWinner);
                return new Result((long) run, winningBody.label());
            }));

            assertTrue(winnerEnteredOperation.await(5, TimeUnit.SECONDS));

            Future<Result> contender = executor.submit(() -> execute("key-fingerprint", differentBody,
                    () -> new Result((long) runs.incrementAndGet(), differentBody.label())));

            finishWinner.countDown();
            assertEquals(new Result(1L, "Home"), winner.get(5, TimeUnit.SECONDS));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> contender.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BadRequestException.class, failure.getCause());
            assertEquals(1, runs.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void anExpiredRecordIsEvictedInsideTheNewClaimTransaction() {
        records.saveAndFlush(IdempotencyRecord.builder()
                .scope(SCOPE)
                .idempotencyKey("key-expired")
                .requestFingerprint("expired")
                .responseStatus(201)
                .responseBody("{}")
                .createdAt(LocalDateTime.now().minusHours(2))
                .expiresAt(LocalDateTime.now().minusSeconds(1))
                .build());

        AtomicInteger runs = new AtomicInteger();
        Body body = new Body("Home", 1);
        Result result = execute("key-expired", body,
                () -> new Result((long) runs.incrementAndGet(), body.label()));

        assertEquals(new Result(1L, "Home"), result);
        assertEquals(1, runs.get());
        assertEquals(1, records.findAll().size());
        assertTrue(records.findLive(SCOPE, "key-expired").orElseThrow()
                .matches(fingerprint(body)));
    }

    private Result execute(String key, Body body, java.util.function.Supplier<Result> operation) {
        return service.execute(SCOPE, key, body, 201, Result.class, operation);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread was interrupted", interrupted);
        }
    }

    private static String fingerprint(Body body) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(mapper.writeValueAsBytes(body)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
