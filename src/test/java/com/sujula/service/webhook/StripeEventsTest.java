package com.sujula.service.webhook;

import org.junit.jupiter.api.Test;

import com.sujula.service.reference.ReferenceDataProperties;
import com.sujula.service.reference.CurrencyCatalogue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stripe's event shape, read into what the payment handler checks.
 *
 * <p>The amount is the one that matters. Stripe sends minor units, and the
 * handler refuses any event whose amount disagrees with the payment — so read
 * as-is, every honest card payment would be refused.
 */
class StripeEventsTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final CurrencyCatalogue currencies = CurrencyCatalogue.of(new ReferenceDataProperties());

    private JsonNode completed(String currency, long amountTotal, String paymentStatus) {
        return mapper.readTree("""
                {"id":"evt_1","type":"checkout.session.completed","data":{"object":{
                  "id":"cs_test_1","client_reference_id":"PAY-1","currency":"%s",
                  "amount_total":%d,"payment_status":"%s","metadata":{"reference":"PAY-1"}}}}
                """.formatted(currency, amountTotal, paymentStatus));
    }

    @Test
    void aEuroAmountIsReadInMajorUnits() {
        JsonNode flat = StripeEvents.flatten(completed("eur", 10800, "paid"), mapper, currencies);

        assertEquals("108.00", flat.path("amount").asString());
        assertEquals("EUR", flat.path("currency").asString());
        assertEquals("PAY-1", flat.path("reference").asString());
        assertEquals("cs_test_1", flat.path("id").asString());
    }

    @Test
    void aCfaAmountHasNoMinorUnitsToMove() {
        // XOF has none: 1250 CFA arrives as 1250, not 12.50 (C2).
        JsonNode flat = StripeEvents.flatten(completed("xof", 1250, "paid"), mapper, currencies);

        assertEquals(0, new java.math.BigDecimal("1250")
                .compareTo(new java.math.BigDecimal(flat.path("amount").asString())));
    }

    @Test
    void aCompletedCheckoutStillWaitingForFundsDoesNotCount() {
        assertTrue(StripeEvents.awaitingFunds("checkout.session.completed",
                completed("eur", 10800, "unpaid")));
        assertFalse(StripeEvents.awaitingFunds("checkout.session.completed",
                completed("eur", 10800, "paid")));
    }
}
