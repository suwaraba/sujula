package com.sujula.service.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import com.sujula.dto.request.admin.AdminLogisticsRequests;
import com.sujula.dto.response.admin.AdminLogisticsResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VehicleType;
import com.sujula.model.delivery.Driver;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.logistics.DeliveryZone;
import com.sujula.model.user.User;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.DriverRepository;
import com.sujula.repository.logistics.DeliveryRateCardRepository;
import com.sujula.repository.logistics.DeliveryZoneRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.AuditService;
import com.sujula.service.NotificationService;
import com.sujula.service.admin.impl.AdminLogisticsServiceImpl;
import com.sujula.service.delivery.DeliveryPricingProperties;
import com.sujula.service.delivery.RateCardRegistry;
import com.sujula.service.delivery.ZoneRegistry;
import com.sujula.service.pickup.PickupCounter;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drivers, counters, zones and rate cards.
 *
 * <p>The claims that matter: a rate card never reaches backwards, a zone edit
 * takes effect at once rather than at the next restart, a counter cannot be shut
 * with somebody's parcel still on its shelf, and a driver cannot be given a zone
 * in a country they do not work in.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AdminLogisticsServiceImpl.class, ZoneRegistry.class, RateCardRegistry.class,
         DeliveryPricingProperties.class, PickupCounter.class,
         AdminLogisticsServiceTest.Jackson.class})
class AdminLogisticsServiceTest {

    /**
     * The JPA slice brings no JSON mapper, and {@link ZoneRegistry} parses real
     * GeoJSON rather than a stub — the polygon arithmetic is half of what these
     * tests are checking.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class Jackson {
        @org.springframework.context.annotation.Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return new tools.jackson.databind.ObjectMapper();
        }
    }

    /** A rough box around Serrekunda, in GeoJSON's own [lng, lat] order. */
    private static final String SERREKUNDA = """
            {"type":"Polygon","coordinates":[[
              [-16.72,13.42],[-16.66,13.42],[-16.66,13.47],[-16.72,13.47],[-16.72,13.42]
            ]]}""";

    private static final String BAKAU = """
            {"type":"Polygon","coordinates":[[
              [-16.70,13.47],[-16.65,13.47],[-16.65,13.50],[-16.70,13.50],[-16.70,13.47]
            ]]}""";

    @Autowired private AdminLogisticsServiceImpl logistics;
    @Autowired private ZoneRegistry zoneRegistry;
    @Autowired private DeliveryZoneRepository zones;
    @Autowired private DeliveryRateCardRepository cards;
    @Autowired private DriverRepository drivers;
    @Autowired private PickupPointRepository points;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    @MockitoBean private AuditService audit;
    @MockitoBean private NotificationService notifications;

    private User operator;
    private Driver ebrima;

    @BeforeEach
    void setUp() {
        operator = users.save(User.builder()
                .firstName("Awa").lastName("Ceesay").email("awa.logistics@sujula.gm")
                .password("x").role(UserRole.ADMIN).enabled(true).build());

        User driverUser = users.save(User.builder()
                .firstName("ebrima").lastName("Person").email("ebrima.logistics@sujula.gm")
                .password("x").role(UserRole.DELIVERY).enabled(true).build());

        ebrima = drivers.save(Driver.builder()
                .user(driverUser).status(DriverStatus.PENDING).countryCode("GM")
                .phone("+2207000001").vehicleType(VehicleType.MOTOR).maxWeight(30)
                .idDocumentUrl("https://files.sujula.gm/id/ebrima.jpg")
                .idDocumentNumber("GM-1234567").idDocumentType("NATIONAL_ID")
                .kycSubmittedAt(java.time.LocalDateTime.now().minusDays(2))
                .build());
        entityManager.flush();
    }

    private DeliveryZone drawSerrekunda() {
        return zones.findById(logistics.createZone(operator,
                new AdminLogisticsRequests.CreateZone("GM-SRK", "Serrekunda", null, "GM",
                        SERREKUNDA, true, null, 10)).id()).orElseThrow();
    }

    // ── Zones ────────────────────────────────────────────────────────────────

