package com.sujula.service.inventory;

import com.sujula.exceptions.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IMEI check digit, which is the cheapest correctness a handset gets.
 *
 * <p>A seller unpacking a box types these by hand or scans a worn label. An
 * IMEI recorded wrong is a handset that cannot be traced if it turns out to be
 * stolen and cannot be matched if it comes back, and the Luhn digit catches most
 * of the ways a hand-typed one goes wrong for the cost of fifteen additions.
 */
class ImeiTest {

    /** A real-format IMEI: fourteen digits and the digit that makes them check. */
    private static final String VALID = "490154203237518";

    @Test
    void aValidImeiPassesAndComesBackCleaned() {
        assertTrue(Imei.isLuhnValid(VALID));
        assertEquals(VALID, Imei.normalise(VALID));
        assertEquals(VALID, Imei.normalise("49-015420-323751-8"));
        assertEquals(VALID, Imei.normalise("  490154203237518  "));
    }

    @Test
    void aSingleMistypedDigitIsCaught() {
        // The commonest error there is, and the whole reason for the check digit.
        assertFalse(Imei.isLuhnValid("490154203237519"));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> Imei.normalise("490154203237519"));
        assertTrue(refused.getMessage().contains("check digit"), refused.getMessage());
    }

    @Test
    void twoAdjacentDigitsSwappedIsCaughtToo() {
        // Luhn catches every single-digit error and almost every transposition,
        // which together are nearly all hand-entry mistakes.
        assertFalse(Imei.isLuhnValid("490154203237581"));
    }

    @Test
    void aCodeOfTheWrongLengthSaysWhereToLookForTheRightOne() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> Imei.normalise("12345"));

        // Telling somebody "invalid IMEI" leaves them guessing; *#06# puts it on
        // the screen of the handset in their hand.
        assertTrue(refused.getMessage().contains("*#06#"), refused.getMessage());
    }

    @Test
    void fourteenDigitsAreCompletedRatherThanRefused() {
        // Labels and packaging routinely omit the check digit. Refusing a box of
        // twenty over a digit the manufacturer left off would be pedantry.
        assertEquals(VALID, Imei.normalise("49015420323751"));
    }

    @Test
    void aSixteenDigitImeisvDropsItsSoftwareVersion() {
        // An IMEISV carries a two-digit software version where the check digit
        // would be. The handset is still the first fourteen.
        assertEquals(VALID, Imei.normalise("4901542032375112"));
    }

    @Test
    void theCheckDigitItComputesIsTheOneThatValidates() {
        for (String fourteen : new String[] {
                "49015420323751", "35209900176148", "86123456789012", "01234567890123" }) {
            String completed = fourteen + Imei.checkDigit(fourteen);
            assertTrue(Imei.isLuhnValid(completed),
                    fourteen + " completed to " + completed + " does not validate");
        }
    }

    @Test
    void nothingAtAllIsRefusedReadably() {
        assertThrows(BadRequestException.class, () -> Imei.normalise(null));
        assertThrows(BadRequestException.class, () -> Imei.normalise("   "));
        assertThrows(BadRequestException.class, () -> Imei.normalise("not-a-number"));
    }
}
