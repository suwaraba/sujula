package com.sujula.service.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.constant.WebhookKind;
import com.sujula.model.constant.WebhookStatus;
import com.sujula.model.webhook.WebhookEvent;
import com.sujula.repository.webhook.WebhookEventRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The front door every webhook comes through.
 *
 * <p>Four things happen here and the order is the design: verify, store,
 * answer, then act. Storing before acting means a crash between them loses
 * nothing — the event is on disk and the worker will pick it up. Answering
 * before acting means a provider is not kept waiting on our database work, and
 * is not retrying while we are halfway through.
 *
 * <p><b>A duplicate is a 200.</b> Providers retry by design, and the correct
 * answer to "I already have this" is the one that makes them stop. A 4xx would
 * make them retry harder at exactly the wrong moment. Deduplication is the
 * unique constraint on {@code (provider, eventId)} rather than a check in code,
 * because two requests arriving at once would both pass a check.
 */
@Slf4j
@Component
public class WebhookIntake {

    private final WebhookEventRepository events;
    private final WebhookProperties properties;
    private final WebhookRecorder recorder;
    private final ObjectMapper mapper;

    public WebhookIntake(WebhookEventRepository events, WebhookProperties properties,
                         WebhookRecorder recorder, ObjectMapper mapper) {
        this.events = events;
        this.properties = properties;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    /** What the controller should answer, and what was stored. */
    public record Accepted(boolean stored, WebhookStatus status, Long eventRowId,
                           String message) {}

    /**
     * Verifies and records one delivery.
     *
     * <p>REQUIRES_NEW so the row survives a duplicate collision: the insert that
     * loses the race rolls back its own transaction, and a caller sharing one
     * would lose whatever else it had done.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Accepted receive(WebhookKind kind, String rawProvider, byte[] body,
                            String signature, String timestamp) {

        String provider = rawProvider == null ? "" : rawProvider.trim().toLowerCase(Locale.ROOT);
        String payload = new String(body, StandardCharsets.UTF_8);

        WebhookSignature.Verdict verdict = WebhookSignature.verify(
                properties.secretFor(provider).orElse(null),
                timestamp, body, signature, properties.getTolerance(), Instant.now());

        String eventId = extractEventId(payload, body);
        String eventType = extractField(payload, "type", "event", "eventType", "event_type");

        if (!verdict.isOk()) {
            // Recorded, not discarded. One rejection is a clock drifting; fifty
            // in a minute is somebody trying signatures, and a platform that
            // threw them away cannot see that happening.
            log.warn("[Webhook] {} from {} rejected: {}", kind, provider, verdict.detail());
            // Through the recorder, on its own transaction. A provider retries
            // a refused delivery as readily as an accepted one, and the retry
            // has the same body and so the same derived id — letting that
            // collision reach the caller would turn a refusal into a 500, which
            // tells them to retry harder.
            WebhookEvent rejected = recorder.recordRejection(WebhookEvent.builder()
                    .kind(kind).provider(provider)
                    // Suffixed, so a rejected attempt cannot occupy the id that
                    // the genuine event will later need.
                    .eventId(eventId + ":rejected:" + shortHash(body))
                    .eventType(eventType)
                    .status(WebhookStatus.REJECTED)
                    .payload(payload)
                    .signatureValid(false)
                    .rejectionReason(verdict.detail())
                    .providerTimestamp(parseTimestamp(timestamp))
                    .receivedAt(LocalDateTime.now())
                    .build());
            return new Accepted(rejected != null, WebhookStatus.REJECTED,
                    rejected == null ? null : rejected.getId(), verdict.publicMessage());
        }

        Optional<WebhookEvent> seen = events.findByProviderAndEventId(provider, eventId);
        if (seen.isPresent()) {
            log.info("[Webhook] {} {} from {} seen already — answering 200 so they stop",
                    kind, eventId, provider);
            return new Accepted(false, WebhookStatus.DUPLICATE, seen.get().getId(),
                    "Already received");
        }

        WebhookEvent event = WebhookEvent.builder()
                .kind(kind).provider(provider).eventId(eventId).eventType(eventType)
                .status(WebhookStatus.RECEIVED)
                .payload(payload)
                .signatureValid(true)
                .providerTimestamp(parseTimestamp(timestamp))
                .receivedAt(LocalDateTime.now())
                .build();

        try {
            events.save(event);
            events.flush();
        } catch (DataIntegrityViolationException race) {
            // Two deliveries of the same event arrived at once. The constraint
            // decided which won, which is the whole reason it is a constraint
            // and not a check.
            log.info("[Webhook] {} {} from {} lost the insert race — it is already stored",
                    kind, eventId, provider);
            return new Accepted(false, WebhookStatus.DUPLICATE, null, "Already received");
        }

        return new Accepted(true, WebhookStatus.RECEIVED, event.getId(), "Accepted");
    }

    /**
     * The provider's own id for this event, or one derived from the body.
     *
     * <p>Theirs when they send one, because deduplication has to survive their
     * retry logic. A hash of the body when they do not — which still
     * deduplicates an identical retry, and is the best available answer rather
     * than a pretence that every provider is well behaved.
     */
    private String extractEventId(String payload, byte[] body) {
        String found = extractField(payload, "id", "eventId", "event_id", "messageId",
                "message_id", "reference");
        return found != null && !found.isBlank() ? found : "body:" + shortHash(body);
    }

    /** Reads a top-level string field, trying several names providers use. */
    private String extractField(String payload, String... names) {
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isObject()) {
                return null;
            }
            for (String name : names) {
                JsonNode value = root.get(name);
                if (value != null && value.isValueNode() && !value.asString().isBlank()) {
                    return value.asString();
                }
                // One level down: several providers nest the interesting part
                // under "data" or "payload".
                for (String wrapper : new String[] { "data", "payload", "object" }) {
                    JsonNode nested = root.path(wrapper).get(name);
                    if (nested != null && nested.isValueNode() && !nested.asString().isBlank()) {
                        return nested.asString();
                    }
                }
            }
        } catch (RuntimeException e) {
            // A body that is not JSON is not an error here: it still has to be
            // stored, and the hash is the id.
            log.debug("[Webhook] Body is not readable JSON: {}", e.getMessage());
        }
        return null;
    }

    private static String shortHash(byte[] body) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) {
            return Long.toHexString(java.util.Arrays.hashCode(body));
        }
    }

    private static LocalDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(Long.parseLong(timestamp.trim())), ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
