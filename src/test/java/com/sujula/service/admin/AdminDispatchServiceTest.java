package com.sujula.service.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sujula.dto.request.admin.AdminDispatchRequests;
import com.sujula.dto.response.admin.AdminDispatchResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VehicleType;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.Driver;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.delivery.DriverRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.admin.impl.AdminDispatchServiceImpl;
import com.sujula.service.security.StepUpVerifier;
import com.sujula.service.shipment.CustodyChain;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Unsticking orders and parcels.
 *
 * <p>The claims that matter: an assignment is an offer rather than a fact, a
 * parcel in somebody's hands cannot be moved by an administrator, and an
 * override leaves a link in the chain that says out loud it was decided rather
 * than proven.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AdminDispatchServiceImpl.class, CustodyChain.class})
class AdminDispatchServiceTest {

    @Autowired private AdminDispatchServiceImpl dispatch;
    @Autowired private ShipmentRepository shipments;
    @Autowired private ShipmentLegRepository legs;
    @Autowired private CustodyEventRepository events;
    @Autowired private DriverRepository drivers;
    @Autowired private OrderRepository orders;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    @MockitoBean private AuditService audit;
    @MockitoBean private StepUpVerifier stepUp;
    @MockitoBean private com.sujula.service.NotificationService notifications;

    private User operator;
    private Driver ebrima;
    private Driver modou;
    private Order order;
    private VendorOrder slice;
    private Shipment parcel;
    private ShipmentLeg leg;

    private static final double ORIGIN_LAT = 13.4530;
    private static final double ORIGIN_LNG = -16.6750;
    private static final double DEST_LAT = 13.4384;
    private static final double DEST_LNG = -16.6781;

