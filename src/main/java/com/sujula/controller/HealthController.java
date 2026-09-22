package com.sujula.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * The two questions an orchestrator asks, and they are not the same question.
 *
 * <p><b>Liveness</b> is "is this process worth keeping". It checks nothing
 * outside the JVM on purpose. A liveness probe that touched the database would
 * restart every instance in the fleet the moment the database blinked — turning
 * a recoverable outage into a restart storm during it, which is the classic way
 * to make an incident considerably worse.
 *
 * <p><b>Readiness</b> is "should this instance be sent traffic". That one does
 * check the database, because an instance that cannot reach it can only answer
 * with errors, and taking it out of rotation is exactly right.
 *
 * <p>Neither says anything a stranger can use. Versions, hostnames and driver
 * details are what somebody scanning wants, and an unauthenticated endpoint that
 * volunteers them is doing their reconnaissance for them.
 */
@Slf4j
@RestController
@RequestMapping("/health")
@Tag(name = "health", description = "Liveness and readiness probes")
public class HealthController {

    /** Longer than this and the database is not usable even if it answers. */
    private static final int PROBE_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final Instant startedAt = Instant.now();

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/liveness")
    @Operation(summary = "Is this process worth keeping",
               description = "Checks nothing outside the JVM, deliberately. A liveness probe that "
                       + "touched the database would restart every instance the moment the "
                       + "database blinked, turning a recoverable outage into a restart storm "
                       + "during it.")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        return uncached(body);
    }

    @GetMapping("/readiness")
    @Operation(summary = "Should this instance be sent traffic",
               description = "Checks the database, because an instance that cannot reach it can "
                       + "only answer with errors. Answers 503 when it cannot — an orchestrator "
                       + "reads the status code, and a 200 saying DOWN in its body keeps traffic "
                       + "arriving at an instance that cannot serve it.")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean ready = checkDatabase(checks);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "DOWN");
        body.put("checks", checks);

        // The status code is the answer. An orchestrator routes on it and does
        // not read the body.
        return ResponseEntity
                .status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * Whether the database is genuinely usable.
     *
     * <p>{@code isValid} rather than a {@code SELECT 1}, because it asks the
     * driver whether the connection works rather than whether one statement
     * happened to succeed — and it takes a timeout, so a database that has
     * stopped answering fails the probe instead of hanging it. A readiness check
     * that blocks forever is an instance that never leaves rotation.
     */
    private boolean checkDatabase(Map<String, Object> checks) {
        long began = System.nanoTime();
        try (var connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(PROBE_TIMEOUT_SECONDS);
            checks.put("database", Map.of(
                    "status", valid ? "UP" : "DOWN",
                    "tookMs", Duration.ofNanos(System.nanoTime() - began).toMillis()));
            return valid;
        } catch (Exception e) {
            // The reason is logged, not returned. "Access denied for user
            // sujula@10.0.3.7" in a public response is a gift to somebody
            // scanning.
            log.error("[Health] Readiness probe could not reach the database", e);
            checks.put("database", Map.of(
                    "status", "DOWN",
                    "tookMs", Duration.ofNanos(System.nanoTime() - began).toMillis()));
            return false;
        }
    }

    private static ResponseEntity<Map<String, Object>> uncached(Map<String, Object> body) {
        // A cached health answer is a probe reading a stale opinion, which is
        // worse than no probe.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
