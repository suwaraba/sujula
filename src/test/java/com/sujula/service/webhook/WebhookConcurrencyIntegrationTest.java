package com.sujula.service.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
import com.sujula.repository.order.OrderStatusHistoryRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.webhook.WebhookEventRepository;
import com.sujula.service.EmailService;
import com.sujula.service.NotificationService;
import com.sujula.service.payment.PaymentSettlementService;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:webhook-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "NON_KEYWORDS=VALUE,READ;LOCK_TIMEOUT=30000")
@Import({WebhookProcessor.class, WebhookIntake.class, WebhookRecorder.class,
         WebhookProperties.class, PaymentSettlementService.class,
         CurrencyCatalogue.class, ReferenceDataProperties.class,
         WebhookConcurrencyIntegrationTest.Json.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookConcurrencyIntegrationTest {

    private static final String SECRET = "concurrent-webhook-secret";

    @org.springframework.boot.test.context.TestConfiguration
    static class Json {
        @org.springframework.context.annotation.Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return new tools.jackson.databind.ObjectMapper();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired private WebhookProcessor processor;
    @org.springframework.beans.factory.annotation.Autowired private WebhookIntake intake;
    @org.springframework.beans.factory.annotation.Autowired private WebhookProperties properties;
    @org.springframework.beans.factory.annotation.Autowired private WebhookEventRepository events;
    @org.springframework.beans.factory.annotation.Autowired private PaymentRepository payments;
    @org.springframework.beans.factory.annotation.Autowired private OrderRepository orders;
    @org.springframework.beans.factory.annotation.Autowired private OrderStatusHistoryRepository history;
    @org.springframework.beans.factory.annotation.Autowired private UserRepository users;

    @MockitoBean private NotificationService notifications;
    @MockitoBean private EmailService email;
    @MockitoSpyBean private PaymentSettlementService settlements;

    private User buyer;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        reset(notifications, email, settlements);
        properties.getSecrets().put("wave", SECRET);

        buyer = users.saveAndFlush(User.builder()
                .firstName("Race").lastName("Buyer")
                .email("race-" + System.nanoTime() + "@example.test")
                .password("x").role(UserRole.CUSTOMER).enabled(true).build());
        order = orders.saveAndFlush(Order.builder()
                .orderNumber("race-order-" + System.nanoTime())
                .customer(buyer).status(OrderStatus.PENDING)
                .paymentStatus(PaymentStatus.PENDING).currency("EUR")
                .subtotal(new BigDecimal("108.64")).shippingCost(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO).discount(BigDecimal.ZERO)
                .total(new BigDecimal("108.64")).items(List.of()).vendorOrders(List.of())
                .build());
        payment = payments.saveAndFlush(Payment.builder()
                .order(order).reference("race-pay-" + System.nanoTime())
                .status(PaymentStatus.PENDING).method(PaymentMethod.CARD)
                .amount(new BigDecimal("108.64")).amountRefunded(BigDecimal.ZERO)
                .currency("EUR").build());
    }

    @AfterEach
    void cleanUp() {
        events.deleteAll();
        history.deleteAll();
        payments.deleteAll();
        orders.deleteAll();
        users.deleteAll();
    }

    @Test
    void concurrentProcessorsOfTheSameEventApplySideEffectsOnce() throws Exception {
        WebhookEvent event = storeSuccess("same-event");

        runTogether(() -> processor.runOne(event.getId()), () -> processor.runOne(event.getId()));

        assertSettledOnce();
        WebhookEvent stored = events.findById(event.getId()).orElseThrow();
        assertEquals(WebhookStatus.PROCESSED, stored.getStatus());
        assertEquals(1, stored.getAttempts());
    }

    @Test
    void distinctSuccessEventsForOnePaymentApplySideEffectsOnce() throws Exception {
        WebhookEvent first = storeSuccess("distinct-a");
        WebhookEvent second = storeSuccess("distinct-b");

        runTogether(() -> processor.runOne(first.getId()), () -> processor.runOne(second.getId()));

        assertSettledOnce();
        assertEquals(WebhookStatus.PROCESSED, events.findById(first.getId()).orElseThrow().getStatus());
        assertEquals(WebhookStatus.PROCESSED, events.findById(second.getId()).orElseThrow().getStatus());
    }

    @Test
    void concurrentDuplicateIntakeAcknowledgesBothAndStoresOneRow() throws Exception {
        String payload = "{\"id\":\"intake-race\",\"type\":\"payment.succeeded\","
                + "\"reference\":\"" + payment.getReference() + "\"}";
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = WebhookSignature.sign(SECRET, timestamp, body);

        WebhookIntake.Accepted[] accepted = new WebhookIntake.Accepted[2];
        runTogether(
                () -> accepted[0] = intake.receive(WebhookKind.PSP, "wave", body, signature, timestamp),
                () -> accepted[1] = intake.receive(WebhookKind.PSP, "wave", body, signature, timestamp));

        assertEquals(1, events.findAll().stream()
                .filter(event -> "intake-race".equals(event.getEventId())).count());
        assertTrue(accepted[0].status() == WebhookStatus.RECEIVED
                || accepted[1].status() == WebhookStatus.RECEIVED);
        assertTrue(accepted[0].status() == WebhookStatus.DUPLICATE
                || accepted[1].status() == WebhookStatus.DUPLICATE);
    }

    @Test
    void rolledBackBusinessFailureIncrementsAttemptsDurably() {
        WebhookEvent event = storeSuccess("failing-event");
        doThrow(new IllegalStateException("settlement failed"))
                .when(settlements).settle(any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> processor.runOne(event.getId()));
        processor.markFailed(event.getId(), "settlement failed", 2);

        assertEquals(PaymentStatus.PENDING, payments.findById(payment.getId()).orElseThrow().getStatus());
        WebhookEvent retrying = events.findById(event.getId()).orElseThrow();
        assertEquals(1, retrying.getAttempts());
        assertEquals(WebhookStatus.RETRYING, retrying.getStatus());

        processor.markFailed(event.getId(), "settlement failed again", 2);
        WebhookEvent failed = events.findById(event.getId()).orElseThrow();
        assertEquals(2, failed.getAttempts());
        assertEquals(WebhookStatus.FAILED, failed.getStatus());
        verify(notifications, times(0)).send(any(), any(), any(), any(), any());
    }

    @Test
    void markFailedCannotOverwriteACompletedEvent() {
        WebhookEvent event = storeSuccess("finished-event");
        processor.runOne(event.getId());

        processor.markFailed(event.getId(), "late loser", 3);

        WebhookEvent stored = events.findById(event.getId()).orElseThrow();
        assertEquals(WebhookStatus.PROCESSED, stored.getStatus());
        assertEquals(1, stored.getAttempts());
    }

    @Test
    void cancelledOrderAndMissingStripeTypeBothFailClosed() {
        order.setStatus(OrderStatus.CANCELLED);
        orders.saveAndFlush(order);
        WebhookEvent late = storeSuccess("late-success");
        processor.runOne(late.getId());

        assertEquals(PaymentStatus.PENDING, payments.findById(payment.getId()).orElseThrow().getStatus());
        assertEquals(WebhookStatus.IGNORED, events.findById(late.getId()).orElseThrow().getStatus());

        order.setStatus(OrderStatus.PENDING);
        orders.saveAndFlush(order);
        WebhookEvent missingType = events.saveAndFlush(WebhookEvent.builder()
                .kind(WebhookKind.PSP).provider("stripe").eventId("missing-type")
                .status(WebhookStatus.RECEIVED)
                .payload("{\"id\":\"missing-type\",\"reference\":\""
                        + payment.getReference() + "\"}")
                .signatureValid(true).receivedAt(LocalDateTime.now()).build());
        processor.runOne(missingType.getId());

        assertEquals(PaymentStatus.PENDING, payments.findById(payment.getId()).orElseThrow().getStatus());
        assertEquals(WebhookStatus.IGNORED,
                events.findById(missingType.getId()).orElseThrow().getStatus());
        verify(notifications, times(0)).send(any(), any(), any(), any(), any());
    }

    private WebhookEvent storeSuccess(String eventId) {
        return events.saveAndFlush(WebhookEvent.builder()
                .kind(WebhookKind.PSP).provider("wave").eventId(eventId)
                .eventType("payment.succeeded").status(WebhookStatus.RECEIVED)
                .payload("{\"id\":\"" + eventId + "\",\"type\":\"payment.succeeded\","
                        + "\"reference\":\"" + payment.getReference() + "\","
                        + "\"amount\":\"108.64\",\"currency\":\"EUR\"}")
                .signatureValid(true).receivedAt(LocalDateTime.now()).build());
    }

    private void assertSettledOnce() {
        Payment storedPayment = payments.findById(payment.getId()).orElseThrow();
        Order storedOrder = orders.findById(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.PAID, storedPayment.getStatus());
        assertEquals(PaymentStatus.PAID, storedOrder.getPaymentStatus());
        assertEquals(OrderStatus.CONFIRMED, storedOrder.getStatus());
        assertEquals(1, history.findByOrderIdOrderByChangedAtAsc(order.getId()).size());
        verify(notifications, times(1)).send(any(), any(), any(), any(), any());
        verify(email, times(1)).sendOrderConfirmationEmail(any(), any(), any());
    }

    private static void runTogether(ThrowingRunnable first, ThrowingRunnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = executor.submit(() -> runReady(ready, start, first));
            Future<?> two = executor.submit(() -> runReady(ready, start, second));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertFalse(one.isDone());
            assertFalse(two.isDone());
            start.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void runReady(CountDownLatch ready, CountDownLatch start,
                                 ThrowingRunnable action) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out before concurrent start");
            }
            action.run();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