    @BeforeEach
    void setUp() {
        operator = user("ops@sujula.gm", UserRole.ADMIN);
        User sellerUser = user("lamin@sujula.gm", UserRole.VENDOR);
        User buyer = user("ousman@example.es", UserRole.CUSTOMER);

        Vendor kombo = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-dispatch")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .build());

        ebrima = drivers.save(Driver.builder()
                .user(user("ebrima@sujula.gm", UserRole.DELIVERY))
                .status(DriverStatus.APPROVED).available(true)
                .vehicleType(VehicleType.MOTOR).zone("Serrekunda").countryCode("GM")
                .currentLatitude(ORIGIN_LAT).currentLongitude(ORIGIN_LNG)
                .maxWeight(20).build());
        modou = drivers.save(Driver.builder()
                .user(user("modou@sujula.gm", UserRole.DELIVERY))
                .status(DriverStatus.APPROVED).available(false)
                .vehicleType(VehicleType.MOTOR).zone("Banjul").countryCode("GM")
                .currentLatitude(13.4549).currentLongitude(-16.5790)
                .maxWeight(20).build());

        order = new Order();
        order.setOrderNumber("SJL-D-9001");
        order.setSubtotal(new BigDecimal("180.00"));
        order.setTotal(new BigDecimal("180.00"));
        order.setCurrency("EUR");
        order.setCustomer(buyer);
        order.setShippingCity("Serrekunda");
        order.setShippingCountry("GM");
        order = orders.save(order);

        slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(kombo).status(VendorOrderStatus.READY_FOR_PICKUP)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("12000.00")).totalNative(new BigDecimal("12000.00"))
                .subtotal(new BigDecimal("180.00")).total(new BigDecimal("180.00"))
                .build());

        parcel = shipments.save(Shipment.builder()
                .reference("SHP-D9001").vendorOrder(slice)
                .recipientName("Fatou Ceesay").destinationCity("Serrekunda")
                .destinationCountry("GM")
                .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                .originLatitude(ORIGIN_LAT).originLongitude(ORIGIN_LNG)
                .parcelCount(1).build());

        leg = legs.save(ShipmentLeg.builder()
                .shipment(parcel).sequence(1).legType(LegType.ORIGIN_TO_RECIPIENT)
                .assignmentStatus(LegAssignmentStatus.UNASSIGNED)
                .originLatitude(ORIGIN_LAT).originLongitude(ORIGIN_LNG)
                .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                .build());

        entityManager.flush();
        entityManager.refresh(parcel);
    }

    private User user(String email, UserRole role) {
        User person = new User();
        person.setEmail(email);
        person.setPassword("x");
        person.setFirstName(email.substring(0, email.indexOf('@')));
        person.setLastName("Person");
        person.setRole(role);
        return users.save(person);
    }

    private void collectIt() {
        events.save(CustodyEvent.builder()
                .shipment(parcel).leg(leg).type(CustodyEventType.COLLECTED)
                .recordedByUserId(ebrima.getUser().getId()).codePresented("111111")
                .occurredAt(LocalDateTime.now().minusHours(1))
                .clientEventId("collected-dispatch").build());
        leg.setAssignmentStatus(LegAssignmentStatus.IN_PROGRESS);
        leg.setDriver(ebrima);
        legs.save(leg);
        entityManager.flush();
        parcel = shipments.findById(parcel.getId()).orElseThrow();
        com.sujula.service.shipment.CustodyChain chain =
                new com.sujula.service.shipment.CustodyChain(events, shipments);
        chain.rederive(parcel, events.findByShipmentIdOrderByOccurredAtAscIdAsc(parcel.getId()));
        entityManager.flush();
    }

    // ── Assignment is an offer ───────────────────────────────────────────────

    @Test
    void assigningOffersTheJobRatherThanDeclaringItTheirs() {
        AdminDispatchResponses.AssignmentMade made = dispatch.assign(operator, parcel.getId(),
                new AdminDispatchRequests.AssignShipment(ebrima.getId(), 30, "Nearest."));
        entityManager.flush();

        ShipmentLeg stored = legs.findById(leg.getId()).orElseThrow();
        // Not ACCEPTED. A platform that could put a job on somebody's screen and
        // call it theirs would be one where a driver who was asleep is
        // accountable for a parcel.
        assertEquals(LegAssignmentStatus.OFFERED, stored.getAssignmentStatus());
        assertEquals(ebrima.getId(), stored.getDriver().getId());
        assertNotNull(made.offerExpiresAt());
        assertTrue(made.message().contains("goes back to the queue"));
    }

    @Test
    void aParcelSomebodyHasAlreadyAcceptedIsNotOfferedToSomebodyElse() {
        dispatch.assign(operator, parcel.getId(),
                new AdminDispatchRequests.AssignShipment(ebrima.getId(), 30, null));
        leg.setAssignmentStatus(LegAssignmentStatus.ACCEPTED);
        legs.save(leg);
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> dispatch.assign(operator, parcel.getId(),
                        new AdminDispatchRequests.AssignShipment(modou.getId(), 30, null)));
        assertTrue(refused.getMessage().contains("already taken this one"));
    }

    @Test
    void aDriverInAnotherCountryIsRefusedBecauseADriverIsADeliveryAnswer() {
        Driver spanish = drivers.save(Driver.builder()
                .user(user("carlos@sujula.es", UserRole.DELIVERY))
                .status(DriverStatus.APPROVED).available(true)
                .vehicleType(VehicleType.MOTOR).zone("Madrid").countryCode("ES")
                .maxWeight(20).build());
        entityManager.flush();

        // C1: a driver is matched against where the goods go, never against
        // anything about the person who paid.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> dispatch.assign(operator, parcel.getId(),
                        new AdminDispatchRequests.AssignShipment(spanish.getId(), 30, null)));
        assertTrue(refused.getMessage().contains("going to GM"));
    }

    @Test
    void aSuspendedDriverIsNotGivenParcels() {
        ebrima.setStatus(DriverStatus.SUSPENDED);
        drivers.save(ebrima);
        entityManager.flush();

        assertThrows(BadRequestException.class, () -> dispatch.assign(operator, parcel.getId(),
                new AdminDispatchRequests.AssignShipment(ebrima.getId(), 30, null)));
    }

    // ── Unassigning ──────────────────────────────────────────────────────────

    @Test
    void takingAJobBackDoesNotCountAsARefusalAgainstTheDriver() {
        dispatch.assign(operator, parcel.getId(),
                new AdminDispatchRequests.AssignShipment(ebrima.getId(), 30, null));
        entityManager.flush();

        AdminDispatchResponses.AssignmentRemoved removed = dispatch.unassign(operator,
                parcel.getId(), new AdminDispatchRequests.UnassignShipment("Wrong zone."));
        entityManager.flush();

        assertFalse(removed.scoreAffected());
        assertTrue(removed.message().contains("not a refusal"));
        assertNull(legs.findById(leg.getId()).orElseThrow().getDriver());
        assertEquals(LegAssignmentStatus.UNASSIGNED,
                legs.findById(leg.getId()).orElseThrow().getAssignmentStatus());
    }

    @Test
    void aParcelBeingCarriedIsNotTakenOffTheDriverHere() {
        collectIt();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> dispatch.unassign(operator, parcel.getId(),
                        new AdminDispatchRequests.UnassignShipment("Changed my mind.")));
        // It would leave the custody chain saying they still have it.
        assertTrue(refused.getMessage().contains("custody chain"));
    }

    @Test
    void aParcelInSomebodysHandsIsNotReassignedByAnAdministrator() {
        collectIt();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> dispatch.reassign(operator, parcel.getId(),
                        new AdminDispatchRequests.ReassignShipment(modou.getId(), "Faster.", 30)));
        // A parcel in somebody's hands moves by a transfer with both drivers
        // attesting, not by an administrator changing a column.
        assertTrue(refused.getMessage().contains("nobody handed it to anybody"));
    }

    @Test
    void aParcelThatFailedAnAttemptCanBeReassigned() {
        collectIt();
        events.save(CustodyEvent.builder()
                .shipment(parcel).leg(leg).type(CustodyEventType.FAILED_ATTEMPT)
                .recordedByUserId(ebrima.getUser().getId())
                .occurredAt(LocalDateTime.now()).clientEventId("failed-1").build());
        entityManager.flush();
        parcel = shipments.findById(parcel.getId()).orElseThrow();
        parcel.applyDerivedState(ShipmentStatus.ATTEMPT_FAILED, 1,
                LocalDateTime.now().minusHours(1), null, null);
        shipments.save(parcel);
        leg.setAssignmentStatus(LegAssignmentStatus.ACCEPTED);
        legs.save(leg);
        entityManager.flush();

        // After a failure it is a fresh dispatch problem, and somebody else
        // should be able to try.
        assertNotNull(dispatch.reassign(operator, parcel.getId(),
                new AdminDispatchRequests.ReassignShipment(modou.getId(), "Ebrima is off.", 30)));
    }

    @Test
    void theDriverWhoLosesAJobIsToldSoItDoesNotSimplyVanishFromTheirScreen() {
        leg.setAssignmentStatus(LegAssignmentStatus.OFFERED);
        leg.setDriver(ebrima);
        legs.save(leg);
        entityManager.flush();

        dispatch.reassign(operator, parcel.getId(),
                new AdminDispatchRequests.ReassignShipment(modou.getId(), "Ebrima is off.", 30));

        // A job that disappears with no explanation is how a driver on their way
        // to collect finds out by arriving. And the notice says out loud that
        // their acceptance rate is untouched, because being moved off a parcel is
        // an administrator's decision rather than a refusal of theirs.
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(notifications).send(
                org.mockito.ArgumentMatchers.eq(ebrima.getUser().getId()),
                org.mockito.ArgumentMatchers.anyString(),
                body.capture(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        assertTrue(body.getValue().contains("Ebrima is off."));
        assertTrue(body.getValue().contains("not a decline"));
    }

    @Test
    void reassigningToTheDriverWhoAlreadyHasItIsRefusedRatherThanReOffered() {
        leg.setAssignmentStatus(LegAssignmentStatus.ACCEPTED);
        leg.setDriver(ebrima);
        legs.save(leg);
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> dispatch.reassign(operator, parcel.getId(),
                        new AdminDispatchRequests.ReassignShipment(
                                ebrima.getId(), "Mis-click.", 30)));
        // Otherwise a slip of the finger sends a driver a fresh offer for a job
        // they have already accepted, and the clock on it starts again.
        assertTrue(refused.getMessage().contains("already has this one"));
    }

    // ── Candidates are ranked and explained ──────────────────────────────────

    @Test
    void unassignedParcelsComeWithRankedCandidatesAndTheReasonForTheOrder() {
        entityManager.flush();

        List<AdminDispatchResponses.UnassignedShipment> queue = dispatch.unassigned(operator, 10);

        assertEquals(1, queue.size());
        List<AdminDispatchResponses.DriverCandidate> candidates = queue.get(0).candidates();
        assertFalse(candidates.isEmpty());
        // On shift first: Ebrima is available, Modou is not.
        assertEquals(ebrima.getId(), candidates.get(0).driverId());
        assertTrue(candidates.get(0).available());
        // A dispatcher handed an unexplained list picks the first one.
        assertNotNull(candidates.get(0).why());
        assertTrue(candidates.get(0).why().contains("on shift"));
        assertTrue(candidates.get(0).why().contains("km from the shop"));
    }

    @Test
    void aDriverWithNoPositionIsUnknownRatherThanFarAway() {
        ebrima.setCurrentLatitude(null);
        ebrima.setCurrentLongitude(null);
        drivers.save(ebrima);
        entityManager.flush();

        AdminDispatchResponses.DriverCandidate candidate = dispatch.unassigned(operator, 10)
                .get(0).candidates().stream()
                .filter(c -> c.driverId().equals(ebrima.getId())).findFirst().orElseThrow();

        // Two different facts, and a dispatcher should be able to tell them
        // apart.
        assertNull(candidate.distanceKm());
        assertTrue(candidate.why().contains("position unknown"));
    }

    // ── The C4 escape hatch ──────────────────────────────────────────────────

    @Test
    void overridingAHandoffDemandsTheAdministratorsOwnCredentialsFirst() {
        collectIt();

        dispatch.overrideHandoff(operator, parcel.getId(),
                new AdminDispatchRequests.OverrideHandoff(CustodyEventType.RELEASED,
                        "admin-password", "123456",
                        "Driver's phone went into the river at Barra. The recipient confirmed on "
                                + "the telephone that she has the parcel.",
                        "Fatou Ceesay, by telephone", DEST_LAT, DEST_LNG));
        entityManager.flush();

        // An admin session left open on a desk must not be enough for the one
        // endpoint that can put a parcel into DELIVERED with no code.
        verify(stepUp).verify(eq(operator), eq("admin-password"), eq("123456"), anyString());
    }

    @Test
    void anOverriddenLinkSaysOutLoudThatItWasDecidedRatherThanProven() {
        collectIt();

        AdminDispatchResponses.HandoffOverridden done = dispatch.overrideHandoff(
                operator, parcel.getId(),
                new AdminDispatchRequests.OverrideHandoff(CustodyEventType.RELEASED,
                        "pw", "123456",
                        "Phone lost. Recipient confirmed receipt by telephone this morning.",
                        "Fatou Ceesay, by telephone", DEST_LAT, DEST_LNG));
        entityManager.flush();

        CustodyEvent released = events
                .findByShipmentIdOrderByOccurredAtAscIdAsc(parcel.getId()).stream()
                .filter(e -> e.getType() == CustodyEventType.RELEASED).findFirst().orElseThrow();

        // Months later this is the entire answer to "why is there no code
        // against this delivery".
        assertEquals("ADMIN_OVERRIDE", released.getReasonCode());
        assertNull(released.getCodePresented());
        assertTrue(released.getNote().contains("without a code"));
        assertTrue(released.getNote().contains("Fatou Ceesay, by telephone"));
        assertTrue(released.getNote().contains("ops@sujula.gm"));
        assertTrue(done.warning().contains("a person decided this"));
    }

    @Test
    void theChainSaysWhetherACodeWasPresentedAndNeverTheCode() {
        collectIt();

        AdminDispatchResponses.CustodyChainView view =
                dispatch.custodyChain(operator, parcel.getId());

        assertTrue(view.events().get(0).codePresented());
        // An administrator has no use for the digits, and a code on a screen is
        // a code somebody can read out.
        assertFalse(view.toString().contains("111111"));
    }

    @Test
    void aChainWithAnOverrideInItCarriesAWarning() {
        collectIt();
        dispatch.overrideHandoff(operator, parcel.getId(),
                new AdminDispatchRequests.OverrideHandoff(CustodyEventType.RELEASED, "pw",
                        "123456", "Phone lost, recipient confirmed by telephone this morning.",
                        "Fatou, by telephone", DEST_LAT, DEST_LNG));
        entityManager.flush();

        AdminDispatchResponses.CustodyChainView view =
                dispatch.custodyChain(operator, parcel.getId());

        assertTrue(view.events().stream()
                .anyMatch(AdminDispatchResponses.CustodyEventView::overridden));
        assertNotNull(view.note());
        assertTrue(view.note().contains("before treating this chain as proof"));
    }

    // ── Break-glass ──────────────────────────────────────────────────────────

    @Test
    void forcingAStatusRecordsThatNothingProducedIt() {
        AdminDispatchResponses.StatusForced forced = dispatch.forceStatus(operator, order.getId(),
                slice.getId(), new AdminDispatchRequests.ForceStatus(VendorOrderStatus.DELIVERED,
                        "Counter burned down overnight; the seller and the buyer both confirm the "
                                + "goods had already been collected on Tuesday."));
        entityManager.flush();

        assertEquals(VendorOrderStatus.READY_FOR_PICKUP, forced.from());
        assertEquals(VendorOrderStatus.DELIVERED, forced.to());
        assertTrue(forced.warning().contains("only record that it was justified"));
        verify(audit).record(eq(com.sujula.model.constant.AuditAction.VENDOR_ORDER_STATUS_FORCED),
                anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void aSliceFromAnotherOrderIsNotFoundRatherThanActedOn() {
        Order other = new Order();
        other.setOrderNumber("SJL-D-9002");
        other.setSubtotal(BigDecimal.ONE);
        other.setTotal(BigDecimal.ONE);
        other.setCurrency("EUR");
        other = orders.save(other);
        entityManager.flush();

        Long otherId = other.getId();
        assertThrows(com.sujula.exceptions.ResourceNotFoundException.class,
                () -> dispatch.forceStatus(operator, otherId, slice.getId(),
                        new AdminDispatchRequests.ForceStatus(VendorOrderStatus.DELIVERED,
                                "A note long enough to pass the length rule on this field.")));
    }

    // ── Cancelling ───────────────────────────────────────────────────────────

    @Test
    void cancellingAnOrderStopsItsParcelsToo() {
        AdminDispatchResponses.OrderCancelled cancelled = dispatch.forceCancel(operator,
                order.getId(), new AdminDispatchRequests.ForceCancelOrder(null,
                        "Buyer's card was stolen; the bank reversed the payment.", true));
        entityManager.flush();

        assertEquals(1, cancelled.cancelledVendorOrderIds().size());
        // A cancelled order with a parcel still moving is refunded goods being
        // delivered, and the driver finds out at the door.
        assertEquals(1, cancelled.parcelsCancelled());
        assertNotNull(shipments.findById(parcel.getId()).orElseThrow().getCancelledAt());
    }

    @Test
    void theOrderViewSaysWhenAParcelIsDeliveredWithNoCollectionEvent() {
        parcel.applyDerivedState(ShipmentStatus.DELIVERED, 0, null, LocalDateTime.now(), null);
        shipments.save(parcel);
        entityManager.flush();

        AdminDispatchResponses.OrderDetail detail = dispatch.order(operator, order.getId());

        // The C4 hole surfaced where somebody would notice it.
        assertTrue(detail.warnings().stream()
                .anyMatch(w -> w.contains("no collection event")));
    }

    @Test
    void placingAnOrderOnSomebodysBehalfSaysWhatToDoInsteadRatherThanDoingSomethingWeaker() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> dispatch.placeOnBehalf(operator,
                        new AdminDispatchRequests.PlaceOrderOnBehalf(1L, "Telephone order.",
                                List.of(new AdminDispatchRequests.Line(1L, null, 1)), 1L, null)));
        // An order priced outside checkout is one nobody can explain.
        assertTrue(refused.getMessage().contains("goes through checkout"));
        assertTrue(refused.getMessage().contains("impersonated session"));
    }

    @Test
    void theBoardSortsByHowLongSomethingHasSatRatherThanByAge() {
        assertEquals(1, dispatch.shipments(operator, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements());
        // Nothing has been sitting for a day, so the stuck filter finds nothing.
        assertEquals(0, dispatch.shipments(operator, null, null, null, 24,
                PageRequest.of(0, 10)).getTotalElements());
    }
}
