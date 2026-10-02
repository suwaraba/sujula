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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

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
        gateway = new StripePaymentGateway(stripe,
                CurrencyCatalogue.of(new ReferenceDataProperties()), JsonMapper.builder().build(),
                "http://localhost:5177", http.build());
    }

    @Test
    void eurosGoToStripeInCents() {
        assertEquals(new BigDecimal("10864"), gateway.toMinor(new BigDecimal("108.64"), "EUR"));
        assertEquals(new BigDecimal("108.64"), gateway.fromMinor(10864, "EUR"));
    }

    @Test
    void cfaHasNoMinorUnits() {
        assertEquals(new BigDecimal("1250"), gateway.toMinor(new BigDecimal("1250"), "XOF"));
        assertEquals(0, new BigDecimal("1250").compareTo(gateway.fromMinor(1250, "XOF")));
    }

    @Test
    void stripeNeverRepairsANonCanonicalPaymentAmount() {
        // The order/payment boundary must already have made XOF whole. Rounding
        // here would let Stripe charge something other than Payment.amount.
        assertThrows(BadRequestException.class,
                () -> gateway.toMinor(new BigDecimal("1250.50"), "XOF"));
        assertThrows(BadRequestException.class,
                () -> gateway.toMinor(new BigDecimal("108.641"), "EUR"));
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
                .andExpect(header("Idempotency-Key", "sujula-pay-v1-operation"))
                .andExpect(content().string(containsString("client_reference_id=PAY-TEST00001")))
                .andExpect(content().string(containsString(
                        "product_data%5D%5Bname%5D=Sujula+payment+PAY-TEST00001")))
                .andExpect(content().string(not(containsString("expires_at"))))
                .andExpect(content().string(not(containsString("customer_email"))))
                .andRespond(withSuccess(
                        "{\"id\":\"cs_NEW\",\"url\":\"https://checkout.stripe.test/cs_NEW\",\"status\":\"open\"}",
                        APPLICATION_JSON));

        PaymentGateway.GatewayCheckout checkout = gateway.createCheckout(
                payment("cs_OLD"), null, "sujula-pay-v1-operation");

        assertEquals("cs_NEW", checkout.transactionId());
        assertEquals("https://checkout.stripe.test/cs_NEW", checkout.checkoutUrl());
        server.verify();
    }

    @Test
    void xofCheckoutSendsTheExactWholeFrancPaymentAmount() {
        server.expect(once(), requestTo(API_BASE + "/v1/checkout/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("currency%5D=xof")))
                .andExpect(content().string(containsString("unit_amount%5D=1251")))
                .andRespond(withSuccess(
                        "{\"id\":\"cs_XOF\",\"url\":\"https://checkout.stripe.test/cs_XOF\"}",
                        APPLICATION_JSON));

        gateway.createCheckout(payment(null, "XOF", new BigDecimal("1251")),
                null, "sujula-pay-xof");

        server.verify();
    }

    @Test
    void refundUsesTheDurableRefundRequestIdentityAsItsStripeIdempotencyKey() {
        server.expect(once(), requestTo(API_BASE + "/v1/refunds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-request-417"))
                .andExpect(content().string(containsString("payment_intent=pi_PAID")))
                .andExpect(content().string(containsString("amount=2500")))
                .andRespond(withSuccess(
                        "{\"id\":\"re_417\",\"amount\":2500,\"status\":\"succeeded\"}",
                        APPLICATION_JSON));

        PaymentGateway.GatewayRefund refunded = gateway.refund(
                payment("pi_PAID"), new BigDecimal("25.00"), "Damaged",
                "refund-request-417");

        assertEquals("re_417", refunded.refundId());
        assertEquals(new BigDecimal("25.00"), refunded.amount());
        server.verify();
    }

    private Payment payment(String transactionId) {
        return payment(transactionId, "GMD", new BigDecimal("1200.00"));
    }

    private Payment payment(String transactionId, String currency, BigDecimal amount) {
        Order order = new Order();
        order.setOrderNumber("SJL-TEST0001");
        return Payment.builder()
                .order(order)
                .reference("PAY-TEST00001")
                .status(PaymentStatus.PENDING)
                .method(PaymentMethod.CARD)
                .amount(amount)
                .currency(currency)
                .transactionId(transactionId)
                .build();
    }
}
