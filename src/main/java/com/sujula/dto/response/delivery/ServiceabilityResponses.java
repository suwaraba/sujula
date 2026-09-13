package com.sujula.dto.response.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.DeliveryMode;

import java.math.BigDecimal;
import java.util.List;

/** What the delivery endpoints return. */
public final class ServiceabilityResponses {

    private ServiceabilityResponses() {}

    /**
     * Whether goods can get from one place to the other, and how.
     *
     * @param deliverable     false only when nothing at all can be arranged
     * @param distanceKm      straight-line; null when either end is unknown
     * @param distanceEstimated true when a scope fallback stood in for a real
     *                        measurement, so the ETA and any price built on it
     *                        are indicative rather than measured
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Serviceability(
            boolean deliverable,
            BigDecimal distanceKm,
            boolean distanceEstimated,
            boolean crossBorder,
            String originCountry,
            String destinationCountry,
            List<Option> options,
            List<NearbyPickupPoint> nearestPickupPoints,
            String message) {}

    /**
     * One way of receiving the goods.
     *
     * @param available whether it can be chosen, with {@code reason} saying why
     *                  not when it cannot — a disabled option with no explanation
     *                  is the most irritating thing a checkout can show
     * @param etaMinDays lower bound in whole days, counted from dispatch
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Option(
            DeliveryMode mode,
            boolean available,
            Integer etaMinDays,
            Integer etaMaxDays,
            String reason) {}

    /** A hub near the destination, with how far it is from it. */
    public record NearbyPickupPoint(
            Long id,
            String name,
            String city,
            String addressStreet,
            String openingHours,
            Double latitude,
            Double longitude,
            BigDecimal distanceKm) {}

    /**
     * What shipping costs, per way of receiving it.
     *
     * @param complete false when a rate could not be converted into
     *                 {@code currency}; the figures are then incomplete and must
     *                 not be charged
     */
    public record Quote(
            String currency,
            BigDecimal distanceKm,
            boolean distanceEstimated,
            BigDecimal weightKg,
            boolean complete,
            List<ModePrice> prices) {}

    /**
     * The price of one mode.
     *
     * @param waivedReason why it costs nothing, when it costs nothing
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModePrice(
            DeliveryMode mode,
            boolean available,
            BigDecimal cost,
            Integer etaMinDays,
            Integer etaMaxDays,
            String waivedReason,
            String reason) {}
}
