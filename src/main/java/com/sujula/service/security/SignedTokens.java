package com.sujula.service.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sujula.exceptions.BadRequestException;

import lombok.extern.slf4j.Slf4j;

/**
 * A short-lived signed token: a payload anyone may read and nobody may edit.
 *
 * <p>Used wherever a URL or a barcode is itself the credential - an invoice
 * link, the QR on a parcel label. In both cases what is wanted is the same
 * thing: a string that names one row and one expiry, that cannot be altered to
 * name another, and that needs no server-side storage to be checked.
 *
 * <p>This class exists because the second such use appeared. One copy of an
 * HMAC is a design decision; two are a pair that drift, and the one that rots is
 * always the one nobody re-read.
 *
 * <p><strong>What it is not.</strong> The payload is signed, not encrypted:
 * anybody holding the token can read the id inside it. That is fine for these
 * uses and would not be for a secret, so nothing sensitive goes in one.
 */
@Slf4j
public final class SignedTokens {

    private final byte[] key;
    private final String purpose;

    /**
     * @param purpose mixed into every signature, so a token minted for one use
     *                cannot be presented to another. Without it, an invoice
     *                link and a parcel label signed with the same key are
     *                interchangeable, and the label endpoint would happily
     *                accept an invoice token naming a different order.
     */
    public SignedTokens(String secret, String purpose) {
        this.purpose = purpose;
        this.key = resolve(secret, purpose);
    }

    private static byte[] resolve(String secret, String purpose) {
        if (secret != null && !secret.isBlank()) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        log.warn("[Tokens] No signing secret configured for '{}': links are signed with a key "
                + "generated for this process, so they stop working on restart and are not valid "
                + "on another instance. Set one in the environment for anything but local work.",
                purpose);
        return generated;
    }

    /** A token naming {@code subject}, valid until {@code expiresAtEpochSecond}. */
    public String mint(String subject, long expiresAtEpochSecond) {
        String payload = subject + "." + expiresAtEpochSecond;
        return encode(payload.getBytes(StandardCharsets.UTF_8)) + "." + encode(sign(payload));
    }

    /**
     * Returns the subject a valid token names, or throws.
     *
     * <p>The signature is checked before the expiry, and both failures produce
     * the same message: a token that is merely stale and one that has been
     * forged should not be distinguishable by somebody probing.
     */
    public String verify(String token, String whatItOpens) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 2) {
            throw new BadRequestException("This " + whatItOpens + " is not valid.");
        }

        String payload;
        byte[] presented;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            presented = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException malformed) {
            throw new BadRequestException("This " + whatItOpens + " is not valid.");
        }
        if (!MessageDigest.isEqual(presented, sign(payload))) {
            throw new BadRequestException("This " + whatItOpens + " is not valid.");
        }

        int split = payload.lastIndexOf('.');
        if (split < 0) {
            throw new BadRequestException("This " + whatItOpens + " is not valid.");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(payload.substring(split + 1));
        } catch (NumberFormatException malformed) {
            throw new BadRequestException("This " + whatItOpens + " is not valid.");
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new BadRequestException("This " + whatItOpens + " has expired.");
        }
        return payload.substring(0, split);
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            // The purpose is signed alongside the payload rather than being part
            // of it, so it cannot be read off or swapped by whoever holds the
            // token.
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
