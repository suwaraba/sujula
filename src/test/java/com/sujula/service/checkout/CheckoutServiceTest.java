package com.sujula.service.checkout;

import com.sujula.dto.request.checkout.CheckoutRequests;
import com.sujula.dto.response.checkout.CheckoutResponses;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartQuote;
import com.sujula.model.order.Order;
import com.sujula.model.user.User;
import com.sujula.repository.order.CartQuoteRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.service.OrderService;
import com.sujula.service.PaymentService;
import com.sujula.service.checkout.impl.CheckoutServiceImpl;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checkout: what it refuses, and in what order it does things.
 *
 * <p>The ordering is the design. Reserving before validating holds stock for a
 * checkout that was never going to complete; opening a payment intent before the
 * order exists leaves money arriving for nothing. Most of these tests are about
 * the refusals, because on a marketplace where somebody is sending a month's
 * income to a country they are not in, a wrong charge is not recoverable by an
 * apology.
 */
class CheckoutServiceTest {

    private static final Long BUYER = 1005L;
    private static final Long INTRUDER = 99L;

    private CartQuoteRepository quotes;
    private OrderRepository orders;
    private OrderService orderService;
    private PaymentService payments;
    private CheckoutServiceImpl checkout;

    @BeforeEach
    void setUp() {
        quotes = mock(CartQuoteRepository.class);
        orders = mock(OrderRepository.class);
        orderService = mock(OrderService.class);
        payments = mock(PaymentService.class);
        CurrencyCatalogue currencies = CurrencyCatalogue.of(new ReferenceDataProperties());

        checkout = new CheckoutServiceImpl(quotes, orders, orderService, payments, currencies);

        when(quotes.save(any(CartQuote.class))).thenAnswer(call -> call.getArgument(0));
        when(payments.initiate(anyLong(), any(), any())).thenReturn(PaymentResponse.builder()
                .paymentId(9L).reference("PAY-1").method(PaymentMethod.CARD)
                .status(PaymentStatus.PENDING).amount(new BigDecimal("129.73")).currency("GBP")
                .build());
    }

