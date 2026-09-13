package com.sujula.dto.response.address;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.GeocodeConfidence;

import java.time.LocalDateTime;

/**
 * What the address book returns.
 *
 * <p>Records rather than entities, so nothing about the persistence model leaks
 * into the API and an entity can never be serialised by accident — including the
 * owner it hangs off, which would take a {@code User} and everything on it out
 * through the response.
 */
public final class AddressResponses {

    private AddressResponses() {}

    /**
     * A saved address, as its owner sees it.
     *
     * @param confidence       how much the pin is worth — the field that lets a
     *                         client decide whether to show a map and ask
     * @param needsPinConfirmation shorthand for the client: the confidence is too
     *                         low to dispatch on and nobody has confirmed it
     * @param dispatchable     whether a rider could be sent here as things stand
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Address(
            Long id,
            String label,
            String fullName,
            String phone,
            String street,
            String apartmentSuite,
            String city,
            String state,
            String postalCode,
            String countryCode,
            Double latitude,
            Double longitude,
            boolean located,
            GeocodeConfidence confidence,
            boolean needsPinConfirmation,
            boolean dispatchable,
            boolean pinConfirmedByOwner,
            boolean isDefault,
            LocalDateTime geocodedAt,
            LocalDateTime createdAt) {}

    /**
     * What happened to an address that was deleted.
     *
     * @param retained true when the row was kept as a tombstone because an order
     *                 was placed against it. The address is gone from the book
     *                 either way; this says whether anything remains behind it,
     *                 which a client can pass on to someone asking to be
     *                 forgotten.
     */
    public record Deletion(Long id, boolean retained, String reason) {}
}
