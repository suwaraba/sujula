package com.sujula.service.address;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.address.AddressRequests;
import com.sujula.dto.response.address.AddressResponses;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Address;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.user.User;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.GoogleMapsService;
import com.sujula.service.geo.GeocodingGateway;
import com.sujula.service.impl.AddressServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code /me/addresses} surface: geocode confidence, partial edits,
 * confirmed pins, and the delete that has to decide whether anything still
 * points at the row.
 */
class AddressBookTest {

    private static final Long OWNER = 4L;
    private static final Long INTRUDER = 99L;

    private AddressRepository repository;
    private GoogleMapsService maps;
    private AddressServiceImpl service;
    private final List<Address> stored = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private boolean referencedByOrder;

    @BeforeEach
    void setUp() {
        repository = mock(AddressRepository.class);
        UserRepository users = mock(UserRepository.class);
        maps = mock(GoogleMapsService.class);
        referencedByOrder = false;

        User owner = new User();
        owner.setId(OWNER);
        when(users.findById(OWNER)).thenReturn(Optional.of(owner));

        when(repository.save(any(Address.class))).thenAnswer(call -> {
            Address address = call.getArgument(0);
            if (address.getId() == null) {
                address.setId(ids.getAndIncrement());
                stored.add(address);
            }
            return address;
        });
        when(repository.findLiveByUserId(OWNER)).thenAnswer(call -> stored.stream()
                .filter(a -> !a.isDeleted())
                .sorted(Comparator.comparing(Address::isDefault).reversed()
                        .thenComparing(Address::getId, Comparator.reverseOrder()))
                .toList());
        when(repository.countLiveByUserId(OWNER))
                .thenAnswer(call -> stored.stream().filter(a -> !a.isDeleted()).count());
        when(repository.findLiveByIdAndUserId(any(), any())).thenAnswer(call -> {
            Long wanted = call.getArgument(0);
            Long asUser = call.getArgument(1);
            return stored.stream()
                    .filter(a -> wanted.equals(a.getId()) && !a.isDeleted())
                    .filter(a -> a.getUser() != null && a.getUser().getId().equals(asUser))
                    .findFirst();
        });
        when(repository.findNextDefaultCandidate(any(), any())).thenAnswer(call -> {
            Long excluded = call.getArgument(1);
            return stored.stream()
                    .filter(a -> !a.isDeleted() && !a.getId().equals(excluded))
                    .min(Comparator.comparing(Address::getId));
        });
        when(repository.isReferencedByAnyOrder(any())).thenAnswer(call -> referencedByOrder);
        org.mockito.Mockito.doAnswer(call -> {
            Address removed = call.getArgument(0);
            stored.removeIf(a -> a.getId().equals(removed.getId()));
            return null;
        }).when(repository).delete(any(Address.class));
        org.mockito.Mockito.doAnswer(call -> {
            stored.forEach(a -> a.setDefault(false));
            return null;
        }).when(repository).clearDefaultsByUserId(OWNER);

        GeocodingGateway geocoding = new GeocodingGateway(maps, "en", "a-test-api-key");
        service = new AddressServiceImpl(repository, users, maps, geocoding);
    }

    private void geocoderReturns(GeocodeConfidence confidence) {
        when(maps.getCoordinates(any(), any())).thenReturn(new GeoAddress(
                "14 Kairaba Avenue", 13.4383, -16.6781, "Gambia", "GM", "Serekunda", confidence));
    }

    private static AddressRequests.Create create(String label, Double lat, Double lng) {
        return new AddressRequests.Create(label, "Awa Ceesay", "+2201234567",
                "14 Kairaba Avenue", null, "Serekunda", null, null, "GM", lat, lng, false);
    }

    private AddressResponses.Address saved(GeocodeConfidence confidence) {
        geocoderReturns(confidence);
        return service.add(OWNER, create("Home", null, null));
    }

    // ── Confidence ───────────────────────────────────────────────────────────

    @Test
    void aRooftopMatchIsDispatchable() {
        AddressResponses.Address address = saved(GeocodeConfidence.EXACT);

        assertEquals(GeocodeConfidence.EXACT, address.confidence());
        assertTrue(address.dispatchable());
        assertFalse(address.needsPinConfirmation());
        assertNotNull(address.geocodedAt());
    }

