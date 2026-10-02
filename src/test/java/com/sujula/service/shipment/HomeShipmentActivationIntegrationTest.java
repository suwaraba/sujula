package com.sujula.service.shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.fulfilment.FulfilmentView;
import com.sujula.service.fulfilment.ParcelLabelRenderer;
import com.sujula.service.fulfilment.impl.VendorFulfilmentServiceImpl;
import com.sujula.service.inventory.StockLedger;

/** Relational proof that canonical ready creates one reusable direct-home route. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({VendorFulfilmentServiceImpl.class, FulfilmentView.class,
        HomeShipmentCoordinator.class, CustodyChain.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HomeShipmentActivationIntegrationTest {

    private static final double ORIGIN_LAT = 13.4542;
    private static final double ORIGIN_LNG = -16.5753;
    private static final double DEST_LAT = 13.4384;
    private static final double DEST_LNG = -16.6781;

    @Autowired private VendorFulfilmentServiceImpl fulfilment;
    @Autowired private ShipmentRepository shipments;
    @Autowired private ShipmentLegRepository legs;
    @Autowired private HandoverCodeRepository codes;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private TransactionTemplate transactions;

    @MockitoBean private StockLedger ledger;
    @MockitoBean private ParcelLabelRenderer labels;

    private Long vendorUserId;
    private Long sliceId;

    @BeforeEach
    void setUp() {
        transactions.executeWithoutResult(ignored -> {
            User seller = user("activation-seller@sujula.gm", UserRole.VENDOR);
            User buyer = user("activation-buyer@example.es", UserRole.CUSTOMER);
            vendorUserId = seller.getId();

            Vendor vendor = vendors.save(Vendor.builder()
                    .user(seller).storeName("Activation Store").storeSlug("activation-store")
                    .status(PartnerStatus.APPROVED).settlementCurrency("GMD")
                    .pickupStreet("12 Kairaba Avenue").pickupCity("Serrekunda")
                    .pickupCountryCode("gm")
                    .pickupLatitude(ORIGIN_LAT).pickupLongitude(ORIGIN_LNG)
                    .build());

            Order order = new Order();
            order.setOrderNumber("SJL-ACTIVATION-1");
            order.setCustomer(buyer);
            order.setSubtotal(new BigDecimal("110.00"));
            order.setTotal(new BigDecimal("120.00"));
            order.setCurrency("EUR");
            order.setPaymentStatus(PaymentStatus.PAID);
            order.setStatus(OrderStatus.CONFIRMED);
            order.setDeliveryMode(DeliveryMode.HOME_DELIVERY);
            order.setShippingFullName("Isatou Ceesay");
            order.setShippingPhone("+2203100077");
            order.setShippingStreet("4 Westfield Road");
            order.setShippingApartment("Flat 2");
            order.setShippingCity("Serrekunda");
            order.setShippingCountry("gm");
            order.setShippingLatitude(DEST_LAT);
            order.setShippingLongitude(DEST_LNG);
            order = orders.save(order);

            sliceId = vendorOrders.save(VendorOrder.builder()
                    .order(order).vendor(vendor).status(VendorOrderStatus.PREPARING)
                    .nativeCurrency("GMD")
                    .subtotalNative(new BigDecimal("7700.00"))
                    .totalNative(new BigDecimal("8400.00"))
                    .subtotal(new BigDecimal("110.00"))
                    .total(new BigDecimal("120.00"))
                    .build()).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(ignored -> {
            codes.deleteAllInBatch();
            legs.deleteAllInBatch();
            shipments.deleteAllInBatch();
            vendorOrders.deleteAllInBatch();
            orders.deleteAllInBatch();
            vendors.deleteAllInBatch();
            users.deleteAllInBatch();
        });
    }

    @Test
    void canonicalReadyCreatesAndReusesOneCompleteDirectHomeRoute() {
        var first = fulfilment.ready(vendorUserId, sliceId);

        Snapshot initial = snapshot();
        assertEquals(VendorOrderStatus.READY_FOR_PICKUP, first.status());
        assertEquals(sliceId, initial.shipment().getVendorOrder().getId());
        assertEquals("Isatou Ceesay", initial.shipment().getRecipientName());
        assertEquals("+2203100077", initial.shipment().getRecipientPhone());
        assertEquals("4 Westfield Road, Flat 2", initial.shipment().getDestinationStreet());
        assertEquals("Serrekunda", initial.shipment().getDestinationCity());
        assertEquals("GM", initial.shipment().getDestinationCountry());
        assertEquals(DEST_LAT, initial.shipment().getDestinationLatitude());
        assertEquals(DEST_LNG, initial.shipment().getDestinationLongitude());
        assertEquals(ORIGIN_LAT, initial.shipment().getOriginLatitude());
        assertEquals(ORIGIN_LNG, initial.shipment().getOriginLongitude());
        assertEquals("12 Kairaba Avenue, Serrekunda, GM", initial.shipment().getOriginAddress());
        assertEquals(1, initial.legs().size());
        assertEquals(1, initial.legs().getFirst().getSequence());
        assertEquals(LegType.ORIGIN_TO_RECIPIENT, initial.legs().getFirst().getLegType());
        assertEquals(LegAssignmentStatus.UNASSIGNED,
                initial.legs().getFirst().getAssignmentStatus());
        assertEquals(1, initial.liveReleaseCodes());

        var second = fulfilment.ready(vendorUserId, sliceId);
        Snapshot repeated = snapshot();
        assertEquals(first.releaseCode().code(), second.releaseCode().code());
        assertEquals(initial.shipment().getId(), repeated.shipment().getId());
        assertEquals(1, repeated.legs().size());
        assertEquals(1, repeated.liveReleaseCodes());
    }

    @Test
    void twoConcurrentReadyRequestsCreateExactlyOneShipmentLegAndLiveCode() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> futures = List.of(
                    pool.submit(() -> readyConcurrently(ready, start, succeeded)),
                    pool.submit(() -> readyConcurrently(ready, start, succeeded)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        Snapshot result = snapshot();
        assertEquals(2, succeeded.get());
        assertEquals(VendorOrderStatus.READY_FOR_PICKUP, result.sliceStatus());
        assertEquals(1, result.shipmentCount());
        assertEquals(1, result.legs().size());
        assertEquals(1, result.liveReleaseCodes());
    }

    @Test
    void nonHomeReadyFailsClosedWithoutShipmentOrReleaseCode() {
        transactions.executeWithoutResult(ignored -> {
            VendorOrder slice = vendorOrders.findById(sliceId).orElseThrow();
            slice.getOrder().setDeliveryMode(DeliveryMode.PICKUP_POINT);
            orders.save(slice.getOrder());
        });

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> fulfilment.ready(vendorUserId, sliceId));

        assertTrue(refused.getMessage().contains("Pickup-point fulfilment"), refused.getMessage());
        assertEquals(0, shipments.count());
        assertEquals(0, codes.count());
        assertEquals(VendorOrderStatus.PREPARING,
                vendorOrders.findById(sliceId).orElseThrow().getStatus());
    }

    private void readyConcurrently(CountDownLatch ready, CountDownLatch start,
                                   AtomicInteger succeeded) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent start timed out");
            }
            fulfilment.ready(vendorUserId, sliceId);
            succeeded.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private Snapshot snapshot() {
        return transactions.execute(ignored -> {
            VendorOrder slice = vendorOrders.findById(sliceId).orElseThrow();
            Shipment shipment = shipments.findByVendorOrderId(sliceId).orElseThrow();
            List<ShipmentLeg> route = legs.findByShipmentIdOrderBySequenceAsc(shipment.getId());
            long live = codes.findLiveReleaseCodes(sliceId).stream()
                    .filter(code -> code.getCodeType() == HandoverCodeType.VENDOR_RELEASE)
                    .count();
            return new Snapshot(shipment, route, live, shipments.count(), slice.getStatus());
        });
    }

    private User user(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("x");
        user.setFirstName("A");
        user.setLastName("Person");
        user.setRole(role);
        return users.save(user);
    }

    private record Snapshot(Shipment shipment, List<ShipmentLeg> legs,
                            long liveReleaseCodes, long shipmentCount,
                            VendorOrderStatus sliceStatus) {
    }
}
