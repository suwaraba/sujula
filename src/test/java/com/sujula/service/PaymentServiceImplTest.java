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
import com.sujula.service.payment.PaymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The payment state machine: which methods an order may use, what a
 * confirmation does to the order, and what the money rules refuse.
 */
class PaymentServiceImplTest {

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

        service = new PaymentServiceImpl(paymentRepository, orderRepository, statusHistoryRepository,
                vendorOrderRepository, userRepository, vendorRepository,
                emailService, notificationService, mock(AuditService.class),
                properties, noGateways);

        order = new Order();
        order.setId(7L);
        order.setOrderNumber("SJL-TEST0001");
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setDeliveryMode(DeliveryMode.HOME_DELIVERY);
        order.setCurrency("GMD");
        order.setTotal(new BigDecimal("1200.00"));

        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
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
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build()));
    }

    @Test
    void refusesCardWhenNoGatewayIsRegistered() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.CARD).build()));
        assertTrue(error.getMessage().contains("not available"));
    }

    // ── Starting a payment ───────────────────────────────────────────────────

    @Test
    void bankTransferGetsInstructionsAndAReferenceToQuote() {
        PaymentResponse payment = service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.BANK_TRANSFER).build());

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
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build());

        assertEquals(existing.getReference(), payment.getReference());
    }

    @Test
    void switchingMethodClearsTheAbandonedGatewayLeg() {
        Payment existing = pending(PaymentMethod.CARD);
        existing.setCheckoutUrl("https://provider.example/checkout/abc");
        existing.setClientSecret("secret_abc");
        existing.setTransactionId("pi_abc");
        when(paymentRepository.findByOrderIdForUpdate(7L)).thenReturn(Optional.of(existing));

        PaymentResponse payment = service.initiate(7L, null,
                InitiatePaymentRequest.builder().method(PaymentMethod.PAY_ON_DELIVERY).build());

        assertEquals(PaymentMethod.PAY_ON_DELIVERY, payment.getMethod());
        assertEquals(null, payment.getCheckoutUrl(), "a stale checkout must not survive a method switch");
        assertEquals(null, payment.getClientSecret());
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
        when(paymentRepository.findByTransactionIdForUpdate("pi_abc")).thenReturn(Optional.of(paid));

        PaymentResponse payment = service.handleCallback(PaymentCallbackRequest.builder()
                .transactionId("pi_abc").status(PaymentStatus.PAID).build());

        assertEquals(PaymentStatus.PAID, payment.getStatus());
        verify(statusHistoryRepository, never()).save(any(OrderStatusHistory.class));
    }

    @Test
    void aCallbackClaimingTheWrongAmountIsRejected() {
        Payment pending = pending(PaymentMethod.CARD);
        pending.setTransactionId("pi_abc");
        when(paymentRepository.findByTransactionIdForUpdate("pi_abc")).thenReturn(Optional.of(pending));

        assertThrows(BadRequestException.class, () -> service.handleCallback(PaymentCallbackRequest.builder()
                .transactionId("pi_abc").status(PaymentStatus.PAID).amount(new BigDecimal("1.00")).build()));
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
                InitiatePaymentRequest.builder().method(PaymentMethod.BANK_TRANSFER).build()));
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

    private static boolean available(List<PaymentMethodOption> options, PaymentMethod method) {
        return options.stream().filter(o -> o.getMethod() == method).findFirst().orElseThrow().isAvailable();
    }

    private static String reasonFor(List<PaymentMethodOption> options, PaymentMethod method) {
        return options.stream().filter(o -> o.getMethod() == method).findFirst().orElseThrow()
                .getUnavailableReason();
    }
}
