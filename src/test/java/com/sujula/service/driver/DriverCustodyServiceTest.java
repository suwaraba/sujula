package com.sujula.service.driver;

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

import com.sujula.dto.request.driver.DriverRequests;
import com.sujula.dto.response.driver.DriverResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
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
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.delivery.DriverRepository;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.EmailService;
import com.sujula.service.driver.impl.DriverCustodyServiceImpl;
import com.sujula.service.shipment.CustodyChain;
import com.sujula.service.shipment.RecipientDirectives;
import com.sujula.service.shipment.HomeShipmentCoordinator;

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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a driver may do to a parcel, and what they may know about it.
 *
 * <p>The two sharpest claims here are the privacy window — an address that
 * appears only while the driver is carrying the goods — and the offline sync,
 * which has to record a day's work exactly once however many times a phone
 * uploads it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// FeatureFlags is real rather than mocked: safe drop is behind a flag, and a
// stub that always said "on" would pass whether or not the flag was ever read.
@Import({DriverCustodyServiceImpl.class, CustodyChain.class, RecipientDirectives.class,
         HomeShipmentCoordinator.class,
         com.sujula.service.platform.FeatureFlags.class})
class DriverCustodyServiceTest {

    @Autowired private DriverCustodyServiceImpl custody;
    @Autowired private ShipmentRepository shipments;
    @Autowired private ShipmentLegRepository legs;
    @Autowired private CustodyEventRepository events;
    @Autowired private HandoverCodeRepository codes;
    @Autowired private DriverRepository drivers;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private VendorLedgerEntryRepository vendorLedger;
    @Autowired private OrderRepository orders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private RecipientDirectives recipientDirectives;

    @MockitoBean private EmailService email;
    // Unconfigured unless a test says otherwise, which is the default deployment.
    @MockitoBean private com.sujula.service.notification.SmsSender sms;

    @Autowired private com.sujula.repository.platform.FeatureFlagRepository featureFlags;
    @Autowired private com.sujula.service.platform.FeatureFlags flags;

    private Shipment shipment;
    private Driver driver;
    private User driverUser;
    private ShipmentLeg leg;

    private static final double DEST_LAT = 13.4384;
    private static final double DEST_LNG = -16.6781;
    private static final double ORIGIN_LAT = 13.4530;
    private static final double ORIGIN_LNG = -16.6750;

    @BeforeEach
    void setUp() {
        User sellerUser = user("lamin@sujula.gm", UserRole.VENDOR);
        driverUser = user("ebrima@sujula.gm", UserRole.DELIVERY);
        User buyer = user("fatou.ceesay@example.es", UserRole.CUSTOMER);

        Vendor vendor = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-electronics")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .storePhone("+2204380001")
                .build());

        driver = drivers.save(Driver.builder()
                .user(driverUser).status(DriverStatus.APPROVED).available(true)
                .vehicleType(VehicleType.MOTOR).zone("Serrekunda").countryCode("GM")
                .maxWeight(20)
                .build());

