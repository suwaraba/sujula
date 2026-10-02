package com.sujula.service.buyerorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sujula.dto.request.buyerorder.BuyerOrderRequests;
import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.aftersales.DisputeRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.buyerorder.impl.BuyerOrderServiceImpl;
import com.sujula.service.aftersales.impl.DisputeServiceImpl;
import com.sujula.service.invoice.InvoiceService;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import com.sujula.service.shipment.CustodyChain;
import com.sujula.service.shipment.HomeShipmentCoordinator;

import jakarta.persistence.EntityManager;

/** Real relational proof of buyer receipt serialization (H2 in MySQL mode). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({BuyerOrderServiceImpl.class, DisputeServiceImpl.class, MoneyLedger.class,
        CustodyChain.class, HomeShipmentCoordinator.class,
        BuyerReceiptConfirmationConcurrencyIntegrationTest.Money.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BuyerReceiptConfirmationConcurrencyIntegrationTest {

    private static final AtomicInteger CASES = new AtomicInteger();

    @TestConfiguration
    static class Money {
        @Bean
        CurrencyCatalogue currencyCatalogue() {
            return CurrencyCatalogue.of(new ReferenceDataProperties());
        }
    }

    @Autowired private BuyerOrderServiceImpl service;
    @Autowired private DisputeServiceImpl disputes;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private DisputeRepository disputeRows;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private ShipmentRepository shipments;
    @Autowired private CustodyEventRepository custodyEvents;
    @Autowired private VendorLedgerEntryRepository ledger;
    @Autowired private UserRepository users;
    @Autowired private VendorRepository vendors;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManager entityManager;

    @MockitoBean private InvoiceService invoices;

    private Long buyerId;
    private Long orderId;
    private Long confirmedSliceId;
    private Long siblingSliceId;

    @BeforeEach
    void setUp() {
        String suffix = Integer.toString(CASES.incrementAndGet());
        transactions.executeWithoutResult(ignored -> {
            User buyer = users.save(user("receipt-race-buyer-" + suffix + "@example.es",
                    UserRole.CUSTOMER));
            buyerId = buyer.getId();
            Vendor first = vendor("receipt-race-first-" + suffix + "@sujula.gm",
                    "Receipt Race First", "receipt-race-first-" + suffix);
            Vendor second = vendor("receipt-race-second-" + suffix + "@sujula.gm",
                    "Receipt Race Second", "receipt-race-second-" + suffix);

            Order order = new Order();
            order.setOrderNumber("SJL-RECEIPT-RACE-" + suffix);
            order.setCustomer(buyer);
            order.setStatus(OrderStatus.SHIPPED);
            order.setPaymentStatus(PaymentStatus.PAID);
            order.setSubtotal(new BigDecimal("150.00"));
            order.setTotal(new BigDecimal("150.00"));
            order.setCurrency("GMD");
            order = orders.save(order);
            orderId = order.getId();
            payments.save(Payment.builder()
                    .order(order).reference("PAY-RECEIPT-RACE-" + suffix)
                    .status(PaymentStatus.PAID).method(PaymentMethod.CARD)
                    .amount(order.getTotal()).currency(order.getCurrency()).build());

            VendorOrder firstSlice = vendorOrders.save(slice(order, first,
                    VendorOrderStatus.SHIPPED, "100.00", "10.00"));
            confirmedSliceId = firstSlice.getId();
            VendorOrder secondSlice = vendorOrders.save(slice(order, second,
                    VendorOrderStatus.PREPARING, "50.00", "5.00"));
            siblingSliceId = secondSlice.getId();

            Shipment shipment = shipments.save(Shipment.builder()
                    .reference("SHP-RECEIPT-RACE-" + suffix).vendorOrder(firstSlice)
                    .recipientName("Race Recipient").recipientPhone("+2203000000")
                    .destinationCountry("GM").parcelCount(1).build());
            custodyEvents.save(CustodyEvent.builder()
                    .shipment(shipment).type(CustodyEventType.RELEASED)
                    .recordedByUserId(buyerId).clientEventId("receipt-race-release-" + suffix)
                    .occurredAt(LocalDateTime.now().minusMinutes(1))
                    .withinGeofence(true).build());
            entityManager.createNativeQuery("UPDATE shipments SET status = 'DELIVERED' WHERE id = :id")
                    .setParameter("id", shipment.getId()).executeUpdate();
            entityManager.flush();
            entityManager.clear();
        });
    }

    @Test
    void simultaneousConfirmationsCommitOneFinancialTransitionAndLeaveSiblingUntouched()
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> futures = List.of(
                    pool.submit(() -> confirm(ready, start, succeeded)),
                    pool.submit(() -> confirm(ready, start, succeeded)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        Snapshot snapshot = transactions.execute(ignored -> {
            VendorOrder confirmed = vendorOrders.findById(confirmedSliceId).orElseThrow();
            VendorOrder sibling = vendorOrders.findById(siblingSliceId).orElseThrow();
            List<VendorLedgerEntry> entries =
                    ledger.findByVendorOrderIdOrderByOccurredAtAsc(confirmedSliceId);
            return new Snapshot(confirmed.getReceiptConfirmedAt(), confirmed.getEscrowReleasedAt(),
                    sibling.getReceiptConfirmedAt(), sibling.getEscrowReleasedAt(),
                    entries.stream().filter(e -> e.getType() == LedgerEntryType.SALE).count(),
                    entries.stream().filter(e -> e.getType() == LedgerEntryType.COMMISSION).count(),
                    entries.stream().filter(VendorLedgerEntry::isHeld).count(),
                    ledger.findByVendorOrderIdOrderByOccurredAtAsc(siblingSliceId).size());
        });

        assertEquals(2, succeeded.get());
        assertNotNull(snapshot.receiptConfirmedAt());
        assertNotNull(snapshot.escrowReleasedAt());
        assertEquals(1, snapshot.sales());
        assertEquals(1, snapshot.commissions());
        assertEquals(0, snapshot.held());
        assertNull(snapshot.siblingReceiptConfirmedAt());
        assertNull(snapshot.siblingEscrowReleasedAt());
        assertEquals(0, snapshot.siblingEntries());
    }

    @Test
    void confirmationAndCancellationCannotCommitContradictorySliceStates() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var confirmation = pool.submit(() -> {
                ready.countDown();
                await(start);
                service.confirmReceipt(buyerId, orderId, confirmedSliceId,
                        new BuyerOrderRequests.ConfirmReceipt(null));
                confirmed.incrementAndGet();
            });
            var cancellation = pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    service.cancelVendorOrder(buyerId, orderId, confirmedSliceId,
                            new BuyerOrderRequests.Cancel("race"));
                    cancelled.incrementAndGet();
                } catch (BadRequestException expected) {
                    // A shipped or delivered slice is not cancellable.
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            confirmation.get(20, TimeUnit.SECONDS);
            cancellation.get(20, TimeUnit.SECONDS);
        }

        VendorOrder result = transactions.execute(
                ignored -> vendorOrders.findById(confirmedSliceId).orElseThrow());
        assertEquals(1, confirmed.get());
        assertEquals(0, cancelled.get());
        assertEquals(VendorOrderStatus.DELIVERED, result.getStatus());
        assertNotNull(result.getReceiptConfirmedAt());
    }

    @Test
    void confirmationAndDisputeSerializeWithoutLeavingReleasedMoneyUnfrozen() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger confirmationRejected = new AtomicInteger();
        AtomicInteger disputeOpened = new AtomicInteger();

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var confirmation = pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    service.confirmReceipt(buyerId, orderId, confirmedSliceId,
                            new BuyerOrderRequests.ConfirmReceipt(null));
                    confirmed.incrementAndGet();
                } catch (BadRequestException expected) {
                    confirmationRejected.incrementAndGet();
                }
            });
            var dispute = pool.submit(() -> {
                ready.countDown();
                await(start);
                disputes.open(buyerId, new AfterSalesRequests.OpenDispute(
                        confirmedSliceId, DisputeReason.NOT_RECEIVED,
                        "The parcel was not received.", new BigDecimal("100.00"), null));
                disputeOpened.incrementAndGet();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            confirmation.get(20, TimeUnit.SECONDS);
            dispute.get(20, TimeUnit.SECONDS);
        }

        DisputeRaceSnapshot snapshot = transactions.execute(ignored -> {
            VendorOrder slice = vendorOrders.findById(confirmedSliceId).orElseThrow();
            List<VendorLedgerEntry> entries =
                    ledger.findByVendorOrderIdOrderByOccurredAtAsc(confirmedSliceId);
            return new DisputeRaceSnapshot(
                    slice.getReceiptConfirmedAt(), slice.getEscrowReleasedAt(),
                    slice.getDisputeFrozenAt(), disputeRows.findFreezingSlice(confirmedSliceId).size(),
                    entries.stream().filter(e -> e.getType() == LedgerEntryType.SALE).count(),
                    entries.stream().filter(e -> e.getType() == LedgerEntryType.COMMISSION).count(),
                    entries.stream().filter(e -> e.getType() == LedgerEntryType.DISPUTE_HOLD).count());
        });

        assertEquals(1, disputeOpened.get());
        assertEquals(1, confirmed.get() + confirmationRejected.get());
        assertNotNull(snapshot.disputeFrozenAt());
        assertEquals(1, snapshot.openDisputes());
        if (confirmed.get() == 1) {
            assertNotNull(snapshot.receiptConfirmedAt());
            assertNotNull(snapshot.escrowReleasedAt());
            assertEquals(1, snapshot.sales());
            assertEquals(1, snapshot.commissions());
            assertEquals(1, snapshot.disputeHolds(),
                    "a dispute opened after release must hold the now-available money");
        } else {
            assertNull(snapshot.receiptConfirmedAt());
            assertNull(snapshot.escrowReleasedAt());
            assertEquals(0, snapshot.sales());
            assertEquals(0, snapshot.commissions());
            assertEquals(0, snapshot.disputeHolds(),
                    "a dispute opened before confirmation leaves the unposted sale in escrow");
        }
    }

    private void confirm(CountDownLatch ready, CountDownLatch start, AtomicInteger succeeded) {
        ready.countDown();
        await(start);
        service.confirmReceipt(buyerId, orderId, confirmedSliceId,
                new BuyerOrderRequests.ConfirmReceipt(null));
        succeeded.incrementAndGet();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent start timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private User user(String email, UserRole role) {
        return User.builder().email(email).password("x").firstName("Race").lastName("Person")
                .role(role).enabled(true).build();
    }

    private Vendor vendor(String email, String name, String slug) {
        User seller = users.save(user(email, UserRole.VENDOR));
        return vendors.save(Vendor.builder().user(seller).storeName(name).storeSlug(slug)
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").build());
    }

    private static VendorOrder slice(Order order, Vendor vendor, VendorOrderStatus status,
                                     String total, String commission) {
        BigDecimal amount = new BigDecimal(total);
        BigDecimal fee = new BigDecimal(commission);
        return VendorOrder.builder().order(order).vendor(vendor).status(status)
                .nativeCurrency("GMD").subtotalNative(amount).totalNative(amount)
                .commissionNative(fee).payoutNative(amount.subtract(fee))
                .subtotal(amount).total(amount).build();
    }

    private record Snapshot(LocalDateTime receiptConfirmedAt, LocalDateTime escrowReleasedAt,
                            LocalDateTime siblingReceiptConfirmedAt,
                            LocalDateTime siblingEscrowReleasedAt, long sales, long commissions,
                            long held, int siblingEntries) {
    }

    private record DisputeRaceSnapshot(LocalDateTime receiptConfirmedAt,
                                       LocalDateTime escrowReleasedAt,
                                       LocalDateTime disputeFrozenAt, int openDisputes,
                                       long sales, long commissions, long disputeHolds) {
    }
}
