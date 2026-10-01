package com.sujula.service;

import com.sujula.dto.request.payment.ConfirmPaymentRequest;
import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.dto.request.payment.PaymentCallbackRequest;
import com.sujula.dto.request.payment.RefundPaymentRequest;
import com.sujula.dto.response.payment.PaymentMethodOption;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderStatusHistory;
import com.sujula.model.order.Payment;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.order.OrderStatusHistoryRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.impl.PaymentServiceImpl;
import com.sujula.service.payment.PaymentGateway;
import com.sujula.service.payment.PaymentOperation;
import com.sujula.service.payment.PaymentProperties;
import com.sujula.service.payment.PaymentSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payment state machine: which methods an order may use, what a
 * confirmation does to the order, and what the money rules refuse.
 */
class PaymentServiceImplTest {

    private static final PaymentOperation OPERATION =
            PaymentOperation.of("user:1:payment.initiate:7", "payment-test-operation");

    private PaymentRepository paymentRepository;
    private OrderRepository orderRepository;
    private OrderStatusHistoryRepository statusHistoryRepository;
    private VendorOrderRepository vendorOrderRepository;
    private UserRepository userRepository;
    private PaymentProperties properties;
    private PaymentServiceImpl service;

    private Order order;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        orderRepository = mock(OrderRepository.class);
        statusHistoryRepository = mock(OrderStatusHistoryRepository.class);
        vendorOrderRepository = mock(VendorOrderRepository.class);
        userRepository = mock(UserRepository.class);
        VendorRepository vendorRepository = mock(VendorRepository.class);

