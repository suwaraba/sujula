package com.sujula.service.idempotency;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.idempotency.IdempotencyRecord;
import com.sujula.repository.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retries, which on a mobile network are not an edge case.
 *
 * <p>A request reaches the server and the response does not reach the client;
 * the client cannot tell that from a request that never arrived, so it sends it
 * again. Everything here is about that one situation.
 */
class IdempotencyServiceTest {

    /** What a request and its response look like, for a test that needs both. */
    public record Body(String label, int quantity) {}
    public record Result(Long id, String label) {}

    private IdempotencyRecordRepository records;
    private IdempotencyService service;
    private final List<IdempotencyRecord> stored = new ArrayList<>();
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        records = mock(IdempotencyRecordRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(records.save(any(IdempotencyRecord.class))).thenAnswer(call -> {
            IdempotencyRecord record = call.getArgument(0);
            boolean clash = stored.stream().anyMatch(r ->
                    r.getScope().equals(record.getScope())
                            && r.getIdempotencyKey().equals(record.getIdempotencyKey()));
            if (clash) {
                throw new DataIntegrityViolationException("uk_idempotency_scope_key");
            }
            stored.add(record);
            return record;
        });
        when(records.findLive(any(), any())).thenAnswer(call -> {
            String scope = call.getArgument(0);
            String key = call.getArgument(1);
            return stored.stream()
                    .filter(r -> r.getScope().equals(scope) && r.getIdempotencyKey().equals(key))
                    .findFirst();
        });

        service = new IdempotencyService(records, objectMapper, Duration.ofHours(24));
    }

    private Result run(String scope, String key, Body body) {
        return service.execute(scope, key, body, 201, Result.class,
                () -> new Result((long) runs.incrementAndGet(), body.label()));
    }

    // ── The point of the whole thing ─────────────────────────────────────────

    @Test
    void theSameKeyRunsTheWorkOnce() {
        Body body = new Body("Home", 1);

        Result first = run("user:4:addresses", "key-1", body);
        Result second = run("user:4:addresses", "key-1", body);

        assertEquals(1, runs.get(), "a retry must not do the work again");
        assertEquals(first, second, "and must get the same answer back");
    }

    @Test
    void aDifferentKeyIsADifferentRequest() {
        run("user:4:addresses", "key-1", new Body("Home", 1));
        run("user:4:addresses", "key-2", new Body("Work", 1));

        assertEquals(2, runs.get());
    }

    /** No key means the client did not ask for this, and nothing is recorded. */
    @Test
    void withoutAKeyTheWorkSimplyRuns() {
        run("user:4:addresses", null, new Body("Home", 1));
        run("user:4:addresses", "", new Body("Home", 1));

        assertEquals(2, runs.get());
        assertEquals(0, stored.size());
    }

    // ── Scope ────────────────────────────────────────────────────────────────

    /**
     * The one that matters most. Clients pick their own keys, so two shoppers
     * will eventually pick the same one — and without the scope, the second would
     * be handed the first's saved address.
     */
    @Test
    void twoShoppersWithTheSameKeyDoNotCollide() {
        Body body = new Body("Home", 1);

        Result mine = run("user:4:addresses", "shared-key", body);
        Result theirs = run("user:9:addresses", "shared-key", body);

        assertEquals(2, runs.get());
        assertEquals(1L, mine.id());
        assertEquals(2L, theirs.id(), "one shopper's answer must never be served to another");
    }

    /** One key reused across two operations must not return the wrong one's answer. */
    @Test
    void oneKeyAcrossTwoOperationsDoesNotCross() {
        Body body = new Body("Home", 1);

        run("user:4:addresses", "key-1", body);
        run("user:4:delivery-contexts", "key-1", body);

        assertEquals(2, runs.get());
    }

    // ── A reused key ─────────────────────────────────────────────────────────

    /**
     * A key sent again with different content is not a retry — it is usually a
     * client reusing one key for a whole session. Replaying the first answer
     * would silently discard this request, so it is refused instead.
     */
    @Test
    void reusingAKeyForDifferentContentIsRefused() {
        run("user:4:addresses", "key-1", new Body("Home", 1));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> run("user:4:addresses", "key-1", new Body("Work", 1)));

        assertEquals(1, runs.get(), "and the second request must not have run");
        org.junit.jupiter.api.Assertions.assertTrue(
                refused.getMessage().contains("already used for a different request"));
    }

    @Test
    void anOverlongKeyIsRefused() {
        assertThrows(BadRequestException.class,
                () -> run("user:4:addresses", "k".repeat(101), new Body("Home", 1)));
    }

    // ── Races ────────────────────────────────────────────────────────────────

    /**
     * Two first attempts arriving together both find nothing. The unique
     * constraint lets exactly one insert; the loser replays the winner's answer
     * rather than returning a second, different one.
     */
    @Test
    void aConcurrentFirstUseReplaysTheWinner() {
        Body body = new Body("Home", 1);
        Result winner = run("user:4:addresses", "key-1", body);

        // The loser: its lookup saw nothing (simulated), then its insert clashed.
        when(records.findLive("user:4:addresses", "key-1"))
                .thenReturn(Optional.empty())
                .thenAnswer(call -> stored.stream()
                        .filter(r -> r.getIdempotencyKey().equals("key-1"))
                        .findFirst());

        Result loser = run("user:4:addresses", "key-1", body);

        assertEquals(winner, loser, "both callers must see one consistent result");
    }

    // ── Scopes ───────────────────────────────────────────────────────────────

    @Test
    void scopesNameBothThePersonAndTheOperation() {
        assertEquals("user:4:addresses", IdempotencyService.scopeFor(4L, "addresses"));
        assertEquals("anon:addresses", IdempotencyService.anonymousScope("addresses"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                IdempotencyService.scopeFor(4L, "addresses"),
                IdempotencyService.anonymousScope("addresses"));
    }
}
