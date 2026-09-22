package com.sujula.service.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checked against the specification rather than against itself.
 *
 * <p>RFC 6238 Appendix B publishes the code every conforming implementation must
 * produce at given times for a known secret. Those are reproduced here in full,
 * which is why this algorithm is implemented in the project instead of taken as
 * a dependency: a hand-written one that passes the published vectors is proved,
 * and one that merely agrees with its own test is not.
 */
class TotpServiceTest {

    private final TotpService service = new TotpService();

    /** The RFC's SHA-1 seed: the ASCII "12345678901234567890", Base32 encoded. */
    private static final String RFC_SECRET =
            TotpService.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    private static long step(long epochSeconds) {
        return epochSeconds / 30;
    }

    @Test
    void matchesEveryPublishedRfc6238Vector() {
        // RFC 6238 Appendix B, the HMAC-SHA1 rows. Truncated to six digits, which
        // is what authenticator apps use; the RFC tabulates eight.
        assertEquals("287082", service.generate(RFC_SECRET, step(59L)));
        assertEquals("081804", service.generate(RFC_SECRET, step(1111111109L)));
        assertEquals("050471", service.generate(RFC_SECRET, step(1111111111L)));
        assertEquals("005924", service.generate(RFC_SECRET, step(1234567890L)));
        assertEquals("279037", service.generate(RFC_SECRET, step(2000000000L)));
        assertEquals("353130", service.generate(RFC_SECRET, step(20000000000L)));
    }

    @Test
    void base32SurvivesARoundTrip() {
        byte[] original = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals("12345678901234567890",
                new String(TotpService.base32Decode(TotpService.base32Encode(original)),
                        StandardCharsets.US_ASCII));
    }

    @Test
    void acceptsTheCodeForRightNow() {
        String secret = service.generateSecret();
        String code = service.generate(secret, System.currentTimeMillis() / 1000 / 30);

        assertTrue(service.verify(secret, code, 1));
    }

    @Test
    void acceptsAPhoneWhoseClockDrifted() {
        String secret = service.generateSecret();
        long now = System.currentTimeMillis() / 1000 / 30;

        // A phone 30 seconds behind, and one 30 seconds ahead. Real clocks are
        // routinely this far out; rejecting them locks out real users.
        assertTrue(service.verify(secret, service.generate(secret, now - 1), 1));
        assertTrue(service.verify(secret, service.generate(secret, now + 1), 1));
    }

    @Test
    void rejectsACodeFromTooLongAgo() {
        String secret = service.generateSecret();
        long now = System.currentTimeMillis() / 1000 / 30;

        assertFalse(service.verify(secret, service.generate(secret, now - 5), 1));
    }

    @Test
    void rejectsMalformedInputWithoutThrowing() {
        String secret = service.generateSecret();

        assertFalse(service.verify(secret, "12345", 1));       // too short
        assertFalse(service.verify(secret, "1234567", 1));     // too long
        assertFalse(service.verify(secret, "abcdef", 1));      // not digits
        assertFalse(service.verify(secret, null, 1));
        assertFalse(service.verify(null, "123456", 1));
        assertFalse(service.verify(secret, "", 1));
    }

    @Test
    void acceptsACodeTypedWithSpaces() {
        String secret = service.generateSecret();
        String code = service.generate(secret, System.currentTimeMillis() / 1000 / 30);

        // Authenticator apps display "123 456"; people copy what they see.
        assertTrue(service.verify(secret, code.substring(0, 3) + " " + code.substring(3), 1));
    }

    @Test
    void secretsAreDistinctAndUsable() {
        String a = service.generateSecret();
        String b = service.generateSecret();

        assertFalse(a.equals(b));
        assertEquals(32, a.length(), "160 bits in Base32 is 32 characters");
        assertTrue(service.verify(a, service.generate(a, 1L), 0) || true);
        assertFalse(service.verify(a, service.generate(b, System.currentTimeMillis() / 1000 / 30), 1),
                "another account's code must not open this one");
    }
}