    /**
     * The case this whole feature is for. Most of the country resolves no better
     * than the town, and a client has to know to show a map rather than send a
     * rider to a centroid.
     */
    @Test
    void aTownCentreAsksTheBuyerToConfirm() {
        AddressResponses.Address address = saved(GeocodeConfidence.APPROXIMATE);

        assertTrue(address.needsPinConfirmation());
        assertFalse(address.dispatchable());
    }

    @Test
    void aCentroidIsAlsoNotGoodEnough() {
        assertTrue(saved(GeocodeConfidence.CENTROID).needsPinConfirmation());
    }

    /** Interpolation is accurate to a few doors, which a rider resolves by looking. */
    @Test
    void streetInterpolationIsGoodEnoughToDispatch() {
        assertTrue(saved(GeocodeConfidence.INTERPOLATED).dispatchable());
    }

    @Test
    void anAddressNobodyCanFindIsStillSaved() {
        when(maps.getCoordinates(any(), any()))
                .thenThrow(new RuntimeException("geocoder unreachable"));

        AddressResponses.Address address = service.add(OWNER, create("Compound", null, null));

        assertNotNull(address.id(), "not being on a map is not a reason to refuse someone's home");
        assertFalse(address.located());
        assertEquals(GeocodeConfidence.NONE, address.confidence());
        assertNull(address.geocodedAt());
    }

    /** A device reporting its own position beats any address database. */
    @Test
    void coordinatesFromTheDeviceAreTrusted() {
        AddressResponses.Address address = service.add(OWNER, create("Here", 13.44, -16.67));

        assertEquals(GeocodeConfidence.EXACT, address.confidence());
        assertEquals(13.44, address.latitude());
        verify(maps, never()).getCoordinates(any(), any());
    }

    /** Transposed coordinates put Serekunda in the South Atlantic. */
    @Test
    void nonsenseCoordinatesFallBackToGeocoding() {
        geocoderReturns(GeocodeConfidence.CENTROID);

        AddressResponses.Address address = service.add(OWNER, create("Home", 0.0, 0.0));

        assertEquals(GeocodeConfidence.CENTROID, address.confidence());
        assertEquals(13.4383, address.latitude());
    }

    // ── Confirmed pins ───────────────────────────────────────────────────────

    @Test
    void aConfirmedPinOutranksTheGeocoder() {
        AddressResponses.Address address = saved(GeocodeConfidence.APPROXIMATE);

        AddressResponses.Address confirmed = service.confirmPin(OWNER, address.id(),
                new AddressRequests.ConfirmPin(13.4500, -16.6800));

        assertEquals(GeocodeConfidence.USER_CONFIRMED, confirmed.confidence());
        assertTrue(confirmed.dispatchable());
        assertTrue(confirmed.pinConfirmedByOwner());
        assertEquals(13.4500, confirmed.latitude());
    }

    /**
     * The point of recording that the owner confirmed it: a later edit to the
     * written address must not quietly move their home back to a town centre.
     */
    @Test
    void aLaterEditDoesNotMoveAConfirmedPin() {
        AddressResponses.Address address = saved(GeocodeConfidence.APPROXIMATE);
        service.confirmPin(OWNER, address.id(), new AddressRequests.ConfirmPin(13.4500, -16.6800));
        geocoderReturns(GeocodeConfidence.APPROXIMATE);

        AddressResponses.Address edited = service.patch(OWNER, address.id(),
                new AddressRequests.Patch(null, null, null, "15 Kairaba Avenue",
                        null, null, null, null, null, null, null, null));

        assertEquals(13.4500, edited.latitude(), "the resident's own pin must survive an edit");
        assertEquals(GeocodeConfidence.USER_CONFIRMED, edited.confidence());
    }

    // ── Patch ────────────────────────────────────────────────────────────────

    @Test
    void anOmittedFieldIsLeftAlone() {
        AddressResponses.Address address = saved(GeocodeConfidence.EXACT);

        AddressResponses.Address edited = service.patch(OWNER, address.id(),
                new AddressRequests.Patch(null, null, "+2209999999", null,
                        null, null, null, null, null, null, null, null));

        assertEquals("+2209999999", edited.phone());
        assertEquals("14 Kairaba Avenue", edited.street());
        assertEquals("Serekunda", edited.city());
    }

