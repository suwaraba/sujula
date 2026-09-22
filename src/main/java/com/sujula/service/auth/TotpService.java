package com.sujula.service.auth;

import com.sujula.exceptions.BadRequestException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * Time-based one-time passwords, RFC 6238.
 *
 * <p>Implemented here rather than pulled in as a dependency: the algorithm is
 * HMAC plus a truncation, it is about forty lines, and RFC 6238 publishes test
 * vectors — so this can be proved correct against the specification instead of
 * trusted. {@code TotpServiceTest} checks every published vector for SHA-1.
 *
 * <p>Secrets are Base32 because that is what authenticator apps read, from a QR
 * code or typed by hand. Base64 would be shorter and unusable.
 */
@Service
public class TotpService {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;          // 160 bits, the RFC 4226 recommendation
    private static final String ALGORITHM = "HmacSHA1";  // what every authenticator app implements
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom random = new SecureRandom();

    /** A fresh Base32 secret to hand to an authenticator app. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * The {@code otpauth://} URI an authenticator app scans.
     *
     * <p>The issuer appears twice, in the label and as a parameter, which looks
     * redundant and is not: older apps read only the label prefix and newer ones
     * prefer the parameter.
     */
    public String provisioningUri(String secret, String accountName, String issuer) {
        String label = encode(issuer) + ":" + encode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + TIME_STEP_SECONDS;
    }

    /**
     * Whether a code is valid now, allowing {@code driftSteps} either side.
     *
     * <p>Drift is not politeness: phone clocks are routinely tens of seconds out,
     * and a window of zero locks out a meaningful share of real users. One step
     * each way accepts a 90-second span, which is the usual compromise.
     */
    public boolean verify(String secret, String code, int driftSteps) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }
        String cleaned = code.replaceAll("\\s", "");
        if (!cleaned.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        long step = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        for (int drift = -Math.abs(driftSteps); drift <= Math.abs(driftSteps); drift++) {
            if (constantTimeEquals(generate(secret, step + drift), cleaned)) {
                return true;
            }
        }
        return false;
    }

    /** The code for a given counter value. Visible for the RFC vector tests. */
    public String generate(String base32Secret, long counter) {
        byte[] key = base32Decode(base32Secret);
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xFF);
            counter >>>= 8;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] hash = mac.doFinal(data);

            // Dynamic truncation, RFC 4226 section 5.3: the low nibble of the last
            // byte picks where in the digest to read the four-byte value from.
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new BadRequestException("Could not compute a one-time password");
        }
    }

    /**
     * Compares without leaking where two codes first differ.
     *
     * <p>Only six digits, so the practical risk is small — but a comparison that
     * returns early on a code is exactly the habit that matters on a longer
     * secret, and there is no reason to write the careless version.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String clean = encoded.trim().replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        int buffer = 0, bitsLeft = 0, index = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new BadRequestException("Authenticator secret is not valid Base32");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
