package com.sujula.service.payment;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.PaymentMethod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Stable identity shared by local replay and provider-side idempotency. */
public record PaymentOperation(
        String scope,
        String clientKey,
        String providerKey,
        String paymentReference) {

    private static final String PROVIDER_NAMESPACE = "sujula-payment-operation:v1";

    public static PaymentOperation of(String scope, String key) {
        String canonicalKey = requireClientKey(key);
        String digest = digest(PROVIDER_NAMESPACE + '\n' + scope + '\n' + canonicalKey);
        return new PaymentOperation(
                scope,
                canonicalKey,
                "sujula-pay-v1-" + digest,
                "PAY-" + digest.substring(0, 20).toUpperCase(Locale.ROOT));
    }

    /** Rejects before caller resolution or business/provider work begins. */
    public static String requireClientKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for payment initiation.");
        }
        String canonical = key.trim();
        if (canonical.length() > 100) {
            throw new BadRequestException("Idempotency-Key must be 100 characters or fewer.");
        }
        return canonical;
    }

    /** PII-free, bounded scope fragment for the historic guest route. */
    public static String guestIdentity(String orderNumber, String email) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return digest("guest-payment:v1\n" + String.valueOf(orderNumber) + '\n' + normalizedEmail);
    }

    public Fingerprint fingerprint(
            String orderIdentity, PaymentMethod method, String returnUrl, String note) {
        return new Fingerprint(scope, orderIdentity, method, normalize(returnUrl), normalize(note));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not derive the payment operation identity", failure);
        }
    }

    /** Only stable inputs that define the logical payment operation. */
    public record Fingerprint(
            String scope,
            String orderIdentity,
            PaymentMethod paymentMethod,
            String returnUrl,
            String note) {
    }
}
