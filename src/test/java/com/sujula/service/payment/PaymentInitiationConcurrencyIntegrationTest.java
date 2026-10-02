package com.sujula.service.payment;

import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.service.AuditService;
import com.sujula.service.EmailService;
import com.sujula.service.NotificationService;
import com.sujula.service.PaymentService;
import com.sujula.service.impl.PaymentServiceImpl;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PaymentServiceImpl.class, PaymentSettlementService.class,
         PaymentInitiationConcurrencyIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentInitiationConcurrencyIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @Bean BlockingGateway blockingGateway() { return new BlockingGateway(); }
        @Bean PaymentProperties paymentProperties() { return new PaymentProperties(); }
        @Bean EmailService emailService() { return mock(EmailService.class); }
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
        @Bean AuditService auditService() { return mock(AuditService.class); }
        @Bean CurrencyCatalogue currencyCatalogue() {
            return CurrencyCatalogue.of(new ReferenceDataProperties());
        }
    }

    static final class BlockingGateway implements PaymentGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);

        void reset() {
            calls.set(0);
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override public boolean supports(PaymentMethod method) { return method == PaymentMethod.CARD; }
        @Override public String name() { return "blocking-test"; }
        @Override public void retireCheckout(String transactionId) { }

        @Override
        public GatewayCheckout createCheckout(
                Payment payment, String returnUrl, String providerOperationKey) {
            int sequence = calls.incrementAndGet();
            entered.countDown();
            await(release);
            return new GatewayCheckout("cs_" + sequence,
                    "https://provider.test/checkout/cs_" + sequence, null, "{}");
        }
    }

    @org.springframework.beans.factory.annotation.Autowired private PaymentService payments;
    @org.springframework.beans.factory.annotation.Autowired private PaymentRepository paymentRepository;
    @org.springframework.beans.factory.annotation.Autowired private OrderRepository orderRepository;
    @org.springframework.beans.factory.annotation.Autowired private BlockingGateway gateway;

    private Order order;

    @BeforeEach
    void setUp() {
        gateway.reset();
        order = orderRepository.saveAndFlush(Order.builder()
                .orderNumber("payment-race-" + System.nanoTime())
                .status(OrderStatus.PENDING)
                .subtotal(new BigDecimal("1200.00"))
                .shippingCost(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("1200.00"))
                .currency("GMD")
                .paymentStatus(PaymentStatus.PENDING)
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .vendorOrders(List.of())
                .items(List.of())
                .build());
    }

    @AfterEach
    void cleanUp() {
        gateway.release.countDown();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void missingPaymentRowIsSerializedThroughTheOrderLock() throws Exception {
        InitiatePaymentRequest request = InitiatePaymentRequest.builder()
                .method(PaymentMethod.CARD)
                .returnUrl("https://shop.test/return")
                .build();
        PaymentOperation firstOperation = PaymentOperation.of(
                "user:system:payment.initiate:" + order.getId(), "race-operation-a");
        PaymentOperation secondOperation = PaymentOperation.of(
                "user:system:payment.initiate:" + order.getId(), "race-operation-b");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentResponse> first = executor.submit(
                    () -> payments.initiate(order.getId(), null, request, firstOperation));
            assertTrue(gateway.entered.await(5, TimeUnit.SECONDS));

            CountDownLatch contenderStarted = new CountDownLatch(1);
            Future<PaymentResponse> second = executor.submit(() -> {
                contenderStarted.countDown();
                return payments.initiate(order.getId(), null, request, secondOperation);
            });
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertFalse(second.isDone(), "the second transaction must wait on the Order row");
            assertEquals(1, gateway.calls.get(), "only the lock holder may enter the provider");

            gateway.release.countDown();
            PaymentResponse winner = first.get(5, TimeUnit.SECONDS);
            PaymentResponse follower = second.get(5, TimeUnit.SECONDS);

            assertEquals(winner.getPaymentId(), follower.getPaymentId());
            assertEquals(winner.getTransactionId(), follower.getTransactionId());
            assertEquals(1, paymentRepository.count());
            assertEquals(1, gateway.calls.get());
        } finally {
            gateway.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for payment race coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("payment test thread was interrupted", interrupted);
        }
    }
}
