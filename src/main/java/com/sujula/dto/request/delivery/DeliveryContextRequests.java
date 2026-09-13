package com.sujula.dto.request.delivery;

import com.sujula.model.constant.DeliveryMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What a client sends to establish where a basket is going. */
public final class DeliveryContextRequests {

    private DeliveryContextRequests() {}

    /**
     * Where the goods are going, and how they are being received.
     *
     * <p>Three ways to say it, in descending order of precision: a saved address
     * (signed-in buyers only), a pair of coordinates, or a written address to be
     * geocoded. Any of them may be empty — a shopper who has said nothing yet
     * still gets a context carrying their resolved country and currency, which is
     * enough to price a catalogue.
     */
    public record Create(
            /** A saved address. Ignored for a guest, who by definition has none. */
            Long addressId,

            @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,

            @Size(max = 255) String addressLine,
            @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 20) String postalCode,

            @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be a 2-letter ISO country code")
            String countryCode,

            /** Defaults to home delivery. */
            DeliveryMode mode,

            /** Required when the mode is collection — the hub prices the leg. */
            Long pickupPointId,

            @Size(min = 3, max = 3) String currency,
            @Size(max = 10) String language) {

        public boolean hasCoordinates() {
            return latitude != null && longitude != null;
        }

        public boolean hasWrittenAddress() {
            return notBlank(addressLine) || notBlank(city) || notBlank(postalCode);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
