package com.sujula.service.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.model.order.RefundRequest;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.RefundRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.NotificationService;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.payment.PaymentGateway;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import com.sujula.service.security.StepUpVerifier;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RefundCoordinator.class, RefundTransactionService.class, MoneyLedger.class,
        RefundTransactionServiceTest.Money.class})
class RefundTransactionServiceTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class Money {
        @org.springframework.context.annotation.Bean
        CurrencyCatalogue currencyCatalogue() {
            return CurrencyCatalogue.of(new ReferenceDataProperties());
        }
    }

    @Autowired private RefundCoordinator coordinator;
    @Autowired private RefundTransactionService transactions;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderRepository orders;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private RefundRequestRepository refunds;
    @Autowired private VendorLedgerEntryRepository ledger;
    @Autowired private UserRepository users;
    @Autowired private VendorRepository vendors;
    @Autowired private MoneyLedger money;

    @MockitoBean private PaymentGateway gateway;
    @MockitoBean private StepUpVerifier stepUp;
    @MockitoBean private AuditService audit;
    @MockitoBean private NotificationService notifications;

    private User admin;

    @BeforeEach
    void setUp() {
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        when(gateway.refund(any(Payment.class), any(BigDecimal.class), anyString(), anyString()))
                .thenAnswer(call -> new PaymentGateway.GatewayRefund(
                        "re_" + call.getArgument(3, String.class),
                        call.getArgument(1, BigDecimal.class),
                        "provider-" + call.getArgument(3, String.class)));
        admin = users.save(User.builder()
                .firstName("Finance").lastName("Admin")
                .email("refund-admin-" + System.nanoTime() + "@sujula.test")
                .password("x").role(UserRole.ADMIN).enabled(true).build());
    }

    @AfterEach
    void cleanDatabase() {
        ledger.deleteAll();
        refunds.deleteAll();
        payments.deleteAll();
        vendorOrders.deleteAll();
        orders.deleteAll();
        vendors.deleteAll();
        users.deleteAll();
    }

    @Test
    void repeatedTwoDecimalRefundsAbsorbCommissionRemainderAndLeaveSiblingUntouched() {
        Scenario scenario = scenario("EUR", new BigDecimal("100.00"),
                new BigDecimal("40.00"), new BigDecimal("10.00"), BigDecimal.ONE);
        List<VendorLedgerEntry> siblingBefore = ledger.findByVendorOrderIdOrderByOccurredAtAsc(
                scenario.sibling().getId());

        RefundCoordinator.RefundResult first = refund(scenario, "33.33", "33.33", false);
        refund(scenario, "33.33", "33.33", false);
        RefundCoordinator.RefundResult last = refund(scenario, null, null, true);
        coordinator.resume(first.refundRequestId());

        List<VendorLedgerEntry> rows = ledger.findByVendorOrderIdOrderByOccurredAtAsc(
                scenario.slice().getId());
        assertEquals(new BigDecimal("100.00"), absoluteSum(rows, LedgerEntryType.REFUND));
        assertEquals(new BigDecimal("10.00"), absoluteSum(rows, LedgerEntryType.COMMISSION_REVERSAL));
        assertEquals(new BigDecimal("3.34"), rows.stream()
                .filter(row -> row.getType() == LedgerEntryType.COMMISSION_REVERSAL)
                .reduce((left, right) -> right).orElseThrow().getAmount());
        assertTrue(last.fullSliceRefund());

        Payment payment = payments.findById(scenario.payment().getId()).orElseThrow();
        Order order = orders.findById(scenario.order().getId()).orElseThrow();
        assertEquals(new BigDecimal("100.00"), payment.getAmountRefunded());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, order.getPaymentStatus());
        assertEquals(3, refunds.findByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                .filter(r -> r.getVendorOrder().getId().equals(scenario.slice().getId()))
                .filter(r -> r.getStatus() == RefundRequestStatus.COMPLETED).count());

        assertEquals(siblingBefore.size(), ledger.findByVendorOrderIdOrderByOccurredAtAsc(
                scenario.sibling().getId()).size());
        VendorOrder sibling = vendorOrders.findById(scenario.sibling().getId()).orElseThrow();
        assertEquals(new BigDecimal("40.00"), sibling.getTotalNative());
        assertEquals(VendorOrderStatus.DELIVERED, sibling.getStatus());
    }

    @Test
    void repeatedXofRefundsStayWholeAndFinalCommissionAbsorbsTheRemainder() {
        Scenario scenario = scenario("XOF", new BigDecimal("100"),
                new BigDecimal("50"), new BigDecimal("10"), BigDecimal.ONE);

        refund(scenario, "33", "33", false);
        refund(scenario, "33", "33", false);
        refund(scenario, null, null, true);

        List<VendorLedgerEntry> rows = ledger.findByVendorOrderIdOrderByOccurredAtAsc(
                scenario.slice().getId());
        assertEquals(0, new BigDecimal("100").compareTo(
                absoluteSum(rows, LedgerEntryType.REFUND)));
        assertEquals(0, new BigDecimal("10").compareTo(
                absoluteSum(rows, LedgerEntryType.COMMISSION_REVERSAL)));
        rows.stream().filter(row -> row.getType() == LedgerEntryType.REFUND
                        || row.getType() == LedgerEntryType.COMMISSION_REVERSAL)
                .forEach(row -> assertEquals(0,
                        row.getAmount().remainder(BigDecimal.ONE).signum()));
    }

    @Test
    void fullCumulativePaymentRefundSynchronizesPaymentOrderAndRequest() {
        Scenario scenario = scenario("EUR", new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ONE);

        RefundCoordinator.RefundResult result = refund(scenario, null, null, true);

        assertEquals(PaymentStatus.REFUNDED,
                payments.findById(scenario.payment().getId()).orElseThrow().getStatus());
        assertEquals(PaymentStatus.REFUNDED,
                orders.findById(scenario.order().getId()).orElseThrow().getPaymentStatus());
        RefundRequest operation = refunds.findById(result.refundRequestId()).orElseThrow();
        assertEquals(RefundRequestStatus.COMPLETED, operation.getStatus());
        assertTrue(operation.getCompletedAt() != null);
    }

    @Test
    void refundRequiresTheExactVendorOrderBelongingToThePayment() {
        Scenario paid = scenario("EUR", new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ONE);
        Scenario anotherOrder = scenario("EUR", new BigDecimal("50.00"),
                BigDecimal.ZERO, new BigDecimal("5.00"), BigDecimal.ONE);

        assertThrows(ResourceNotFoundException.class, () -> coordinator.execute(
                new RefundCoordinator.RefundCommand(admin, paid.payment().getId(),
                        anotherOrder.slice().getId(), new BigDecimal("10.00"),
                        new BigDecimal("10.00"), false, "Wrong slice", null, null)));

        verify(gateway, never()).refund(any(Payment.class), any(BigDecimal.class),
                anyString(), anyString());
        assertEquals(BigDecimal.ZERO.setScale(2), payments.findById(
                paid.payment().getId()).orElseThrow().getAmountRefunded());
    }

    @Test
    void crossCurrencyRefundUsesOnlyTheVendorOrdersFrozenFxSnapshot() {
        Scenario scenario = scenario("EUR", new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ONE);
        LocalDateTime frozenAt = LocalDateTime.now().minusDays(4).truncatedTo(ChronoUnit.MICROS);
        scenario.slice().setNativeCurrency("GMD");
        scenario.slice().setSubtotalNative(new BigDecimal("1000.00"));
        scenario.slice().setTotalNative(new BigDecimal("1000.00"));
        scenario.slice().setCommissionNative(new BigDecimal("100.00"));
        scenario.slice().setPayoutNative(new BigDecimal("900.00"));
        scenario.slice().setFx(FxSnapshot.published(
                "GMD", "EUR", new BigDecimal("0.10000000"), frozenAt));
        vendorOrders.save(scenario.slice());

        RefundCoordinator.RefundResult result = refund(scenario, "25.00", "250.00", false);

        assertEquals(new BigDecimal("25.00"), result.displayAmount());
        assertEquals(new BigDecimal("250.00"), result.nativeAmount());
        assertEquals(new BigDecimal("0.10000000"), result.fxRate());
        assertEquals(frozenAt.truncatedTo(ChronoUnit.MICROS), result.fxRateAt());
    }

    @Test
    void capsAndSameCurrencyConsistencyFailBeforeMoneyMoves() {
        Scenario same = scenario("EUR", new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ONE);

        assertTrue(assertThrows(BadRequestException.class,
                () -> refund(same, "10.00", "9.99", false))
                .getMessage().contains("must match"));

        Payment currentPayment = payments.findById(same.payment().getId()).orElseThrow();
        currentPayment.setAmount(new BigDecimal("200.00"));
        payments.save(currentPayment);
        assertTrue(assertThrows(BadRequestException.class,
                () -> refund(same, "100.01", "100.01", false))
                .getMessage().contains("display amount"));

        same.slice().setSubtotal(new BigDecimal("200.00"));
        same.slice().setTotal(new BigDecimal("200.00"));
        same.slice().setSubtotalNative(new BigDecimal("200.00"));
        same.slice().setTotalNative(new BigDecimal("200.00"));
        vendorOrders.save(same.slice());
        currentPayment = payments.findById(same.payment().getId()).orElseThrow();
        currentPayment.setAmount(new BigDecimal("100.00"));
        payments.save(currentPayment);
        assertTrue(assertThrows(BadRequestException.class,
                () -> refund(same, "150.00", "150.00", false))
                .getMessage().contains("payment received"));

        same.slice().setSubtotal(new BigDecimal("100.00"));
        same.slice().setTotal(new BigDecimal("100.00"));
        same.slice().setNativeCurrency("GMD");
        same.slice().setSubtotalNative(new BigDecimal("500.00"));
        same.slice().setTotalNative(new BigDecimal("500.00"));
        same.slice().setFx(FxSnapshot.published(
                "GMD", "EUR", new BigDecimal("0.10000000"), LocalDateTime.now()));
        vendorOrders.save(same.slice());
        currentPayment = payments.findById(same.payment().getId()).orElseThrow();
        currentPayment.setAmount(new BigDecimal("200.00"));
        payments.save(currentPayment);
        assertTrue(assertThrows(BadRequestException.class,
                () -> refund(same, "60.00", "600.00", false))
                .getMessage().contains("native amount"));

        Payment unchanged = payments.findById(same.payment().getId()).orElseThrow();
        assertEquals(BigDecimal.ZERO.setScale(2), unchanged.getAmountRefunded());
        assertEquals(0, ledger.findByVendorOrderIdOrderByOccurredAtAsc(same.slice().getId()).stream()
                .filter(row -> row.getType() == LedgerEntryType.REFUND).count());
    }

    @Test
    void providerFailureLeavesApprovedOperationButNoInternalCompletion() {
        Scenario scenario = scenario("EUR", new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ONE);
        when(gateway.refund(any(Payment.class), any(BigDecimal.class), anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThrows(IllegalStateException.class,
                () -> refund(scenario, "25.00", "25.00", false));

        RefundRequest operation = refunds.findByOrderIdOrderByCreatedAtDesc(
                scenario.order().getId()).getFirst();
        assertEquals(RefundRequestStatus.APPROVED, operation.getStatus());
        assertEquals(BigDecimal.ZERO.setScale(2), payments.findById(
                scenario.payment().getId()).orElseThrow().getAmountRefunded());
        assertEquals(0, ledger.findByVendorOrderIdOrderByOccurredAtAsc(scenario.slice().getId())
                .stream().filter(row -> row.getType() == LedgerEntryType.REFUND).count());
    }

    @Test
    void providerSuccessDatabaseFailureAndRetryFinalizeOneLedgerMutation() {
        Scenario scenario = scenario("EUR", new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ONE);
        when(audit.record(eq(AuditAction.PAYMENT_REFUNDED), eq("PAYMENT"),
                any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("finalization failed"))
                .thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> refund(scenario, "25.00", "25.00", false));
        RefundCoordinator.RefundResult completed = refund(
                scenario, "25.00", "25.00", false);

        ArgumentCaptor<String> providerKeys = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(2)).refund(any(Payment.class), eq(new BigDecimal("25.00")),
                eq("Refund test"), providerKeys.capture());
        assertEquals(providerKeys.getAllValues().get(0), providerKeys.getAllValues().get(1));
        assertEquals("refund-request-" + completed.refundRequestId(), providerKeys.getValue());

        List<VendorLedgerEntry> rows = ledger.findByVendorOrderIdOrderByOccurredAtAsc(
                scenario.slice().getId());
        assertEquals(1, rows.stream().filter(row -> row.getType() == LedgerEntryType.REFUND).count());
        assertEquals(1, rows.stream()
                .filter(row -> row.getType() == LedgerEntryType.COMMISSION_REVERSAL).count());
        assertEquals(new BigDecimal("25.00"), payments.findById(
                scenario.payment().getId()).orElseThrow().getAmountRefunded());
        assertEquals(RefundRequestStatus.COMPLETED,
                refunds.findById(completed.refundRequestId()).orElseThrow().getStatus());
    }

    private RefundCoordinator.RefundResult refund(Scenario scenario, String display,
                                                  String nativeAmount, boolean full) {
        return coordinator.execute(new RefundCoordinator.RefundCommand(
                admin, scenario.payment().getId(), scenario.slice().getId(),
                display == null ? null : new BigDecimal(display),
                nativeAmount == null ? null : new BigDecimal(nativeAmount),
                full, "Refund test", null, null));
    }

    private Scenario scenario(String currency, BigDecimal sliceTotal, BigDecimal siblingTotal,
                              BigDecimal commission, BigDecimal rate) {
        User buyer = users.save(User.builder().firstName("Buyer").lastName("One")
                .email("buyer-" + System.nanoTime() + "@sujula.test")
                .password("x").role(UserRole.CUSTOMER).enabled(true).build());
        User sellerA = users.save(User.builder().firstName("Seller").lastName("A")
                .email("seller-a-" + System.nanoTime() + "@sujula.test")
                .password("x").role(UserRole.VENDOR).enabled(true).build());
        User sellerB = users.save(User.builder().firstName("Seller").lastName("B")
                .email("seller-b-" + System.nanoTime() + "@sujula.test")
                .password("x").role(UserRole.VENDOR).enabled(true).build());
        Vendor vendorA = vendors.save(Vendor.builder().user(sellerA).storeName("A")
                .storeSlug("refund-a-" + System.nanoTime()).status(PartnerStatus.APPROVED)
                .settlementCurrency(currency).addressCountryCode("GM").build());
        Vendor vendorB = vendors.save(Vendor.builder().user(sellerB).storeName("B")
                .storeSlug("refund-b-" + System.nanoTime()).status(PartnerStatus.APPROVED)
                .settlementCurrency(currency).addressCountryCode("GM").build());
        BigDecimal paymentTotal = sliceTotal.add(siblingTotal);
        Order order = orders.save(Order.builder().orderNumber("SJL-RFD-" + System.nanoTime())
                .customer(buyer).status(OrderStatus.PROCESSING).paymentStatus(PaymentStatus.PAID)
                .currency(currency).subtotal(paymentTotal).total(paymentTotal)
                .paidAt(LocalDateTime.now()).build());
        VendorOrder slice = vendorOrders.save(VendorOrder.builder().order(order).vendor(vendorA)
                .status(VendorOrderStatus.DELIVERED).nativeCurrency(currency)
                .subtotalNative(sliceTotal).totalNative(sliceTotal)
                .commissionRate(new BigDecimal("10.00")).commissionNative(commission)
                .payoutNative(sliceTotal.subtract(commission))
                .subtotal(sliceTotal).total(sliceTotal)
                .fx(FxSnapshot.identity(currency, LocalDateTime.now())).build());
        VendorOrder sibling = vendorOrders.save(VendorOrder.builder().order(order).vendor(vendorB)
                .status(VendorOrderStatus.DELIVERED).nativeCurrency(currency)
                .subtotalNative(siblingTotal).totalNative(siblingTotal)
                .commissionRate(BigDecimal.ZERO).commissionNative(BigDecimal.ZERO)
                .payoutNative(siblingTotal).subtotal(siblingTotal).total(siblingTotal)
                .fx(FxSnapshot.identity(currency, LocalDateTime.now())).build());
        Payment payment = payments.save(Payment.builder().order(order)
                .reference("PAY-RFD-" + System.nanoTime()).status(PaymentStatus.PAID)
                .method(PaymentMethod.CARD).amount(paymentTotal).amountRefunded(BigDecimal.ZERO)
                .currency(currency).transactionId("pi_refund_test").paidAt(LocalDateTime.now())
                .build());
        money.postSale(slice);
        money.postSale(sibling);
        return new Scenario(order, payment, slice, sibling);
    }

    private BigDecimal absoluteSum(List<VendorLedgerEntry> rows, LedgerEntryType type) {
        return rows.stream().filter(row -> row.getType() == type)
                .map(VendorLedgerEntry::getAmount).map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record Scenario(Order order, Payment payment, VendorOrder slice,
                            VendorOrder sibling) {}
}
