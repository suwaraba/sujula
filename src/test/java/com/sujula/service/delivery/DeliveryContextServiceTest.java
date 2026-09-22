package com.sujula.service.delivery;

import com.sujula.dto.request.delivery.DeliveryContextRequests;
import com.sujula.dto.response.delivery.DeliveryContextResponse;
import com.sujula.dto.response.geo.GeoResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Address;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.delivery.DeliveryContext;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.user.User;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.DeliveryContextRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.GoogleMapsService;
import com.sujula.service.geo.GeoLookupService;
import com.sujula.service.geo.GeocodingGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The delivery context, and the one question that makes it security-sensitive:
 * who is allowed to read one back.
 *
 * <p>For a guest the id is the credential — there is nothing else to go on. For
 * a signed-in shopper the row is bound to them as well, so a leaked id does not
 * hand a stranger their home address.
 */
class DeliveryContextServiceTest {

    private static final Long OWNER = 4L;
    private static final Long INTRUDER = 99L;

    private DeliveryContextRepository repository;
    private AddressRepository addresses;
    private PickupPointRepository pickupPoints;
    private DeliveryContextService service;
    private final List<DeliveryContext> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(DeliveryContextRepository.class);
        addresses = mock(AddressRepository.class);
        pickupPoints = mock(PickupPointRepository.class);
        UserRepository users = mock(UserRepository.class);
        GeoLookupService geoLookup = mock(GeoLookupService.class);
        GoogleMapsService maps = mock(GoogleMapsService.class);
        GeocodingGateway geocoding = new GeocodingGateway(maps, "en", "");

        User owner = new User();
        owner.setId(OWNER);
        when(users.findById(OWNER)).thenReturn(Optional.of(owner));

        when(geoLookup.resolveContext(any())).thenReturn(new GeoResponses.ResolvedContext(
                true, "GM", "GMD", "en", "Africa/Banjul", "ip"));

        when(repository.save(any(DeliveryContext.class))).thenAnswer(call -> {
            DeliveryContext context = call.getArgument(0);
            stored.add(context);
            return context;
        });
        when(repository.findLive(any())).thenAnswer(call -> {
            String id = call.getArgument(0);
            return stored.stream()
                    .filter(context -> context.getId().equals(id) && !context.isExpired())
                    .findFirst();
        });

