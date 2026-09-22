package com.sujula.service.recipient;

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

import com.sujula.dto.request.recipient.RecipientRequests;
import com.sujula.dto.response.recipient.RecipientResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.RecipientInstructionType;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.ParcelAccessCode;
import com.sujula.model.shipment.RecipientInstruction;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ParcelAccessCodeRepository;
import com.sujula.repository.shipment.RecipientInstructionRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.EmailService;
import com.sujula.service.recipient.impl.RecipientParcelServiceImpl;
import com.sujula.service.shipment.CustodyChain;
import com.sujula.service.shipment.RecipientDirectives;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * The sister in Serrekunda, who did not sign up for anything.
 *
 * <p>What is being asserted here is mostly what the surface refuses to say and
 * refuses to accept: that the page carries no surname, street or phone number;
 * that asking for a code has nowhere to put a destination; that a wrong code
 * burns; and that an instruction is a record with proof against it rather than
 * a column somebody set.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({RecipientParcelServiceImpl.class, RecipientDirectives.class, CustodyChain.class})
class RecipientParcelServiceTest {

    @Autowired private RecipientParcelServiceImpl parcels;
    @Autowired private RecipientDirectives directives;
    @Autowired private ShipmentRepository shipments;
    @Autowired private ShipmentLegRepository legs;
    @Autowired private CustodyEventRepository events;
    @Autowired private ParcelAccessCodeRepository accessCodes;
    @Autowired private RecipientInstructionRepository instructions;
    @Autowired private PickupPointRepository points;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    @MockitoBean private EmailService email;

    private Shipment shipment;
    private ShipmentLeg leg;
    private PickupPoint counter;
    private User buyer;

    private static final double LAT = 13.4429;
    private static final double LNG = -16.6776;

    @BeforeEach
    void setUp() {
        User sellerUser = user("lamin.shop@sujula.gm", UserRole.VENDOR);
        User operator = user("isatou.counter@sujula.gm", UserRole.PICKUP_OPERATOR);
        // He pays from Madrid. She receives in Serrekunda. That is the shape.
        buyer = user("ousman.jallow@example.es", UserRole.CUSTOMER);

        Vendor vendor = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-recipient")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .build());

        counter = points.save(PickupPoint.builder()
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
        order.setOrderNumber("SJL-R-0001");
        order.setSubtotal(new BigDecimal("180.00"));
        order.setTotal(new BigDecimal("180.00"));
        order.setCurrency("EUR");
        order.setCustomer(buyer);
        order = orders.save(order);

        VendorOrder slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(vendor).status(VendorOrderStatus.READY_FOR_PICKUP)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("12000.00")).totalNative(new BigDecimal("12000.00"))
                .subtotal(new BigDecimal("180.00")).total(new BigDecimal("180.00"))
                .build());

        shipment = shipments.save(Shipment.builder()
                .reference("SHP-R0001").vendorOrder(slice)
                .recipientName("Fatou Ceesay Njie").recipientPhone("+2203100077")
                .destinationStreet("12 Kairaba Avenue").destinationCity("Serrekunda")
                .destinationCountry("GM")
                .destinationLatitude(LAT).destinationLongitude(LNG)
                .originLatitude(13.4530).originLongitude(-16.6750)
                .parcelCount(1)
                .build());

        leg = legs.save(ShipmentLeg.builder()
                .shipment(shipment).sequence(1).legType(LegType.ORIGIN_TO_RECIPIENT)
                .assignmentStatus(LegAssignmentStatus.IN_PROGRESS)
                .destinationLatitude(LAT).destinationLongitude(LNG)
                .destinationLabel("Kairaba Avenue")
                .build());

        events.save(CustodyEvent.builder()
                .shipment(shipment).leg(leg).type(CustodyEventType.COLLECTED)
                .recordedByUserId(sellerUser.getId()).codePresented("111111")
                .withinGeofence(true).occurredAt(LocalDateTime.now().minusHours(2))
                .clientEventId("seed-collected-recipient").build());

