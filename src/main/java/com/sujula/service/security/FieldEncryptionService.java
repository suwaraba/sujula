package com.sujula.service.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sujula.exceptions.BadRequestException;

import lombok.extern.slf4j.Slf4j;

/**
 * Encrypts the few columns that are worth encrypting.
 *
 * <p>Not everything: a marketplace that encrypts every field ends up with a
 * database nothing can query and a key that is therefore kept somewhere
 * convenient. This is for the values where a leaked backup is a direct loss to
 * the person the row is about — a bank account number, an IBAN, a mobile-money
 * line — and each of those is written once and read only when a payout runs.
 *
 * <p>AES-256-GCM with a fresh 96-bit nonce per value. GCM rather than CBC
 * because it authenticates: a ciphertext someone has edited fails to decrypt
 * rather than decrypting to something else, which matters when the plaintext is
 * where money is about to be sent.
 *
 * <h2>What it does without a key</h2>
 *
 * <p>It refuses to encrypt, and callers that were going to store a secret must
 * fail. That is the whole design decision here. The tempting alternative —
 * write it in clear and log a warning — produces a system that looks like it
 * encrypts bank details and does not, and nobody finds out until the dump is
 * already somewhere else. An endpoint that returns "payout details cannot be
 * saved on this deployment" is recoverable; a column of plaintext account
 * numbers is not.
 *
 * <p>Reading is more forgiving, and deliberately so: a value with no version
 * marker is returned unchanged, so rows written before a key existed — the
 * development seed among them — still read. That asymmetry is safe because it
 * cannot create a plaintext row, only tolerate one.
 */
@Slf4j
@Service
public class FieldEncryptionService {

    /** Marks a value this class wrote, and which scheme wrote it. */
    private static final String V1 = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /**
     * The instance the JPA converter reads.
     *
     * <p>Static because {@link EncryptedStringConverter} is built by Hibernate
     * in contexts that do not scan services — see that class for why injecting
     * it instead breaks every JPA slice test in the codebase. Written once, as
     * the bean is created; read-only thereafter.
     */
    private static volatile FieldEncryptionService shared;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public FieldEncryptionService(
            @Value("${sujula.security.field-encryption.key:}") String configuredKey) {
        this.key = parse(configuredKey);
        shared = this;
        if (key == null) {
            log.warn("[Crypto] sujula.security.field-encryption.key is unset. Payout details "
                    + "cannot be saved on this deployment — the endpoint will refuse rather than "
                    + "store an account number in clear. Set a base64 32-byte key to enable it.");
        }
    }

    private static SecretKeySpec parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        byte[] material;
        try {
            material = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(
                    "sujula.security.field-encryption.key is not valid base64", notBase64);
        }
        if (material.length != KEY_BYTES) {
            // Refused rather than stretched or truncated: a 16-byte key silently
            // accepted is AES-128 on a deployment that believes it has AES-256.
            throw new IllegalStateException(
                    "sujula.security.field-encryption.key must decode to exactly " + KEY_BYTES
                    + " bytes; got " + material.length);
        }
        return new SecretKeySpec(material, "AES");
    }

    /** For {@link EncryptedStringConverter} only. Null before the bean exists. */
    static FieldEncryptionService shared() {
        return shared;
    }

    /**
     * What to do with a stored value when there is no service to decrypt it.
     *
     * <p>A value this class never wrote is not encrypted and needs no key.
     * Anything carrying the marker does, and saying so beats handing a caller a
     * string of base64 it will try to use as an account number.
     */
    static String passThroughIfPlain(String stored) {
        if (stored != null && stored.startsWith(V1)) {
            throw new IllegalStateException(
                    "This row holds an encrypted field and no encryption service is available.");
        }
        return stored;
    }

    public boolean isConfigured() {
        return key != null;
    }

    /**
     * Refuses the operation when there is nowhere safe to put a secret.
     *
     * <p>Called by the write path before anything is validated or saved, so a
     * deployment without a key fails at the door rather than half way through
     * storing payout details.
     */
    public void requireConfigured(String whatWasBeingSaved) {
        if (!isConfigured()) {
            throw new BadRequestException(
                    whatWasBeingSaved + " cannot be saved on this deployment: no encryption key is "
                    + "configured, and storing it unencrypted is not an option. Contact support.");
        }
    }

    /** Null and blank pass through: an absent value is not a secret. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        requireConfigured("This value");
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(sealed, 0, envelope, nonce.length, sealed.length);

            return V1 + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException failed) {
            // Never include the plaintext, here or anywhere else in this class.
            throw new IllegalStateException("Could not encrypt a field", failed);
        }
    }

    /**
     * Reverses {@link #encrypt}.
     *
     * <p>A value without the version marker is returned as it is. See the class
     * note: that tolerates rows written before a key existed without ever
     * creating one.
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(V1)) {
            return stored;
        }
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "This database holds encrypted fields but no encryption key is configured. "
                    + "Set sujula.security.field-encryption.key to the key they were written with.");
        }
        try {
            byte[] envelope = Base64.getDecoder().decode(stored.substring(V1.length()));
            byte[] nonce = java.util.Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
            byte[] sealed = java.util.Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failed) {
            throw new IllegalStateException(
                    "A stored encrypted field could not be read. It was written with a different "
                    + "key, or it has been altered.", failed);
        }
    }

    /**
     * The last four characters, which is what a person needs to recognise their
     * own account and what a stranger cannot do anything with.
     *
     * <p>Kept in clear beside the ciphertext so the vendor's own page can say
     * "••••4417" without a key being loaded to render a settings screen.
     */
    public static String last4(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\s", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }
}