    @Test
    void aZoneIsMeasuredWhenItIsDrawnRatherThanWhenItIsAsked() {
        AdminLogisticsResponses.ZoneSaved saved = logistics.createZone(operator,
                new AdminLogisticsRequests.CreateZone("GM-SRK", "Serrekunda", null, "GM",
                        SERREKUNDA, true, null, 10));

        // The box is derived on the write. Measuring it at read time is how a box
        // comes to disagree with its own shape, and a box that disagrees silently
        // excludes addresses that are inside the polygon.
        assertEquals(5, saved.vertexCount());
        assertEquals(13.42, saved.minLatitude(), 0.0001);
        assertEquals(-16.66, saved.maxLongitude(), 0.0001);
    }

    @Test
    void aZoneDrawnInTheWrongCountryIsRefusedRatherThanStoredEmpty() {
        // The Serrekunda box with its coordinates swapped: every position is
        // legal, it parses, and it would contain no Gambian address ever.
        String swapped = """
                {"type":"Polygon","coordinates":[[
                  [13.42,-16.72],[13.42,-16.66],[13.47,-16.66],[13.47,-16.72],[13.42,-16.72]
                ]]}""";
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> logistics.createZone(operator,
                        new AdminLogisticsRequests.CreateZone("GM-BAD", "Wrong", null, "GM",
                                swapped, true, null, 0)));
        assertTrue(refused.getMessage().contains("[latitude, longitude]"),
                "it has to name the mistake: " + refused.getMessage());
    }

    @Test
    void editingAZoneReloadsTheCacheSoItIsLiveNowRatherThanAtTheNextRestart() {
        DeliveryZone zone = drawSerrekunda();
        entityManager.flush();

        // Warm the cache by asking a question of it.
        assertTrue(zoneRegistry.matchAtDestination(13.44, -16.69).isPresent());
        long before = zoneRegistry.version();

        AdminLogisticsResponses.ZoneSaved saved = logistics.patchZone(operator, zone.getId(),
                new AdminLogisticsRequests.PatchZone(null, null, null, false,
                        "The ferry is out of service this month.", null, null));
        entityManager.flush();

        assertTrue(saved.cacheVersion() > before, "the cache version moves on every write");

        // And the answer itself has changed, not merely the version number.
        ZoneRegistry.Serviceability now = zoneRegistry.serviceabilityAtDestination(13.44, -16.69);
        assertFalse(now.deliverable());
        assertTrue(now.reason().contains("ferry"));
    }

    @Test
    void markingAZoneUnserviceableWithoutSayingWhyIsRefused() {
        DeliveryZone zone = drawSerrekunda();
        // "Unavailable" is not something a shopper can act on. "The ferry is out
        // of service" is.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> logistics.patchZone(operator, zone.getId(),
                        new AdminLogisticsRequests.PatchZone(null, null, null, false, null,
                                null, null)));
        assertTrue(refused.getMessage().contains("Say why"));
    }

    @Test
    void aDestinationInNoZoneAtAllIsStillDeliverable() {
        drawSerrekunda();
        entityManager.flush();
        zoneRegistry.invalidate();

        // Zones are how a platform says "not here" about somewhere it has looked
        // at. On the day this ships nothing has been drawn anywhere, and refusing
        // what nobody has drawn yet would close the marketplace.
        assertTrue(zoneRegistry.serviceabilityAtDestination(14.70, -17.44).deliverable(),
                "Dakar is in no zone and must still be quotable");
    }

    @Test
    void theHighestPriorityZoneWinsWhereTwoOverlap() {
        drawSerrekunda();
        logistics.createZone(operator, new AdminLogisticsRequests.CreateZone(
                "GM-KOMBO", "Greater Kombo", null, "GM", """
                {"type":"Polygon","coordinates":[[
                  [-16.80,13.30],[-16.50,13.30],[-16.50,13.60],[-16.80,13.60],[-16.80,13.30]
                ]]}""", true, null, 1));
        entityManager.flush();
        zoneRegistry.invalidate();

        // Overlap is how a denser city rate is expressed inside a wider one, not
        // a mistake — so the tie has to be broken by a stated rule rather than by
        // whichever row came back first.
        assertEquals("GM-SRK",
                zoneRegistry.matchAtDestination(13.44, -16.69).orElseThrow().code());
    }

    // ── Drivers ──────────────────────────────────────────────────────────────

    @Test
    void aDriverWithNoIdentityDocumentIsNotApproved() {
        ebrima.setIdDocumentUrl(null);
        drivers.save(ebrima);
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> logistics.approveDriver(operator, ebrima.getId(),
                        new AdminLogisticsRequests.ApproveDriver("Looks fine.", List.of())));
        assertTrue(refused.getMessage().contains("identity document"));
    }

    @Test
    void anExpiredLicenceStopsAnApproval() {
        ebrima.setLicenseExpiresOn(LocalDate.now().minusMonths(2));
        drivers.save(ebrima);
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> logistics.approveDriver(operator, ebrima.getId(),
                        new AdminLogisticsRequests.ApproveDriver(null, List.of())))
                .getMessage().contains("licence expired"));
    }

    @Test
    void anApprovedDriverWithNoZoneIsToldTheyWillBeOfferedNothing() {
        AdminLogisticsResponses.DriverDecision decision = logistics.approveDriver(
                operator, ebrima.getId(),
                new AdminLogisticsRequests.ApproveDriver("Papers checked.", List.of()));

        assertEquals(DriverStatus.APPROVED, decision.status());
        // Approving somebody and then never offering them work, with nothing
        // saying why, is how a driver decides the platform is broken.
        assertTrue(decision.message().contains("cover no zone"));
    }

    @Test
    void aDriverCannotBeGivenAZoneInACountryTheyDoNotWorkIn() {
        drawSerrekunda();
        logistics.createZone(operator, new AdminLogisticsRequests.CreateZone(
                "SN-DKR", "Dakar", null, "SN", """
                {"type":"Polygon","coordinates":[[
                  [-17.55,14.65],[-17.35,14.65],[-17.35,14.80],[-17.55,14.80],[-17.55,14.65]
                ]]}""", true, null, 0));
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> logistics.setDriverZones(operator, ebrima.getId(),
                        new AdminLogisticsRequests.SetDriverZones(List.of("SN-DKR"), null)));
        assertTrue(refused.getMessage().contains("not in"));
    }

    @Test
    void everyUnknownZoneCodeIsNamedRatherThanJustTheFirst() {
        drawSerrekunda();
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> logistics.setDriverZones(operator, ebrima.getId(),
                        new AdminLogisticsRequests.SetDriverZones(
                                List.of("GM-SRK", "GM-NOPE", "GM-ALSO-NOPE"), null)));
        // Somebody fixing a list of typos should do it once, not once per typo.
        assertTrue(refused.getMessage().contains("GM-NOPE"));
        assertTrue(refused.getMessage().contains("GM-ALSO-NOPE"));
    }

    @Test
    void suspendingADriverNamesTheParcelsTheyAreStillHoldingRatherThanCancellingThem() {
        logistics.approveDriver(operator, ebrima.getId(),
                new AdminLogisticsRequests.ApproveDriver(null, List.of()));
        entityManager.flush();

        AdminLogisticsResponses.DriverDecision decision = logistics.suspendDriver(
                operator, ebrima.getId(),
                new AdminLogisticsRequests.SuspendDriver("Complaints from two recipients.", null));

        assertEquals(DriverStatus.SUSPENDED, decision.status());
        // Nothing in their hands here, but the rule is the point: a suspension is
        // a decision about the person, and the parcels are other people's goods.
        assertTrue(decision.message().contains("carrying nothing"));

        Driver reloaded = drivers.findById(ebrima.getId()).orElseThrow();
        assertFalse(reloaded.isAvailable(), "a suspended driver is taken off shift");
    }

    // ── Pickup points ────────────────────────────────────────────────────────

    @Test
    void aCounterCreatedByAnAdministratorIsOpenImmediately() {
        AdminLogisticsResponses.PickupPointSaved saved = logistics.createPickupPoint(operator,
                newCounter("Kairaba Pharmacy"));

        // The approval step exists to review an application from outside. There
        // is no application here — an administrator typed it in.
        assertEquals(PartnerStatus.APPROVED, saved.status());
        assertTrue(saved.active());
    }

    @Test
    void twoCountersWithTheSameNameInOneCityAreRefused() {
        logistics.createPickupPoint(operator, newCounter("Kairaba Pharmacy"));
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> logistics.createPickupPoint(operator, newCounter("Kairaba Pharmacy")))
                .getMessage().contains("already a point called"));
    }

    @Test
    void suspendingACounterStopsDepositsAndLeavesTheShelfAlone() {
        Long id = logistics.createPickupPoint(operator, newCounter("Kairaba Pharmacy")).id();
        entityManager.flush();

        AdminLogisticsResponses.PickupPointSaved saved = logistics.suspendPickupPoint(operator, id,
                new AdminLogisticsRequests.SuspendPickupPoint("Operator is not answering."));

        assertEquals(PartnerStatus.SUSPENDED, saved.status());
        // Still active: the parcels on the shelf belong to people who are coming
        // for them, and suspension is about the operator rather than about them.
        assertTrue(saved.active());
        PickupPoint reloaded = points.findById(id).orElseThrow();
        assertFalse(reloaded.canAcceptParcels(), "and no new parcel may be sent there");
    }

    private AdminLogisticsRequests.CreatePickupPoint newCounter(String name) {
        return new AdminLogisticsRequests.CreatePickupPoint(
                name, "Kairaba Avenue", null, "Serrekunda", null, null, "GM",
                13.44, -16.69, "+2207000002", null, "Modou Faal",
                "Mon–Sat 08:00–20:00", 40, 7, new BigDecimal("15.00"), "GMD",
                null, "Opened for the Kombo run.");
    }

    // ── Rate cards ───────────────────────────────────────────────────────────

    @Test
    void aRateCardCannotStartInThePast() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> logistics.createRateCard(operator, card(LocalDate.now().minusDays(7))));
        // Orders placed last week were priced under what was in force last week,
        // and backdating would make those totals impossible to reproduce.
        assertTrue(refused.getMessage().contains("cannot start in the past"));
    }

    @Test
    void aNewCardClosesTheOneItSupersedesRatherThanCompetingWithIt() {
        logistics.createRateCard(operator, card(LocalDate.now()));
        entityManager.flush();

        AdminLogisticsResponses.RateCardSaved second = logistics.createRateCard(operator,
                card(LocalDate.now().plusDays(30)));
        entityManager.flush();

        // Two live cards for one scope on one day is a mistake, not a tie to be
        // broken at read time by whichever row came back first.
        assertNotNull(second.supersededCardId());
        assertEquals(LocalDate.now().plusDays(29), second.supersededEndsOn());

        List<com.sujula.model.logistics.DeliveryRateCard> today =
                cards.findInForceOn(LocalDate.now());
        assertEquals(1, today.size(), "exactly one card prices today");
    }

    @Test
    void twoCardsStartingTheSameDayForTheSameScopeAreRefused() {
        logistics.createRateCard(operator, card(LocalDate.now().plusDays(3)));
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> logistics.createRateCard(operator, card(LocalDate.now().plusDays(3))))
                .getMessage().contains("already starts on"));
    }

    @Test
    void aFloorAboveTheCeilingIsRefusedBecauseEveryLegWouldHitTheCeiling() {
        assertTrue(assertThrows(BadRequestException.class,
                () -> logistics.createRateCard(operator,
                        new AdminLogisticsRequests.CreateRateCard(
                                "Broken", null, "GM", null, "GMD",
                                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("12.00"),
                                BigDecimal.ZERO, new BigDecimal("25.00"),
                                new BigDecimal("500.00"), new BigDecimal("100.00"),
                                null, LocalDate.now(), null)))
                .getMessage().contains("above the ceiling"));
    }

    @Test
    void patchingACardChangesItsWordsAndNeverItsNumbers() {
        AdminLogisticsResponses.RateCardSaved created =
                logistics.createRateCard(operator, card(LocalDate.now()));
        entityManager.flush();

        BigDecimal baseBefore = created.card().baseFee();
        AdminLogisticsResponses.RateCardSaved patched = logistics.patchRateCard(
                operator, created.card().id(),
                new AdminLogisticsRequests.PatchRateCard("Kombo standard, revised name",
                        "Renamed after the zone split.", null, null));

        assertEquals(baseBefore, patched.card().baseFee(), "the figures are untouchable");
        assertTrue(patched.message().contains("write a new card from a new date"));
    }

    @Test
    void aCardCannotBeClosedInThePastEither() {
        AdminLogisticsResponses.RateCardSaved created =
                logistics.createRateCard(operator, card(LocalDate.now()));
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> logistics.patchRateCard(operator, created.card().id(),
                        new AdminLogisticsRequests.PatchRateCard(null, null,
                                LocalDate.now().minusDays(1), null)))
                .getMessage().contains("cannot be closed in the past"));
    }

    @Test
    void thePreviewPricesALegFromTheCardThatIsActuallyLive() {
        logistics.createRateCard(operator, card(LocalDate.now()));
        entityManager.flush();

        List<AdminLogisticsResponses.RateCardPreview> preview =
                logistics.previewRates(null, "GM");

        AdminLogisticsResponses.RateCardPreview home = preview.stream()
                .filter(p -> p.currency() != null)
                .findFirst().orElseThrow();
        assertEquals("GMD", home.currency());
        // 50 base + 12/km beyond nothing included, on a 5km leg with 1kg:
        // 50 + 60 + 25 = 135, floored at 50 and under no ceiling.
        assertTrue(home.fiveKmOneKg().signum() > 0);
        assertTrue(home.twentyKmThreeKg().compareTo(home.fiveKmOneKg()) > 0,
                "a longer, heavier leg costs more");
    }

    @Test
    void withNoCardWrittenAtAllThePreviewFallsBackToWhatTheDeploymentShippedWith() {
        List<AdminLogisticsResponses.RateCardPreview> preview =
                logistics.previewRates(null, "GM");

        // Most of this platform's life is spent with no card written, and a
        // marketplace that could not quote carriage until somebody filled in a
        // form would have no first order.
        assertNull(preview.get(0).cardId());
        assertEquals("deployment configuration", preview.get(0).source());
    }

    @Test
    void aCardForAZoneBeatsACardForTheWholeCountry() {
        DeliveryZone zone = drawSerrekunda();
        logistics.createRateCard(operator, new AdminLogisticsRequests.CreateRateCard(
                "Gambia standard", null, "GM", null, "GMD",
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("12.00"),
                BigDecimal.ZERO, new BigDecimal("25.00"), null, null, null,
                LocalDate.now(), null));
        logistics.createRateCard(operator, new AdminLogisticsRequests.CreateRateCard(
                "Serrekunda dense", zone.getId(), "GM", DeliveryMode.HOME_DELIVERY, "GMD",
                new BigDecimal("30.00"), new BigDecimal("2"), new BigDecimal("8.00"),
                BigDecimal.ZERO, new BigDecimal("20.00"), null, null, null,
                LocalDate.now(), null));
        entityManager.flush();

        List<AdminLogisticsResponses.RateCardPreview> inZone =
                logistics.previewRates(zone.getId(), "GM");
        AdminLogisticsResponses.RateCardPreview home = inZone.stream()
                .filter(p -> "Serrekunda dense".equals(p.cardName()))
                .findFirst().orElseThrow();

        // Most specific wins, stated as a rule rather than left to query order —
        // a denser city rate inside a national one is the ordinary arrangement.
        assertTrue(home.source().contains("GM-SRK"));
    }

    private static AdminLogisticsRequests.CreateRateCard card(LocalDate from) {
        return new AdminLogisticsRequests.CreateRateCard(
                "Kombo standard", null, "GM", null, "GMD",
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("12.00"),
                BigDecimal.ZERO, new BigDecimal("25.00"), null, null, null,
                from, "Opening rate for the Kombos.");
    }

    // ── Lists ────────────────────────────────────────────────────────────────

    @Test
    void aDriverOnShiftWithNoPositionIsFlaggedAsAPhoneRatherThanAsAPerson() {
        logistics.approveDriver(operator, ebrima.getId(),
                new AdminLogisticsRequests.ApproveDriver(null, List.of()));
        Driver reloaded = drivers.findById(ebrima.getId()).orElseThrow();
        reloaded.setAvailable(true);
        reloaded.setOnlineSince(java.time.LocalDateTime.now().minusHours(6));
        drivers.save(reloaded);
        entityManager.flush();

        AdminLogisticsResponses.DriverRow row = logistics
                .listDrivers(null, null, null, null, null, PageRequest.of(0, 20))
                .getContent().get(0);

        // The score alone reads as a judgement about a person. Beside the stale
        // position it reads as a dead battery, and those want opposite responses.
        assertTrue(row.flags().stream().anyMatch(f -> f.contains("never reported a position")));
        assertNull(row.minutesSinceLastPing());
    }
}
