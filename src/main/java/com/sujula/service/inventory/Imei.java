package com.sujula.service.inventory;

import com.sujula.exceptions.BadRequestException;

/**
 * Checks an IMEI is an IMEI.
 *
 * <p>The last digit of an IMEI is a Luhn check digit over the other fourteen,
 * which makes most typos detectable for nothing. That matters more here than the
 * arithmetic suggests: a seller unpacking a box types these by hand or scans a
 * worn label, and an IMEI recorded wrong is a handset that cannot be traced if
 * it turns out to be stolen and cannot be matched if it comes back.
 *
 * <p>Not a security control. Somebody inventing a number can compute a valid
 * check digit as easily as this can verify one. It catches mistakes, which is
 * what almost all wrong IMEIs are.
 */
public final class Imei {

    private Imei() {}

    /** Fourteen digits plus a check digit; sixteen with a software version. */
    private static final int LENGTH = 15;

    /**
     * Strips spaces and hyphens and returns the fifteen digits, or explains why
     * it will not.
     *
     * <p>A 14-digit code is accepted and completed: the check digit is
     * frequently omitted on labels and packaging, and refusing a seller's box of
     * twenty over a digit the manufacturer left off would be pedantry rather
     * than correctness.
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("An IMEI is required.");
        }
        String digits = raw.replaceAll("[^0-9]", "");

        if (digits.length() == 16) {
            // A 16-digit IMEISV carries a two-digit software version in place of
            // the check digit. The handset is the first fourteen.
            digits = digits.substring(0, 14);
        }
        if (digits.length() == 14) {
            return digits + checkDigit(digits);
        }
        if (digits.length() != LENGTH) {
            throw new BadRequestException(
                    "'" + raw + "' is not an IMEI. It should be 15 digits - dial *#06# on the "
                    + "handset to see it.");
        }
        if (!isLuhnValid(digits)) {
            throw new BadRequestException(
                    "'" + raw + "' has the right length but fails its own check digit, so one of "
                    + "the numbers is wrong. Check it against the handset.");
        }
        return digits;
    }

    /** Whether the whole number, check digit included, satisfies Luhn. */
    public static boolean isLuhnValid(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /** The digit that would make {@code fourteen} valid. */
    public static char checkDigit(String fourteen) {
        int sum = 0;
        boolean doubling = true;   // the check digit sits at the unchanged position
        for (int i = fourteen.length() - 1; i >= 0; i--) {
            int digit = fourteen.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return (char) ('0' + ((10 - (sum % 10)) % 10));
    }
}
