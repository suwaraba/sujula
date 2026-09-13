package com.sujula.dto.request.address;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a client sends to the address book.
 *
 * <p>Records, so they arrive immutable and cannot be edited on the way through a
 * controller into a service. Nested in one holder because they are one
 * vocabulary and reading them together is how you see that {@code Create}
 * requires what {@code Patch} makes optional.
 */
public final class AddressRequests {

    private AddressRequests() {}

    /**
     * A new address.
     *
     * <p>Coordinates are optional and are trusted when present: a device that
     * offers "use my current location" already knows better than any geocoder
     * where the person is standing. Absent, the written address is geocoded.
     */
    public record Create(
            @NotBlank @Size(max = 60) String label,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 200) String street,
            @Size(max = 120) String apartmentSuite,
            @NotBlank @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 20) String postalCode,

            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be a 2-letter ISO country code")
            String countryCode,

            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") Double latitude,
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") Double longitude,

            /** Make this the address checkout preselects. The first one saved always is. */
            boolean makeDefault) {}

    /**
     * A partial edit.
     *
     * <p>Every field is optional and only what is sent is changed. A PATCH that
     * null-blanked the omitted fields would wipe an address any time a client
     * posted a half-filled form, which is the normal shape of a "change the
     * phone number" screen.
     */
    public record Patch(
            @Size(max = 60) String label,
            @Size(max = 120) String fullName,
            @Size(max = 30) String phone,
            @Size(max = 200) String street,
            @Size(max = 120) String apartmentSuite,
            @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 20) String postalCode,

            @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be a 2-letter ISO country code")
            String countryCode,

            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") Double latitude,
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") Double longitude,

            Boolean makeDefault) {

        /**
         * Whether this edit changes where the address is, as opposed to who is
         * at it.
         *
         * <p>This is what decides whether to geocode again. Correcting a phone
         * number must not cost a geocoding call, and — more to the point — must
         * not move a pin the resident placed themselves.
         */
        public boolean touchesLocation() {
            return street != null || city != null || state != null
                    || postalCode != null || countryCode != null;
        }

        /** Whether the client supplied a pin of its own. */
        public boolean hasCoordinates() {
            return latitude != null && longitude != null;
        }
    }

    /**
     * The buyer moving the pin to where they actually live.
     *
     * <p>The answer to a geocoder that placed them in the middle of their town,
     * which in much of the region is the usual result rather than a failure. What
     * they confirm outranks anything resolved afterwards — nobody knows the
     * compound better than the person in it.
     */
    public record ConfirmPin(
            @NotNull @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") Double latitude,
            @NotNull @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") Double longitude) {}
}
