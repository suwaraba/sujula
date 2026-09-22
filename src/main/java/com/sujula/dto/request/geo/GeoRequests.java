package com.sujula.dto.request.geo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What the public geo endpoints accept. */
public final class GeoRequests {

    private GeoRequests() {}

    /**
     * An address to check before anyone commits to it.
     *
     * <p>Either a single line or the parts; the parts are joined in the order a
     * geocoder reads best. Nothing is saved, which is the point — this is the
     * lookup a checkout form runs while someone is still typing.
     */
    public record ValidateAddress(
            @Size(max = 300) String query,
            @Size(max = 200) String street,
            @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 20) String postalCode,

            @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be a 2-letter ISO country code")
            String countryCode,

            @Size(max = 10) String language) {

        /** Everything given, in the order a geocoder reads best. */
        public String toSearchText() {
            if (query != null && !query.isBlank()) {
                return query.trim();
            }
            StringBuilder text = new StringBuilder();
            for (String part : new String[]{street, city, state, postalCode, countryCode}) {
                if (part != null && !part.isBlank()) {
                    if (!text.isEmpty()) {
                        text.append(", ");
                    }
                    text.append(part.trim());
                }
            }
            return text.isEmpty() ? null : text.toString();
        }
    }

    /** A point on a map to put a name to. */
    public record Reverse(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
            @Size(max = 10) String language) {}
}
