package com.sujula.service.aftersales;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What may be said between a buyer and a seller, and what may not.
 *
 * <p>Both halves matter equally and the second is the harder one. A filter that
 * removes every long number is easy to write and makes the messaging surface
 * useless for its actual job — which is two people sorting out an order, quoting
 * amounts, dates, order numbers and the IMEI of the handset that arrived. The
 * people it fails first are the ones writing in a second language about a parcel
 * that has not turned up.
 */
class ContactDetailFilterTest {

    private final ContactDetailFilter filter = new ContactDetailFilter();

    // ── What must be taken out ───────────────────────────────────────────────

    @Test
    void aGambianMobileNumberIsRemoved() {
        ContactDetailFilter.Result result = filter.clean("Call me on +220 310 0077 and we sort it");

        assertFalse(result.cleaned().contains("310"));
        assertTrue(result.kinds().contains("PHONE"));
        assertTrue(result.cleaned().startsWith("Call me on "));
        assertTrue(result.cleaned().endsWith(" and we sort it"));
    }

    @Test
    void soIsASpanishOne() {
        ContactDetailFilter.Result result = filter.clean("mi numero es 612 34 56 78");
        assertTrue(result.kinds().contains("PHONE"));
        assertFalse(result.cleaned().contains("612"));
    }

    @Test
    void andOneWrittenWithNoSpacesAtAll() {
        ContactDetailFilter.Result result = filter.clean("3100077 is my line");
        assertTrue(result.kinds().contains("PHONE"));
    }

    @Test
    void anEmailAddressIsRemovedHoweverItIsSpelt() {
        assertTrue(filter.clean("write to lamin@kombo.gm").kinds().contains("EMAIL"));
        // The ordinary way of writing one here, not a clever evasion.
        assertTrue(filter.clean("write to lamin at kombo dot gm").kinds().contains("EMAIL"));
        assertTrue(filter.clean("lamin (at) kombo.gm").kinds().contains("EMAIL"));
    }

    @Test
    void aLinkIsRemovedWithOrWithoutTheScheme() {
        assertTrue(filter.clean("see https://kombo-phones.com/deals").kinds().contains("LINK"));
        assertTrue(filter.clean("see www.kombo-phones.com").kinds().contains("LINK"));
        assertTrue(filter.clean("see kombo-phones.shop").kinds().contains("LINK"));
    }

    @Test
    void movingTheConversationToAnotherAppIsTheWholePointOfThis() {
        ContactDetailFilter.Result result = filter.clean(
                "just whatsapp me and I will send the details");
        assertTrue(result.kinds().contains("SOCIAL"));

        // The reason it is stopped, in the notice the sender reads.
        assertNotNull(result.notice());
        assertTrue(result.notice().contains("held until the parcel is proven delivered"));
    }

    @Test
    void aBareHandleIsNotAnAttemptToLeave() {
        // "@fatou" is how people address each other. Treating it as an escape
        // attempt would flag half the ordinary conversation on the platform.
        assertFalse(filter.clean("@fatou did the charger come?").changed());
    }

    // ── What must survive, which is the harder half ──────────────────────────

    @Test
    void moneySurvives() {
        ContactDetailFilter.Result result = filter.clean("I paid 12,000 GMD for it");
        assertFalse(result.changed(), result.cleaned());
        assertTrue(result.cleaned().contains("12,000 GMD"));
    }

    @Test
    void soDoesMoneyInTheBuyersOwnCurrency() {
        assertFalse(filter.clean("It came to 180.00 EUR").changed());
        assertFalse(filter.clean("It came to €180.00").changed());
        assertFalse(filter.clean("6500 dalasi").changed());
    }

    @Test
    void aDateSurvives() {
        assertFalse(filter.clean("ordered on 12/09/2026").changed());
    }

    @Test
    void anOrderNumberSurvives() {
        ContactDetailFilter.Result result = filter.clean("this is about order SJL-2026-114307");
        assertFalse(result.changed(), result.cleaned());
    }

    @Test
    void aParcelTrackingCodeSurvives() {
        assertFalse(filter.clean("the code in my link is PARCQ4T8NHRW6JZY").changed());
    }

    @Test
    void anImeiSurvives() {
        // Fifteen digits. A buyer quoting the handset they were actually sent is
        // doing exactly what the seller needs them to do.
        ContactDetailFilter.Result result = filter.clean("the IMEI on it is 356938035643809");
        assertFalse(result.changed(), result.cleaned());
    }

    @Test
    void quantitiesAndShortNumbersSurvive() {
        assertFalse(filter.clean("send 2 of them, size 128 GB").changed());
        assertFalse(filter.clean("it is 4 days late").changed());
    }

    // ── The shape of the result ──────────────────────────────────────────────

    @Test
    void theMessageArrivesRatherThanVanishing() {
        ContactDetailFilter.Result result = filter.clean(
                "The screen is cracked. Call me on 3100077 and I will show you.");

        // A message that disappeared would teach people to write in code. One
        // that arrives with the number taken out teaches them the rule.
        assertTrue(result.cleaned().contains("The screen is cracked."));
        assertTrue(result.cleaned().contains("I will show you."));
        assertTrue(result.cleaned().contains(ContactDetailFilter.REDACTION));
    }

    @Test
    void severalKindsInOneMessageAreAllNamed() {
        ContactDetailFilter.Result result = filter.clean(
                "email lamin@kombo.gm or call +220 310 0077, see www.kombo.com");

        assertTrue(result.kinds().contains("EMAIL"));
        assertTrue(result.kinds().contains("PHONE"));
        assertTrue(result.kinds().contains("LINK"));
        assertEquals("EMAIL,LINK,PHONE", sorted(result.kindsAsText()));
    }

    @Test
    void anOrdinaryMessageIsUntouchedAndSaysSo() {
        ContactDetailFilter.Result result = filter.clean(
                "Hello, the phone arrived but the charger was missing from the box.");

        assertFalse(result.changed());
        assertEquals("Hello, the phone arrived but the charger was missing from the box.",
                result.cleaned());
        // Nothing to tell the sender, so nothing is said.
        assertEquals(null, result.notice());
        assertEquals(null, result.kindsAsText());
    }

    @Test
    void nothingIsSaidAboutAnEmptyMessage() {
        assertFalse(filter.clean(null).changed());
        assertFalse(filter.clean("   ").changed());
    }

    private static String sorted(String csv) {
        String[] parts = csv.split(",");
        java.util.Arrays.sort(parts);
        return String.join(",", parts);
    }
}
