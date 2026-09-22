package com.sujula.service.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4, tested as the property it is: a shipment's status is a function of its
 * custody events and of nothing else.
 *
 * <p>The sharpest test here is {@code rederivingTheChainChangesNothing} — it
 * replays every event over a shipment and asserts the derived state comes back
 * identical. A status that could drift from its evidence would fail it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(CustodyChain.class)
class CustodyChainTest {

    @Autowired private CustodyChain chain;
    @Autowired private ShipmentRepository shipments;
    @Autowired private ShipmentLegRepository legs;
    @Autowired private CustodyEventRepository events;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private Shipment shipment;
    private User driver;

    /** Serrekunda, where the parcel is going. */
    private static final double DEST_LAT = 13.4384;
    private static final double DEST_LNG = -16.6781;

    @BeforeEach
    void setUp() {
        User sellerUser = user("lamin@sujula.gm", UserRole.VENDOR);
        driver = user("ebrima.driver@sujula.gm", UserRole.DELIVERY);

        Vendor vendor = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-electronics")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .build());

        Order order = new Order();
        order.setOrderNumber("SJL-C-0001");
        order.setSubtotal(new BigDecimal("9700.00"));
        order.setTotal(new BigDecimal("9700.00"));
        order.setCurrency("EUR");
        order = orders.save(order);

        VendorOrder slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(vendor).status(VendorOrderStatus.READY_FOR_PICKUP)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("9700.00")).totalNative(new BigDecimal("9700.00"))
                .subtotal(new BigDecimal("9700.00")).total(new BigDecimal("9700.00"))
                .build());

        shipment = shipments.save(Shipment.builder()
                .reference("SHP-TEST0001").vendorOrder(slice)
                .recipientName("Isatou Ceesay").recipientPhone("+2203100077")
                .destinationCity("Serrekunda").destinationCountry("GM")
                .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                .originLatitude(13.4530).originLongitude(-16.6750)
                .build());
        entityManager.flush();
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

    private ShipmentLeg leg(LegType type, int sequence, LegAssignmentStatus status) {
        ShipmentLeg created = legs.save(ShipmentLeg.builder()
                .shipment(shipment).sequence(sequence).legType(type)
                .assignmentStatus(status)
                .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                .build());
        entityManager.flush();
        entityManager.refresh(shipment);
        return created;
    }

    /** An event at the destination, well inside any sane geofence. */
    private CustodyEvent at(CustodyEventType type, double lat, double lng) {
        BigDecimal distance = Geofence.metresBetween(lat, lng, DEST_LAT, DEST_LNG);
        return CustodyEvent.builder()
                .type(type)
                .recordedByUserId(driver.getId())
                .latitude(lat).longitude(lng)
                .accuracyMetres(new BigDecimal("12.00"))
                .metresFromExpected(distance)
                .withinGeofence(Geofence.isWithin(distance, new BigDecimal("12.00"),
                        Geofence.DEFAULT_RADIUS_M))
                .codePresented("123456")
                .occurredAt(LocalDateTime.now())
                .build();
    }

    private CustodyEvent event(CustodyEventType type) {
        return at(type, DEST_LAT, DEST_LNG);
    }

    // ── The status is derived, never set ─────────────────────────────────────

    @Test
    void aShipmentWithNoEventsIsWaitingForADriver() {
        assertEquals(ShipmentStatus.AWAITING_COLLECTION, shipment.getStatus());
    }

    @Test
    void offeringAndAcceptingALegMoveTheStatusWithoutAnyEvent() {
        ShipmentLeg offered = leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.OFFERED);
        assertEquals(ShipmentStatus.DRIVER_OFFERED, chain.rederive(shipment, List.of()));

        offered.setAssignmentStatus(LegAssignmentStatus.ACCEPTED);
        legs.save(offered);
        entityManager.flush();
        entityManager.refresh(shipment);

        assertEquals(ShipmentStatus.DRIVER_ASSIGNED, chain.rederive(shipment, List.of()));
    }

    @Test
    void collectingPutsItOutForDeliveryOnAStraightRun() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);

        chain.append(shipment, event(CustodyEventType.COLLECTED));

        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, shipment.getStatus());
        assertNotNull(shipment.getCollectedAt(), "and the timestamp follows from the event");
    }

    @Test
    void collectingOnAHubLegIsInTransitRatherThanOutForDelivery() {
        leg(LegType.ORIGIN_TO_PICKUP, 1, LegAssignmentStatus.IN_PROGRESS);

        chain.append(shipment, event(CustodyEventType.COLLECTED));

        assertEquals(ShipmentStatus.IN_TRANSIT, shipment.getStatus());
    }

    @Test
    void releasingIsTheOnlyThingThatMakesAParcelDelivered() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        chain.append(shipment, event(CustodyEventType.COLLECTED));
        chain.append(shipment, event(CustodyEventType.RELEASED));

        assertEquals(ShipmentStatus.DELIVERED, shipment.getStatus());
        assertNotNull(shipment.getDeliveredAt());
    }

    @Test
    void thereIsNoWayToSetAStatusWithoutAnEvent() throws Exception {
        // C4 stated as a shape: Shipment exposes no status setter at all, so
        // there is no method anywhere that could mark a parcel delivered
        // without somebody having handed it over.
        for (var method : Shipment.class.getMethods()) {
            assertFalse(method.getName().equals("setStatus"),
                    "Shipment must not expose a status setter");
        }
        // The one door in is applyDerivedState, and CustodyChain is its caller.
        assertNotNull(Shipment.class.getMethod("applyDerivedState",
                ShipmentStatus.class, int.class,
                LocalDateTime.class, LocalDateTime.class, LocalDateTime.class));
    }

    // ── The chain is the truth ───────────────────────────────────────────────

    @Test
    void rederivingTheChainChangesNothing() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        chain.append(shipment, event(CustodyEventType.COLLECTED));
        chain.append(shipment, event(CustodyEventType.FAILED_ATTEMPT));
        chain.append(shipment, event(CustodyEventType.RELEASED));

        ShipmentStatus was = shipment.getStatus();
        LocalDateTime deliveredAt = shipment.getDeliveredAt();
        int failed = shipment.getFailedAttempts();

        // Replay the whole chain. A status that could drift from its evidence
        // would come back different here.
        ShipmentStatus again = chain.rederive(shipment,
                events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()));

        assertEquals(was, again);
        assertEquals(deliveredAt, shipment.getDeliveredAt());
        assertEquals(failed, shipment.getFailedAttempts());
    }

    @Test
    void theMostRecentMovingEventDecidesWhereTheParcelIs() {
        leg(LegType.ORIGIN_TO_PICKUP, 1, LegAssignmentStatus.IN_PROGRESS);
        chain.append(shipment, event(CustodyEventType.COLLECTED));
        chain.append(shipment, event(CustodyEventType.DEPOSITED));

        // Not "the furthest it got" — who has it now.
        assertEquals(ShipmentStatus.AT_PICKUP_POINT, shipment.getStatus());
    }

    @Test
    void aFailedAttemptLeavesTheParcelWithTheDriverAndIsCounted() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        chain.append(shipment, event(CustodyEventType.COLLECTED));
        chain.append(shipment, event(CustodyEventType.FAILED_ATTEMPT));
        chain.append(shipment, event(CustodyEventType.FAILED_ATTEMPT));

        assertEquals(ShipmentStatus.ATTEMPT_FAILED, shipment.getStatus());
        assertEquals(2, shipment.getFailedAttempts());
        assertTrue(shipment.getStatus().isCustodyActive(), "the driver still has it");
    }

    // ── The hole C4 exists to close ──────────────────────────────────────────

    @Test
    void aParcelCannotBeDeliveredWithoutHavingBeenCollected() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);

        ShipmentStatus before = chain.rederive(shipment, List.of());

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> chain.append(shipment, event(CustodyEventType.RELEASED)));

        assertTrue(refused.getMessage().contains("Nobody has collected"), refused.getMessage());
        // The refusal changed nothing: no event was written, so nothing was
        // derived from one.
        assertEquals(ShipmentStatus.DRIVER_ASSIGNED, before);
        assertEquals(before, shipment.getStatus());
        assertTrue(events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).isEmpty());
    }

    @Test
    void norLeftAtAHubNorHandedOnNorFailed() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);

        for (CustodyEventType type : List.of(CustodyEventType.DEPOSITED,
                CustodyEventType.TRANSFERRED, CustodyEventType.FAILED_ATTEMPT,
                CustodyEventType.REDISPATCHED)) {
            assertThrows(BadRequestException.class, () -> chain.append(shipment, event(type)),
                    type + " must not be possible before collection");
        }
    }

    @Test
    void aParcelCannotBeCollectedTwice() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        chain.append(shipment, event(CustodyEventType.COLLECTED));

        assertThrows(BadRequestException.class,
                () -> chain.append(shipment, event(CustodyEventType.COLLECTED)));
    }

    @Test
    void nothingHappensToAParcelThatHasAlreadyArrived() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        chain.append(shipment, event(CustodyEventType.COLLECTED));
        chain.append(shipment, event(CustodyEventType.RELEASED));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> chain.append(shipment, event(CustodyEventType.FAILED_ATTEMPT)));
        assertTrue(refused.getMessage().contains("already finished"), refused.getMessage());
    }

    // ── Clocks ───────────────────────────────────────────────────────────────

    @Test
    void anEventFromTheFutureIsRefusedBecauseAPhoneClockIsSettable() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        CustodyEvent tomorrow = event(CustodyEventType.COLLECTED);
        tomorrow.setOccurredAt(LocalDateTime.now().plusDays(1));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> chain.append(shipment, tomorrow));
        assertTrue(refused.getMessage().contains("clock"), refused.getMessage());
    }

    @Test
    void anEventFromHoursAgoIsBelievedBecauseThatIsWhatOfflineMeans() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        CustodyEvent thisMorning = event(CustodyEventType.COLLECTED);
        thisMorning.setOccurredAt(LocalDateTime.now().minusHours(6));
        thisMorning.setCapturedOffline(true);

        // A driver out of signal for six hours is Tuesday here, not an attack.
        assertNotNull(chain.append(shipment, thisMorning));
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, shipment.getStatus());
    }

    @Test
    void anEventFromMonthsAgoIsNotALateUploadButAMistake() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        CustodyEvent ancient = event(CustodyEventType.COLLECTED);
        ancient.setOccurredAt(LocalDateTime.now().minusMonths(3));

        assertThrows(BadRequestException.class, () -> chain.append(shipment, ancient));
    }

    @Test
    void anEventWithNoTimeAtAllIsRefused() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        CustodyEvent timeless = event(CustodyEventType.COLLECTED);
        timeless.setOccurredAt(null);

        assertThrows(BadRequestException.class, () -> chain.append(shipment, timeless));
    }

    // ── The evidence is kept, not just checked ───────────────────────────────

    @Test
    void aHandoverAtTheDoorIsRecordedAsAttested() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        chain.append(shipment, event(CustodyEventType.COLLECTED));

        CustodyEvent saved = chain.append(shipment, at(CustodyEventType.RELEASED, DEST_LAT, DEST_LNG));

        assertTrue(saved.isWithinGeofence());
        assertEquals(0, saved.getMetresFromExpected().compareTo(BigDecimal.ZERO));
    }

    @Test
    void aHandoverFromMilesAwayIsStillRecordedButNotAttested() {
        leg(LegType.ORIGIN_TO_RECIPIENT, 1, LegAssignmentStatus.IN_PROGRESS);
        chain.append(shipment, event(CustodyEventType.COLLECTED));

        // Banjul, about 10km from Serrekunda. The parcel may genuinely have
        // changed hands, so this is evidence rather than a refusal — but the
        // chain says plainly that the position does not corroborate it.
        CustodyEvent faraway = chain.append(shipment,
                at(CustodyEventType.RELEASED, 13.4549, -16.5790));

        assertFalse(faraway.isWithinGeofence());
        assertTrue(Geofence.isImplausible(faraway.getMetresFromExpected()));
        assertEquals(ShipmentStatus.DELIVERED, shipment.getStatus());
    }

    @Test
    void aPositionWeDoNotHaveIsNotTheSameAsAPositionFarAway() {
        // Null distance means "we do not know", and that must not read as
        // "inside the fence" nor as "two kilometres out".
        assertNull(Geofence.metresBetween(null, null, DEST_LAT, DEST_LNG));
        assertFalse(Geofence.isWithin(null, null, Geofence.DEFAULT_RADIUS_M));
        assertFalse(Geofence.isImplausible(null));
    }

    @Test
    void anHonestlyImpreciseFixIsNotPunished() {
        // A phone admitting to 300m of uncertainty cannot be held to a 250m
        // radius; penalising it would reward the device that reports nothing.
        BigDecimal distance = new BigDecimal("400.00");
        assertFalse(Geofence.isWithin(distance, null, Geofence.DEFAULT_RADIUS_M));
        assertTrue(Geofence.isWithin(distance, new BigDecimal("300.00"), Geofence.DEFAULT_RADIUS_M));
    }
}
