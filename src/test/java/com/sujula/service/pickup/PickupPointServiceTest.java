package com.sujula.service.pickup;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sujula.dto.request.pickup.PickupRequests;
import com.sujula.dto.response.pickup.PickupResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.EmailService;
import com.sujula.service.pickup.impl.PickupPointServiceImpl;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A counter that holds parcels.
 *
 * <p>The sharpest claims here are the split between what the public sees and
 * what the operator sees, and that releasing a parcel is a link in the custody
 * chain rather than a status change.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PickupPointServiceImpl.class, PickupCounter.class, CustodyChain.class})
class PickupPointServiceTest {

    @Autowired private PickupPointServiceImpl pickup;
    @Autowired private PickupCounter counter;
    @Autowired private PickupPointRepository points;
    @Autowired private ShipmentRepository shipments;
    @Autowired private ShipmentLegRepository legs;
    @Autowired private CustodyEventRepository events;
    @Autowired private HandoverCodeRepository codes;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    @MockitoBean private EmailService email;

    private PickupPoint point;
    private User operator;
    private Shipment shipment;
    private ShipmentLeg leg;
    private Vendor vendor;

    private static final double LAT = 13.4429;
    private static final double LNG = -16.6776;

    @BeforeEach
    void setUp() {
        operator = user("isatou.pickup@sujula.gm", UserRole.PICKUP_OPERATOR);
        User sellerUser = user("lamin@sujula.gm", UserRole.VENDOR);
        User buyer = user("fatou.ceesay@example.es", UserRole.CUSTOMER);

        vendor = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-electronics")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .build());

        point = points.save(PickupPoint.builder()
                .operatorUser(operator).name("Westfield Junction Kiosk")
                .status(PartnerStatus.APPROVED).active(true)
                .addressStreet("Westfield Junction").city("Serekunda").countryCode("GM")
                .latitude(LAT).longitude(LNG)
                .contactPhone("+2203100008").managerName("Isatou Sanneh")
                .openingHours("Mon-Sat 08:00-20:00")
                .capacity(10).storageDays(7)
                .commissionPerParcel(new BigDecimal("25.00")).commissionCurrency("GMD")
                .build());

        Order order = new Order();
        order.setOrderNumber("SJL-P-0001");
        order.setSubtotal(new BigDecimal("9700.00"));
        order.setTotal(new BigDecimal("9700.00"));
        order.setCurrency("EUR");
        order.setCustomer(buyer);
        order = orders.save(order);

        VendorOrder slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(vendor).status(VendorOrderStatus.READY_FOR_PICKUP)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("9700.00")).totalNative(new BigDecimal("9700.00"))
                .subtotal(new BigDecimal("9700.00")).total(new BigDecimal("9700.00"))
                .build());

        shipment = shipments.save(Shipment.builder()
                .reference("SHP-P0001").vendorOrder(slice)
                .recipientName("Isatou Ceesay").recipientPhone("+2203100077")
                .destinationStreet("12 Kairaba Avenue").destinationCity("Serrekunda")
                .destinationCountry("GM")
                .destinationLatitude(LAT).destinationLongitude(LNG)
                .originLatitude(13.4530).originLongitude(-16.6750)
                .parcelCount(1)
                .build());

        leg = legs.save(ShipmentLeg.builder()
                .shipment(shipment).sequence(1).legType(LegType.ORIGIN_TO_PICKUP)
                .assignmentStatus(LegAssignmentStatus.IN_PROGRESS)
                .destinationPickupPoint(point)
                .destinationLatitude(LAT).destinationLongitude(LNG)
                .destinationLabel("Westfield Junction")
                .build());

        // The parcel has been collected from the shop, which is what makes it
        // a thing a counter can be handed.
        events.save(CustodyEvent.builder()
                .shipment(shipment).leg(leg).type(CustodyEventType.COLLECTED)
                .recordedByUserId(operator.getId()).codePresented("111111")
                .withinGeofence(true).occurredAt(LocalDateTime.now().minusHours(1))
                .clientEventId("seed-collected").build());

        entityManager.flush();
        entityManager.refresh(shipment);
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

    private HandoverCode code(HandoverCodeType type, String value) {
        HandoverCode saved = codes.save(HandoverCode.builder()
                .shipment(shipment).codeType(type).code(value).used(false)
                .expiresAt(LocalDateTime.now().plusDays(2)).build());
        entityManager.flush();
        return saved;
    }

    /** Gets the parcel onto the shelf. */
    private PickupResponses.ParcelAccepted acceptIt() {
        code(HandoverCodeType.VENDOR_TO_PICKUP, "222222");
        PickupResponses.ParcelAccepted accepted = pickup.accept(operator.getId(), point.getId(),
                shipment.getId(), new PickupRequests.AcceptParcel("222222", null, null, null, null));
        entityManager.flush();
        entityManager.refresh(shipment);
        entityManager.refresh(point);
        return accepted;
    }

    // ── The public view says less ────────────────────────────────────────────

    @Test
    void thePublicViewCarriesNoOperatorAndNoCounts() {
        String rendered = pickup.publicPoint(point.getId()).toString();

        // A competitor reading this learns where the counter is and when it
        // opens, which is what a counter wants known.
        assertFalse(rendered.contains("isatou.pickup"), "no operator email");
        assertFalse(rendered.contains("Isatou Sanneh"), "no manager name");
        assertTrue(rendered.contains("Westfield Junction"), "but the address is the point of it");
        assertTrue(rendered.contains("Mon-Sat"), "and the hours");
    }

    @Test
    void howFullACounterIsComesBackAsABandRatherThanACount() {
        assertEquals(PickupResponses.Capacity.AVAILABLE, pickup.publicPoint(point.getId()).capacity());

        // Nine of ten is filling up; that it is exactly nine is the operator's
        // business.
        fillShelf(9);
        assertEquals(PickupResponses.Capacity.LIMITED, pickup.publicPoint(point.getId()).capacity());

        fillShelf(1);
        assertEquals(PickupResponses.Capacity.FULL, pickup.publicPoint(point.getId()).capacity());
        assertFalse(pickup.publicPoint(point.getId()).toString().contains("10 of 10"));
    }

    /** Puts n throwaway parcels on the shelf, to move the band. */
    private void fillShelf(int count) {
        for (int i = 0; i < count; i++) {
            Order order = new Order();
            order.setOrderNumber("SJL-FILL-" + point.getStoredParcels() + "-" + i);
            order.setSubtotal(BigDecimal.ONE);
            order.setTotal(BigDecimal.ONE);
            order.setCurrency("GMD");
            order = orders.save(order);

            VendorOrder slice = vendorOrders.save(VendorOrder.builder()
                    .order(order).vendor(vendor).status(VendorOrderStatus.READY_FOR_PICKUP)
                    .nativeCurrency("GMD").subtotalNative(BigDecimal.ONE).totalNative(BigDecimal.ONE)
                    .subtotal(BigDecimal.ONE).total(BigDecimal.ONE).build());

            Shipment filler = shipments.save(Shipment.builder()
                    .reference("SHP-FILL-" + order.getOrderNumber()).vendorOrder(slice)
                    .heldAtPickupPoint(point).shelfCode("F-" + i)
                    .storedAt(LocalDateTime.now())
                    .storageDeadline(LocalDateTime.now().plusDays(7))
                    .build());
            entityManager.flush();
        }
        counter.recount(point);
        entityManager.flush();
        entityManager.refresh(point);
    }

    @Test
    void aClosedCounterIsNotOfferedToShoppers() {
        point.setClosedUntil(LocalDateTime.now().plusDays(3));
        point.setClosureReason("Away for a funeral");
        points.save(point);
        entityManager.flush();

        PickupResponses.PublicPoint seen = pickup.publicPoint(point.getId());
        assertEquals(PickupResponses.Capacity.CLOSED, seen.capacity());
        assertFalse(seen.openNow());
        // Sending somebody to a shuttered counter is worse than showing nothing.
        assertTrue(pickup.search(new PickupRequests.NearbySearch(LAT, LNG, 5.0, null))
                .points().isEmpty());
    }

    @Test
    void aSuspendedCounterIsNotFoundAtAllRatherThanShownAsUnavailable() {
        point.setStatus(PartnerStatus.SUSPENDED);
        points.save(point);
        entityManager.flush();

        // Its state is its own business, and it is not a place anybody should
        // be sent.
        assertThrows(ResourceNotFoundException.class, () -> pickup.publicPoint(point.getId()));
    }

    @Test
    void aSearchWithNowhereToLookIsRefusedRatherThanListingTheCountry() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> pickup.search(new PickupRequests.NearbySearch(null, null, null, null)));
        assertTrue(refused.getMessage().contains("Say where to look"), refused.getMessage());
    }

    @Test
    void aSearchByTownReportsNoDistanceBecauseThereIsNoneToReport() {
        PickupResponses.PublicPoints found =
                pickup.search(new PickupRequests.NearbySearch(null, null, null, "Serekunda"));

        assertEquals(1, found.points().size());
        // A distance from nowhere is not a number, and a client would sort by it.
        assertNull(found.points().get(0).distanceKm());
        assertNull(found.searchLat());
    }

    // ── Taking a parcel in ───────────────────────────────────────────────────

    @Test
    void acceptingVerifiesTheDriversCodeAndGivesTheParcelAShelf() {
        PickupResponses.ParcelAccepted accepted = acceptIt();

        assertNotNull(accepted.shelfCode());
        assertNotNull(accepted.storageDeadline());
        assertEquals(1, accepted.storedCount());
        // The commission is frozen from the point at the moment of acceptance.
        assertEquals(0, accepted.commission().compareTo(new BigDecimal("25.00")));
        assertEquals(ShipmentStatus.AT_PICKUP_POINT, shipment.getStatus());
    }

    @Test
    void acceptingIsALinkInTheChainRatherThanAStatusChange() {
        acceptIt();

        // The status is derived from this event, not assigned alongside it.
        assertTrue(events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).stream()
                .anyMatch(e -> e.getType() == CustodyEventType.DEPOSITED));
        assertEquals(ShipmentStatus.AT_PICKUP_POINT, shipment.getStatus());
    }

    @Test
    void theWrongDriverCodeIsRefusedAndNothingGoesOnTheShelf() {
        code(HandoverCodeType.VENDOR_TO_PICKUP, "222222");

        assertThrows(BadRequestException.class,
                () -> pickup.accept(operator.getId(), point.getId(), shipment.getId(),
                        new PickupRequests.AcceptParcel("999999", null, null, null, null)));
        entityManager.flush();
        entityManager.refresh(point);

        assertEquals(0, point.getStoredParcels());
    }

    @Test
    void aFullCounterSaysItIsFullRatherThanJustRefusing() {
        fillShelf(10);
        code(HandoverCodeType.VENDOR_TO_PICKUP, "222222");

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> pickup.accept(operator.getId(), point.getId(), shipment.getId(),
                        new PickupRequests.AcceptParcel("222222", null, null, null, null)));
        assertTrue(refused.getMessage().contains("shelf is full"), refused.getMessage());
        assertTrue(refused.getMessage().contains("10 of 10"), refused.getMessage());
    }

    @Test
    void aClosedCounterSaysItIsClosedRatherThanFull() {
        point.setClosedUntil(LocalDateTime.now().plusDays(2));
        points.save(point);
        code(HandoverCodeType.VENDOR_TO_PICKUP, "222222");

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> pickup.accept(operator.getId(), point.getId(), shipment.getId(),
                        new PickupRequests.AcceptParcel("222222", null, null, null, null)));
        // Four different reasons, and the operator is told which.
        assertTrue(refused.getMessage().contains("closed until"), refused.getMessage());
    }

    @Test
    void twoParcelsCannotShareAShelf() {
        acceptIt();
        String taken = shipment.getShelfCode();

        assertThrows(BadRequestException.class,
                () -> counter.allocateShelfCode(point, taken));
    }

    // ── Turning one away ─────────────────────────────────────────────────────

    @Test
    void rejectingLeavesTheParcelWithTheDriver() {
        PickupResponses.ParcelRejected rejected = pickup.reject(operator.getId(), point.getId(),
                shipment.getId(), new PickupRequests.RejectParcel(
                        PickupRequests.RejectParcel.Reason.DAMAGED,
                        "Box is crushed", "https://m.invalid/damage.jpg", null));
        entityManager.flush();
        entityManager.refresh(shipment);
        entityManager.refresh(point);

        // Custody does not move — somebody is still accountable for the parcel.
        assertEquals(ShipmentStatus.ATTEMPT_FAILED, rejected.shipmentStatus());
        assertEquals(0, point.getStoredParcels());
        assertNull(shipment.getHeldAtPickupPoint());
    }

    // ── Handing it over ──────────────────────────────────────────────────────

    @Test
    void releasingNeedsTheRecipientsCodeAndEndsTheChain() {
        acceptIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "333333");

        PickupResponses.ParcelReleased released = pickup.release(operator.getId(), point.getId(),
                shipment.getId(), new PickupRequests.ReleaseParcel("333333", "Isatou Ceesay",
                        "GM-ID-99881", null, null, null, null));
        entityManager.flush();
        entityManager.refresh(shipment);
        entityManager.refresh(point);

        assertEquals(ShipmentStatus.DELIVERED, released.shipmentStatus());
        assertTrue(released.nameMatched());
        assertEquals(0, point.getStoredParcels(), "and the shelf is free");
        assertNull(shipment.getHeldAtPickupPoint());
    }

    @Test
    void aRelativeCollectingIsAllowedAndWrittenDown() {
        acceptIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "333333");

        // The ordinary case here: a brother collects for his sister. Refusing on
        // a name would refuse the thing this marketplace is for.
        PickupResponses.ParcelReleased released = pickup.release(operator.getId(), point.getId(),
                shipment.getId(), new PickupRequests.ReleaseParcel("333333", "Ebrima Jallow",
                        "GM-ID-44120", null, null, "Her brother", null));
        entityManager.flush();

        assertEquals(ShipmentStatus.DELIVERED, released.shipmentStatus());
        assertFalse(released.nameMatched());
        assertTrue(released.message().contains("normal when a relative collects"),
                released.message());

        // And the record says who actually took it.
        assertTrue(events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).stream()
                .anyMatch(e -> e.getType() == CustodyEventType.RELEASED
                        && e.getNote().contains("Ebrima Jallow")));
    }

    @Test
    void aSisterWithTheSameSurnameCountsAsAMatch() {
        acceptIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "333333");

        // Names here are transliterated inconsistently; a shared surname is
        // what makes "her sister came" legible as such.
        assertTrue(pickup.release(operator.getId(), point.getId(), shipment.getId(),
                new PickupRequests.ReleaseParcel("333333", "Awa Ceesay", null, null, null, null, null))
                .nameMatched());
    }

    @Test
    void theWrongCollectionCodeHandsOverNothing() {
        acceptIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "333333");

        assertThrows(BadRequestException.class,
                () -> pickup.release(operator.getId(), point.getId(), shipment.getId(),
                        new PickupRequests.ReleaseParcel("000000", "Isatou Ceesay",
                                null, null, null, null, null)));
        entityManager.flush();
        entityManager.refresh(shipment);

        assertEquals(ShipmentStatus.AT_PICKUP_POINT, shipment.getStatus());
        assertNotNull(shipment.getHeldAtPickupPoint(), "it is still on the shelf");
    }

    @Test
    void thereIsNothingToReleaseWithoutACodeHavingBeenSent() {
        acceptIt();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> pickup.release(operator.getId(), point.getId(), shipment.getId(),
                        new PickupRequests.ReleaseParcel("333333", "Isatou Ceesay",
                                null, null, null, null, null)));
        assertTrue(refused.getMessage().contains("Send one to the buyer"), refused.getMessage());
    }

    // ── Sending it back ──────────────────────────────────────────────────────

    @Test
    void aParcelCannotBeSentBackWhileItCanStillBeCollected() {
        acceptIt();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> pickup.returnToVendor(operator.getId(), point.getId(), shipment.getId(),
                        new PickupRequests.ReturnParcel(null, null)));

        // Somebody may be travelling to collect it; sending it back early takes
        // a decision that is not the counter's to take.
        assertTrue(refused.getMessage().contains("can still be collected"), refused.getMessage());
    }

    @Test
    void anOverdueParcelGoesBack() {
        acceptIt();
        shipment.setStorageDeadline(LocalDateTime.now().minusDays(2));
        shipments.save(shipment);
        entityManager.flush();

        PickupResponses.ParcelReturning returning = pickup.returnToVendor(operator.getId(),
                point.getId(), shipment.getId(), new PickupRequests.ReturnParcel(null, null));
        entityManager.flush();
        entityManager.refresh(shipment);
        entityManager.refresh(point);

        assertEquals(ShipmentStatus.RETURNED, returning.shipmentStatus());
        assertEquals(2, returning.daysOverdue());
        assertEquals(0, point.getStoredParcels());
    }

    // ── Resending the code ───────────────────────────────────────────────────

    @Test
    void resendingEmailsTheBuyerAndNeverShowsTheOperatorTheCode() {
        acceptIt();

        PickupResponses.CodeResent resent = pickup.resendCode(operator.getId(), point.getId(),
                shipment.getId());

        verify(email).sendRecipientReleaseCode(eq("fatou.ceesay@example.es"), anyString(),
                eq("Isatou Ceesay"), eq("SJL-P-0001"), anyString(), any());
        assertTrue(resent.sent());

        // An operator who could read it could hand the parcel to whoever is
        // standing there.
        String issued = codes.findLiveForShipment(shipment.getId(),
                HandoverCodeType.RECIPIENT_RELEASE, LocalDateTime.now()).get(0).getCode();
        assertFalse(resent.toString().contains(issued), resent.toString());
        assertFalse(resent.sentTo().contains("fatou.ceesay"), resent.sentTo());
    }

    @Test
    void resendingKillsTheCodeTheyHadBefore() {
        acceptIt();
        pickup.resendCode(operator.getId(), point.getId(), shipment.getId());
        entityManager.flush();
        String first = codes.findLiveForShipment(shipment.getId(),
                HandoverCodeType.RECIPIENT_RELEASE, LocalDateTime.now()).get(0).getCode();

        pickup.resendCode(operator.getId(), point.getId(), shipment.getId());
        entityManager.flush();

        List<HandoverCode> live = codes.findLiveForShipment(shipment.getId(),
                HandoverCodeType.RECIPIENT_RELEASE, LocalDateTime.now());
        assertEquals(1, live.size(), "two working codes is two chances to read the wrong one out");
        assertFalse(live.get(0).getCode().equals(first));
    }

    @Test
    void resendingTooOftenIsRefused() {
        acceptIt();
        for (int i = 0; i < 3; i++) {
            pickup.resendCode(operator.getId(), point.getId(), shipment.getId());
            entityManager.flush();
        }

        assertThrows(BadRequestException.class,
                () -> pickup.resendCode(operator.getId(), point.getId(), shipment.getId()));
    }

    // ── The shelf count is recounted, never nudged ───────────────────────────

    @Test
    void theStoredCountIsAlwaysWhatIsActuallyOnTheShelf() {
        acceptIt();
        fillShelf(3);

        int actual = (int) shipments.countByHeldAtPickupPointId(point.getId());
        assertEquals(actual, point.getStoredParcels().intValue());

        // Recounting changes nothing, which is the property worth having: a
        // number that could drift would drift into accepting parcels there is
        // no room for.
        assertEquals(actual, counter.recount(point));
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    @Test
    void anotherOperatorsCounterIsNotFound() {
        User stranger = user("stranger@sujula.gm", UserRole.PICKUP_OPERATOR);
        entityManager.flush();

        assertThrows(ResourceNotFoundException.class,
                () -> pickup.parcels(stranger.getId(), point.getId()));
        assertThrows(ResourceNotFoundException.class,
                () -> pickup.resendCode(stranger.getId(), point.getId(), shipment.getId()));
        verify(email, never()).sendRecipientReleaseCode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aParcelOnAnotherCounterIsNotFound() {
        acceptIt();
        User otherOperator = user("other@sujula.gm", UserRole.PICKUP_OPERATOR);
        PickupPoint other = points.save(PickupPoint.builder()
                .operatorUser(otherOperator).name("Latrikunda Kiosk")
                .status(PartnerStatus.APPROVED).active(true)
                .addressStreet("Latrikunda Sabiji Road")
                .city("Latrikunda").countryCode("GM").latitude(13.41).longitude(-16.69)
                .capacity(10).build());
        entityManager.flush();

        assertThrows(ResourceNotFoundException.class,
                () -> pickup.release(otherOperator.getId(), other.getId(), shipment.getId(),
                        new PickupRequests.ReleaseParcel("333333", "Isatou Ceesay",
                                null, null, null, null, null)));
    }

    // ── The operator's own view ──────────────────────────────────────────────

    @Test
    void anIncomingParcelCarriesNoRecipientBecauseItIsNotHereYet() {
        PickupResponses.Parcels piles = pickup.parcels(operator.getId(), point.getId());

        assertEquals(1, piles.incoming().size());
        assertTrue(piles.stored().isEmpty());
        assertFalse(piles.incoming().toString().contains("Isatou Ceesay"),
                "an operator expecting parcels needs to know that, not who they are for");
    }

    @Test
    void aStoredParcelCarriesTheNameToCheckAndOnlyAHintOfTheNumber() {
        acceptIt();

        PickupResponses.StoredParcel stored =
                pickup.parcels(operator.getId(), point.getId()).stored().get(0);

        // The name is needed to check who is collecting ...
        assertEquals("Isatou Ceesay", stored.recipientName());
        // ... the full number is not.
        assertFalse(stored.recipientPhoneHint().contains("2203100077"));
        assertTrue(stored.recipientPhoneHint().endsWith("077"));
    }

    @Test
    void overdueParcelsAreTheirOwnPile() {
        acceptIt();
        shipment.setStorageDeadline(LocalDateTime.now().minusDays(1));
        shipments.save(shipment);
        entityManager.flush();

        PickupResponses.Parcels piles = pickup.parcels(operator.getId(), point.getId());
        assertTrue(piles.stored().isEmpty());
        assertEquals(1, piles.overdue().size());
        assertTrue(piles.note().contains("should"), piles.note());
    }

    @Test
    void capacityCannotBeSetBelowWhatIsAlreadyOnTheShelf() {
        fillShelf(6);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> pickup.updatePoint(operator.getId(), point.getId(),
                        new PickupRequests.UpdatePoint(null, null, 3, null, null, null, null,
                                null, null)));
        // The parcels do not go away because a number changed.
        assertTrue(refused.getMessage().contains("holding 6"), refused.getMessage());
    }

    @Test
    void closingKeepsTheParcelsAlreadyOnTheShelf() {
        acceptIt();

        PickupResponses.OperatorPoint closed = pickup.updatePoint(operator.getId(), point.getId(),
                new PickupRequests.UpdatePoint(null, null, null, null, null, null, null,
                        LocalDateTime.now().plusDays(3), "Away for a funeral"));

        assertEquals(1, closed.storedParcels());
        assertTrue(closed.message().contains("stay yours to release"), closed.message());
    }

    // ── Money ────────────────────────────────────────────────────────────────

    @Test
    void earningsAreCountedPerParcelAndKeptApartByCurrency() {
        acceptIt();

        PickupResponses.Earnings earnings = pickup.earnings(operator.getId(), point.getId(),
                java.time.LocalDate.now().minusDays(1), java.time.LocalDate.now().plusDays(1));

        assertEquals(1, earnings.parcelsHandled());
        assertEquals(1, earnings.byCurrency().size());
        assertEquals("GMD", earnings.byCurrency().get(0).currency());
        assertEquals(0, earnings.byCurrency().get(0).total().compareTo(new BigDecimal("25.00")));
    }
}
