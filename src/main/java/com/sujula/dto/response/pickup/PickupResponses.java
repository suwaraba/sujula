package com.sujula.dto.response.pickup;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ShipmentStatus;

/**
 * What a pickup point tells the world, and what it tells its operator.
 *
 * <p>The two halves are deliberately different shapes. {@link PublicPoint} is
 * served to anybody — a shopper choosing where to collect, before they have
 * signed in — so it carries an address, opening hours and a capacity
 * <em>band</em>, and no operator name, no contact email, no parcel counts and
 * no earnings. A competitor reading the public endpoint learns where the counter
 * is and when it opens, which is what a counter wants known, and nothing about
 * how much business it does.
 *
 * <p>The operator's shapes carry recipients' names and phone numbers, and are
 * scoped to the point they run.
 */
public final class PickupResponses {

    private PickupResponses() {}

    // ── Public ───────────────────────────────────────────────────────────────

    public record PublicPoints(List<PublicPoint> points, Double searchLat, Double searchLng,
                               Double radiusKm, String note) {}

    /**
     * A counter, as a shopper sees it.
     *
     * <p>{@code distanceKm} is present only when the search gave a position: a
     * distance from nowhere is not a number, and rendering one would have a
     * client sort by noise.
     */
    public record PublicPoint(Long id, String name, String addressStreet, String city,
                              String country, Double lat, Double lng, Double distanceKm,
                              String openingHours, Capacity capacity,
                              boolean openNow, LocalDateTime closedUntil, String closureReason,
                              String contactPhone, String imageUrl) {}

    /**
     * How full a counter is, as a band rather than a count.
     *
     * <p>A shopper needs to know whether their parcel can go here; they do not
     * need to know that this shop is holding a hundred and ninety parcels, which
     * is a thing about somebody's business. The band answers the question and
     * says nothing else.
     */
    public enum Capacity {
        /** Plenty of room. */
        AVAILABLE,
        /** Filling up — worth choosing an alternative if there is one. */
        LIMITED,
        /** Cannot take another parcel today. */
        FULL,
        /** Open, but not accepting: closed for the week, or suspended. */
        CLOSED
    }

    // ── The operator's own points ────────────────────────────────────────────

    public record OperatorPoints(List<OperatorPoint> points, String message) {}

    public record OperatorPoint(Long id, String name, PartnerStatus status, boolean active,
                                String addressStreet, String city, String country,
                                Double lat, Double lng,
                                String openingHours, Integer capacity, Integer storedParcels,
                                Integer overdueParcels, Integer incomingParcels,
                                Capacity capacityBand,
                                LocalDateTime closedUntil, String closureReason,
                                Integer storageDays,
                                BigDecimal commissionPerParcel, String commissionCurrency,
                                String contactPhone, String contactEmail, String managerName,
                                LocalDateTime createdAt, String message) {}

    /** What an application produced. */
    public record ApplicationSubmitted(Long id, String name, PartnerStatus status,
                                       LocalDateTime submittedAt, String whatHappensNext) {}

    // ── Parcels at the counter ───────────────────────────────────────────────

    /**
     * The three piles an operator actually has.
     *
     * <p>Incoming is what to expect, stored is what is behind the counter, and
     * overdue is what has to go back. Kept as three lists rather than one with a
     * flag, because they are three different jobs and an operator works them at
     * different times of day.
     */
    public record Parcels(List<IncomingParcel> incoming, List<StoredParcel> stored,
                          List<StoredParcel> overdue,
                          int storedCount, int capacity, Capacity capacityBand,
                          String note) {}

    /**
     * A parcel on its way here.
     *
     * <p>No recipient name and no phone number. It is not at this counter yet,
     * and an operator expecting six parcels this afternoon needs to know that
     * and not who they are for.
     */
    public record IncomingParcel(Long shipmentId, String reference, ShipmentStatus status,
                                 int parcelCount, String fromStore, String expectedFrom) {}

    /**
     * A parcel on the shelf.
     *
     * <p>Carries the recipient's name, because releasing it means checking that
     * the person at the counter is the person it is for — and a masked phone
     * number, because an operator confirming they have the right parcel does not
     * need to be able to ring somebody they have never met.
     */
    public record StoredParcel(Long shipmentId, String reference, String shelfCode,
                               String recipientName, String recipientPhoneHint,
                               int parcelCount, String fromStore,
                               LocalDateTime storedAt, LocalDateTime storageDeadline,
                               boolean overdue, long daysRemaining,
                               BigDecimal commission, String commissionCurrency,
                               String note) {}

    // ── What happened at the counter ─────────────────────────────────────────

    public record ParcelAccepted(Long shipmentId, String reference, String shelfCode,
                                 LocalDateTime storedAt, LocalDateTime storageDeadline,
                                 int storedCount, int capacity,
                                 BigDecimal commission, String commissionCurrency,
                                 boolean duplicate, String message) {}

    public record ParcelRejected(Long shipmentId, String reference, ShipmentStatus shipmentStatus,
                                 String reason, boolean duplicate, String message) {}

    /**
     * A parcel handed to the person it was for.
     *
     * <p>The end of the chain, exactly as a doorstep delivery is. It releases
     * the seller's money, so it needs the recipient's code and a name that
     * matches — and the response says plainly which of those was checked.
     */
    public record ParcelReleased(Long shipmentId, String reference,
                                 ShipmentStatus shipmentStatus, LocalDateTime releasedAt,
                                 String releasedTo, boolean nameMatched,
                                 int storedCount, boolean duplicate, String message) {}

    public record ParcelReturning(Long shipmentId, String reference,
                                  ShipmentStatus shipmentStatus, LocalDateTime storageDeadline,
                                  long daysOverdue, boolean duplicate, String message) {}

    /**
     * The result of having the collection code sent again.
     *
     * <p>The code is never in it. An operator who could read it could hand a
     * parcel to whoever is standing there, which is the one thing the code
     * exists to prevent.
     */
    public record CodeResent(Long shipmentId, boolean sent, String sentTo,
                             LocalDateTime expiresAt, int requestsRemaining, String message) {}

    // ── Money ────────────────────────────────────────────────────────────────

    /**
     * What a counter has earned.
     *
     * <p>Per currency, like every other money figure here: a point that has
     * handled parcels priced in dalasi and in CFA has two earnings, and adding
     * them would need a rate nobody agreed to (C2).
     */
    public record Earnings(java.time.LocalDate from, java.time.LocalDate to,
                           List<CurrencyEarnings> byCurrency, int parcelsHandled, String note) {}

    public record CurrencyEarnings(String currency, BigDecimal total, int parcels,
                                   List<EarningLine> lines) {}

    public record EarningLine(Long shipmentId, String reference, BigDecimal commission,
                              String currency, LocalDateTime storedAt,
                              LocalDateTime settledAt, String outcome) {}
}
