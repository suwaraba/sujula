package com.sujula.service.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
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

import com.sujula.dto.request.driver.DriverRequests;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VehicleType;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.Driver;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.delivery.DriverRepository;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.EmailService;
import com.sujula.service.driver.impl.DriverCustodyServiceImpl;
import com.sujula.service.shipment.CustodyChain;

/** Real relational proof of shipment/code serialization (H2 in MySQL mode). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DriverCustodyServiceImpl.class, CustodyChain.class,
        com.sujula.service.platform.FeatureFlags.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DriverCustodyConcurrencyIntegrationTest {

    private static final double ORIGIN_LAT = 13.4530;
    private static final double ORIGIN_LNG = -16.6750;
    private static final double DEST_LAT = 13.4384;
    private static final double DEST_LNG = -16.6781;

    @Autowired private DriverCustodyServiceImpl custody;
    @Autowired private ShipmentRepository shipments;
    @Autowired private ShipmentLegRepository legs;
    @Autowired private CustodyEventRepository events;
    @Autowired private HandoverCodeRepository codes;
    @Autowired private DriverRepository drivers;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private TransactionTemplate transactions;

    @MockitoBean private EmailService email;
    @MockitoBean private com.sujula.service.notification.SmsSender sms;

    private Long shipmentId;
    private Long driverUserId;
    private Long codeId;

    @BeforeEach
    void setUp() {
        transactions.executeWithoutResult(ignored -> {
            User seller = user("race-seller@sujula.gm", UserRole.VENDOR);
            User buyer = user("race-buyer@example.es", UserRole.CUSTOMER);
            User driverUser = user("race-driver@sujula.gm", UserRole.DELIVERY);
            driverUserId = driverUser.getId();

            Vendor vendor = vendors.save(Vendor.builder()
                    .user(seller).storeName("Race Store").storeSlug("race-store")
                    .status(PartnerStatus.APPROVED).settlementCurrency("GMD")
                    .pickupCountryCode("GM").build());
            Driver driver = drivers.save(Driver.builder().user(driverUser)
                    .status(DriverStatus.APPROVED).available(true)
                    .vehicleType(VehicleType.MOTOR).zone("Serrekunda")
                    .countryCode("GM").maxWeight(20).build());

            Order order = new Order();
            order.setOrderNumber("SJL-RACE-1");
            order.setSubtotal(new BigDecimal("100.00"));
            order.setTotal(new BigDecimal("100.00"));
            order.setCurrency("EUR");
            order.setCustomer(buyer);
            order = orders.save(order);

            VendorOrder slice = vendorOrders.save(VendorOrder.builder()
                    .order(order).vendor(vendor).status(VendorOrderStatus.READY_FOR_PICKUP)
                    .nativeCurrency("GMD").subtotalNative(new BigDecimal("100.00"))
                    .totalNative(new BigDecimal("100.00"))
                    .subtotal(new BigDecimal("100.00")).total(new BigDecimal("100.00"))
                    .build());

            Shipment shipment = shipments.save(Shipment.builder()
                    .reference("SHP-RACE-1").vendorOrder(slice)
                    .recipientName("Race Recipient").recipientPhone("+2203000000")
                    .destinationStreet("1 Race Street").destinationCity("Serrekunda")
                    .destinationCountry("GM")
                    .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                    .originLatitude(ORIGIN_LAT).originLongitude(ORIGIN_LNG)
                    .originAddress("Race Store").parcelCount(1).build());
            shipmentId = shipment.getId();

            legs.save(ShipmentLeg.builder().shipment(shipment).sequence(1)
                    .legType(LegType.ORIGIN_TO_RECIPIENT)
                    .assignmentStatus(LegAssignmentStatus.ACCEPTED).driver(driver)
                    .originLatitude(ORIGIN_LAT).originLongitude(ORIGIN_LNG)
                    .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                    .earning(BigDecimal.ZERO).earningCurrency("GMD").build());
        });

        custody.arrivedAtOrigin(driverUserId, shipmentId,
                new DriverRequests.Arrived(ORIGIN_LAT, ORIGIN_LNG,
                        BigDecimal.TEN, null, "race-arrived"));

        transactions.executeWithoutResult(ignored -> {
            Shipment shipment = shipments.findById(shipmentId).orElseThrow();
            codeId = codes.save(HandoverCode.builder().vendorOrder(shipment.getVendorOrder())
                    .codeType(HandoverCodeType.VENDOR_RELEASE).code("111111")
                    .used(false).expiresAt(LocalDateTime.now().plusHours(1)).build()).getId();
        });
    }

    @Test
    void sameShipmentAndCodeCommitExactlyOneCollection() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> futures = List.of(
                    pool.submit(() -> collectConcurrently("race-collect-a", ready, start,
                            succeeded, rejected)),
                    pool.submit(() -> collectConcurrently("race-collect-b", ready, start,
                            succeeded, rejected)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        Snapshot result = transactions.execute(ignored -> {
            HandoverCode code = codes.findById(codeId).orElseThrow();
            Shipment shipment = shipments.findById(shipmentId).orElseThrow();
            long collected = events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipmentId).stream()
                    .filter(event -> event.getType() == CustodyEventType.COLLECTED).count();
            ShipmentLeg leg = legs.findByShipmentIdOrderBySequenceAsc(shipmentId).getFirst();
            return new Snapshot(code.isUsed(), collected, shipment.getStatus(),
                    leg.getAssignmentStatus());
        });

        assertEquals(1, succeeded.get());
        assertEquals(1, rejected.get());
        assertTrue(result.codeUsed());
        assertEquals(1, result.collectedEvents());
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, result.status());
        assertEquals(LegAssignmentStatus.IN_PROGRESS, result.legStatus());
    }

    private void collectConcurrently(String eventId, CountDownLatch ready, CountDownLatch start,
                                     AtomicInteger succeeded, AtomicInteger rejected) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent start timed out");
            }
            custody.collect(driverUserId, shipmentId,
                    new DriverRequests.Handover("111111", null,
                            ORIGIN_LAT, ORIGIN_LNG, BigDecimal.TEN,
                            null, null, null, null, eventId));
            succeeded.incrementAndGet();
        } catch (RuntimeException refused) {
            rejected.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
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

    private record Snapshot(boolean codeUsed, long collectedEvents, ShipmentStatus status,
                            LegAssignmentStatus legStatus) {
    }
}