        service = new DeliveryContextService(repository, addresses, pickupPoints, users,
                geocoding, geoLookup, java.time.Duration.ofHours(12));
    }

    private static DeliveryContextRequests.Create request(Double lat, Double lng) {
        return new DeliveryContextRequests.Create(null, lat, lng, "14 Kairaba Avenue", "Serekunda",
                null, null, "GM", DeliveryMode.HOME_DELIVERY, null, null, null);
    }

    private static HttpServletRequest http() {
        return mock(HttpServletRequest.class);
    }

    // ── Ownership ────────────────────────────────────────────────────────────

    /** A guest has nothing but the id, so the id has to be enough. */
    @Test
    void aGuestReadsTheirContextBackWithTheIdAlone() {
        DeliveryContextResponse created = service.create(null, request(13.44, -16.67), http());

        assertTrue(created.guest());
        assertEquals(created.id(), service.get(created.id(), null).id());
    }

    /**
     * The one that matters. A context made while signed in must not be readable
     * by anyone who comes across the id.
     */
    @Test
    void aSignedInShoppersContextIsNotReadableByAnybodyElse() {
        DeliveryContextResponse created = service.create(OWNER, request(13.44, -16.67), http());

        assertFalse(created.guest());
        assertEquals(created.id(), service.get(created.id(), OWNER).id());

        // Not-found rather than forbidden: confirming it exists would tell
        // whoever guessed the id that they guessed right.
        assertThrows(ResourceNotFoundException.class, () -> service.get(created.id(), INTRUDER));
        assertThrows(ResourceNotFoundException.class, () -> service.get(created.id(), null));
    }

    /**
     * The non-throwing form, and why it has to exist rather than being a catch
     * around {@link DeliveryContextService#require}.
     *
     * <p>Reading a cart prices its shipping on a best-effort basis, so a context
     * that has lapsed or belongs to somebody else has to leave the cart readable
     * without a shipping figure. Asking for that as a caught exception did not
     * produce it: require() joins the caller's transaction, its exception marks
     * that transaction rollback-only, and the commit after the catch returned
     * normally failed — GET /carts/&#123;token&#125; answered 500 in exactly the
     * case the degradation was written for.
     *
     * <p>Same three answers as get(), and never an exception for any of them.
     */
    @Test
    void lookupAnswersEmptyWhereRequireThrows() {
        DeliveryContextResponse mine = service.create(OWNER, request(13.44, -16.67), http());
        DeliveryContextResponse guests = service.create(null, request(13.27, -16.64), http());

        assertTrue(service.lookup(mine.id(), OWNER).isPresent());
        assertTrue(service.lookup(guests.id(), null).isPresent(),
                "a guest's context is readable by whoever holds the id");

        assertTrue(service.lookup(mine.id(), INTRUDER).isEmpty(), "somebody else's context");
        assertTrue(service.lookup(mine.id(), null).isEmpty(), "nobody signed in");
        assertTrue(service.lookup("never-issued", null).isEmpty(), "an id that was never real");
        assertTrue(service.lookup(null, OWNER).isEmpty(), "no id at all");
        assertTrue(service.lookup("   ", OWNER).isEmpty(), "a blank id");

        // And require still throws for the callers that want a 404.
        assertThrows(ResourceNotFoundException.class, () -> service.require(mine.id(), INTRUDER));
    }

    @Test
    void lookupTreatsALapsedContextAsAbsentRatherThanThrowing() {
        DeliveryContextResponse created = service.create(null, request(13.44, -16.67), http());
        stored.forEach(context -> context.setExpiresAt(LocalDateTime.now().minusMinutes(1)));

        assertTrue(service.lookup(created.id(), null).isEmpty());
    }

    @Test
    void anExpiredContextIsIndistinguishableFromOneThatNeverExisted() {
        DeliveryContextResponse created = service.create(null, request(13.44, -16.67), http());
        stored.forEach(context -> context.setExpiresAt(LocalDateTime.now().minusMinutes(1)));

        assertThrows(ResourceNotFoundException.class, () -> service.get(created.id(), null));
        assertThrows(ResourceNotFoundException.class, () -> service.get("never-issued", null));
    }

    /**
     * Sequential ids would put the next shopper's destination one increment away,
     * which for a guest is the only thing between a stranger and their address.
     */
    @Test
    void idsAreLongAndUnguessable() {
        String first = service.create(null, request(13.44, -16.67), http()).id();
        String second = service.create(null, request(13.44, -16.67), http()).id();

        assertNotEquals(first, second);
        assertTrue(first.length() >= 43, "a 256-bit id, got " + first.length() + " characters");
        assertFalse(first.matches("\\d+"), "must not be a sequence");
    }

    // ── Saved addresses ──────────────────────────────────────────────────────

    @Test
    void asavedAddressIsCopiedInWithItsConfidence() {
        Address address = new Address();
        address.setId(70L);
        address.setStreet("14 Kairaba Avenue");
        address.setCity("Serekunda");
        address.setCountryCode("GM");
        address.setLatitude(13.4383);
        address.setLongitude(-16.6781);
        address.setGeocodeConfidence(GeocodeConfidence.USER_CONFIRMED);
        when(addresses.findLiveByIdAndUserId(70L, OWNER)).thenReturn(Optional.of(address));

        DeliveryContextResponse created = service.create(OWNER,
                new DeliveryContextRequests.Create(70L, null, null, null, null, null, null, null,
                        DeliveryMode.HOME_DELIVERY, null, null, null), http());

        assertEquals(70L, created.addressId());
        assertEquals(13.4383, created.latitude());
        assertEquals(GeocodeConfidence.USER_CONFIRMED, created.confidence());
        assertFalse(created.needsPinConfirmation());
    }

    /** Ownership is the query: another buyer's address id resolves to nothing. */
    @Test
    void anotherBuyersAddressCannotBeBorrowed() {
        when(addresses.findLiveByIdAndUserId(70L, INTRUDER)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.create(INTRUDER,
                new DeliveryContextRequests.Create(70L, null, null, null, null, null, null, null,
                        DeliveryMode.HOME_DELIVERY, null, null, null), http()));
    }

    @Test
    void aGuestCannotNameASavedAddress() {
        assertThrows(BadRequestException.class, () -> service.create(null,
                new DeliveryContextRequests.Create(70L, null, null, null, null, null, null, null,
                        DeliveryMode.HOME_DELIVERY, null, null, null), http()));
    }

    // ── Collection ───────────────────────────────────────────────────────────

    @Test
    void collectionNeedsAHub() {
        assertThrows(BadRequestException.class, () -> service.create(null,
                new DeliveryContextRequests.Create(null, 13.44, -16.67, null, null, null, null, "GM",
                        DeliveryMode.PICKUP_POINT, null, null, null), http()));
    }

    @Test
    void aSuspendedHubIsRefused() {
        PickupPoint hub = new PickupPoint();
        hub.setId(8L);
        hub.setActive(true);
        hub.setStatus(PartnerStatus.SUSPENDED);
        when(pickupPoints.findById(8L)).thenReturn(Optional.of(hub));

        assertThrows(BadRequestException.class, () -> service.create(null,
                new DeliveryContextRequests.Create(null, 13.44, -16.67, null, null, null, null, "GM",
                        DeliveryMode.PICKUP_POINT, 8L, null, null), http()));
    }

    /**
     * A client switching from collection back to home delivery usually leaves the
     * old id in the payload. Failing the request over a field that no longer
     * applies helps nobody.
     */
    @Test
    void aStalePickupPointIdIsIgnoredWhenTheModeChanged() {
        DeliveryContextResponse created = service.create(null,
                new DeliveryContextRequests.Create(null, 13.44, -16.67, null, null, null, null, "GM",
                        DeliveryMode.HOME_DELIVERY, 8L, null, null), http());

        assertEquals(DeliveryMode.HOME_DELIVERY, created.mode());
        assertEquals(null, created.pickupPointId());
    }

    // ── Falling back ─────────────────────────────────────────────────────────

    /**
     * A shopper who has said nothing yet still gets a country and a currency,
     * which is enough to price a catalogue.
     */
    @Test
    void anEmptyRequestStillResolvesAShoppingContext() {
        DeliveryContextResponse created = service.create(null,
                new DeliveryContextRequests.Create(null, null, null, null, null, null, null, null,
                        null, null, null, null), http());

        assertEquals("GM", created.countryCode());
        assertEquals("GMD", created.currency());
        assertEquals("Africa/Banjul", created.timezone());
        assertFalse(created.deliverable(), "nowhere to deliver to yet, and it says so");
    }

    /** Collection needs no destination at all — the parcel goes to a hub. */
    @Test
    void collectionIsDeliverableWithoutADestinationPin() {
        PickupPoint hub = new PickupPoint();
        hub.setId(8L);
        hub.setActive(true);
        hub.setStatus(PartnerStatus.ACTIVE);
        when(pickupPoints.findById(8L)).thenReturn(Optional.of(hub));

        DeliveryContextResponse created = service.create(null,
                new DeliveryContextRequests.Create(null, null, null, null, null, null, null, "GM",
                        DeliveryMode.PICKUP_POINT, 8L, null, null), http());

        assertTrue(created.deliverable());
        assertEquals(8L, created.pickupPointId());
    }

    @Test
    void aRequestedCurrencyWinsOverTheInferredOne() {
        DeliveryContextResponse created = service.create(null,
                new DeliveryContextRequests.Create(null, 13.44, -16.67, null, null, null, null, "GM",
                        DeliveryMode.HOME_DELIVERY, null, "gbp", null), http());

        assertEquals("GBP", created.currency());
    }
}
