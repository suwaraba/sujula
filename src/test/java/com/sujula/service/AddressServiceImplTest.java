package com.sujula.service;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.user.AddressRequest;
import com.sujula.dto.response.user.AddressResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Address;
import com.sujula.model.user.User;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.impl.AddressServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The address book: geocoding, defaults, and who is allowed to see what. */
class AddressServiceImplTest {

    private AddressRepository addressRepository;
    private GoogleMapsService googleMapsService;
    private AddressServiceImpl service;
    private final List<Address> stored = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        addressRepository = mock(AddressRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        googleMapsService = mock(GoogleMapsService.class);

        User owner = new User();
        owner.setId(4L);
        when(userRepository.findById(4L)).thenReturn(Optional.of(owner));

        when(addressRepository.findByUserId(4L)).thenAnswer(i -> List.copyOf(stored));
        when(addressRepository.countByUserId(4L)).thenAnswer(i -> (long) stored.size());
        when(addressRepository.save(any(Address.class))).thenAnswer(i -> {
            Address address = i.getArgument(0);
            if (address.getId() == null) {
                address.setId(ids.getAndIncrement());
                stored.add(address);
            }
            return address;
        });
        when(addressRepository.findById(any())).thenAnswer(i -> {
            Long wanted = i.getArgument(0);
            return stored.stream().filter(a -> wanted.equals(a.getId())).findFirst();
        });
        org.mockito.Mockito.doAnswer(i -> {
            Address removed = i.getArgument(0);
            stored.removeIf(a -> a.getId().equals(removed.getId()));
            return null;
        }).when(addressRepository).delete(any(Address.class));
        org.mockito.Mockito.doAnswer(i -> {
            stored.forEach(a -> a.setDefault(false));
            return null;
        }).when(addressRepository).clearDefaultsByUserId(4L);

        when(googleMapsService.getCoordinates(any(), any()))
                .thenReturn(new GeoAddress("Kairaba Ave", 13.4383, -16.6781, "Gambia", "GM", "Serekunda"));

        service = new AddressServiceImpl(addressRepository, userRepository, googleMapsService);
    }

    @Test
    void geocodesAnAddressTheBuyerTypedWithoutCoordinates() {
        AddressResponse saved = service.create(4L, request("Home", null, null));

        assertEquals(13.4383, saved.getLatitude());
        assertEquals(-16.6781, saved.getLongitude());
        assertTrue(saved.isLocated());
    }

    @Test
    void keepsCoordinatesTheClientAlreadyHad() {
        AddressResponse saved = service.create(4L, request("Pin", 13.45, -16.58));

        // A map pin beats a geocoder guess, so no lookup is made at all.
        assertEquals(13.45, saved.getLatitude());
        verify(googleMapsService, org.mockito.Mockito.never()).getCoordinates(any(), any());
    }

    @Test
    void savesTheAddressAnywayWhenItCannotBePlacedOnAMap() {
        when(googleMapsService.getCoordinates(any(), any()))
                .thenThrow(new RuntimeException("geocoder unreachable"));

        AddressResponse saved = service.create(4L, request("Village", null, null));

        // Someone in a rural area must still be able to say where they live.
        assertNull(saved.getLatitude());
        assertFalse(saved.isLocated(), "the client needs to know delivery will be estimated");
    }

    @Test
    void theFirstAddressBecomesTheDefaultWhateverWasAsked() {
        AddressResponse first = service.create(4L, request("Home", 13.4, -16.6));

        assertTrue(first.isDefault(), "a buyer with one address and no default reaches checkout with nothing selected");
    }

    @Test
    void makingOneDefaultClearsTheRest() {
        service.create(4L, request("Home", 13.4, -16.6));
        AddressResponse work = service.create(4L, request("Work", 13.5, -16.7));

        service.makeDefault(work.getId(), 4L);

        verify(addressRepository, org.mockito.Mockito.atLeastOnce()).clearDefaultsByUserId(4L);
        assertEquals(1, stored.stream().filter(Address::isDefault).count());
        assertTrue(stored.stream().filter(Address::isDefault).findFirst().orElseThrow()
                .getId().equals(work.getId()));
    }

    @Test
    void deletingTheDefaultPromotesAnother() {
        AddressResponse home = service.create(4L, request("Home", 13.4, -16.6));
        service.create(4L, request("Work", 13.5, -16.7));

        service.delete(home.getId(), 4L);

        assertEquals(1, stored.size());
        assertTrue(stored.stream().anyMatch(Address::isDefault),
                "a buyer is never left with addresses and no default");
    }

    @Test
    void anotherBuyersAddressIsNotEvenAcknowledgedToExist() {
        AddressResponse mine = service.create(4L, request("Home", 13.4, -16.6));

        // An address is a name, a phone number and a location: not-found rather
        // than forbidden, so a stranger learns nothing.
        assertThrows(ResourceNotFoundException.class, () -> service.findOne(mine.getId(), 99L));
    }

    @Test
    void capsHowManyAddressesOneBuyerCanHoard() {
        when(addressRepository.countByUserId(4L)).thenReturn(20L);

        assertThrows(BadRequestException.class, () -> service.create(4L, request("Another", 13.4, -16.6)));
    }

    private static AddressRequest request(String label, Double lat, Double lng) {
        return AddressRequest.builder()
                .label(label)
                .fullName("Awa Ceesay")
                .phone("+2201234567")
                .street("14 Kairaba Avenue")
                .city("Serekunda")
                .countryCode("gm")
                .latitude(lat)
                .longitude(lng)
                .build();
    }
}
