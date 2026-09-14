package com.sujula.dto.response.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.PartnerStatus;

/**
 * What the back office is shown about the delivery network.
 *
 * <p>Each of these carries the reasoning as well as the numbers. A dispatcher
 * looking at a driver list needs to know not only that somebody accepts 40% of
 * offers but that they have three jobs on and have not reported a position in
 * four hours — the number alone says the driver is unreliable, the three
 * together say their phone is dead.
 */
public final class AdminLogisticsResponses {

    private AdminLogisticsResponses() {}

    // ── Drivers ──────────────────────────────────────────────────────────────

    public record DriverRow(
            Long driverId,
            Long userId,
            String name,
            String email,
            String phone,
            DriverStatus status,
            String countryCode,

            /** What they wrote on their application. See {@link #zones}. */
            String declaredZone,

            /** What the platform assigned them, which is the authority. */
            List<ZoneBadge> zones,

            boolean available,
            LocalDateTime onlineSince,
            LocalDateTime lastLocationAt,

            /**
             * How stale their position is, in minutes. Null when they have never
             * reported one.
             */
            Long minutesSinceLastPing,

            BigDecimal acceptanceScore,
            Integer offersReceived,
            Integer offersAccepted,
            Integer offersDeclined,

            /** Offers, accepted jobs and jobs in progress — what they hold now. */
            int openJobs,

            Integer totalDeliveries,
            BigDecimal averageRating,
            Integer totalRatings,

            /** KYC, licence expiry and anything else worth a dispatcher's eye. */
            List<String> flags,

            String adminNote) {}

    public record ZoneBadge(Long zoneId, String code, String name, boolean serviceable) {}

    public record DriverDecision(
            Long driverId,
            String name,
            DriverStatus status,
            List<ZoneBadge> zones,
            String message) {}

    // ── Pickup points ────────────────────────────────────────────────────────

    public record PickupPointRow(
            Long id,
            String name,
            PartnerStatus status,
            boolean active,
            String city,
            String countryCode,
            Double latitude,
            Double longitude,
            String operatorName,
            Long operatorUserId,

            Integer capacity,
            Integer storedParcels,

            /** Capacity minus what is on the shelf; negative never happens, zero does. */
            int spaceLeft,

            /** Parcels past their storage deadline and still sitting there. */
            long overdueParcels,

            /** The oldest one's deadline, so a counter in trouble sorts to the top. */
            LocalDateTime oldestOverdueSince,

            Integer storageDays,
            BigDecimal commissionPerParcel,
            String commissionCurrency,

            LocalDateTime closedUntil,
            String closureReason,

            List<String> flags,
            String adminNote) {}

    public record PickupPointSaved(Long id, String name, PartnerStatus status, boolean active,
                                   String message) {}

    // ── Zones ────────────────────────────────────────────────────────────────

    public record ZoneRow(
            Long id,
            String code,
            String name,
            String description,
            String countryCode,
            boolean serviceable,
            String unserviceableReason,
            int priority,
            boolean active,

            Double minLatitude,
            Double maxLatitude,
            Double minLongitude,
            Double maxLongitude,
            int vertexCount,

            /** How many rate cards price against this zone. */
            long rateCards,

            /** How many drivers cover it. */
            long drivers,

            LocalDateTime updatedAt,
            Long lastEditedByUserId) {}

    /** The shape itself, returned only on a single-zone read. */
    public record ZoneDetail(ZoneRow zone, String geometry) {}

    public record ZoneSaved(
            Long id,
            String code,
            int vertexCount,
            int polygonCount,
            Double minLatitude, Double maxLatitude,
            Double minLongitude, Double maxLongitude,

            /**
             * The serviceability cache's version after this write.
             *
             * <p>Returned so an administrator can see the edit took effect rather
             * than trust that it did. A zone changed in the back office has to be
             * live on the next request — the reason somebody edits one is usually
             * that parcels are being quoted wrong right now.
             */
            long cacheVersion,
            int zonesLive,
            String message) {}

    // ── Rate cards ───────────────────────────────────────────────────────────

    public record RateCardRow(
            Long id,
            String name,
            Long zoneId,
            String zoneCode,
            String countryCode,
            DeliveryMode mode,
            String currency,

            BigDecimal baseFee,
            BigDecimal includedKm,
            BigDecimal perKm,
            BigDecimal includedKg,
            BigDecimal perKg,
            BigDecimal minFee,
            BigDecimal maxFee,
            BigDecimal freeAbove,

            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            boolean active,

            /** Whether this card is the one pricing legs today. */
            boolean inForceToday,

            String note,
            Long createdByUserId,
            LocalDateTime createdAt) {}

    public record RateCardSaved(
            RateCardRow card,

            /**
             * The card this one closed, if any, and the day it now ends.
             *
             * <p>Named out loud because superseding is the normal way a price
             * changes, and an administrator who did not realise they were ending
             * an existing card has just changed a price they did not mean to.
             */
            Long supersededCardId,
            LocalDate supersededEndsOn,
            String message) {}

    /**
     * What a leg would cost right now under the live cards — an administrator's
     * own check that what they wrote prices what they meant.
     */
    public record RateCardPreview(
            Long cardId,
            String cardName,
            String source,
            String currency,
            BigDecimal fiveKmOneKg,
            BigDecimal twentyKmThreeKg,
            BigDecimal hundredKmTenKg) {}
}
