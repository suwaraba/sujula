package com.sujula.service.payment;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Stripe arithmetic and HTTP lifecycle behavior against an in-memory mock server. */
class StripePaymentGatewayTest {

    private static final String API_BASE = "https://stripe.test";

    private StripePaymentGateway gateway;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        StripeProperties stripe = new StripeProperties();
        stripe.setSecretKey("sk_test_unit");
        stripe.setApiBase(API_BASE);
        RestClient.Builder http = RestClient.builder().baseUrl(API_BASE);
        server = MockRestServiceServer.bindTo(http).build();
        gateway = new StripePaymentGateway(stripe, new PaymentProperties(),
                CurrencyCatalogue.of(new ReferenceDataProperties()), JsonMapper.builder().build(),
                "http://localhost:5177", http.build());
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

    @Test
    void retiresTheExactExplicitCheckoutSession() {
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD/expire"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"cs_OLD\",\"status\":\"expired\"}", APPLICATION_JSON));

        gateway.retireCheckout("cs_OLD");

        server.verify();
    }

    @Test
    void nullAndBlankCheckoutIdsNeverCallStripe() {
        gateway.retireCheckout(null);
        gateway.retireCheckout("");
        gateway.retireCheckout("   ");

        server.verify();
    }

    @Test
    void foreignProviderIdIsRejectedWithoutCallingStripe() {
        assertThrows(BadRequestException.class, () -> gateway.retireCheckout("pi_NOT_A_SESSION"));

        server.verify();
    }

    @Test
    void providerFailurePropagatesWhenReconciliationStillShowsOpen() {
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD/expire"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"temporarily unavailable\"}}"));
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"cs_OLD\",\"status\":\"open\"}", APPLICATION_JSON));

        assertThrows(BadRequestException.class, () -> gateway.retireCheckout("cs_OLD"));

        server.verify();
    }

    @Test
    void ambiguousFailurePropagatesWhenReconciliationAlsoFails() {
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD/expire"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"expire outcome unknown\"}}"));
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT)
                        .contentType(APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"reconciliation unavailable\"}}"));

        BadRequestException failure = assertThrows(
                BadRequestException.class, () -> gateway.retireCheckout("cs_OLD"));

        assertEquals(1, failure.getSuppressed().length);
        server.verify();
    }

    @Test
    void failedExpireCanProceedOnlyWhenExactSessionIsConfirmedExpired() {
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD/expire"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"already expired\"}}"));
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD"))
                .andRespond(withSuccess("{\"id\":\"cs_OLD\",\"status\":\"expired\"}", APPLICATION_JSON));

        gateway.retireCheckout("cs_OLD");

        server.verify();
    }

    @Test
    void mismatchedExpiredResponseDoesNotConfirmRetirement() {
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD/expire"))
                .andRespond(withSuccess("{\"id\":\"cs_OTHER\",\"status\":\"expired\"}", APPLICATION_JSON));
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions/cs_OLD"))
                .andRespond(withSuccess("{\"id\":\"cs_OLD\",\"status\":\"open\"}", APPLICATION_JSON));

        assertThrows(BadRequestException.class, () -> gateway.retireCheckout("cs_OLD"));

        server.verify();
    }

    @Test
    void creatingCheckoutDoesNotImplicitlyExpirePaymentTransactionId() {
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"id\":\"cs_NEW\",\"url\":\"https://checkout.stripe.test/cs_NEW\",\"status\":\"open\"}",
                        APPLICATION_JSON));

        PaymentGateway.GatewayCheckout checkout = gateway.createCheckout(payment("cs_OLD"), null);

        assertEquals("cs_NEW", checkout.transactionId());
        assertEquals("https://checkout.stripe.test/cs_NEW", checkout.checkoutUrl());
        server.verify();
    }

    private Payment payment(String transactionId) {
        Order order = new Order();
        order.setOrderNumber("SJL-TEST0001");
        return Payment.builder()
                .order(order)
                .reference("PAY-TEST00001")
                .status(PaymentStatus.PENDING)
                .method(PaymentMethod.CARD)
                .amount(new BigDecimal("1200.00"))
                .currency("GMD")
                .transactionId(transactionId)
                .build();
    }
}
