package com.sujula.service.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a webhook really came from who it says.
 *
 * <p>The claims that matter are the refusals: an unconfigured provider, a body
 * that changed by one byte, and a signature captured yesterday. The last one is
 * the reason the timestamp is inside the signature rather than beside it.
 */
class WebhookSignatureTest {

    private static final String SECRET = "a-shared-secret-nobody-else-has";
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aProperlySignedRequestIsAccepted() {
        Instant now = Instant.now();
        String timestamp = String.valueOf(now.getEpochSecond());
        byte[] payload = body("{\"id\":\"evt_1\",\"type\":\"payment.succeeded\"}");

        assertEquals(WebhookSignature.Verdict.OK, WebhookSignature.verify(
                SECRET, timestamp, payload,
                WebhookSignature.sign(SECRET, timestamp, payload), TOLERANCE, now));
    }

    @Test
    void aProviderWithNoConfiguredSecretIsRefusedRatherThanTrusted() {
        Instant now = Instant.now();
        String timestamp = String.valueOf(now.getEpochSecond());
        byte[] payload = body("{\"id\":\"evt_1\"}");

        // The default state of a configuration map is empty. A platform that
        // waved through an unconfigured provider would accept "payment
        // succeeded" from anybody who could guess the path.
        assertEquals(WebhookSignature.Verdict.NO_SECRET, WebhookSignature.verify(
                null, timestamp, payload, "anything", TOLERANCE, now));
        assertEquals(WebhookSignature.Verdict.NO_SECRET, WebhookSignature.verify(
                "  ", timestamp, payload, "anything", TOLERANCE, now));
    }

    @Test
    void oneAlteredByteBreaksTheSignature() {
        Instant now = Instant.now();
        String timestamp = String.valueOf(now.getEpochSecond());
        byte[] genuine = body("{\"id\":\"evt_1\",\"amount\":\"108.64\"}");
        String signature = WebhookSignature.sign(SECRET, timestamp, genuine);

        // Somebody in the middle changing the amount is exactly the attack this
        // stops, and 108.64 to 1.64 is one character.
        byte[] altered = body("{\"id\":\"evt_1\",\"amount\":\"1.64\"}");
        assertEquals(WebhookSignature.Verdict.MISMATCH, WebhookSignature.verify(
                SECRET, timestamp, altered, signature, TOLERANCE, now));
    }

    @Test
    void aSignatureCapturedYesterdayCannotBeSentAgainToday() {
        Instant yesterday = Instant.now().minus(Duration.ofDays(1));
        String timestamp = String.valueOf(yesterday.getEpochSecond());
        byte[] payload = body("{\"id\":\"evt_1\",\"type\":\"payment.succeeded\"}");
        String signature = WebhookSignature.sign(SECRET, timestamp, payload);

        // The signature itself is still perfectly valid — that is the point. A
        // signature over the body alone is valid forever, and the timestamp
        // inside it is the only thing that expires.
        assertEquals(WebhookSignature.Verdict.STALE, WebhookSignature.verify(
                SECRET, timestamp, payload, signature, TOLERANCE, Instant.now()));
    }

    @Test
    void aTimestampInTheFutureIsAsSuspectAsOneInThePast() {
        Instant now = Instant.now();
        Instant ahead = now.plus(Duration.ofHours(2));
        String timestamp = String.valueOf(ahead.getEpochSecond());
        byte[] payload = body("{\"id\":\"evt_1\"}");

        // A future timestamp is what somebody sends to buy themselves a replay
        // window that opens later.
        assertEquals(WebhookSignature.Verdict.STALE, WebhookSignature.verify(
                SECRET, timestamp, payload,
                WebhookSignature.sign(SECRET, timestamp, payload), TOLERANCE, now));
    }

    @Test
    void aRequestWithNoTimestampIsRefusedEvenWithAValidBodySignature() {
        Instant now = Instant.now();
        byte[] payload = body("{\"id\":\"evt_1\"}");

        assertEquals(WebhookSignature.Verdict.NO_TIMESTAMP, WebhookSignature.verify(
                SECRET, null, payload, "deadbeef", TOLERANCE, now));
    }

    @Test
    void aTimestampThatIsNotANumberIsRefusedRatherThanTreatedAsZero() {
        Instant now = Instant.now();
        byte[] payload = body("{\"id\":\"evt_1\"}");

        // Epoch zero is 1970, which is outside every tolerance — but reaching
        // that answer by accident rather than by a check is how a parser bug
        // becomes an open door.
        assertEquals(WebhookSignature.Verdict.BAD_TIMESTAMP, WebhookSignature.verify(
                SECRET, "not-a-number", payload, "deadbeef", TOLERANCE, now));
    }

    @Test
    void anotherProvidersSecretDoesNotWork() {
        Instant now = Instant.now();
        String timestamp = String.valueOf(now.getEpochSecond());
        byte[] payload = body("{\"id\":\"evt_1\"}");

        // Which is why secrets are per provider: one shared between two means
        // either can forge the other's events.
        String signedByOther = WebhookSignature.sign("a-different-providers-secret",
                timestamp, payload);
        assertEquals(WebhookSignature.Verdict.MISMATCH, WebhookSignature.verify(
                SECRET, timestamp, payload, signedByOther, TOLERANCE, now));
    }

    @Test
    void aPrefixedSignatureIsUnderstoodBecauseSeveralProvidersSendOne() {
        Instant now = Instant.now();
        String timestamp = String.valueOf(now.getEpochSecond());
        byte[] payload = body("{\"id\":\"evt_1\"}");
        String signature = WebhookSignature.sign(SECRET, timestamp, payload);

        assertEquals(WebhookSignature.Verdict.OK, WebhookSignature.verify(
                SECRET, timestamp, payload, "sha256=" + signature, TOLERANCE, now));
        assertEquals(WebhookSignature.Verdict.OK, WebhookSignature.verify(
                SECRET, timestamp, payload,
                signature.toUpperCase(java.util.Locale.ROOT), TOLERANCE, now));
    }

    @Test
    void theSameBodyAtTwoTimesHasTwoSignatures() {
        byte[] payload = body("{\"id\":\"evt_1\"}");
        String first = WebhookSignature.sign(SECRET, "1700000000", payload);
        String second = WebhookSignature.sign(SECRET, "1700000001", payload);

        // The timestamp is inside the signature, not beside it. If it were
        // beside it, these would be equal and the stale check above could be
        // bypassed by changing one header.
        assertNotEquals(first, second);
    }

    @Test
    void everyRejectionSaysTheSameThingToTheCaller() {
        // Telling somebody probing whether they got the signature or the clock
        // wrong is telling them how to make progress.
        for (WebhookSignature.Verdict verdict : WebhookSignature.Verdict.values()) {
            if (verdict.isOk()) continue;
            assertEquals("Rejected", verdict.publicMessage(),
                    verdict + " must not distinguish itself to a caller");
            assertTrue(verdict.detail().length() > 10,
                    verdict + " still has to say something useful in the log");
        }
    }
}