        entityManager.flush();
        entityManager.refresh(shipment);
    }

    private User user(String emailAddress, UserRole role) {
        User person = new User();
        person.setEmail(emailAddress);
        person.setPassword("x");
        person.setFirstName("A");
        person.setLastName("Person");
        person.setRole(role);
        return users.save(person);
    }

    /** Asks for a code and reads back what was actually stored. */
    private String issueCode() {
        parcels.requestCode(shipment.getTrackingCode());
        entityManager.flush();
        List<ParcelAccessCode> live = accessCodes.findLive(shipment.getId(), LocalDateTime.now());
        assertEquals(1, live.size(), "one live code at a time");
        return live.get(0).getCode();
    }

    // ── The code exists at all ───────────────────────────────────────────────

    @Test
    void everyParcelGetsATrackingCodeWithoutAnybodyRememberingTo() {
        // Nothing in the setup asked for one. A parcel with no tracking code is
        // a parcel the person waiting for it cannot reach, and the failure is
        // silent — everything else about the shipment works.
        assertNotNull(shipment.getTrackingCode());
        assertEquals(16, shipment.getTrackingCode().length());
        assertFalse(shipment.getTrackingCode().matches(".*[IOL01U].*"),
                "read aloud down a telephone line, so no characters that sound like each other");
        assertFalse(shipment.getTrackingCode().equals(shipment.getReference()),
                "the reference is what support quotes; this is what the recipient holds");
    }

    // ── The page ─────────────────────────────────────────────────────────────

    @Test
    void thePageCarriesAFirstNameAndATownAndNothingThatNamesAHousehold() {
        RecipientResponses.Parcel page = parcels.parcel(shipment.getTrackingCode());

        assertEquals("Fatou", page.forName(), "a first name is enough to recognise your own parcel");
        assertEquals("Serrekunda", page.destinationCity());
        assertEquals("GM", page.destinationCountry());

        String rendered = page.toString();
        assertFalse(rendered.contains("Ceesay"), "no surname");
        assertFalse(rendered.contains("Njie"), "no surname");
        assertFalse(rendered.contains("Kairaba"), "no street — a street names a home");
        assertFalse(rendered.contains("2203100077"), "no phone number");
        assertFalse(rendered.contains("SJL-R-0001"), "no order number");
        assertFalse(rendered.contains("180"), "no prices");
        assertFalse(rendered.contains("Kombo Electronics"), "no seller");
    }

    @Test
    void theHistoryIsWrittenHereRatherThanTakenFromWhatADriverTyped() {
        events.save(CustodyEvent.builder()
                .shipment(shipment).leg(leg).type(CustodyEventType.FAILED_ATTEMPT)
                .recordedByUserId(1L)
                .note("Nobody home, spoke to Ndey next door, number is +2203100099")
                .occurredAt(LocalDateTime.now().minusHours(1))
                .clientEventId("free-text").build());
        entityManager.flush();

        RecipientResponses.Parcel page = parcels.parcel(shipment.getTrackingCode());
        // A driver's note is free text on a page anybody who has the link can
        // open. That is how a neighbour's phone number gets published.
        assertFalse(page.toString().contains("2203100099"));
        assertFalse(page.toString().contains("Ndey"));
        assertTrue(page.history().stream()
                .anyMatch(step -> "A delivery attempt did not succeed.".equals(step.description())));
    }

    @Test
    void aTrackingCodeThatIsNotAParcelIsNotFoundRatherThanRefused() {
        assertThrows(ResourceNotFoundException.class, () -> parcels.parcel("NOTAREALCODE1234"));
    }

    // ── The code ─────────────────────────────────────────────────────────────

    @Test
    void theCodeGoesToTheContactOnTheOrderAndTheRequestHasNowhereToPutADestination() {
        RecipientResponses.CodeSent sent = parcels.requestCode(shipment.getTrackingCode());

        // The buyer, not the recipient. She may have no email at all; he paid
        // for the parcel and has one. He reads her the number.
        verify(email).sendParcelAccessCode(eq("ousman.jallow@example.es"), anyString(),
                eq("Fatou"), eq(shipment.getTrackingCode()), anyString(), any());

        assertTrue(sent.sent());
        assertTrue(sent.sentTo().contains("•"), "masked, so the page does not publish his address");
        assertFalse(sent.toString().matches(".*\\b\\d{6}\\b.*"), "the code is never in the response");
    }

    @Test
    void askingRepeatedlyStopsBeforeTheBuyersInboxDoes() {
        parcels.requestCode(shipment.getTrackingCode());
        parcels.requestCode(shipment.getTrackingCode());
        parcels.requestCode(shipment.getTrackingCode());
        entityManager.flush();

        // The limit is on the parcel, because whoever is asking is anonymous by
        // design — somebody who found a tracking code must not be able to fill
        // an inbox by holding down a button.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> parcels.requestCode(shipment.getTrackingCode()));
        assertTrue(refused.getMessage().contains("3 times in the last hour"));
    }

    @Test
    void aNewCodeKillsTheOldOne() {
        String first = issueCode();
        String second = issueCode();

        entityManager.flush();
        assertThrows(BadRequestException.class, () -> parcels.reschedule(
                shipment.getTrackingCode(), reschedule(first)),
                "two live codes is two chances for the wrong one to be read out");
        assertNotNull(parcels.reschedule(shipment.getTrackingCode(), reschedule(second)));
    }

    @Test
    void fiveWrongGuessesBurnTheCode() {
        String real = issueCode();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(BadRequestException.class, () -> parcels.reschedule(
                    shipment.getTrackingCode(), reschedule("000000")));
        }
        entityManager.flush();

        BadRequestException dead = assertThrows(BadRequestException.class,
                () -> parcels.reschedule(shipment.getTrackingCode(), reschedule(real)));
        assertTrue(dead.getMessage().contains("too many times"),
                "the right code stops working too — the guesser does not know which one they hit");
    }

    @Test
    void theCodeSurvivesBeingUsedBecauseSheMayChangeHerMindTwice() {
        String code = issueCode();

        parcels.choosePickupPoint(shipment.getTrackingCode(),
                new RecipientRequests.ChoosePickupPoint(code, counter.getId()));
        // Burning it here would mean telephoning Madrid again between choosing
        // a counter and asking for a different day.
        assertNotNull(parcels.reschedule(shipment.getTrackingCode(), reschedule(code)));

        entityManager.flush();
        ParcelAccessCode stored = accessCodes.findLive(shipment.getId(), LocalDateTime.now()).get(0);
        assertEquals(2, stored.getInstructionsGiven());
        assertNotNull(stored.getLastUsedAt());
    }

    // ── The instructions ─────────────────────────────────────────────────────

    @Test
    void choosingACounterIsARecordWithTheCodeThatWasPresentedAgainstIt() {
        String code = issueCode();
        parcels.choosePickupPoint(shipment.getTrackingCode(),
                new RecipientRequests.ChoosePickupPoint(code, counter.getId()));
        entityManager.flush();
        entityManager.refresh(shipment);

        RecipientInstruction recorded = instructions.findInForceOfType(
                shipment.getId(), RecipientInstructionType.CHOOSE_PICKUP_POINT).orElseThrow();
        assertEquals(counter.getId(), recorded.getPickupPoint().getId());
        assertNotNull(recorded.getVerifiedByCodeId(), "who said so, and what they held when they did");
        assertNotNull(recorded.getVerifiedAt());

        // And the derived summary agrees with the record, because it is a
        // function of it.
        assertEquals(counter.getId(), shipment.getRequestedPickupPoint().getId());
    }

    @Test
    void changingYourMindWritesASecondRowRatherThanEditingTheFirst() {
        String code = issueCode();
        parcels.reschedule(shipment.getTrackingCode(), reschedule(code,
                LocalDateTime.now().plusDays(1)));
        parcels.reschedule(shipment.getTrackingCode(), reschedule(code,
                LocalDateTime.now().plusDays(3)));
        entityManager.flush();

        List<RecipientInstruction> all = instructions
                .findByShipmentIdOrderByCreatedAtAscIdAsc(shipment.getId());
        assertEquals(2, all.size(), "append-only");
        assertNotNull(all.get(0).getSupersededAt(),
                "a driver who set off on the first one has to be able to show it was the instruction");
        assertNull(all.get(1).getSupersededAt());
        assertEquals(1, instructions.findInForce(shipment.getId()).size());
    }

    @Test
    void aCounterInTheWrongCountryIsRefusedBecauseACounterIsADeliveryAnswer() {
        PickupPoint madrid = points.save(PickupPoint.builder()
                .operatorUser(users.findAll().get(0)).name("Lavapiés Locker")
                .status(PartnerStatus.APPROVED).active(true)
                .addressStreet("Calle de Argumosa 12").city("Madrid").countryCode("ES")
                .latitude(40.4090).longitude(-3.7010)
                .capacity(10).storageDays(7)
                .commissionPerParcel(new BigDecimal("1.00")).commissionCurrency("EUR")
                .build());
        String code = issueCode();

        // C1: the buyer is in Madrid, the parcel is going to Serrekunda, and a
        // counter is chosen against the delivery context or against nothing.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> parcels.choosePickupPoint(shipment.getTrackingCode(),
                        new RecipientRequests.ChoosePickupPoint(code, madrid.getId())));
        assertTrue(refused.getMessage().contains("going to GM"));
    }

    @Test
    void aFullCounterIsRefusedWithSomethingToDoInstead() {
        counter.applyStoredCount(counter.getCapacity());
        points.save(counter);
        String code = issueCode();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> parcels.choosePickupPoint(shipment.getTrackingCode(),
                        new RecipientRequests.ChoosePickupPoint(code, counter.getId())));
        assertTrue(refused.getMessage().contains("Choose another"));
    }

    @Test
    void aWindowInThePastIsRefused() {
        String code = issueCode();
        assertThrows(BadRequestException.class, () -> parcels.reschedule(
                shipment.getTrackingCode(),
                new RecipientRequests.Reschedule(code, LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().plusDays(1), null)));
    }

    @Test
    void aWindowTooFarOutIsRefusedAndPointsAtTheCounterNetwork() {
        String code = issueCode();
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> parcels.reschedule(shipment.getTrackingCode(),
                        new RecipientRequests.Reschedule(code, LocalDateTime.now().plusDays(40),
                                LocalDateTime.now().plusDays(41), null)));
        assertTrue(refused.getMessage().contains("collection point"));
    }

    // ── Safe drop ────────────────────────────────────────────────────────────

    @Test
    void aSafeDropKeepsTheWordsSheUsed() {
        String code = issueCode();
        parcels.authoriseSafeDrop(shipment.getTrackingCode(),
                new RecipientRequests.AuthoriseSafeDrop(code,
                        "with the pharmacy next door", "Ndey", null));
        entityManager.flush();
        entityManager.refresh(shipment);

        RecipientInstruction recorded = instructions.findInForceOfType(
                shipment.getId(), RecipientInstructionType.AUTHORISE_SAFE_DROP).orElseThrow();
        // Never normalised. "Behind the shop" is an address in this market, and
        // parsing it into a street and a number would lose the only part a
        // driver can act on.
        assertEquals("with the pharmacy next door", recorded.getSafeDropLocation());
        assertEquals("Ndey", recorded.getSafeDropPerson());
        assertTrue(shipment.isSafeDropAuthorised());
    }

    @Test
    void leaveItSomewhereIsNotAnInstructionADriverCanActOn() {
        String code = issueCode();
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> parcels.authoriseSafeDrop(shipment.getTrackingCode(),
                        new RecipientRequests.AuthoriseSafeDrop(code, "  ", null, null)));
        assertTrue(refused.getMessage().contains("where it should be left"));
    }

    @Test
    void withdrawingASafeDropSupersedesItRatherThanDeletingIt() {
        String code = issueCode();
        parcels.authoriseSafeDrop(shipment.getTrackingCode(),
                new RecipientRequests.AuthoriseSafeDrop(code, "behind the shop", null, null));
        parcels.authoriseSafeDrop(shipment.getTrackingCode(),
                new RecipientRequests.AuthoriseSafeDrop(code, null, null, false));
        entityManager.flush();
        entityManager.refresh(shipment);

        assertFalse(shipment.isSafeDropAuthorised());
        assertNull(shipment.getSafeDropLocation());
        // The row survives. It is how anybody later works out whether the driver
        // was acting on a live authorisation at the time.
        List<RecipientInstruction> all = instructions
                .findByShipmentIdOrderByCreatedAtAscIdAsc(shipment.getId());
        assertEquals(1, all.size());
        assertNotNull(all.get(0).getSupersededAt());
    }

    @Test
    void sendingItToACounterTakesBackASafeDropBecauseACounterReleasesAgainstACode() {
        String code = issueCode();
        parcels.authoriseSafeDrop(shipment.getTrackingCode(),
                new RecipientRequests.AuthoriseSafeDrop(code, "behind the shop", null, null));
        parcels.choosePickupPoint(shipment.getTrackingCode(),
                new RecipientRequests.ChoosePickupPoint(code, counter.getId()));
        entityManager.flush();
        entityManager.refresh(shipment);

        // An authorisation nobody will ever read is worse than none: it is still
        // on the record as permission.
        assertFalse(shipment.isSafeDropAuthorised());
        assertEquals(counter.getId(), shipment.getRequestedPickupPoint().getId());
    }

    // ── When it is too late ──────────────────────────────────────────────────

    @Test
    void aParcelAlreadyOnAShelfIsNotRedirectedFromThisPage() {
        shipment.setHeldAtPickupPoint(counter);
        shipment.setStoredAt(LocalDateTime.now());
        shipments.save(shipment);
        entityManager.flush();
        String code = issueCode();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> parcels.reschedule(shipment.getTrackingCode(), reschedule(code)));
        assertTrue(refused.getMessage().contains("Westfield Junction Kiosk"));

        RecipientResponses.Parcel page = parcels.parcel(shipment.getTrackingCode());
        assertFalse(page.youCan().reschedule());
        assertFalse(page.youCan().choosePickupPoint());
        assertNotNull(page.youCan().why(), "a refusal with no reason is what makes somebody telephone");
        assertNotNull(page.waitingAt(), "she has to be told where to walk to");
        assertNull(page.waitingAt().toString().contains("shelf") ? "leaked" : null,
                "the shelf code is the operator's filing system, not hers");
    }

    @Test
    void aDeliveredParcelHasNothingLeftToChange() {
        events.save(CustodyEvent.builder()
                .shipment(shipment).leg(leg).type(CustodyEventType.RELEASED)
                .recordedByUserId(1L).codePresented("222222")
                .withinGeofence(true).occurredAt(LocalDateTime.now())
                .clientEventId("released-recipient").build());
        entityManager.flush();

        // Derived from the chain, not set — so this is what the custody events
        // say, not what anybody typed.
        directives.rederive(shipment);
        shipment = shipments.findById(shipment.getId()).orElseThrow();
        shipment.applyDerivedState(com.sujula.model.constant.ShipmentStatus.DELIVERED, 0,
                LocalDateTime.now().minusHours(2), LocalDateTime.now(), null);
        shipments.save(shipment);
        entityManager.flush();

        assertThrows(BadRequestException.class,
                () -> parcels.requestCode(shipment.getTrackingCode()));
        assertFalse(parcels.parcel(shipment.getTrackingCode()).youCan().authoriseSafeDrop());
    }

    // ── Re-deriving changes nothing ──────────────────────────────────────────

    @Test
    void rederivingTheDirectivesOverExistingRowsChangesNothing() {
        String code = issueCode();
        parcels.choosePickupPoint(shipment.getTrackingCode(),
                new RecipientRequests.ChoosePickupPoint(code, counter.getId()));
        parcels.reschedule(shipment.getTrackingCode(), reschedule(code));
        entityManager.flush();
        entityManager.refresh(shipment);

        Long before = shipment.getRequestedPickupPoint().getId();
        LocalDateTime windowBefore = shipment.getRequestedWindowFrom();

        directives.rederive(shipment);
        entityManager.flush();
        entityManager.refresh(shipment);

        // If this ever changes something, the summary had drifted and the
        // instructions were right.
        assertEquals(before, shipment.getRequestedPickupPoint().getId());
        assertEquals(windowBefore, shipment.getRequestedWindowFrom());
    }

    private RecipientRequests.Reschedule reschedule(String code) {
        return reschedule(code, LocalDateTime.now().plusDays(2));
    }

    private RecipientRequests.Reschedule reschedule(String code, LocalDateTime from) {
        return new RecipientRequests.Reschedule(code, from, from.plusHours(4), null);
    }
}