    private static User buyer(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private CartQuote quote(String id, Long ownerId, String currency, String total) {
        Cart cart = Cart.builder().id(1L).token("cart-token").build();
        CartQuote quote = CartQuote.builder()
                .id(id).cart(cart)
                .user(ownerId == null ? null : buyer(ownerId))
                .cartFingerprint("abc")
                .displayCurrency(currency)
                .subtotal(new BigDecimal(total)).total(new BigDecimal(total))
                .complete(true)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .build();
        when(quotes.findLive(id)).thenReturn(Optional.of(quote));
        return quote;
    }

    private Order order(String total, String currency) {
        Order order = new Order();
        order.setId(42L);
        order.setOrderNumber("SJL-1");
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setCurrency(currency);
        order.setSubtotal(new BigDecimal(total));
        order.setTotal(new BigDecimal(total));
        order.setCustomer(buyer(BUYER));
        order.setVendorOrders(List.of());
        when(orderService.createFromCart(anyLong(), anyLong(), any(), any())).thenReturn(order);
        when(orders.findById(42L)).thenReturn(Optional.of(order));
        return order;
    }

    private static CheckoutRequests.Checkout request(String quoteId) {
        return new CheckoutRequests.Checkout(quoteId, 70L, PaymentMethod.CARD, null);
    }

    // ── The happy path, and its order of operations ──────────────────────────

    @Test
    void placesTheOrderAndOpensAPaymentIntent() {
        quote("q1", BUYER, "GBP", "129.73");
        order("129.73", "GBP");

        CheckoutResponses.Placed placed = checkout.checkout(BUYER, request("q1"));

        assertEquals(42L, placed.orderId());
        assertEquals("SJL-1", placed.orderNumber());
        assertNotNull(placed.payment(), "an order with no way to pay for it is not checkout");
        assertEquals(PaymentStatus.PENDING, placed.payment().status());
    }

    @Test
    void theQuoteIsMarkedSpentAgainstTheOrderItPaidFor() {
        CartQuote held = quote("q1", BUYER, "GBP", "129.73");
        order("129.73", "GBP");

        checkout.checkout(BUYER, request("q1"));

        assertTrue(held.isConsumed());
        assertEquals(42L, held.getConsumedOrderId(),
                "months later, this is what says which order that quote became");
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    /**
     * The property that matters most: a buyer is never charged a figure they did
     * not agree to.
     */
    @Test
    void aPriceThatMovedIsRefusedRatherThanCharged() {
        quote("q1", BUYER, "GBP", "129.73");
        order("151.40", "GBP");   // the market moved while they typed a card number

        BadRequestException refused =
                assertThrows(BadRequestException.class, () -> checkout.checkout(BUYER, request("q1")));

        assertTrue(refused.getMessage().contains("Nothing has been charged"));
        verify(payments, never()).initiate(anyLong(), any(), any());
    }

    /**
     * A conversion and a re-conversion can legitimately differ by one butut.
     * Failing checkout over rounding residue would be its own bug.
     */
    @Test
    void roundingResidueIsToleratedInATwoPlaceCurrency() {
        quote("q1", BUYER, "GBP", "129.73");
        order("129.74", "GBP");

        assertNotNull(checkout.checkout(BUYER, request("q1")));
    }

    /** In CFA the smallest amount that exists is a whole franc, so that is the tolerance. */
    @Test
    void theToleranceIsTheCurrencysOwnSmallestUnit() {
        quote("q1", BUYER, "XOF", "13050");
        order("13051", "XOF");

        assertNotNull(checkout.checkout(BUYER, request("q1")),
                "one franc is the smallest amount CFA can express");

        quote("q2", BUYER, "XOF", "13050");
        order("13055", "XOF");
        assertThrows(BadRequestException.class, () -> checkout.checkout(BUYER, request("q2")));
    }

    // ── What a quote has to be ───────────────────────────────────────────────

    @Test
    void anExpiredQuoteIsIndistinguishableFromOneThatNeverExisted() {
        when(quotes.findLive("gone")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> checkout.checkout(BUYER, request("gone")));
    }

    @Test
    void aQuoteCanOnlyBeSpentOnce() {
        CartQuote held = quote("q1", BUYER, "GBP", "129.73");
        held.setConsumedAt(LocalDateTime.now());
        held.setConsumedOrderId(41L);

        BadRequestException refused =
                assertThrows(BadRequestException.class, () -> checkout.checkout(BUYER, request("q1")));

        assertTrue(refused.getMessage().contains("already been used"));
        verify(orderService, never()).createFromCart(anyLong(), anyLong(), any(), any());
    }

    /**
     * A quote that could not be fully converted would undercharge — the platform
     * would silently owe the difference on one vendor's goods.
     */
    @Test
    void anIncompleteQuoteCannotBePaid() {
        CartQuote held = quote("q1", BUYER, "GBP", "129.73");
        held.setComplete(false);

        assertThrows(BadRequestException.class, () -> checkout.checkout(BUYER, request("q1")));
        verify(orderService, never()).createFromCart(anyLong(), anyLong(), any(), any());
    }

    @Test
    void anotherBuyersQuoteIsNotFound() {
        quote("q1", BUYER, "GBP", "129.73");

        assertThrows(ResourceNotFoundException.class,
                () -> checkout.checkout(INTRUDER, request("q1")));
    }

    @Test
    void anAddressIsRequired() {
        quote("q1", BUYER, "GBP", "129.73");

        assertThrows(BadRequestException.class, () -> checkout.checkout(BUYER,
                new CheckoutRequests.Checkout("q1", null, PaymentMethod.CARD, null)));
    }

    /** Nothing is reserved for a checkout that was never going to complete. */
    @Test
    void nothingIsReservedBeforeTheQuoteIsValidated() {
        when(quotes.findLive("gone")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> checkout.checkout(BUYER, request("gone")));

        verify(orderService, never()).createFromCart(anyLong(), anyLong(), any(), any());
        verify(payments, never()).initiate(anyLong(), any(), any());
    }

    // ── Retry ────────────────────────────────────────────────────────────────

    @Test
    void aFailedPaymentCanBeRetriedWithoutRecreatingTheOrder() {
        Order existing = order("129.73", "GBP");
        existing.setPaymentStatus(PaymentStatus.FAILED);

        CheckoutResponses.PaymentIntent intent = checkout.retryPayment(BUYER, 42L,
                new CheckoutRequests.RetryPayment(PaymentMethod.BANK_TRANSFER));

        assertNotNull(intent);
        verify(orderService, never()).createFromCart(anyLong(), anyLong(), any(), any());
    }

    @Test
    void aPaidOrderCannotBePaidAgain() {
        Order existing = order("129.73", "GBP");
        existing.setPaymentStatus(PaymentStatus.PAID);

        assertThrows(BadRequestException.class, () -> checkout.retryPayment(BUYER, 42L,
                new CheckoutRequests.RetryPayment(PaymentMethod.CARD)));
    }

    @Test
    void anotherBuyersOrderCannotBeRetriedOrPolled() {
        order("129.73", "GBP");

        assertThrows(ResourceNotFoundException.class, () -> checkout.retryPayment(INTRUDER, 42L,
                new CheckoutRequests.RetryPayment(PaymentMethod.CARD)));
        assertThrows(ResourceNotFoundException.class, () -> checkout.status(INTRUDER, 42L));
    }

    // ── Status ───────────────────────────────────────────────────────────────

    @Test
    void statusReportsSettlementOnlyWhenItHasSettled() {
        Order existing = order("129.73", "GBP");

        CheckoutResponses.Status pending = checkout.status(BUYER, 42L);
        assertFalse(pending.settled(), "a browser coming back is not a payment");
        assertTrue(pending.retryable());

        existing.setPaymentStatus(PaymentStatus.PAID);
        CheckoutResponses.Status paid = checkout.status(BUYER, 42L);
        assertTrue(paid.settled());
        assertFalse(paid.retryable(), "a settled order must not be payable a second time");
    }
}
