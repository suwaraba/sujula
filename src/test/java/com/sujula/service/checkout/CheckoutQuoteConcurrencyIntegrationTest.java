package com.sujula.service.checkout;

import com.sujula.dto.request.checkout.CheckoutRequests;
import com.sujula.dto.response.checkout.CheckoutResponses;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.delivery.DeliveryContext;
import com.sujula.model.idempotency.IdempotencyRecord;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartQuote;
import com.sujula.model.order.CartQuoteLine;
import com.sujula.model.order.CartQuoteVendorSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.user.User;
import com.sujula.repository.idempotency.IdempotencyRecordRepository;
import com.sujula.repository.order.CartQuoteRepository;
import com.sujula.repository.order.CartRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.CartService;
import com.sujula.service.OrderService;
import com.sujula.service.PaymentService;
import com.sujula.service.cart.CartStructureFingerprint;
import com.sujula.service.checkout.impl.CheckoutServiceImpl;
import com.sujula.service.delivery.DeliveryContextService;
import com.sujula.service.idempotency.IdempotencyAttemptService;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.payment.PaymentOperation;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves quote single-spend separately from same-key idempotency. The requests
 * use different idempotency keys, so only the real quote PESSIMISTIC_WRITE lock
 * can stop the second checkout after the first one has entered order creation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({CheckoutServiceImpl.class, IdempotencyAttemptService.class,
        CheckoutQuoteConcurrencyIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutQuoteConcurrencyIntegrationTest {

    private static final String CONTEXT_ID = "checkout-context";

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        IdempotencyService idempotencyService(IdempotencyRecordRepository records,
                                              IdempotencyAttemptService attempts,
                                              ObjectMapper objectMapper) {
            return new IdempotencyService(records, attempts, objectMapper);
        }

        @Bean
        CurrencyCatalogue currencyCatalogue() {
            return CurrencyCatalogue.of(new ReferenceDataProperties());
        }

        @Bean
        CartService cartService(CartRepository carts) {
            CartService service = mock(CartService.class);
            when(service.getCartForCheckout(anyLong(), anyLong())).thenAnswer(call -> {
                Long userId = call.getArgument(0);
                Long cartId = call.getArgument(1);
                Cart cart = carts.findByIdForUpdate(cartId)
                        .orElseThrow(() -> new ResourceNotFoundException("Cart", cartId));
                if (cart.getUser() == null || !userId.equals(cart.getUser().getId())) {
                    throw new ResourceNotFoundException("Cart", cartId);
                }
                return checkoutCart(cart);
            });
            return service;
        }

        @Bean
        OrderService orderService() {
            return mock(OrderService.class);
        }

        @Bean
        PaymentService paymentService() {
            return mock(PaymentService.class);
        }

        @Bean
        DeliveryContextService deliveryContextService() {
            return mock(DeliveryContextService.class);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private CartQuoteRepository quotes;

    @org.springframework.beans.factory.annotation.Autowired
    private CartRepository carts;

    @org.springframework.beans.factory.annotation.Autowired
    private OrderRepository orders;

    @org.springframework.beans.factory.annotation.Autowired
    private UserRepository users;

    @org.springframework.beans.factory.annotation.Autowired
    private IdempotencyRecordRepository idempotencyRecords;

    @org.springframework.beans.factory.annotation.Autowired
    private IdempotencyService idempotency;

    @org.springframework.beans.factory.annotation.Autowired
    private CheckoutServiceImpl checkout;

    @org.springframework.beans.factory.annotation.Autowired
    private OrderService orderService;

    @org.springframework.beans.factory.annotation.Autowired
    private PaymentService payments;

    @org.springframework.beans.factory.annotation.Autowired
    private DeliveryContextService deliveryContexts;

    private User buyer;
    private Cart sourceCart;
    private CartQuote quote;

    @BeforeEach
    void setUp() {
        buyer = users.saveAndFlush(User.builder()
                .email("checkout-" + UUID.randomUUID() + "@example.test")
                .password("test-password")
                .firstName("Checkout")
                .lastName("Buyer")
                .role(UserRole.CUSTOMER)
                .build());
        sourceCart = carts.saveAndFlush(Cart.builder()
                .user(buyer)
                .token("cart-" + UUID.randomUUID())
                .displayCurrency("GMD")
                .deliveryContextId(CONTEXT_ID)
                .build());
        quote = CartQuote.builder()
                .id("quote-" + UUID.randomUUID())
                .cart(sourceCart)
                .user(buyer)
                .cartFingerprint(CartStructureFingerprint.of(checkoutCart(sourceCart)))
                .displayCurrency("GMD")
                .deliveryContextId(CONTEXT_ID)
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(BigDecimal.TEN)
                .discount(BigDecimal.ZERO)
                .shipping(BigDecimal.ZERO)
                .tax(BigDecimal.ZERO)
                .total(BigDecimal.TEN)
                .complete(true)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .build();
        quote.setLines(List.of(CartQuoteLine.builder()
                .quote(quote)
                .productId(101L)
                .vendorId(501L)
                .quantity(1)
                .listingCurrency("GMD")
                .unitPriceNative(BigDecimal.TEN)
                .lineTotalNative(BigDecimal.TEN)
                .unitPrice(BigDecimal.TEN)
                .lineTotal(BigDecimal.TEN)
                .deliveryCost(BigDecimal.ZERO)
                .deliverable(true)
                .fx(FxSnapshot.identity("GMD", LocalDateTime.now()))
                .build()));
        quote.setVendorSnapshots(List.of(CartQuoteVendorSnapshot.builder()
                .quote(quote)
                .vendorId(501L)
                .discountDisplay(BigDecimal.ZERO)
                .discountNative(BigDecimal.ZERO)
                .platformDiscountShareDisplay(BigDecimal.ZERO)
                .build()));
        quote = quotes.saveAndFlush(quote);

        DeliveryContext context = new DeliveryContext();
        context.setId(CONTEXT_ID);
        context.setMode(DeliveryMode.HOME_DELIVERY);
        context.setAddressId(77L);
        when(deliveryContexts.require(CONTEXT_ID, buyer.getId())).thenReturn(context);
    }

    @AfterEach
    void cleanUp() {
        idempotencyRecords.deleteAll();
        orders.deleteAll();
        quotes.deleteAll();
        carts.deleteAll();
        users.deleteAll();
    }

    @Test
    void differentKeysForTheSameQuoteCreateExactlyOneOrderAndPayment() throws Exception {
        AtomicInteger orderCreations = new AtomicInteger();
        AtomicInteger paymentInitiations = new AtomicInteger();
        CountDownLatch winnerEnteredOrderCreation = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        when(orderService.createFromQuote(anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> {
                    int sequence = orderCreations.incrementAndGet();
                    winnerEnteredOrderCreation.countDown();
                    await(releaseWinner);
                    return orders.save(Order.builder()
                            .orderNumber("quote-order-" + sequence)
                            .customer(buyer)
                            .status(OrderStatus.PENDING)
                            .subtotal(BigDecimal.TEN)
                            .shippingCost(BigDecimal.ZERO)
                            .taxAmount(BigDecimal.ZERO)
                            .discount(BigDecimal.ZERO)
                            .total(BigDecimal.TEN)
                            .currency("GMD")
                            .paymentStatus(PaymentStatus.PENDING)
                            .deliveryMode(DeliveryMode.HOME_DELIVERY)
                            .vendorOrders(List.of())
                            .items(List.of())
                            .build());
                });
        when(payments.initiate(anyLong(), anyLong(), any(), any())).thenAnswer(call -> {
            paymentInitiations.incrementAndGet();
            return PaymentResponse.builder()
                    .paymentId(1L)
                    .reference("payment-1")
                    .method(PaymentMethod.CARD)
                    .status(PaymentStatus.PENDING)
                    .amount(BigDecimal.TEN)
                    .currency("GMD")
                    .build();
        });

        CheckoutRequests.Checkout request = new CheckoutRequests.Checkout(
                quote.getId(), 77L, PaymentMethod.CARD, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CheckoutResponses.Placed> winner = executor.submit(
                    () -> checkoutWithKey("key-a", request));
            assertTrue(winnerEnteredOrderCreation.await(5, TimeUnit.SECONDS));

            CountDownLatch contenderEnteredCheckout = new CountDownLatch(1);
            Future<CheckoutResponses.Placed> contender = executor.submit(() -> {
                contenderEnteredCheckout.countDown();
                return checkoutWithKey("key-b", request);
            });
            assertTrue(contenderEnteredCheckout.await(5, TimeUnit.SECONDS));
            assertFalse(contender.isDone(), "the second key must wait on the quote lock");
            assertEquals(1, orderCreations.get(), "the loser must not enter order creation");

            releaseWinner.countDown();
            CheckoutResponses.Placed placed = winner.get(5, TimeUnit.SECONDS);
            assertNotNull(placed.orderId());

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> contender.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BadRequestException.class, failure.getCause());

            CartQuote consumed = quotes.findById(quote.getId()).orElseThrow();
            assertEquals(1, orders.count());
            assertEquals(1, orderCreations.get());
            assertEquals(1, paymentInitiations.get());
            assertNotNull(consumed.getConsumedAt());
            assertEquals(placed.orderId(), consumed.getConsumedOrderId());
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private CheckoutResponses.Placed checkoutWithKey(String key, CheckoutRequests.Checkout request) {
        return idempotency.execute(
                IdempotencyService.scopeFor(buyer.getId(), "checkout.place"),
                key,
                request,
                201,
                CheckoutResponses.Placed.class,
                () -> checkout.checkout(buyer.getId(), request,
                        PaymentOperation.of("user:" + buyer.getId() + ":checkout.place:payment",
                                "quote-concurrency-operation")));
    }

    private static CartResponse checkoutCart(Cart cart) {
        return CartResponse.builder()
                .cartId(cart.getId())
                .displayCurrency(cart.getDisplayCurrency())
                .deliveryContextId(cart.getDeliveryContextId())
                .totalsComplete(true)
                .deliverable(true)
                .vendors(List.of(CartResponse.VendorGroup.builder()
                        .vendorId(501L)
                        .items(List.of(CartResponse.CartItemResponse.builder()
                                .productId(101L)
                                .variantId(null)
                                .quantity(1)
                                .build()))
                        .build()))
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent checkout coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("checkout test thread was interrupted", interrupted);
        }
    }
}
