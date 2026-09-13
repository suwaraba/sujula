package com.sujula.service;

import com.sujula.dto.request.address.AddressRequests;
import com.sujula.dto.request.user.AddressRequest;
import com.sujula.dto.response.address.AddressResponses;
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

    // ─────────────────────────────────────────────────────────────────────────
    //  The /me/addresses surface
    //
    //  Same rules, same table, same owner checks — a second implementation over
    //  one entity is how two sets of rules end up disagreeing about whose
    //  address is whose. What is new here is what the older shape could not
    //  express: geocoding confidence, partial edits, a confirmed pin, and a
    //  delete that knows whether an order named the row.
    // ─────────────────────────────────────────────────────────────────────────

    /** The address book: live addresses only, default first, then newest. */
    List<AddressResponses.Address> listMine(Long userId);

    /** One address, or not-found if it is not this owner's. */
    AddressResponses.Address getMine(Long userId, Long addressId);

    /** Saves a new address, geocoding it when the client supplied no pin. */
    AddressResponses.Address add(Long userId, AddressRequests.Create request);

    /**
     * Changes part of an address.
     *
     * <p>Re-geocodes only when the edit moved it, and never when the owner has
     * confirmed the pin themselves.
     */
    AddressResponses.Address patch(Long userId, Long addressId, AddressRequests.Patch request);

    /**
     * Removes an address from the book.
     *
     * <p>Kept as a tombstone when an order was placed against it, so that order
     * still resolves; removed outright when none was.
     */
    AddressResponses.Deletion remove(Long userId, Long addressId);

    /** Records the pin its owner placed, which outranks any later geocoding. */
    AddressResponses.Address confirmPin(Long userId, Long addressId, AddressRequests.ConfirmPin request);
}
