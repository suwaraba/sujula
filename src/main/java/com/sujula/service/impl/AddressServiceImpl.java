package com.sujula.service.impl;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.user.AddressRequest;
import com.sujula.dto.response.user.AddressResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Address;
import com.sujula.model.user.User;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.AddressService;
import com.sujula.service.GoogleMapsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Saved delivery addresses, geocoded on the way in.
 *
 * <p>Ownership is checked on every read and write, and a stranger's address
 * reads as not-found rather than forbidden: confirming that address 41 exists
 * tells someone something about a person they have no business knowing.
 */
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private static final Logger log = LoggerFactory.getLogger(AddressServiceImpl.class);

    /** Beyond this a buyer is collecting addresses, not using them. */
    private static final int MAX_ADDRESSES_PER_USER = 20;

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final GoogleMapsService googleMapsService;

    @Value("${sujula.geocoding.default-language:en}")
    private String geocodingLanguage;

    @Override
    @Transactional(readOnly = true)
    public List<AddressResponse> findMine(Long userId) {
        return addressRepository.findByUserId(requireUserId(userId)).stream()
                // Default first, then newest: the order a chooser should offer them in.
                .sorted(Comparator.comparing(Address::isDefault).reversed()
                        .thenComparing(Address::getId, Comparator.reverseOrder()))
                .map(AddressResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AddressResponse findOne(Long addressId, Long userId) {
        return AddressResponse.from(requireOwnAddress(addressId, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public AddressResponse findDefault(Long userId) {
        return addressRepository.findByUserId(requireUserId(userId)).stream()
                .filter(Address::isDefault)
                .findFirst()
                .map(AddressResponse::from)
                .orElse(null);
    }

    @Override
    @Transactional
    public AddressResponse create(Long userId, AddressRequest request) {
        requireUserId(userId);
        if (addressRepository.countByUserId(userId) >= MAX_ADDRESSES_PER_USER) {
            throw new BadRequestException(
                    "You can save up to " + MAX_ADDRESSES_PER_USER + " addresses. Remove one to add another.");
        }
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Address address = new Address();
        address.setUser(owner);
        apply(address, request);

        // The first address is the default whatever was asked for: a buyer with
        // exactly one address and no default would reach checkout with nothing
        // selected.
        boolean first = addressRepository.countByUserId(userId) == 0;
        if (first || request.isMakeDefault()) {
            addressRepository.clearDefaultsByUserId(userId);
            address.setDefault(true);
        }
        return AddressResponse.from(addressRepository.save(address));
    }

    @Override
    @Transactional
    public AddressResponse update(Long addressId, Long userId, AddressRequest request) {
        Address address = requireOwnAddress(addressId, userId);
        apply(address, request);

        if (request.isMakeDefault() && !address.isDefault()) {
            addressRepository.clearDefaultsByUserId(userId);
            address.setDefault(true);
        }
        return AddressResponse.from(addressRepository.save(address));
    }

    @Override
    @Transactional
    public AddressResponse makeDefault(Long addressId, Long userId) {
        Address address = requireOwnAddress(addressId, userId);
        if (address.isDefault()) {
            return AddressResponse.from(address);
        }
        addressRepository.clearDefaultsByUserId(userId);
        address.setDefault(true);
        return AddressResponse.from(addressRepository.save(address));
    }

    @Override
    @Transactional
    public void delete(Long addressId, Long userId) {
        Address address = requireOwnAddress(addressId, userId);
        boolean wasDefault = address.isDefault();
        addressRepository.delete(address);

        if (!wasDefault) {
            return;
        }
        // Never leave a buyer with addresses but no default — the next oldest
        // takes over rather than checkout finding nothing selected.
        addressRepository.findByUserId(userId).stream()
                .min(Comparator.comparing(Address::getId))
                .ifPresent(next -> {
                    next.setDefault(true);
                    addressRepository.save(next);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void apply(Address address, AddressRequest request) {
        address.setLabel(request.getLabel().trim());
        address.setFullName(request.getFullName().trim());
        address.setPhone(request.getPhone().trim());
        address.setStreet(request.getStreet().trim());
        address.setApartmentSuite(blankToNull(request.getApartmentSuite()));
        address.setCity(request.getCity().trim());
        address.setState(blankToNull(request.getState()));
        address.setPostalCode(blankToNull(request.getPostalCode()));
        address.setCountryCode(request.getCountryCode().trim().toUpperCase());

        if (request.getLatitude() != null && request.getLongitude() != null) {
            address.setLatitude(request.getLatitude());
            address.setLongitude(request.getLongitude());
            return;
        }
        locate(address);
    }

    /**
     * Places the address on a map from what the buyer typed.
     *
     * <p>Best-effort: a geocoder that is unreachable, unconfigured or simply
     * cannot find a rural address must not stop someone saving where they live.
     * The address is stored without coordinates and delivery falls back to a
     * scope distance, which the response flags so the client can ask again.
     */
    private void locate(Address address) {
        String search = String.join(", ",
                List.of(address.getStreet(), address.getCity(), address.getCountryCode()));
        // Defaulted here as well as on the property: the geocoder rejects a null
        // language, and a misconfigured property would otherwise look exactly
        // like an address nobody can find.
        String language = (geocodingLanguage == null || geocodingLanguage.isBlank()) ? "en" : geocodingLanguage;
        try {
            GeoAddress located = googleMapsService.getCoordinates(search, language);
            address.setLatitude(located.getLatitude());
            address.setLongitude(located.getLongitude());
        } catch (RuntimeException ex) {
            address.setLatitude(null);
            address.setLongitude(null);
            log.info("[Address] Could not place '{}' on a map; delivery will use a fallback distance: {}",
                    search, ex.getMessage());
        }
    }

    /**
     * A stranger's address reads as not-found rather than forbidden: an address
     * carries a name, a phone number and a location, and confirming that one
     * exists is itself worth withholding.
     */
    private Address requireOwnAddress(Long addressId, Long userId) {
        requireUserId(userId);
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address", addressId));
        if (address.getUser() == null || !address.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Address", addressId);
        }
        return address;
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new BadRequestException("An address belongs to a signed-in buyer");
        }
        return userId;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
