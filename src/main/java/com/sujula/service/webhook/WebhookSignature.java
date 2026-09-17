package com.sujula.service.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Whether a webhook really came from who it says.
 *
 * <p>HMAC-SHA256 over {@code <timestamp>.<body>}, which is the scheme every
 * provider worth integrating with uses, and the two halves each do a job. The
 * body being signed is what stops the contents being changed; the timestamp
 * being <em>inside</em> the signature is what stops a captured request being
 * replayed tomorrow — a signature over the body alone is valid forever.
 *
 * <p>Three rules hold here and each has cost somebody a breach somewhere:
 *
 * <ol>
 *   <li><b>Compare in constant time.</b> {@code equals} on two hex strings
 *       returns as soon as they differ, and the time it takes leaks how much of
 *       a guess was right. A few thousand requests turn that into the whole
 *       signature.</li>
 *   <li><b>Sign the raw bytes.</b> Not a re-serialised object — the provider
 *       signed their own formatting, and any parse-and-print in between changes
 *       whitespace and the signature no longer matches.</li>
 *   <li><b>No secret means no.</b> An unconfigured provider is refused rather
 *       than trusted, because the default state of configuration is absent.</li>
 * </ol>
 */
public final class WebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {}

    /** What was wrong, or {@link Verdict#OK}. */
    public enum Verdict {
        OK,
        NO_SECRET,
        NO_SIGNATURE,
        NO_TIMESTAMP,
        BAD_TIMESTAMP,
        STALE,
        MISMATCH;

        public boolean isOk() {
            return this == OK;
        }

        /**
         * What to say back.
         *
         * <p>Deliberately vague. "Signature mismatch" and "timestamp too old"
         * are the same answer to somebody probing, and telling them which one
         * they got right is telling them how to make progress.
         */
        public String publicMessage() {
            return this == OK ? "Accepted" : "Rejected";
        }

        /** What to write down, which is a different question. */
        public String detail() {
            return switch (this) {
                case OK -> "Verified";
                case NO_SECRET -> "No signing secret is configured for this provider, so nothing "
                        + "from it can be trusted.";
                case NO_SIGNATURE -> "No signature header.";
                case NO_TIMESTAMP -> "No timestamp header — without one the signature is valid "
                        + "forever and can be replayed.";
                case BAD_TIMESTAMP -> "The timestamp is not a number of seconds since the epoch.";
                case STALE -> "The timestamp is outside the tolerance window.";
                case MISMATCH -> "The signature does not match the body.";
            };
        }
    }

    /**
     * Checks a request.
     *
     * @param secret    the shared secret, or null when none is configured
     * @param timestamp the value of the provider's timestamp header
     * @param body      the raw bytes as they arrived
     * @param signature the provider's hex signature; a {@code sha256=} prefix is
     *                  tolerated because several providers send one
     * @param tolerance how far out of date the timestamp may be
     */
    public static Verdict verify(String secret, String timestamp, byte[] body, String signature,
                                 Duration tolerance, Instant now) {
        if (secret == null || secret.isBlank()) {
            return Verdict.NO_SECRET;
        }
        if (signature == null || signature.isBlank()) {
            return Verdict.NO_SIGNATURE;
        }
        if (timestamp == null || timestamp.isBlank()) {
            return Verdict.NO_TIMESTAMP;
        }

        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return Verdict.BAD_TIMESTAMP;
        }

        // Both directions. A timestamp in the future is as suspect as one in the
        // past — it is what somebody sends to buy themselves a replay window.
        Duration drift = Duration.between(Instant.ofEpochSecond(seconds), now).abs();
        if (drift.compareTo(tolerance) > 0) {
            return Verdict.STALE;
        }

        String expected = sign(secret, timestamp, body);
        String presented = signature.trim();
        int marker = presented.indexOf('=');
        if (marker > 0 && marker < 12) {
            presented = presented.substring(marker + 1);   // sha256=…
        }

        return constantTimeEquals(expected, presented) ? Verdict.OK : Verdict.MISMATCH;
    }

    /** The hex HMAC of {@code <timestamp>.<body>}. Also used by tests to sign. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            // A JVM without HMAC-SHA256 is not a running system. Failing loudly
            // beats returning something that might compare equal to nothing.
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    /**
     * Compares without leaking where the difference is.
     *
     * <p>Through {@link MessageDigest#isEqual}, which is documented not to
     * short-circuit. Lengths are compared first — that does leak the length,
     * which for a fixed-width hex digest is not a secret.
     */
    private static boolean constantTimeEquals(String expected, String presented) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = presented.toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