        Order order = new Order();
        order.setOrderNumber("SJL-D-0001");
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
                .reference("SHP-D0001").vendorOrder(slice)
                .recipientName("Isatou Ceesay").recipientPhone("+2203100077")
                .destinationStreet("12 Kairaba Avenue").destinationCity("Serrekunda")
                .destinationCountry("GM")
                .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                .originLatitude(ORIGIN_LAT).originLongitude(ORIGIN_LNG)
                .originAddress("14 Kairaba Avenue, Serekunda")
                .parcelCount(1)
                .build());

        leg = legs.save(ShipmentLeg.builder()
                .shipment(shipment).sequence(1).legType(LegType.ORIGIN_TO_RECIPIENT)
                .assignmentStatus(LegAssignmentStatus.ACCEPTED).driver(driver)
                .originLatitude(ORIGIN_LAT).originLongitude(ORIGIN_LNG)
                .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                .destinationLabel("Serrekunda")
                .earning(new BigDecimal("150.00")).earningCurrency("GMD")
                .build());

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
        HandoverCode.HandoverCodeBuilder builder = HandoverCode.builder()
                .codeType(type).code(value).used(false)
                .expiresAt(LocalDateTime.now().plusDays(2));
        if (type == HandoverCodeType.VENDOR_RELEASE) {
            builder.vendorOrder(shipment.getVendorOrder());
        } else {
            builder.shipment(shipment);
        }
        HandoverCode saved = codes.save(builder.build());
        entityManager.flush();
        return saved;
    }

    private DriverRequests.Handover handover(String code, double lat, double lng, String photo) {
        return new DriverRequests.Handover(code, null, lat, lng, new BigDecimal("10.00"),
                photo, null, null, null, null);
    }

    /** Gets the parcel into the driver's hands. */
    private void collectIt() {
        arriveIt(null, null);
        code(HandoverCodeType.VENDOR_RELEASE, "111111");
        custody.collect(driverUser.getId(), shipment.getId(),
                handover("111111", ORIGIN_LAT, ORIGIN_LNG, null));
        entityManager.flush();
        entityManager.refresh(shipment);
    }

    private void arriveIt(LocalDateTime capturedAt, String clientEventId) {
        custody.arrivedAtOrigin(driverUser.getId(), shipment.getId(),
                new DriverRequests.Arrived(ORIGIN_LAT, ORIGIN_LNG,
                        new BigDecimal("10.00"), capturedAt, clientEventId));
    }

    private DriverRequests.SyncEntry offline(String type, String eventId,
                                              LocalDateTime capturedAt, String code,
                                              String photoUrl, String reasonCode) {
        boolean atDestination = "RELEASED".equals(type) || "FAILED_ATTEMPT".equals(type);
        return new DriverRequests.SyncEntry(shipment.getId(), type, eventId, capturedAt, code,
                atDestination ? DEST_LAT : ORIGIN_LAT,
                atDestination ? DEST_LNG : ORIGIN_LNG,
                new BigDecimal("10.00"), photoUrl, reasonCode, null);
    }

    // ── The privacy window ───────────────────────────────────────────────────

    @Test
    void beforeCollectingTheDriverGetsNoAddressAtAll() {
        DriverResponses.ShipmentDetail detail =
                custody.shipment(driverUser.getId(), shipment.getId());

        // Absent, not blanked. A client cannot render empty fields where an
        // address used to be, and a future caller cannot forget to check a flag.
        assertNull(detail.destination());
        assertFalse(detail.custodyActive());
        assertTrue(detail.privacyNote().contains("once you are carrying"));
    }

    @Test
    void whileCarryingItTheDriverGetsWhatTheyNeedToFindTheDoor() {
        collectIt();
        entityManager.refresh(shipment.getVendorOrder());

        DriverResponses.ShipmentDetail detail =
                custody.shipment(driverUser.getId(), shipment.getId());

        assertNotNull(detail.destination());
        assertEquals("Isatou Ceesay", detail.destination().recipientName());
        assertEquals("+2203100077", detail.destination().recipientPhone());
        assertEquals("12 Kairaba Avenue", detail.destination().street());
        assertTrue(detail.custodyActive());
        assertEquals(VendorOrderStatus.SHIPPED, shipment.getVendorOrder().getStatus());
    }

    @Test
    void onceHandedOverTheAddressGoesAway() {
        collectIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "222222");
        custody.deliver(driverUser.getId(), shipment.getId(),
                handover("222222", DEST_LAT, DEST_LNG, "https://media.invalid/pod.jpg"));
        entityManager.flush();
        entityManager.refresh(shipment);
        entityManager.refresh(shipment.getVendorOrder());

        DriverResponses.ShipmentDetail detail =
                custody.shipment(driverUser.getId(), shipment.getId());

        assertEquals(ShipmentStatus.DELIVERED, detail.status());
        assertEquals(VendorOrderStatus.SHIPPED, shipment.getVendorOrder().getStatus(),
                "physical delivery must not bypass buyer receipt confirmation");
        assertNull(shipment.getVendorOrder().getReceiptConfirmedAt());
        assertNull(shipment.getVendorOrder().getEscrowReleasedAt());
        assertTrue(vendorLedger.findByVendorOrderIdOrderByOccurredAtAsc(
                shipment.getVendorOrder().getId()).isEmpty(),
                "custody delivery must not post SALE or COMMISSION ledger entries");
        // The job is done. A driver who handed a parcel over an hour ago has no
        // reason to still hold somebody's front door.
        assertNull(detail.destination());
        assertFalse(detail.custodyActive());
    }

    @Test
    void anotherDriversParcelIsNotFoundRatherThanForbidden() {
        User otherUser = user("other@sujula.gm", UserRole.DELIVERY);
        drivers.save(Driver.builder().user(otherUser).status(DriverStatus.APPROVED)
                .vehicleType(VehicleType.MOTOR).maxWeight(20).build());
        entityManager.flush();

        // "Forbidden" would confirm both that the shipment is real and that the
        // prober guessed a live id — and this row leads to a home address.
        assertThrows(ResourceNotFoundException.class,
                () -> custody.shipment(otherUser.getId(), shipment.getId()));
    }

    @Test
    void theChainShownToADriverCarriesNoCodes() {
        collectIt();

        String rendered = custody.shipment(driverUser.getId(), shipment.getId()).toString();

        // A driver reading the history of a parcel they hold must not be able
        // to read the code that opens its next handover.
        assertFalse(rendered.contains("111111"), rendered);
    }

    // ── Codes are the proof ──────────────────────────────────────────────────

    @Test
    void collectingNeedsTheSellersCode() {
        arriveIt(null, null);
        code(HandoverCodeType.VENDOR_RELEASE, "111111");

        BadRequestException wrong = assertThrows(BadRequestException.class,
                () -> custody.collect(driverUser.getId(), shipment.getId(),
                        handover("999999", ORIGIN_LAT, ORIGIN_LNG, null)));
        assertTrue(wrong.getMessage().contains("not right"), wrong.getMessage());
        assertEquals(1, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).size());
        entityManager.refresh(shipment.getVendorOrder());
        assertEquals(VendorOrderStatus.READY_FOR_PICKUP,
                shipment.getVendorOrder().getStatus());
    }

    @Test
    void collectionAdvancesOnlyTheExactLinkedCommercialSlice() {
        VendorOrder sibling = vendorOrders.save(VendorOrder.builder()
                .order(shipment.getVendorOrder().getOrder())
                .vendor(shipment.getVendorOrder().getVendor())
                .status(VendorOrderStatus.PREPARING)
                .nativeCurrency("GMD")
                .subtotalNative(BigDecimal.TEN).totalNative(BigDecimal.TEN)
                .subtotal(BigDecimal.TEN).total(BigDecimal.TEN)
                .build());
        entityManager.flush();

        collectIt();
        entityManager.refresh(sibling);
        entityManager.refresh(shipment.getVendorOrder());

        assertEquals(VendorOrderStatus.SHIPPED, shipment.getVendorOrder().getStatus());
        assertEquals(VendorOrderStatus.PREPARING, sibling.getStatus());
    }

    @Test
    void aCodeIsSpentOnceAndCannotOpenASecondHandover() {
        collectIt();

        HandoverCode spent = codes.findAll().stream()
                .filter(c -> c.getCodeType() == HandoverCodeType.VENDOR_RELEASE)
                .findFirst().orElseThrow();
        assertTrue(spent.isUsed());
        assertNotNull(spent.getUsedAt());
    }

    @Test
    void thereIsNothingToCollectBeforeTheSellerHasPackedIt() {
        arriveIt(null, null);
        BadRequestException none = assertThrows(BadRequestException.class,
                () -> custody.collect(driverUser.getId(), shipment.getId(),
                        handover("111111", ORIGIN_LAT, ORIGIN_LNG, null)));
        assertTrue(none.getMessage().contains("mark it ready"), none.getMessage());
    }

    @Test
    void guessingBurnsTheCodeRatherThanAllowingAThousandTries() {
        arriveIt(null, null);
        code(HandoverCodeType.VENDOR_RELEASE, "111111");

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(BadRequestException.class,
                    () -> custody.collect(driverUser.getId(), shipment.getId(),
                            handover("000000", ORIGIN_LAT, ORIGIN_LNG, null)));
        }
        entityManager.flush();

        // Even the right code no longer works: the row is dead.
        assertThrows(BadRequestException.class,
                () -> custody.collect(driverUser.getId(), shipment.getId(),
                        handover("111111", ORIGIN_LAT, ORIGIN_LNG, null)));
    }

    // ── Delivery asks for the most ───────────────────────────────────────────

    @Test
    void deliveryWithoutAPhotographIsRefused() {
        collectIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "222222");

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> custody.deliver(driverUser.getId(), shipment.getId(),
                        handover("222222", DEST_LAT, DEST_LNG, null)));

        // This is the link that releases the seller's money and ends the chain.
        assertTrue(refused.getMessage().contains("photograph"), refused.getMessage());
    }

    @Test
    void aDeliveryAtTheDoorIsRecordedAsAttested() {
        collectIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "222222");

        DriverResponses.CustodyRecorded done = custody.deliver(driverUser.getId(),
                shipment.getId(), handover("222222", DEST_LAT, DEST_LNG, "https://m.invalid/p.jpg"));

        assertTrue(done.attestedByPosition());
        assertEquals(ShipmentStatus.DELIVERED, done.shipmentStatus());
    }

    @Test
    void aDeliveryFromMilesAwayStillHappensButIsFlagged() {
        collectIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "222222");

        // Banjul, roughly 10km off. The parcel may genuinely have changed
        // hands, so this is evidence rather than a refusal.
        DriverResponses.CustodyRecorded done = custody.deliver(driverUser.getId(),
                shipment.getId(), handover("222222", 13.4549, -16.5790, "https://m.invalid/p.jpg"));

        assertFalse(done.attestedByPosition());
        assertTrue(done.message().contains("flagged"), done.message());
        assertEquals(ShipmentStatus.DELIVERED, done.shipmentStatus());
    }

    // ── Safe drop: the one delivery with no code ─────────────────────────────

    @Test
    void aDeliveryWithNoCodeAndNoAuthorisationIsRefused() {
        collectIt();
        code(HandoverCodeType.RECIPIENT_RELEASE, "222222");

        // The check the DTO stopped making when safe drops became expressible.
        // A missing code with nothing standing in for it is the whole of C4
        // going quietly missing.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> custody.deliver(driverUser.getId(), shipment.getId(),
                        handover(null, DEST_LAT, DEST_LNG, "https://m.invalid/p.jpg")));
        assertTrue(refused.getMessage().contains("proof"), refused.getMessage());
    }

    @Test
    void aDeliveryStandingOnHerAuthorisationCarriesItInTheChain() {
        collectIt();
        authoriseSafeDrop("with the pharmacy next door", "Ndey");

        DriverResponses.CustodyRecorded done = custody.deliver(driverUser.getId(),
                shipment.getId(), handover(null, DEST_LAT, DEST_LNG, "https://m.invalid/p.jpg"));
        entityManager.flush();

        assertEquals(ShipmentStatus.DELIVERED, done.shipmentStatus());
        CustodyEvent released = events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId())
                .stream().filter(e -> e.getType() == CustodyEventType.RELEASED)
                .findFirst().orElseThrow();

        // Months later, "why is there no code against this delivery" has to
        // have an answer in the row itself.
        assertEquals("SAFE_DROP", released.getReasonCode());
        assertNull(released.getHandoverCodeId());
        assertTrue(released.getNote().contains("Ndey"));
        assertTrue(released.getNote().contains("pharmacy next door"));
        assertNotNull(released.getPhotoUrl());
    }

    @Test
    void aSafeDropNowhereNearTheAddressIsRefusedRatherThanFlagged() {
        collectIt();
        authoriseSafeDrop("behind the shop", null);

        // Everywhere else a position that does not match is recorded and
        // flagged, because the code proves the parcel changed hands. Here there
        // is no code, so the position is the only thing corroborating that the
        // driver was at the place she authorised.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> custody.deliver(driverUser.getId(), shipment.getId(),
                        handover(null, 13.4549, -16.5790, "https://m.invalid/p.jpg")));
        assertTrue(refused.getMessage().contains("safe"), refused.getMessage());

        assertTrue(events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).stream()
                .noneMatch(e -> e.getType() == CustodyEventType.RELEASED));
    }

    @Test
    void aCodeThatWasActuallyReadOutBeatsAStandingPermission() {
        collectIt();
        authoriseSafeDrop("behind the shop", null);
        code(HandoverCodeType.RECIPIENT_RELEASE, "222222");

        custody.deliver(driverUser.getId(), shipment.getId(),
                handover("222222", DEST_LAT, DEST_LNG, "https://m.invalid/p.jpg"));
        entityManager.flush();

        CustodyEvent released = events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId())
                .stream().filter(e -> e.getType() == CustodyEventType.RELEASED)
                .findFirst().orElseThrow();
        // She was in after all. A code somebody read out is better evidence
        // than a permission given hours earlier, so it takes the ordinary path.
        assertNull(released.getReasonCode());
        assertNotNull(released.getHandoverCodeId());
    }

    @Test
    void theDriverIsToldWhatSheAskedForOrItMeansNothing() {
        collectIt();
        authoriseSafeDrop("with the pharmacy next door", "Ndey");

        DriverResponses.ShipmentDetail detail =
                custody.shipment(driverUser.getId(), shipment.getId());

        assertNotNull(detail.destination().instructions());
        assertTrue(detail.destination().instructions().contains("Ndey"));
        assertTrue(detail.destination().instructions().contains("photograph"));
        assertTrue(detail.whatToDoNext().contains("without a code"), detail.whatToDoNext());
    }

    @Test
    void switchingSafeDropOffStopsDropsTodayWithoutErasingWhatSheAsked() {
        collectIt();
        authoriseSafeDrop("behind the shop", null);

        // The row already exists: FeatureFlags writes every declared flag at
        // its default on startup, which is the behaviour being relied on here.
        com.sujula.model.platform.FeatureFlag flag = featureFlags
                .findByFlagKey(com.sujula.service.platform.FeatureFlags.SAFE_DROP)
                .orElseThrow(() -> new AssertionError(
                        "the declared flags should have been created at startup"));
        assertTrue(flag.isEnabled(), "and safe drop is on by default");

        flag.setEnabled(false);
        featureFlags.save(flag);
        entityManager.flush();
        flags.invalidate();

        // A drop with no code is refused while the flag is down — the platform
        // has stopped honouring the permission, which is a different fact from
        // her never having given one.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> custody.deliver(driverUser.getId(), shipment.getId(),
                        handover(null, DEST_LAT, DEST_LNG, "https://m.invalid/p.jpg")));
        assertTrue(refused.getMessage().contains("code is required"), refused.getMessage());

        // And her instruction is still on the parcel, untouched.
        entityManager.refresh(shipment);
        assertTrue(shipment.isSafeDropAuthorised());
        assertEquals("behind the shop", shipment.getSafeDropLocation());

        // Switched back on, the same request goes through.
        flag.setEnabled(true);
        featureFlags.save(flag);
        entityManager.flush();
        flags.invalidate();

        assertNotNull(custody.deliver(driverUser.getId(), shipment.getId(),
                handover(null, DEST_LAT, DEST_LNG, "https://m.invalid/p.jpg")));
    }

    /** Records the authorisation the way the recipient surface does. */
    private void authoriseSafeDrop(String where, String who) {
        recipientDirectives.record(shipment, com.sujula.model.shipment.RecipientInstruction.builder()
                .type(com.sujula.model.constant.RecipientInstructionType.AUTHORISE_SAFE_DROP)
                .safeDropLocation(where).safeDropPerson(who)
                .verifiedByCodeId(1L).verifiedAt(LocalDateTime.now())
                .summary("Leave with " + who + " at " + where)
                .build());
        entityManager.flush();
        entityManager.refresh(shipment);
    }

    // ── The recipient's code goes to the buyer ───────────────────────────────

    @Test
    void theCodeIsEmailedToTheBuyerAndNeverShownToTheDriver() {
        collectIt();

        DriverResponses.RecipientCodeRequested asked =
                custody.requestRecipientCode(driverUser.getId(), shipment.getId());

        // To the person who paid, who passes it on. She may have no account, no
        // app and no email of her own (C5).
        verify(email).sendRecipientReleaseCode(eq("fatou.ceesay@example.es"), anyString(),
                eq("Isatou Ceesay"), eq("SJL-D-0001"), anyString(), any());

        assertTrue(asked.sent());
        // A driver who could read the code could mark a parcel delivered
        // without meeting anybody.
        String code = codes.findAll().stream()
                .filter(c -> c.getCodeType() == HandoverCodeType.RECIPIENT_RELEASE)
                .findFirst().orElseThrow().getCode();
        assertFalse(asked.toString().contains(code), asked.toString());
    }

    @Test
    void withAnSmsProviderTheCodeIsTextedToTheRecipientAndStillEmailedToTheBuyer() {
        collectIt();
        when(sms.isConfigured()).thenReturn(true);
        when(sms.send(anyString(), anyString())).thenReturn(true);

        DriverResponses.RecipientCodeRequested asked =
                custody.requestRecipientCode(driverUser.getId(), shipment.getId());

        String code = codes.findAll().stream()
                .filter(c -> c.getCodeType() == HandoverCodeType.RECIPIENT_RELEASE)
                .findFirst().orElseThrow().getCode();
        // To her own number: she has a phone and nothing else (C5).
        verify(sms).send(eq("+2203100077"), contains(code));
        verify(email).sendRecipientReleaseCode(eq("fatou.ceesay@example.es"), anyString(),
                eq("Isatou Ceesay"), eq("SJL-D-0001"), eq(code), any());

        assertTrue(asked.sent());
        assertEquals("•••077", asked.sentTo());
        assertFalse(asked.toString().contains(code), asked.toString());
    }

    @Test
    void theBuyersAddressIsMaskedInTheAnswer() {
        collectIt();
        DriverResponses.RecipientCodeRequested asked =
                custody.requestRecipientCode(driverUser.getId(), shipment.getId());

        // Enough to confirm where it went, not enough to read out.
        assertNotNull(asked.sentTo());
        assertFalse(asked.sentTo().contains("fatou.ceesay"), asked.sentTo());
        assertTrue(asked.sentTo().endsWith("@example.es"));
    }

    @Test
    void askingForACodeTooOftenIsRefusedSoTheBuyerIsNotSpammed() {
        collectIt();
        custody.requestRecipientCode(driverUser.getId(), shipment.getId());
        custody.requestRecipientCode(driverUser.getId(), shipment.getId());
        custody.requestRecipientCode(driverUser.getId(), shipment.getId());
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> custody.requestRecipientCode(driverUser.getId(), shipment.getId()));
        assertTrue(refused.getMessage().contains("will not make that faster"), refused.getMessage());
    }

    @Test
    void reissuingKillsThePreviousCode() {
        collectIt();
        custody.requestRecipientCode(driverUser.getId(), shipment.getId());
        entityManager.flush();
        String first = codes.findLiveForShipment(shipment.getId(),
                HandoverCodeType.RECIPIENT_RELEASE, LocalDateTime.now()).get(0).getCode();

        custody.requestRecipientCode(driverUser.getId(), shipment.getId());
        entityManager.flush();

        // A recipient holding two working codes is two chances to read the
        // wrong one out.
        List<HandoverCode> live = codes.findLiveForShipment(shipment.getId(),
                HandoverCodeType.RECIPIENT_RELEASE, LocalDateTime.now());
        assertEquals(1, live.size());
        assertFalse(live.get(0).getCode().equals(first));
    }

    // ── Failed attempts ──────────────────────────────────────────────────────

    @Test
    void nobodyHomeSchedulesAnotherTryAndLeavesTheParcelWithTheDriver() {
        collectIt();

        DriverResponses.AttemptFailed failed = custody.deliveryFailed(driverUser.getId(),
                shipment.getId(), new DriverRequests.DeliveryFailed(
                        DriverRequests.DeliveryFailed.FailureReason.NOBODY_HOME,
                        "Compound locked", "https://m.invalid/door.jpg",
                        DEST_LAT, DEST_LNG, new BigDecimal("10.00"), null, null));

        assertFalse(failed.returning());
        assertNotNull(failed.nextAttemptAfter());
        assertEquals(1, failed.failedAttempts());
        assertTrue(failed.shipmentStatus().isCustodyActive(), "the driver still has it");
    }

    @Test
    void aRefusedParcelGoesBackRatherThanBeingTriedAgain() {
        collectIt();

        DriverResponses.AttemptFailed failed = custody.deliveryFailed(driverUser.getId(),
                shipment.getId(), new DriverRequests.DeliveryFailed(
                        DriverRequests.DeliveryFailed.FailureReason.REFUSED,
                        "She would not take it", null,
                        DEST_LAT, DEST_LNG, null, null, null));

        assertTrue(failed.returning());
        assertNull(failed.nextAttemptAfter());
    }

    @Test
    void afterThreeFailuresItStopsBeingTried() {
        collectIt();
        for (int i = 0; i < 3; i++) {
            custody.deliveryFailed(driverUser.getId(), shipment.getId(),
                    new DriverRequests.DeliveryFailed(
                            DriverRequests.DeliveryFailed.FailureReason.NOBODY_HOME,
                            null, null, DEST_LAT, DEST_LNG, null, null, null));
            entityManager.flush();
            entityManager.refresh(shipment);
        }

        DriverResponses.AttemptFailed last = custody.deliveryFailed(driverUser.getId(),
                shipment.getId(), new DriverRequests.DeliveryFailed(
                        DriverRequests.DeliveryFailed.FailureReason.NOBODY_HOME,
                        null, null, DEST_LAT, DEST_LNG, null, null, null));

        assertTrue(last.returning());
        assertTrue(last.message().contains("goes back"), last.message());
    }

    // ── Offline ──────────────────────────────────────────────────────────────

    @Test
    void aBatchUploadedTwiceRecordsEachEventOnce() {
        code(HandoverCodeType.VENDOR_RELEASE, "111111");
        LocalDateTime captured = LocalDateTime.now().minusHours(3);
        DriverRequests.SyncBatch batch = new DriverRequests.SyncBatch(List.of(
                new DriverRequests.SyncEntry(shipment.getId(), "ARRIVED_AT_ORIGIN", "evt-0",
                        captured.minusMinutes(5), null,
                        ORIGIN_LAT, ORIGIN_LNG, new BigDecimal("15.00"), null, null, null),
                new DriverRequests.SyncEntry(shipment.getId(), "COLLECTED", "evt-1",
                        captured, "111111",
                        ORIGIN_LAT, ORIGIN_LNG, new BigDecimal("15.00"), null, null, null)));

        DriverResponses.SyncResult first = custody.sync(driverUser.getId(), batch);
        entityManager.flush();
        DriverResponses.SyncResult second = custody.sync(driverUser.getId(), batch);

        assertEquals(2, first.recorded());
        assertEquals(0, first.duplicates());
        // The phone could not know whether the first upload landed.
        assertEquals(0, second.recorded());
        assertEquals(2, second.duplicates());
        assertEquals(2, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).size());
    }

    @Test
    void aBatchIsAppliedOldestFirstWhateverOrderItArrivesIn() {
        code(HandoverCodeType.VENDOR_RELEASE, "111111");
        code(HandoverCodeType.RECIPIENT_RELEASE, "222222");
        LocalDateTime morning = LocalDateTime.now().minusHours(4);

        // Deliberately the wrong way round. Applied as sent, the delivery would
        // arrive before its collection and be refused — correctly, and for the
        // wrong reason.
        DriverResponses.SyncResult result = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(
                        new DriverRequests.SyncEntry(shipment.getId(), "RELEASED", "evt-b",
                                morning.plusHours(2), "222222", DEST_LAT, DEST_LNG,
                                null, "https://m.invalid/p.jpg", null, null),
                        new DriverRequests.SyncEntry(shipment.getId(), "COLLECTED", "evt-a",
                                morning.plusHours(1), "111111", ORIGIN_LAT, ORIGIN_LNG,
                                null, null, null, null),
                        new DriverRequests.SyncEntry(shipment.getId(), "ARRIVED_AT_ORIGIN", "evt-0",
                                morning, null, ORIGIN_LAT, ORIGIN_LNG,
                                null, null, null, null))));

        assertEquals(3, result.recorded());
        assertEquals(0, result.rejected());
        entityManager.flush();
        entityManager.refresh(shipment);
        assertEquals(ShipmentStatus.DELIVERED, shipment.getStatus());
    }

    @Test
    void oneBadEntryDoesNotThrowAwayTheRestOfTheDay() {
        code(HandoverCodeType.VENDOR_RELEASE, "111111");

        DriverResponses.SyncResult result = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(
                        new DriverRequests.SyncEntry(shipment.getId(), "ARRIVED_AT_ORIGIN", "evt-arrive",
                                LocalDateTime.now().minusHours(3), null,
                                ORIGIN_LAT, ORIGIN_LNG, null, null, null, null),
                        new DriverRequests.SyncEntry(shipment.getId(), "COLLECTED", "evt-good",
                                LocalDateTime.now().minusHours(2), "111111",
                                ORIGIN_LAT, ORIGIN_LNG, null, null, null, null),
                        new DriverRequests.SyncEntry(shipment.getId(), "NONSENSE", "evt-bad",
                                LocalDateTime.now().minusHours(1), null,
                                DEST_LAT, DEST_LNG, null, null, null, null))));

        assertEquals(2, result.recorded());
        assertEquals(1, result.rejected());
        // Reported entry by entry so the app knows what to stop retrying.
        assertTrue(result.outcomes().stream()
                .anyMatch(o -> "evt-bad".equals(o.clientEventId()) && !o.accepted()
                        && o.problem() != null));
        assertTrue(result.message().contains("stop retrying"), result.message());
    }

    @Test
    void anOfflineEventIsMarkedAsOneSoTheGapInTheClocksIsExplainable() {
        custody.sync(driverUser.getId(), new DriverRequests.SyncBatch(List.of(
                new DriverRequests.SyncEntry(shipment.getId(), "ARRIVED_AT_ORIGIN", "evt-1",
                        LocalDateTime.now().minusHours(5), null,
                        ORIGIN_LAT, ORIGIN_LNG, null, null, null, null))));
        entityManager.flush();

        var event = events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).get(0);
        assertTrue(event.isCapturedOffline());
        // Two clocks, both kept: the gap is visible rather than collapsed.
        assertTrue(event.getOccurredAt().isBefore(event.getRecordedAt().minusHours(4)));
    }

    @Test
    void offlineCollectionWithoutAnArrivalOrVendorCodeCannotManufactureCustody() {
        HandoverCode vendorCode = code(HandoverCodeType.VENDOR_RELEASE, "111111");

        DriverResponses.SyncResult beforeArrival = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("COLLECTED", "collect-too-soon",
                        LocalDateTime.now(), "111111", null, null))));
        assertEquals(1, beforeArrival.rejected());
        assertFalse(vendorCode.isUsed());
        assertTrue(events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).isEmpty());

        arriveIt(null, "arrived-online");
        DriverResponses.SyncResult withoutCode = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("COLLECTED", "collect-no-code",
                        LocalDateTime.now(), null, null, null))));
        assertEquals(1, withoutCode.rejected());
        assertFalse(vendorCode.isUsed());
        assertEquals(1, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).size());
    }

    @Test
    void expiredUsedAndInvalidatedVendorCodesDoNotCreateOfflineCollection() {
        arriveIt(null, "arrived");
        HandoverCode expired = code(HandoverCodeType.VENDOR_RELEASE, "111111");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        codes.save(expired);
        HandoverCode used = code(HandoverCodeType.VENDOR_RELEASE, "222222");
        used.setUsed(true);
        used.setUsedAt(LocalDateTime.now());
        codes.save(used);
        HandoverCode invalidated = code(HandoverCodeType.VENDOR_RELEASE, "333333");
        invalidated.setInvalidatedAt(LocalDateTime.now());
        codes.save(invalidated);
        entityManager.flush();

        for (String presented : List.of("111111", "222222", "333333")) {
            DriverResponses.SyncResult result = custody.sync(driverUser.getId(),
                    new DriverRequests.SyncBatch(List.of(offline("COLLECTED", "bad-" + presented,
                            LocalDateTime.now(), presented, null, null))));
            assertEquals(1, result.rejected(), presented);
        }
        assertEquals(1, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).size());
    }

    @Test
    void offlineDeliveryRequiresCodeAndPhotoAndConsumesValidEvidenceOnce() {
        collectIt();
        HandoverCode recipient = code(HandoverCodeType.RECIPIENT_RELEASE, "222222");

        DriverResponses.SyncResult noCode = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("RELEASED", "release-no-code",
                        LocalDateTime.now(), null, "https://m.invalid/proof.jpg", null))));
        DriverResponses.SyncResult noPhoto = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("RELEASED", "release-no-photo",
                        LocalDateTime.now(), "222222", null, null))));
        DriverResponses.SyncResult wrongCode = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("RELEASED", "release-wrong-code",
                        LocalDateTime.now(), "999999", "https://m.invalid/proof.jpg", null))));

        assertEquals(1, noCode.rejected());
        assertEquals(1, noPhoto.rejected());
        assertEquals(1, wrongCode.rejected());
        assertFalse(recipient.isUsed());

        DriverResponses.SyncResult valid = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("RELEASED", "release-valid",
                        LocalDateTime.now(), "222222", "https://m.invalid/proof.jpg", null))));
        entityManager.flush();
        entityManager.refresh(recipient);
        entityManager.refresh(shipment);
        assertEquals(1, valid.recorded());
        assertTrue(recipient.isUsed());
        assertEquals(ShipmentStatus.DELIVERED, shipment.getStatus());
        assertEquals(1, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).stream()
                .filter(e -> e.getType() == CustodyEventType.RELEASED).count());
    }

    @Test
    void offlineSafeDropNeedsStoredAuthorisationPhotoAndCorroboratingPosition() {
        collectIt();
        authoriseSafeDrop("behind the blue gate", null);

        DriverResponses.SyncResult farAway = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(new DriverRequests.SyncEntry(
                        shipment.getId(), "RELEASED", "safe-far", LocalDateTime.now(), null,
                        13.4549, -16.5790, new BigDecimal("10.00"),
                        "https://m.invalid/proof.jpg", null, null))));
        DriverResponses.SyncResult noPhoto = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("RELEASED", "safe-no-photo",
                        LocalDateTime.now(), null, null, null))));
        assertEquals(1, farAway.rejected());
        assertEquals(1, noPhoto.rejected());

        DriverResponses.SyncResult valid = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("RELEASED", "safe-valid",
                        LocalDateTime.now(), null, "https://m.invalid/proof.jpg", null))));
        assertEquals(1, valid.recorded());
    }

    // ── Scoping on writes ────────────────────────────────────────────────────

    @Test
    void everyNonActiveHistoricalLegStateFailsClosed() {
        for (LegAssignmentStatus status : List.of(
                LegAssignmentStatus.OFFERED, LegAssignmentStatus.DECLINED,
                LegAssignmentStatus.EXPIRED, LegAssignmentStatus.COMPLETED,
                LegAssignmentStatus.CANCELLED)) {
            leg.setAssignmentStatus(status);
            legs.save(leg);
            entityManager.flush();
            assertThrows(BadRequestException.class,
                    () -> custody.arrivedAtOrigin(driverUser.getId(), shipment.getId(),
                            new DriverRequests.Arrived(ORIGIN_LAT, ORIGIN_LNG, null,
                                    null, "inactive-" + status)));
        }
        assertTrue(events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).isEmpty());
    }

    @Test
    void reassignedDriverCannotReplayQueuedEvidence() {
        User replacementUser = user("replacement@sujula.gm", UserRole.DELIVERY);
        Driver replacement = drivers.save(Driver.builder().user(replacementUser)
                .status(DriverStatus.APPROVED).available(true)
                .vehicleType(VehicleType.MOTOR).maxWeight(20).build());
        leg.setAssignmentStatus(LegAssignmentStatus.CANCELLED);
        legs.save(leg);
        legs.save(ShipmentLeg.builder().shipment(shipment).sequence(2)
                .legType(LegType.ORIGIN_TO_RECIPIENT)
                .assignmentStatus(LegAssignmentStatus.ACCEPTED).driver(replacement)
                .originLatitude(ORIGIN_LAT).originLongitude(ORIGIN_LNG)
                .destinationLatitude(DEST_LAT).destinationLongitude(DEST_LNG)
                .earning(BigDecimal.ZERO).earningCurrency("GMD").build());
        entityManager.flush();

        DriverResponses.SyncResult result = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("ARRIVED_AT_ORIGIN", "stale-driver",
                        LocalDateTime.now(), null, null, null))));
        assertEquals(1, result.rejected());
        assertTrue(events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).isEmpty());
    }

    @Test
    void cancelledShipmentRejectsBeforeCodeConsumptionOrLegCompletion() {
        arriveIt(null, "arrived-before-cancel");
        HandoverCode vendorCode = code(HandoverCodeType.VENDOR_RELEASE, "111111");
        shipment.setCancelledAt(LocalDateTime.now());
        shipments.save(shipment);
        entityManager.flush();

        assertThrows(BadRequestException.class,
                () -> custody.collect(driverUser.getId(), shipment.getId(),
                        handover("111111", ORIGIN_LAT, ORIGIN_LNG, null)));
        entityManager.refresh(vendorCode);
        entityManager.refresh(leg);
        assertFalse(vendorCode.isUsed());
        assertEquals(LegAssignmentStatus.ACCEPTED, leg.getAssignmentStatus());
        assertEquals(1, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).size());
    }

    @Test
    void staleOfflineEvidenceCannotRegressAnAdvancedChain() {
        LocalDateTime arrivalTime = LocalDateTime.now().minusHours(2);
        arriveIt(arrivalTime, "arrived");
        code(HandoverCodeType.VENDOR_RELEASE, "111111");
        custody.collect(driverUser.getId(), shipment.getId(),
                new DriverRequests.Handover("111111", null, ORIGIN_LAT, ORIGIN_LNG,
                        null, null, null, null, arrivalTime.plusHours(1), "collected"));

        DriverResponses.SyncResult stale = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("FAILED_ATTEMPT", "stale-failure",
                        arrivalTime.plusMinutes(30), null, null, "NOBODY_HOME"))));
        entityManager.refresh(shipment);
        assertEquals(1, stale.rejected());
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, shipment.getStatus());
        assertEquals(2, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).size());
    }

    @Test
    void newClientEventIdCannotRepeatACompletedCollection() {
        collectIt();
        HandoverCode second = code(HandoverCodeType.VENDOR_RELEASE, "333333");

        DriverResponses.SyncResult duplicateAction = custody.sync(driverUser.getId(),
                new DriverRequests.SyncBatch(List.of(offline("COLLECTED", "new-id-same-action",
                        LocalDateTime.now(), "333333", null, null))));
        assertEquals(1, duplicateAction.rejected());
        assertFalse(second.isUsed());
        assertEquals(1, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).stream()
                .filter(e -> e.getType() == CustodyEventType.COLLECTED).count());
    }

    @Test
    void driverPickupDepositAndGenericCounterpartyEventsFailClosed() {
        collectIt();
        int before = events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).size();
        assertThrows(BadRequestException.class,
                () -> custody.depositAtPickup(driverUser.getId(), shipment.getId(),
                        handover("123456", DEST_LAT, DEST_LNG, null)));

        for (String type : List.of("DEPOSITED", "TRANSFERRED", "REDISPATCHED", "RETURNED")) {
            DriverResponses.SyncResult result = custody.sync(driverUser.getId(),
                    new DriverRequests.SyncBatch(List.of(offline(type, "blocked-" + type,
                            LocalDateTime.now(), "123456", null, null))));
            assertEquals(1, result.rejected(), type);
        }
        assertEquals(before,
                events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()).size());
    }

    @Test
    void aDriverWhoHasNotAcceptedTheJobCannotMoveTheParcel() {
        leg.setAssignmentStatus(LegAssignmentStatus.OFFERED);
        legs.save(leg);
        entityManager.flush();
        code(HandoverCodeType.VENDOR_RELEASE, "111111");

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> custody.collect(driverUser.getId(), shipment.getId(),
                        handover("111111", ORIGIN_LAT, ORIGIN_LNG, null)));
        assertTrue(refused.getMessage().contains("Accept the job first"), refused.getMessage());
    }

    @Test
    void emailIsNeverSentForAParcelTheDriverIsNotCarrying() {
        // Accepted but not collected: the parcel is still on the seller's
        // shelf. Emailing a delivery code now tells the buyer it is on its way
        // when it is not, and spends one of the few sends allowed.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> custody.requestRecipientCode(driverUser.getId(), shipment.getId()));
        assertTrue(refused.getMessage().contains("not collected"), refused.getMessage());
        verify(email, never()).sendRecipientReleaseCode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void norForAParcelTheDriverWasNeverGiven() {
        User otherUser = user("stranger@sujula.gm", UserRole.DELIVERY);
        drivers.save(Driver.builder().user(otherUser).status(DriverStatus.APPROVED)
                .vehicleType(VehicleType.MOTOR).maxWeight(20).build());
        entityManager.flush();

        assertThrows(ResourceNotFoundException.class,
                () -> custody.requestRecipientCode(otherUser.getId(), shipment.getId()));
        verify(email, never()).sendRecipientReleaseCode(any(), any(), any(), any(), any(), any());
    }
}
