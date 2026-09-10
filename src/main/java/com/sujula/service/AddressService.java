package com.sujula.service;

import com.sujula.dto.request.user.AddressRequest;
import com.sujula.dto.response.user.AddressResponse;

import java.util.List;

/**
 * A buyer's saved delivery addresses.
 *
 * <p>Every method is scoped to one owner and takes their id from the caller's
 * authenticated principal, never from the request — an address carries a name, a
 * phone number and a location, which is enough to find someone.
 *
 * <p>Addresses are geocoded on save when the client did not supply coordinates.
 * Delivery is priced from the distance a parcel travels, so an address without
 * them prices every leg from a flat fallback.
 */
public interface AddressService {

    List<AddressResponse> findMine(Long userId);

    AddressResponse findOne(Long addressId, Long userId);

    /** The one checkout uses when the buyer does not choose. Null when they have none saved. */
    AddressResponse findDefault(Long userId);

    /** The first address saved becomes the default regardless of what was asked for. */
    AddressResponse create(Long userId, AddressRequest request);

    AddressResponse update(Long addressId, Long userId, AddressRequest request);

    AddressResponse makeDefault(Long addressId, Long userId);

    /**
     * Removes an address. Orders already placed keep their own snapshot of it, so
     * deleting one never rewrites history.
     */
    void delete(Long addressId, Long userId);
}
