package com.sujula.service.payment;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.sujula.model.constant.PaymentMethod;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arithmetic between this platform's amounts and Stripe's minor units. No network. */
class StripePaymentGatewayTest {

    private final StripePaymentGateway gateway;

    StripePaymentGatewayTest() {
        StripeProperties stripe = new StripeProperties();
        stripe.setSecretKey("sk_test_unit");
        gateway = new StripePaymentGateway(stripe, new PaymentProperties(),
                CurrencyCatalogue.of(new ReferenceDataProperties()), JsonMapper.builder().build(),
                "http://localhost:5177");
    }

    @Test
    void eurosGoToStripeInCents() {
        assertEquals(new BigDecimal("10800"), gateway.toMinor(new BigDecimal("108.00"), "EUR"));
        assertEquals(new BigDecimal("108.00"), gateway.fromMinor(10800, "EUR"));
    }

    @Test
    void cfaHasNoMinorUnits() {
        // Rounded to the currency's own scale first: 1250.50 CFA is not an
        // amount that exists (C2).
        assertEquals(new BigDecimal("1251"), gateway.toMinor(new BigDecimal("1250.50"), "XOF"));
        assertEquals(0, new BigDecimal("1250").compareTo(gateway.fromMinor(1250, "XOF")));
    }

    @Test
    void onlyCardsGoThroughStripe() {
        assertTrue(gateway.supports(PaymentMethod.CARD));
        assertFalse(gateway.supports(PaymentMethod.PAYPAL));
        assertFalse(gateway.supports(PaymentMethod.BANK_TRANSFER));
    }
}
