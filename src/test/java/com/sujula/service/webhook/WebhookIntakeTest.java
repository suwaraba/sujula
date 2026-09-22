package com.sujula.service.webhook;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.WebhookKind;
import com.sujula.model.constant.WebhookStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.model.user.User;
import com.sujula.model.webhook.WebhookEvent;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.webhook.WebhookEventRepository;
import com.sujula.service.NotificationService;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens to a webhook between arriving and being acted on.
 *
 * <p>The claims that matter: a rejected delivery is still recorded, the same
 * event twice credits an order once, and a provider cannot decide that an order
 * cost something other than what it costs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({WebhookIntake.class, WebhookProcessor.class, WebhookProperties.class,
         WebhookRecorder.class, com.sujula.service.reference.CurrencyCatalogue.class,
         com.sujula.service.reference.ReferenceDataProperties.class,
         WebhookIntakeTest.Json.class})
class WebhookIntakeTest {

    private static final String SECRET = "the-providers-shared-secret";
    private static final String PROVIDER = "wave";

    @org.springframework.boot.test.context.TestConfiguration
    static class Json {
        @org.springframework.context.annotation.Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return new tools.jackson.databind.ObjectMapper();
        }
    }

    @Autowired private WebhookIntake intake;
    @Autowired private WebhookProcessor processor;
    @Autowired private WebhookProperties properties;
    @Autowired private WebhookEventRepository events;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderRepository orders;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    @MockitoBean private NotificationService notifications;

    private Payment payment;

    @BeforeEach
    void setUp() {
        properties.getSecrets().put(PROVIDER, SECRET);

        User buyer = users.save(User.builder()
                .firstName("Oliver").lastName("Bennett").email("oliver.hook@example.co.uk")
                .password("x").role(UserRole.CUSTOMER).enabled(true).build());

        Order order = orders.save(Order.builder()
                .orderNumber("SJL-HOOK-0001").customer(buyer)
                .status(OrderStatus.PENDING).paymentStatus(PaymentStatus.PENDING)
                .currency("EUR").subtotal(new BigDecimal("108.64"))
                .total(new BigDecimal("108.64"))
                .billingCountry("ES").shippingCountry("GM").build());

        payment = payments.save(Payment.builder()
                .order(order).reference("PAY-HOOK-0001").status(PaymentStatus.PENDING)
                .method(PaymentMethod.CARD).amount(new BigDecimal("108.64"))
                .amountRefunded(BigDecimal.ZERO).currency("EUR").build());
        entityManager.flush();
    }

    private WebhookIntake.Accepted post(String json) {
        return post(json, String.valueOf(Instant.now().getEpochSecond()), SECRET);
    }

    private WebhookIntake.Accepted post(String json, String timestamp, String signingSecret) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return intake.receive(WebhookKind.PSP, PROVIDER, body,
                WebhookSignature.sign(signingSecret, timestamp, body), timestamp);
    }

    // ── Intake ───────────────────────────────────────────────────────────────

    @Test
    void aSignedEventIsStoredBeforeAnythingActsOnIt() {
        WebhookIntake.Accepted accepted = post(
                "{\"id\":\"evt_100\",\"type\":\"payment.succeeded\",\"reference\":\"PAY-HOOK-0001\"}");
        entityManager.flush();

        assertEquals(WebhookStatus.RECEIVED, accepted.status());
        WebhookEvent stored = events.findByProviderAndEventId(PROVIDER, "evt_100").orElseThrow();
        // Stored first, acted on after. A crash between the two must not lose
        // the fact that a buyer paid.
        assertTrue(stored.isSignatureValid());
        assertEquals(PaymentStatus.PENDING,
                payments.findById(payment.getId()).orElseThrow().getStatus());
    }

    @Test
    void aForgedEventIsRecordedRatherThanDiscarded() {
        WebhookIntake.Accepted accepted = post(
                "{\"id\":\"evt_101\",\"type\":\"payment.succeeded\"}",
                String.valueOf(Instant.now().getEpochSecond()),
                "somebody-elses-guess");
        entityManager.flush();

        assertEquals(WebhookStatus.REJECTED, accepted.status());
        // One rejection is a clock drifting; fifty in a minute is somebody
        // trying signatures, and a platform that threw them away cannot see it.
        //
        // Counted by payload rather than globally: intake commits on its own
        // transaction — deliberately, so a rejected delivery survives whatever
        // the caller does — which means rows written by earlier tests are still
        // there. A global count would make this assertion depend on test order.
        WebhookEvent rejected = events.findById(accepted.eventRowId()).orElseThrow();
        assertEquals(WebhookStatus.REJECTED, rejected.getStatus());
        assertFalse(rejected.isSignatureValid());
        assertTrue(rejected.getPayload().contains("evt_101"),
                "the body is kept, because what they sent is what settles an argument");
        assertNotNull(rejected.getRejectionReason());
    }

    @Test
    void aRejectedAttemptDoesNotOccupyTheIdTheGenuineEventWillNeed() {
        post("{\"id\":\"evt_102\",\"reference\":\"PAY-HOOK-0001\"}",
                String.valueOf(Instant.now().getEpochSecond()), "wrong-secret");
        entityManager.flush();

        // The real one arrives afterwards and must still be accepted.
        WebhookIntake.Accepted genuine = post(
                "{\"id\":\"evt_102\",\"type\":\"payment.succeeded\",\"reference\":\"PAY-HOOK-0001\"}");
        assertEquals(WebhookStatus.RECEIVED, genuine.status());
    }

    @Test
    void theSameEventTwiceIsAcceptedOnceAndAnsweredTwice() {
        String json = "{\"id\":\"evt_103\",\"type\":\"payment.succeeded\","
                + "\"reference\":\"PAY-HOOK-0001\"}";
        assertEquals(WebhookStatus.RECEIVED, post(json).status());
        entityManager.flush();

        // Providers retry by design. The right answer to "I already have this"
        // is the one that makes them stop — a 4xx would make them retry harder.
        WebhookIntake.Accepted again = post(json);
        assertEquals(WebhookStatus.DUPLICATE, again.status());
        assertFalse(again.stored());
        assertEquals(1, events.findAll().stream()
                .filter(e -> "evt_103".equals(e.getEventId())).count());
    }

    @Test
    void aProviderThatSendsNoIdIsStillDeduplicatedByItsBody() {
        String json = "{\"type\":\"payment.succeeded\",\"reference\":\"PAY-HOOK-0001\"}";
        WebhookIntake.Accepted first = post(json);
        entityManager.flush();

        assertEquals(WebhookStatus.RECEIVED, first.status());
        // Not every provider is well behaved. A hash of the body is the best
        // available answer and still stops an identical retry.
        assertEquals(WebhookStatus.DUPLICATE, post(json).status());
    }

    @Test
    void thesameRefusedDeliveryArrivingTwiceIsStillJustARefusal() {
        String json = "{\"id\":\"evt_105\",\"type\":\"payment.succeeded\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        assertEquals(WebhookStatus.REJECTED, post(json, timestamp, "wrong-secret").status());
        entityManager.flush();

        // Providers retry a refusal as readily as an acceptance, and the retry
        // has the same body and so the same derived id. This used to collide on
        // the unique constraint and answer 500 — which tells a provider to retry
        // harder at exactly the moment we are telling them no.
        assertEquals(WebhookStatus.REJECTED, post(json, timestamp, "wrong-secret").status());
    }

    @Test
    void aStaleDeliveryIsRefusedEvenThoughItsSignatureIsPerfect() {
        String old = String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond());
        WebhookIntake.Accepted accepted = post(
                "{\"id\":\"evt_104\",\"type\":\"payment.succeeded\"}", old, SECRET);

        assertEquals(WebhookStatus.REJECTED, accepted.status());
    }

    // ── Acting on it ─────────────────────────────────────────────────────────

    @Test
    void aVerifiedSuccessMarksThePaymentPaid() {
        WebhookIntake.Accepted accepted = post(
                "{\"id\":\"evt_200\",\"type\":\"payment.succeeded\","
                        + "\"reference\":\"PAY-HOOK-0001\",\"amount\":\"108.64\","
                        + "\"currency\":\"EUR\"}");
        entityManager.flush();

        processor.runOne(accepted.eventRowId());
        entityManager.flush();

        assertEquals(PaymentStatus.PAID,
                payments.findById(payment.getId()).orElseThrow().getStatus());
        assertEquals(WebhookStatus.PROCESSED,
                events.findById(accepted.eventRowId()).orElseThrow().getStatus());
    }

    @Test
    void aProviderCannotDecideAnOrderCostSomethingElse() {
        WebhookIntake.Accepted accepted = post(
                "{\"id\":\"evt_201\",\"type\":\"payment.succeeded\","
                        + "\"reference\":\"PAY-HOOK-0001\",\"amount\":\"1.00\","
                        + "\"currency\":\"EUR\"}");
        entityManager.flush();

        processor.runOne(accepted.eventRowId());
        entityManager.flush();

        // An event saying a 108.64 order was paid 1.00 is a bug or an attack,
        // and crediting it is the same mistake either way.
        assertEquals(PaymentStatus.PENDING,
                payments.findById(payment.getId()).orElseThrow().getStatus());
        WebhookEvent stored = events.findById(accepted.eventRowId()).orElseThrow();
        assertEquals(WebhookStatus.FAILED, stored.getStatus());
        assertTrue(stored.getOutcome().contains("cannot decide an order cost something else"));
    }

    @Test
    void aCurrencyThatDoesNotMatchIsRefusedBecauseCurrenciesAreNotInterchangeable() {
        WebhookIntake.Accepted accepted = post(
                "{\"id\":\"evt_202\",\"type\":\"payment.succeeded\","
                        + "\"reference\":\"PAY-HOOK-0001\",\"amount\":\"108.64\","
                        + "\"currency\":\"GMD\"}");
        entityManager.flush();

        processor.runOne(accepted.eventRowId());
        entityManager.flush();

        // 108.64 EUR and 108.64 GMD differ by two orders of magnitude here.
        assertEquals(PaymentStatus.PENDING,
                payments.findById(payment.getId()).orElseThrow().getStatus());
        assertEquals(WebhookStatus.FAILED,
                events.findById(accepted.eventRowId()).orElseThrow().getStatus());
    }

    @Test
    void aRefundEventIsKeptAsEvidenceAndMovesNothing() {
        WebhookIntake.Accepted accepted = post(
                "{\"id\":\"evt_203\",\"type\":\"charge.refunded\","
                        + "\"reference\":\"PAY-HOOK-0001\",\"amount\":\"50.00\"}");
        entityManager.flush();

        processor.runOne(accepted.eventRowId());
        entityManager.flush();

        // The platform's own refund path writes the ledger rows. A webhook that
        // moved money would bypass every one of them.
        assertEquals(BigDecimal.ZERO.setScale(2),
                payments.findById(payment.getId()).orElseThrow().getAmountRefunded()
                        .setScale(2));
        WebhookEvent stored = events.findById(accepted.eventRowId()).orElseThrow();
        assertEquals(WebhookStatus.IGNORED, stored.getStatus());
        assertTrue(stored.getOutcome().contains("has not moved anything"));
    }

    @Test
    void aFailureArrivingAfterASuccessDoesNotUnpayTheOrder() {
        WebhookIntake.Accepted paid = post(
                "{\"id\":\"evt_204\",\"type\":\"payment.succeeded\","
                        + "\"reference\":\"PAY-HOOK-0001\"}");
        entityManager.flush();
        processor.runOne(paid.eventRowId());
        entityManager.flush();

        WebhookIntake.Accepted failed = post(
                "{\"id\":\"evt_205\",\"type\":\"payment.failed\","
                        + "\"reference\":\"PAY-HOOK-0001\"}");
        entityManager.flush();
        processor.runOne(failed.eventRowId());
        entityManager.flush();

        // Events arrive out of order. Un-paying here would cancel goods that are
        // already being packed.
        assertEquals(PaymentStatus.PAID,
                payments.findById(payment.getId()).orElseThrow().getStatus());
        assertEquals(WebhookStatus.IGNORED,
                events.findById(failed.eventRowId()).orElseThrow().getStatus());
    }

    @Test
    void anEventForSomethingThisPlatformNeverCreatedIsIgnoredRatherThanFailed() {
        WebhookIntake.Accepted accepted = post(
                "{\"id\":\"evt_206\",\"type\":\"payment.succeeded\","
                        + "\"reference\":\"PAY-SOMEBODY-ELSE\"}");
        entityManager.flush();

        processor.runOne(accepted.eventRowId());
        entityManager.flush();

        // Recording "not ours" stops somebody later assuming a missing effect
        // was a bug.
        WebhookEvent stored = events.findById(accepted.eventRowId()).orElseThrow();
        assertEquals(WebhookStatus.IGNORED, stored.getStatus());
        assertNotNull(stored.getOutcome());
    }

    @Test
    void anIdentityProviderDoesNotGetToApproveASeller() {
        byte[] body = ("{\"id\":\"evt_300\",\"result\":\"clear\",\"reference\":\"999\"}")
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        WebhookIntake.Accepted accepted = intake.receive(WebhookKind.KYC, PROVIDER, body,
                WebhookSignature.sign(SECRET, timestamp, body), timestamp);
        entityManager.flush();

        processor.runOne(accepted.eventRowId());
        entityManager.flush();

        // No document 999 exists, so it is ignored — but the important half is
        // that even a matching one would only be recorded, never approved. The
        // cost of being wrong is a driver's goods in a stranger's hands.
        assertEquals(WebhookStatus.IGNORED,
                events.findById(accepted.eventRowId()).orElseThrow().getStatus());
    }

    @Test
    void aBouncedMessageIsRecordedBecauseACodeThatBouncedIsSomebodyUnableToCollect() {
        byte[] body = ("{\"id\":\"evt_400\",\"type\":\"bounce\","
                + "\"recipient\":\"oliver.hook@example.co.uk\"}")
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        WebhookIntake.Accepted accepted = intake.receive(WebhookKind.MESSAGING, PROVIDER, body,
                WebhookSignature.sign(SECRET, timestamp, body), timestamp);
        entityManager.flush();

        processor.runOne(accepted.eventRowId());
        entityManager.flush();

        WebhookEvent stored = events.findById(accepted.eventRowId()).orElseThrow();
        assertEquals(WebhookStatus.PROCESSED, stored.getStatus());
        assertTrue(stored.getOutcome().contains("did not arrive"));
    }
}
