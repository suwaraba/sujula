package com.sujula.dto.request.delivery;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** What the delivery endpoints accept. */
public final class ServiceabilityRequests {

    private ServiceabilityRequests() {}

    /**
     * A point, given whichever way the caller has it.
     *
     * <p>Coordinates if it has them; a vendor or a pickup point if it only has an
     * id; written text as the last resort, which is geocoded. Most clients have
     * exactly one of the three, which is why all three are accepted rather than
     * making the client resolve it first and get it wrong.
     */
    public record Point(
            @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            Long vendorId,
            Long pickupPointId,
            @Size(max = 300) String address,
            @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode) {

        public boolean hasCoordinates() {
            return latitude != null && longitude != null;
        }
    }

    /**
     * Can this be delivered, how, and roughly when.
     *
     * <p>Asked before a basket exists — on a product page, where the only
     * question is whether this seller reaches that buyer at all.
     */
    public record Serviceability(
            Point origin,
            Point destination,

            /** The context to take the destination from, when one was made. */
            @Size(max = 64) String deliveryContextId,

            /** How many hubs to return. Capped; a list nobody scrolls is wasted work. */
            Integer nearestPickupPoints) {}

    /**
     * What shipping costs, for each way of receiving it.
     *
     * <p>Weight and value rather than a list of products, because this answers
     * the shipping row of a cart the client has already totalled. The
     * per-product quote — which knows each vendor's origin and each product's
     * scope — is what checkout uses.
     */
    public record Quote(
            Point origin,
            Point destination,

            @Size(max = 64) String deliveryContextId,

            /** Billable weight of the basket. Zero or absent uses the configured default. */
            @PositiveOrZero @Digits(integer = 5, fraction = 3) BigDecimal weightKg,

            /** Basket value, for the free-delivery threshold. */
            @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal value,

            @Size(min = 3, max = 3) String currency) {}
}
