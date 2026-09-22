package com.sujula.dto.request.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.DeliveryMode;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What an administrator sends when they change the shape of the network.
 *
 * <p>Drivers, counters, zones and rate cards — the four things that decide
 * whether a parcel can get somewhere and at what price. Every one of them is a
 * delivery-side fact, and nothing in this file takes a payer, a currency
 * preference, or a buyer's country: a zone drawn round the payer's position
 * would be a zone with no parcels in it.
 */
public final class AdminLogisticsRequests {

    private AdminLogisticsRequests() {}

    // ── Drivers ──────────────────────────────────────────────────────────────

    public record ApproveDriver(
            @Size(max = 500) String note,

            /**
             * Zones this driver may be offered work in, by code. Empty leaves
             * their coverage as it stands, which for a new driver is nothing —
             * approval says they may carry, not where.
             */
            List<@NotBlank @Size(max = 40) String> zoneCodes) {}

    public record SuspendDriver(
            @NotBlank(message = "Say why — the driver is shown this")
            @Size(max = 500) String reason,

            /** Null suspends until somebody lifts it. */
            LocalDateTime until) {}

    public record SetDriverZones(
            @NotNull(message = "Send the zones, even if the list is empty")
            List<@NotBlank @Size(max = 40) String> zoneCodes,

            @Size(max = 500) String note) {}

    // ── Pickup points ────────────────────────────────────────────────────────

    public record CreatePickupPoint(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 255) String addressStreet,
            @Size(max = 255) String addressApartment,
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,

            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "Two-letter country code")
            String countryCode,

            /**
             * Required, unlike the postal code.
             *
             * <p>A counter is a place a driver has to find. Most addresses in
             * this market have no postal code and many have no street number, so
             * the pin is the address — a point without one cannot be routed to
             * and should not be created.
             */
            @NotNull(message = "Drop a pin — this is what a driver navigates to")
            @DecimalMin(value = "-90.0") @jakarta.validation.constraints.DecimalMax(value = "90.0")
            Double latitude,

            @NotNull(message = "Drop a pin — this is what a driver navigates to")
            @DecimalMin(value = "-180.0") @jakarta.validation.constraints.DecimalMax(value = "180.0")
            Double longitude,

            @Size(max = 25) String contactPhone,
            @jakarta.validation.constraints.Email @Size(max = 150) String contactEmail,
            @Size(max = 120) String managerName,
            @Size(max = 255) String openingHours,

            @Min(1) @Max(5000) Integer capacity,
            @Min(1) @Max(90) Integer storageDays,

            @DecimalMin("0.00") BigDecimal commissionPerParcel,
            @Pattern(regexp = "^[A-Za-z]{3}$") String commissionCurrency,

            /**
             * Who runs it. Null is a counter the platform runs itself, which is
             * how the first few in a new city usually start.
             */
            Long operatorUserId,

            @Size(max = 1000) String adminNote) {}

    public record PatchPickupPoint(
            @Size(max = 255) String name,
            @Size(max = 255) String addressStreet,
            @Size(max = 255) String addressApartment,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @DecimalMin("-90.0") @jakarta.validation.constraints.DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @jakarta.validation.constraints.DecimalMax("180.0") Double longitude,
            @Size(max = 25) String contactPhone,
            @jakarta.validation.constraints.Email @Size(max = 150) String contactEmail,
            @Size(max = 120) String managerName,
            @Size(max = 255) String openingHours,
            @Min(1) @Max(5000) Integer capacity,
            @Min(1) @Max(90) Integer storageDays,
            @DecimalMin("0.00") BigDecimal commissionPerParcel,
            @Pattern(regexp = "^[A-Za-z]{3}$") String commissionCurrency,
            Long operatorUserId,
            Boolean active,
            @Size(max = 1000) String adminNote) {}

    public record SuspendPickupPoint(
            @NotBlank(message = "Say why — the operator is shown this")
            @Size(max = 500) String reason) {}

    // ── Zones ────────────────────────────────────────────────────────────────

    public record CreateZone(
            @NotBlank @Size(max = 40)
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$",
                     message = "Letters, digits, dash and underscore")
            String code,

            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,

            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "Two-letter country code")
            String countryCode,

            /** GeoJSON: a Polygon, a MultiPolygon, or a Feature wrapping one. */
            @NotBlank(message = "Upload the polygon") String geometry,

            Boolean serviceable,
            @Size(max = 400) String unserviceableReason,
            @Min(0) @Max(1000) Integer priority) {}

    public record PatchZone(
            @Size(max = 120) String name,
            @Size(max = 500) String description,
            String geometry,
            Boolean serviceable,
            @Size(max = 400) String unserviceableReason,
            @Min(0) @Max(1000) Integer priority,
            Boolean active) {}

    // ── Rate cards ───────────────────────────────────────────────────────────

    public record CreateRateCard(
            @NotBlank @Size(max = 120) String name,

            /** Null prices a whole country; both null prices everywhere. */
            Long zoneId,
            @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode,

            /** Null applies to every mode. */
            DeliveryMode mode,

            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,

            @NotNull @DecimalMin("0.00") BigDecimal baseFee,
            @DecimalMin("0.00") BigDecimal includedKm,
            @DecimalMin("0.00") BigDecimal perKm,
            @DecimalMin("0.00") BigDecimal includedKg,
            @DecimalMin("0.00") BigDecimal perKg,
            @DecimalMin("0.00") BigDecimal minFee,
            @DecimalMin("0.00") BigDecimal maxFee,
            @DecimalMin("0.00") BigDecimal freeAbove,

            /** Null starts today. Never in the past — see the service. */
            LocalDate effectiveFrom,

            @Size(max = 500) String note) {}

    /**
     * What may be changed on a card that already exists.
     *
     * <p>Deliberately not the numbers. A card's figures are what an order was
     * priced under, and editing them rewrites history — a new card from a new
     * date is how a price changes. Only the things that describe rather than
     * price are editable here.
     */
    public record PatchRateCard(
            @Size(max = 120) String name,
            @Size(max = 500) String note,

            /**
             * Closes the card from a day forward. Never in the past, for the
             * same reason the numbers are not editable.
             */
            LocalDate effectiveUntil,

            Boolean active) {}
}