        // Collectors: an admin, a driver, and a vendor who owns part of order 7.
        when(userRepository.findById(1L)).thenReturn(Optional.of(staff(1L, UserRole.ADMIN)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(staff(2L, UserRole.DELIVERY)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(staff(3L, UserRole.VENDOR)));
        Vendor vendorProfile = Vendor.builder().id(50L).storeName("Kombo").build();
        when(vendorRepository.findByUserId(3L)).thenReturn(Optional.of(vendorProfile));
        when(vendorOrderRepository.existsByOrderIdAndVendorId(7L, 50L)).thenReturn(true);
        EmailService emailService = mock(EmailService.class);
        NotificationService notificationService = mock(NotificationService.class);

        properties = new PaymentProperties();
        properties.getBankTransfer().setBankName("Trust Bank");
        properties.getBankTransfer().setAccountName("Sujula Ltd");
        properties.getBankTransfer().setAccountNumber("0123456789");

        ObjectProvider<PaymentGateway> noGateways = mock(ObjectProvider.class);
        when(noGateways.stream()).thenAnswer(invocation -> java.util.stream.Stream.empty());

        PaymentSettlementService settlements = new PaymentSettlementService(
                orderRepository, statusHistoryRepository, emailService, notificationService);
        service = new PaymentServiceImpl(paymentRepository, orderRepository,
                vendorOrderRepository, userRepository, vendorRepository,
                notificationService, mock(AuditService.class),
                properties, noGateways, settlements);

        order = new Order();
        order.setId(7L);
        order.setOrderNumber("SJL-TEST0001");
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setDeliveryMode(DeliveryMode.HOME_DELIVERY);
        order.setCurrency("GMD");
        order.setTotal(new BigDecimal("1200.00"));

        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForPaymentUpdate(7L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.empty());
        when(paymentRepository.existsByReference(any())).thenReturn(false);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ── Choosing a method ────────────────────────────────────────────────────

    @Test
    void offersOnlyTheMethodsThisOrderCanActuallyUse() {
        List<PaymentMethodOption> options = service.availableMethods(7L, null);

        assertTrue(available(options, PaymentMethod.PAY_ON_DELIVERY),
                "a home delivery can be paid at the door");
        assertTrue(available(options, PaymentMethod.BANK_TRANSFER),
                "bank details are configured, so transfer is offered");
        // Card needs a gateway adapter, and none is registered in this test.
        assertTrue(!available(options, PaymentMethod.CARD));
        assertTrue(!available(options, PaymentMethod.PAY_AT_PICKUP),
                "this order is not going to a pickup point");
        assertNotNull(reasonFor(options, PaymentMethod.CARD), "an unavailable method must explain itself");
    }

    @Test
    void refusesAnInPersonMethodThatDoesNotMatchHowTheOrderIsFulfilled() {
        order.setDeliveryMode(DeliveryMode.PICKUP_POINT);

        assertThrows(BadRequestException.class, () -> service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build(), OPERATION));
    }

    /** The same service, but with one gateway bean registered. */
    @SuppressWarnings("unchecked")
    private PaymentServiceImpl serviceWith(PaymentGateway gateway) {
        ObjectProvider<PaymentGateway> gateways = mock(ObjectProvider.class);
        when(gateways.stream()).thenAnswer(invocation -> java.util.stream.Stream.of(gateway));
        EmailService email = mock(EmailService.class);
        NotificationService notifications = mock(NotificationService.class);
        PaymentSettlementService settlements = new PaymentSettlementService(
                orderRepository, statusHistoryRepository, email, notifications);
        return new PaymentServiceImpl(paymentRepository, orderRepository,
                mock(VendorOrderRepository.class), userRepository, mock(VendorRepository.class),
                notifications, mock(AuditService.class), properties, gateways, settlements);
    }

    @Test
    void aGatewayThatTakesTheMoneyInlineLeavesThePaymentPaid() {
        // The mock gateway used before a provider is integrated behaves this way,
        // and so does a stored card charged synchronously: there is no callback
        // coming, so a payment left PENDING here would never be settled at all.
        PaymentServiceImpl withGateway = serviceWith(new ImmediateGateway());

        PaymentResponse payment = withGateway.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.CARD).build(), OPERATION);

        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertNotNull(payment.getPaidAt());
        assertEquals("IMMEDIATE-TX", payment.getCollectionReference());
    }

    @Test
    void aGatewayWithAHostedPageLeavesThePaymentPending() {
        PaymentServiceImpl withGateway = serviceWith(new HostedPageGateway());

        PaymentResponse payment = withGateway.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.CARD).build(), OPERATION);

        // The buyer has not paid yet — they have been handed a page to pay on.
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals("https://pay.example/checkout/HOSTED-TX", payment.getCheckoutUrl());
    }

    private static class ImmediateGateway implements PaymentGateway {
        @Override public boolean supports(PaymentMethod method) { return method.requiresGateway(); }
        @Override public String name() { return "immediate"; }
        @Override public boolean settlesImmediately() { return true; }
        @Override public void retireCheckout(String transactionId) { }
        @Override public GatewayCheckout createCheckout(Payment payment, String returnUrl, String operationKey) {
            return new GatewayCheckout("IMMEDIATE-TX", null, null, "{}");
        }
    }

    private static class HostedPageGateway implements PaymentGateway {
        @Override public boolean supports(PaymentMethod method) { return method.requiresGateway(); }
        @Override public String name() { return "hosted"; }
        @Override public void retireCheckout(String transactionId) { }
        @Override public GatewayCheckout createCheckout(Payment payment, String returnUrl, String operationKey) {
            return new GatewayCheckout("HOSTED-TX", "https://pay.example/checkout/HOSTED-TX", null, "{}");
        }
    }

    @Test
    void refusesCardWhenNoGatewayIsRegistered() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.CARD).build(), OPERATION));
        assertTrue(error.getMessage().contains("not available"));
    }

    // ── Starting a payment ───────────────────────────────────────────────────

    @Test
    void bankTransferGetsInstructionsAndAReferenceToQuote() {
        PaymentResponse payment = service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.BANK_TRANSFER).build(), OPERATION);

        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(new BigDecimal("1200.00"), payment.getAmount());
        assertEquals("GMD", payment.getCurrency());
        assertTrue(payment.getInstructions().contains(payment.getReference()),
                "the buyer must be told which reference to quote");
        assertTrue(payment.getInstructions().contains("0123456789"));
        assertTrue(payment.isActionRequired());

        // The order carries the flag, so nothing has to join to payments to read it.
        assertEquals(PaymentStatus.PENDING, order.getPaymentStatus());
        assertEquals(PaymentMethod.BANK_TRANSFER, order.getPaymentMethod());
    }

    @Test
    void reAskingForTheSameMethodReturnsTheSamePaymentRatherThanASecondOne() {
        Payment existing = pending(PaymentMethod.PAY_ON_DELIVERY);
        existing.setInstructions("Have 1200.00 GMD ready for the driver.");
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        PaymentResponse payment = service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build(), OPERATION);

        assertEquals(existing.getReference(), payment.getReference());
    }

    @Test
    void pendingCardWithAUsableCheckoutIsReusedWithoutProviderCalls() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        PaymentServiceImpl withGateway = serviceWith(gateway);

        Payment existing = pending(PaymentMethod.CARD);
        existing.setCheckoutUrl("https://provider.example/checkout/cs_OLD");
        existing.setTransactionId("cs_OLD");
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        PaymentResponse payment = withGateway.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.CARD).build(), OPERATION);

        assertEquals("cs_OLD", payment.getTransactionId());
        assertEquals("https://provider.example/checkout/cs_OLD", payment.getCheckoutUrl());
        verify(gateway, never()).retireCheckout(anyString());
        verify(gateway, never()).createCheckout(any(Payment.class), any(), any());
    }

    @Test
    void cardReplacementRetiresOldCheckoutBeforeCreatingNewOne() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        when(gateway.createCheckout(any(Payment.class), eq("https://shop.example/return"),
                eq(OPERATION.providerKey())))
                .thenAnswer(invocation -> {
                    Payment replacing = invocation.getArgument(0);
                    assertEquals(PaymentStatus.PENDING, replacing.getStatus());
                    assertEquals(PaymentMethod.CARD, replacing.getMethod());
                    assertNull(replacing.getTransactionId());
                    assertNull(replacing.getCheckoutUrl());
                    assertNull(replacing.getClientSecret());
                    assertNull(replacing.getGatewayResponse());
                    return new PaymentGateway.GatewayCheckout(
                            "cs_NEW", "https://provider.example/checkout/cs_NEW", null, "new-response");
                });
        PaymentServiceImpl withGateway = serviceWith(gateway);

        Payment existing = providerLeg(PaymentStatus.FAILED);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        PaymentResponse payment = withGateway.initiate(7L, null, InitiatePaymentRequest.builder()
                .method(PaymentMethod.CARD)
                .returnUrl("https://shop.example/return")
                .build(), OPERATION);

        InOrder ordered = inOrder(gateway);
        ordered.verify(gateway).retireCheckout("cs_OLD");
        ordered.verify(gateway).createCheckout(
                same(existing), eq("https://shop.example/return"), eq(OPERATION.providerKey()));
        assertEquals("cs_NEW", payment.getTransactionId());
        assertEquals("https://provider.example/checkout/cs_NEW", payment.getCheckoutUrl());
        assertEquals("new-response", existing.getGatewayResponse());
    }

    @Test
    void switchingCardToOfflineRetiresItBeforeClearingProviderEvidence() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        PaymentServiceImpl withGateway = serviceWith(gateway);

        Payment existing = providerLeg(PaymentStatus.PENDING);
        doAnswer(invocation -> {
            assertEquals(PaymentMethod.CARD, existing.getMethod());
            assertEquals(PaymentStatus.PENDING, existing.getStatus());
            assertEquals("cs_OLD", existing.getTransactionId());
            assertEquals("old-client-secret", existing.getClientSecret());
            assertEquals("old-response", existing.getGatewayResponse());
            return null;
        }).when(gateway).retireCheckout("cs_OLD");
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        PaymentResponse payment = withGateway.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build(), OPERATION);

        assertEquals(PaymentMethod.PAY_ON_DELIVERY, payment.getMethod());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertNull(payment.getTransactionId());
        assertNull(payment.getCheckoutUrl(), "a stale checkout must not survive a method switch");
        assertNull(payment.getClientSecret());
        assertNull(existing.getGatewayResponse());
        assertNotNull(payment.getInstructions());
        verify(gateway).retireCheckout("cs_OLD");
        verify(gateway, never()).createCheckout(any(Payment.class), any(), any());
    }

    @Test
    void retirementFailureLeavesOldCardLegUntouchedAndCreatesNothing() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        doThrow(new IllegalStateException("provider outcome unknown"))
                .when(gateway).retireCheckout("cs_OLD");
        PaymentServiceImpl withGateway = serviceWith(gateway);

        Payment existing = providerLeg(PaymentStatus.PENDING);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> withGateway.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build(), OPERATION));

        assertProviderLegUnchanged(existing, PaymentStatus.PENDING);
        verify(gateway, never()).createCheckout(any(Payment.class), any(), any());
    }

    @Test
    void providerEvidenceWithoutAnIdentifierFailsClosed() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        PaymentServiceImpl withGateway = serviceWith(gateway);

        Payment existing = pending(PaymentMethod.CARD);
        existing.setCheckoutUrl("https://provider.example/checkout/unknown");
        existing.setClientSecret("old-client-secret");
        existing.setGatewayResponse("old-response");
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class, () -> withGateway.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build(), OPERATION));

        assertEquals(PaymentMethod.CARD, existing.getMethod());
        assertEquals("https://provider.example/checkout/unknown", existing.getCheckoutUrl());
        assertEquals("old-client-secret", existing.getClientSecret());
        assertEquals("old-response", existing.getGatewayResponse());
        verify(gateway, never()).retireCheckout(anyString());
        verify(gateway, never()).createCheckout(any(Payment.class), any(), any());
    }

    @Test
    void missingOldGatewayFailsClosedBeforeAnOfflineSwitch() {
        Payment existing = providerLeg(PaymentStatus.PENDING);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class, () -> service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build(), OPERATION));

        assertProviderLegUnchanged(existing, PaymentStatus.PENDING);
    }

    @Test
    void failedReplacementCreationRestoresOldEvidenceAndCanBeRetried() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        when(gateway.createCheckout(any(Payment.class), any(), eq(OPERATION.providerKey())))
                .thenThrow(new IllegalStateException("create response lost"))
                .thenReturn(new PaymentGateway.GatewayCheckout(
                        "cs_NEW", "https://provider.example/checkout/cs_NEW", null, "new-response"));
        PaymentServiceImpl withGateway = serviceWith(gateway);

        Payment existing = providerLeg(PaymentStatus.FAILED);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));
        InitiatePaymentRequest request = InitiatePaymentRequest.builder().method(PaymentMethod.CARD).build();

        assertThrows(IllegalStateException.class,
                () -> withGateway.initiate(7L, null, request, OPERATION));
        assertProviderLegUnchanged(existing, PaymentStatus.FAILED);

        PaymentResponse retried = withGateway.initiate(7L, null, request, OPERATION);

        assertEquals(PaymentStatus.PENDING, retried.getStatus());
        assertEquals("cs_NEW", retried.getTransactionId());
        verify(gateway, times(2)).retireCheckout("cs_OLD");
        verify(gateway, times(2)).createCheckout(
                same(existing), any(), eq(OPERATION.providerKey()));
    }

    @Test
    void providerSuccessFollowedByLocalRollbackRetriesTheSameProviderOperation() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        List<String> references = new ArrayList<>();
        List<String> providerKeys = new ArrayList<>();
        List<String> returnUrls = new ArrayList<>();
        when(gateway.createCheckout(any(Payment.class), any(), any())).thenAnswer(call -> {
            references.add(call.<Payment>getArgument(0).getReference());
            returnUrls.add(call.getArgument(1));
            providerKeys.add(call.getArgument(2));
            return new PaymentGateway.GatewayCheckout(
                    "cs_STABLE", "https://provider.example/checkout/cs_STABLE", null, "stable-response");
        });
        AtomicInteger saves = new AtomicInteger();
        when(paymentRepository.save(any(Payment.class))).thenAnswer(call -> {
            if (saves.incrementAndGet() == 2) {
                throw new IllegalStateException("local commit path failed after provider success");
            }
            return call.getArgument(0);
        });
        PaymentServiceImpl withGateway = serviceWith(gateway);
        InitiatePaymentRequest request = InitiatePaymentRequest.builder()
                .method(PaymentMethod.CARD)
                .returnUrl("https://shop.example/return")
                .build();

        assertThrows(IllegalStateException.class,
                () -> withGateway.initiate(7L, null, request, OPERATION));
        PaymentResponse retried = withGateway.initiate(7L, null, request, OPERATION);

        assertEquals("cs_STABLE", retried.getTransactionId());
        assertEquals(List.of(OPERATION.paymentReference(), OPERATION.paymentReference()), references);
        assertEquals(List.of(OPERATION.providerKey(), OPERATION.providerKey()), providerKeys);
        assertEquals(List.of("https://shop.example/return", "https://shop.example/return"), returnUrls);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class,
            names = {"CARD", "PAYPAL", "BANK_TRANSFER", "PAY_ON_DELIVERY"})
    void authorizedPaymentCannotOpenAnyGatewayOrOfflineLeg(PaymentMethod requestedMethod) {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.supports(any(PaymentMethod.class))).thenReturn(true);
        PaymentServiceImpl withGateway = serviceWith(gateway);

        Payment existing = providerLeg(PaymentStatus.AUTHORIZED);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class, () -> withGateway.initiate(7L, null,
                InitiatePaymentRequest.builder().method(requestedMethod).build(), OPERATION));

        assertProviderLegUnchanged(existing, PaymentStatus.AUTHORIZED);
        verify(gateway, never()).retireCheckout(anyString());
        verify(gateway, never()).createCheckout(any(Payment.class), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"PAID", "CANCELLED", "PARTIALLY_REFUNDED", "REFUNDED"})
    void paidAndClosedPaymentsStillCannotBeReopened(PaymentStatus status) {
        Payment existing = pending(PaymentMethod.BANK_TRANSFER);
        existing.setStatus(status);
        existing.setInstructions("old instructions");
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class, () -> service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.BANK_TRANSFER).build(), OPERATION));

        assertEquals(status, existing.getStatus());
        assertEquals(PaymentMethod.BANK_TRANSFER, existing.getMethod());
        assertEquals("old instructions", existing.getInstructions());
    }

    // ── Confirming money ─────────────────────────────────────────────────────

    @Test
    void cashTakenAtTheDoorSettlesThePaymentAndConfirmsTheOrder() {
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending(PaymentMethod.PAY_ON_DELIVERY)));

        PaymentResponse payment = service.collectInPerson(7L,
                ConfirmPaymentRequest.builder().collectionReference("RCPT-99").build(), 1L);

        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertNotNull(payment.getPaidAt());
        assertEquals("RCPT-99", payment.getCollectionReference());

        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        assertNotNull(order.getPaidAt());
        verify(statusHistoryRepository).save(any(OrderStatusHistory.class));
    }

    @Test
    void settlingDoesNotDragADeliveredOrderBackToConfirmed() {
        order.setStatus(OrderStatus.DELIVERED);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending(PaymentMethod.PAY_ON_DELIVERY)));

        service.collectInPerson(7L, null, 1L);

        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        verify(statusHistoryRepository, never()).save(any(OrderStatusHistory.class));
    }

    @Test
    void refusesToSettleOnLessMoneyThanIsDue() {
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending(PaymentMethod.PAY_ON_DELIVERY)));

        assertThrows(BadRequestException.class, () -> service.collectInPerson(7L,
                ConfirmPaymentRequest.builder().amountReceived(new BigDecimal("900.00")).build(), 1L));
        assertEquals(PaymentStatus.PENDING, order.getPaymentStatus());
    }

    @Test
    void refusesToCollectCashForAnOnlineOrder() {
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending(PaymentMethod.CARD)));

        assertThrows(BadRequestException.class, () -> service.collectInPerson(7L, null, 1L));
    }

    @Test
    void confirmTransferOnlyAppliesToATransferPayment() {
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending(PaymentMethod.PAY_ON_DELIVERY)));

        assertThrows(BadRequestException.class, () -> service.confirmTransfer(7L, null, null));
    }

    @Test
    void aRepeatedProviderCallbackIsNotAppliedTwice() {
        Payment paid = pending(PaymentMethod.CARD);
        paid.setTransactionId("pi_abc");
        paid.setStatus(PaymentStatus.PAID);
        when(paymentRepository.findOrderIdByTransactionId("pi_abc")).thenReturn(Optional.of(7L));
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(paid));

        PaymentResponse payment = service.handleCallback(PaymentCallbackRequest.builder()
                .transactionId("pi_abc").status(PaymentStatus.PAID).build());

        assertEquals(PaymentStatus.PAID, payment.getStatus());
        verify(statusHistoryRepository, never()).save(any(OrderStatusHistory.class));
    }

    @Test
    void aCallbackClaimingTheWrongAmountIsRejected() {
        Payment pending = pending(PaymentMethod.CARD);
        pending.setTransactionId("pi_abc");
        when(paymentRepository.findOrderIdByTransactionId("pi_abc")).thenReturn(Optional.of(7L));
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending));

        assertThrows(BadRequestException.class, () -> service.handleCallback(PaymentCallbackRequest.builder()
                .transactionId("pi_abc").status(PaymentStatus.PAID).amount(new BigDecimal("1.00")).build()));
    }

    @Test
    void aValidPaidCallbackLocksOrderBeforePaymentAndUsesCanonicalSettlement() {
        Payment pending = pending(PaymentMethod.CARD);
        pending.setTransactionId("pi_paid");
        when(paymentRepository.findOrderIdByTransactionId("pi_paid")).thenReturn(Optional.of(7L));
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending));

        PaymentResponse response = service.handleCallback(PaymentCallbackRequest.builder()
                .transactionId("pi_paid").status(PaymentStatus.PAID)
                .amount(new BigDecimal("1200.00")).build());

        assertEquals(PaymentStatus.PAID, response.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(statusHistoryRepository, times(1)).save(any(OrderStatusHistory.class));
        InOrder locks = inOrder(orderRepository, paymentRepository);
        locks.verify(orderRepository).findByIdForPaymentUpdate(7L);
        locks.verify(paymentRepository).findByOrderIdForUpdate(7L);
    }

    @Test
    void aPaidPaymentCannotRegressOnALateFailureCallback() {
        Payment paid = pending(PaymentMethod.CARD);
        paid.setTransactionId("pi_late_failure");
        paid.setStatus(PaymentStatus.PAID);
        when(paymentRepository.findOrderIdByTransactionId("pi_late_failure"))
                .thenReturn(Optional.of(7L));
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(paid));

        PaymentResponse response = service.handleCallback(PaymentCallbackRequest.builder()
                .transactionId("pi_late_failure").status(PaymentStatus.FAILED).build());

        assertEquals(PaymentStatus.PAID, response.getStatus());
        verify(statusHistoryRepository, never()).save(any(OrderStatusHistory.class));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"CANCELLED", "PARTIALLY_REFUNDED", "REFUNDED"})
    void terminalPaymentsCannotBeReopenedBySuccess(PaymentStatus current) {
        Payment terminal = pending(PaymentMethod.CARD);
        terminal.setTransactionId("pi_terminal");
        terminal.setStatus(current);
        when(paymentRepository.findOrderIdByTransactionId("pi_terminal"))
                .thenReturn(Optional.of(7L));
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(terminal));

        PaymentResponse response = service.handleCallback(PaymentCallbackRequest.builder()
                .transactionId("pi_terminal").status(PaymentStatus.PAID).build());

        assertEquals(current, response.getStatus());
        verify(statusHistoryRepository, never()).save(any(OrderStatusHistory.class));
    }

    @Test
    void aRefundCallbackCannotRefundMoneyThatWasNeverSettled() {
        Payment pending = pending(PaymentMethod.CARD);
        pending.setTransactionId("pi_unsettled_refund");
        when(paymentRepository.findOrderIdByTransactionId("pi_unsettled_refund"))
                .thenReturn(Optional.of(7L));
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending));

        PaymentResponse response = service.handleCallback(PaymentCallbackRequest.builder()
                .transactionId("pi_unsettled_refund").status(PaymentStatus.REFUNDED)
                .amount(new BigDecimal("1200.00")).build());

        assertEquals(PaymentStatus.PENDING, response.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getAmountRefunded()));
    }

    // ── Who may take the money ───────────────────────────────────────────────

    @Test
    void aDriverCannotSettleACounterSale() {
        order.setDeliveryMode(DeliveryMode.VENDOR_PICKUP);
        when(paymentRepository.findByOrderIdForUpdate(7L))
                .thenReturn(Optional.of(pending(PaymentMethod.CASH_IN_STORE)));

        assertThrows(AccessDeniedException.class, () -> service.collectInPerson(7L, null, 2L));
    }

    @Test
    void aSellerCannotSettleAnOrderThatIsNothingToDoWithThem() {
        order.setDeliveryMode(DeliveryMode.VENDOR_PICKUP);
        when(paymentRepository.findByOrderIdForUpdate(7L))
                .thenReturn(Optional.of(pending(PaymentMethod.CASH_IN_STORE)));
        when(vendorOrderRepository.existsByOrderIdAndVendorId(7L, 50L)).thenReturn(false);

        // Without this check any seller could settle any order in the platform —
        // and read the buyer's total on the way out.
        assertThrows(AccessDeniedException.class, () -> service.collectInPerson(7L, null, 3L));
    }

    @Test
    void aSellerCanSettleTheirOwnCounterSale() {
        order.setDeliveryMode(DeliveryMode.VENDOR_PICKUP);
        when(paymentRepository.findByOrderIdForUpdate(7L))
                .thenReturn(Optional.of(pending(PaymentMethod.CASH_IN_STORE)));

        assertEquals(PaymentStatus.PAID, service.collectInPerson(7L, null, 3L).getStatus());
    }

    @Test
    void aSellerCannotTakeCashThatBelongsToTheDriver() {
        when(paymentRepository.findByOrderIdForUpdate(7L))
                .thenReturn(Optional.of(pending(PaymentMethod.PAY_ON_DELIVERY)));

        assertThrows(AccessDeniedException.class, () -> service.collectInPerson(7L, null, 3L));
    }

    // ── Refunds ──────────────────────────────────────────────────────────────

    @Test
    void aPartialRefundLeavesTheRestSettled() {
        Payment paid = pending(PaymentMethod.CARD);
        paid.setStatus(PaymentStatus.PAID);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(paid));

        PaymentResponse payment = service.refund(7L,
                RefundPaymentRequest.builder().amount(new BigDecimal("200.00")).reason("Damaged item").build(), null);

        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
        assertEquals(new BigDecimal("200.00"), payment.getAmountRefunded());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, order.getPaymentStatus());
    }

    @Test
    void refundingEverythingClosesThePayment() {
        Payment paid = pending(PaymentMethod.CARD);
        paid.setStatus(PaymentStatus.PAID);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(paid));

        PaymentResponse payment = service.refund(7L, null, null);

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(new BigDecimal("1200.00"), payment.getAmountRefunded());
    }

    @Test
    void refuseARefundBiggerThanWhatIsLeft() {
        Payment paid = pending(PaymentMethod.CARD);
        paid.setStatus(PaymentStatus.PAID);
        paid.setAmountRefunded(new BigDecimal("1000.00"));
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(paid));

        assertThrows(BadRequestException.class, () -> service.refund(7L,
                RefundPaymentRequest.builder().amount(new BigDecimal("500.00")).build(), null));
    }

    @Test
    void anUnpaidOrderCannotBeRefundedAndAPaidOneCannotBeCancelled() {
        Payment pending = pending(PaymentMethod.BANK_TRANSFER);
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(pending));
        assertThrows(BadRequestException.class, () -> service.refund(7L, null, null));

        pending.setStatus(PaymentStatus.PAID);
        assertThrows(BadRequestException.class, () -> service.cancel(7L, "changed my mind"));
    }

    @Test
    void aPaidOrderRefusesAFreshPayment() {
        order.setPaymentStatus(PaymentStatus.PAID);

        assertThrows(BadRequestException.class, () -> service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.BANK_TRANSFER).build(), OPERATION));
    }

    @Test
    void ordersOfOtherBuyersAreNotReadable() {
        assertThrows(BadRequestException.class, () -> service.findForOrder(7L, 99L));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static User staff(Long id, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setEmail(role.name().toLowerCase() + "@sujula.gm");
        user.setFirstName(role.name());
        user.setLastName("Staff");
        user.setRole(role);
        return user;
    }

    private Payment pending(PaymentMethod method) {
        return Payment.builder()
                .id(3L)
                .order(order)
                .reference("PAY-TEST00001")
                .status(PaymentStatus.PENDING)
                .method(method)
                .amount(new BigDecimal("1200.00"))
                .amountRefunded(BigDecimal.ZERO)
                .currency("GMD")
                .build();
    }

    private Payment providerLeg(PaymentStatus status) {
        Payment payment = pending(PaymentMethod.CARD);
        payment.setStatus(status);
        payment.setTransactionId("cs_OLD");
        payment.setCheckoutUrl("https://provider.example/checkout/cs_OLD");
        payment.setClientSecret("old-client-secret");
        payment.setGatewayResponse("old-response");
        payment.setFailureReason("old-failure");
        return payment;
    }

    private static void assertProviderLegUnchanged(Payment payment, PaymentStatus status) {
        assertEquals(PaymentMethod.CARD, payment.getMethod());
        assertEquals(status, payment.getStatus());
        assertEquals("cs_OLD", payment.getTransactionId());
        assertEquals("https://provider.example/checkout/cs_OLD", payment.getCheckoutUrl());
        assertEquals("old-client-secret", payment.getClientSecret());
        assertEquals("old-response", payment.getGatewayResponse());
        assertEquals("old-failure", payment.getFailureReason());
    }

    private static boolean available(List<PaymentMethodOption> options, PaymentMethod method) {
        return options.stream().filter(o -> o.getMethod() == method).findFirst().orElseThrow().isAvailable();
    }

    private static String reasonFor(List<PaymentMethodOption> options, PaymentMethod method) {
        return options.stream().filter(o -> o.getMethod() == method).findFirst().orElseThrow()
                .getUnavailableReason();
    }
}