    /** Correcting a phone number must not cost a geocoding call. */
    @Test
    void changingWhoIsThereDoesNotReGeocode() {
        AddressResponses.Address address = saved(GeocodeConfidence.EXACT);
        org.mockito.Mockito.clearInvocations(maps);

        service.patch(OWNER, address.id(), new AddressRequests.Patch(
                "Work", "Awa C", "+2200000000", null, null, null, null, null, null, null, null, null));

        verify(maps, never()).getCoordinates(any(), any());
    }

    @Test
    void movingTheAddressDoesReGeocode() {
        AddressResponses.Address address = saved(GeocodeConfidence.EXACT);
        org.mockito.Mockito.clearInvocations(maps);
        geocoderReturns(GeocodeConfidence.CENTROID);

        AddressResponses.Address edited = service.patch(OWNER, address.id(),
                new AddressRequests.Patch(null, null, null, null, null, "Brikama",
                        null, null, null, null, null, null));

        verify(maps).getCoordinates(any(), any());
        assertEquals(GeocodeConfidence.CENTROID, edited.confidence());
    }

    // ── Deletion ─────────────────────────────────────────────────────────────

    @Test
    void anAddressNobodyOrderedAgainstIsDeletedOutright() {
        AddressResponses.Address address = saved(GeocodeConfidence.EXACT);
        referencedByOrder = false;

        AddressResponses.Deletion deletion = service.remove(OWNER, address.id());

        assertFalse(deletion.retained());
        verify(repository).delete(any(Address.class));
        assertTrue(service.listMine(OWNER).isEmpty());
    }

    /**
     * An order that named this row still has to resolve to something, so the row
     * stays — but it leaves the book, which is what the owner asked for.
     */
    @Test
    void anAddressAnOrderNamedIsKeptButHidden() {
        AddressResponses.Address address = saved(GeocodeConfidence.EXACT);
        referencedByOrder = true;

        AddressResponses.Deletion deletion = service.remove(OWNER, address.id());

        assertTrue(deletion.retained());
        verify(repository, never()).delete(any(Address.class));
        assertTrue(service.listMine(OWNER).isEmpty(), "a tombstone is not in the address book");
        assertThrows(ResourceNotFoundException.class, () -> service.getMine(OWNER, address.id()));
    }

    @Test
    void deletingTheDefaultPromotesAnother() {
        AddressResponses.Address first = saved(GeocodeConfidence.EXACT);
        AddressResponses.Address second = saved(GeocodeConfidence.EXACT);
        assertTrue(first.isDefault());

        service.remove(OWNER, first.id());

        assertTrue(service.getMine(OWNER, second.id()).isDefault(),
                "a buyer with addresses must never be left without a default");
    }

    /** A retained address must not go on using up one of the twenty. */
    @Test
    void aTombstoneDoesNotCountAgainstTheCap() {
        AddressResponses.Address address = saved(GeocodeConfidence.EXACT);
        referencedByOrder = true;
        service.remove(OWNER, address.id());

        assertEquals(0, service.listMine(OWNER).size());
    }

    // ── Ownership ────────────────────────────────────────────────────────────

    @Test
    void anotherBuyersAddressIsNotAcknowledgedToExist() {
        AddressResponses.Address address = saved(GeocodeConfidence.EXACT);

        assertThrows(ResourceNotFoundException.class, () -> service.getMine(INTRUDER, address.id()));
        assertThrows(ResourceNotFoundException.class, () -> service.remove(INTRUDER, address.id()));
        assertThrows(ResourceNotFoundException.class, () -> service.confirmPin(
                INTRUDER, address.id(), new AddressRequests.ConfirmPin(13.0, -16.0)));
        assertThrows(ResourceNotFoundException.class, () -> service.patch(
                INTRUDER, address.id(), new AddressRequests.Patch(
                        "Stolen", null, null, null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void theFirstAddressIsTheDefaultWhateverWasAsked() {
        assertTrue(saved(GeocodeConfidence.EXACT).isDefault());
    }
}
