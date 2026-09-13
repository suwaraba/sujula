package com.sujula.dto.response.geo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.GeocodeConfidence;

/** What the public geo endpoints return. */
public final class GeoResponses {

    private GeoResponses() {}

    /**
     * The result of looking an address up.
     *
     * @param resolved   whether anything was found at all
     * @param available  whether this deployment can geocode. False means nobody
     *                   looked — which is a different thing from having looked
     *                   and found nothing, and leads to different advice: one is
     *                   ours to fix, the other is a prompt to drop a pin
     * @param confidence how much the returned point is worth
     * @param message    what to tell the person, in words they can act on
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AddressLookup(
            boolean resolved,
            boolean available,
            String formattedAddress,
            Double latitude,
            Double longitude,
            String country,
            String countryCode,
            String city,
            GeocodeConfidence confidence,
            boolean needsPinConfirmation,
            String message) {

        public static AddressLookup unavailable() {
            return new AddressLookup(false, false, null, null, null, null, null, null,
                    GeocodeConfidence.NONE, false,
                    "Address lookup is not configured on this deployment. Enter the address and "
                            + "place the pin on the map yourself.");
        }

        public static AddressLookup notFound() {
            return new AddressLookup(false, true, null, null, null, null, null, null,
                    GeocodeConfidence.NONE, true,
                    "That address could not be found. Check the spelling, or place the pin on the "
                            + "map — many addresses in the region are not on any street map.");
        }
    }

    /**
     * Where the shopper appears to be, and what that means for what they see.
     *
     * @param currency what to price in
     * @param timezone their local time, so a delivery window means what it says
     * @param resolved false when the country could not be worked out at all; the
     *                 defaults are still returned so a client always has
     *                 something to render
     */
    public record ResolvedContext(
            boolean resolved,
            String countryCode,
            String currency,
            String language,
            String timezone,
            String source) {}
}
