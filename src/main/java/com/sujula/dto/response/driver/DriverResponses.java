package com.sujula.dto.response.driver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.constant.VehicleType;

/**
 * What a driver's app is told.
 *
 * <p>The rule that shapes most of this: <strong>a recipient's address and phone
 * number appear only while the driver is actually carrying the parcel.</strong>
 * Before they accept, they get a town and a distance — enough to decide whether
 * to take the job. After they hand it over, it goes away again. A driver's
 * history is a list of jobs, not a list of where people live.
 *
 * <p>That is expressed in the types rather than in a rule somebody has to
 * remember: {@link Destination} is a separate object that is simply absent when
 * custody is not active, so there is no field to forget to blank.
 */
public final class DriverResponses {

    private DriverResponses() {}

    // ── The driver themselves ────────────────────────────────────────────────

    public record Profile(Long id, DriverStatus status, boolean online,
                          String phone, VehicleType vehicleType, String vehiclePlate,
                          String vehicleModel, String vehicleColor, String avatarUrl,
                          String zone, String countryCode, Integer maxWeightKg,
                          String licenseNumber, LocalDate licenseExpiresOn,
                          Kyc kyc, Score score,
                          Integer totalDeliveries, BigDecimal averageRating,
                          LocalDateTime onlineSince, LocalDateTime lastLocationAt,
                          LocalDateTime createdAt, String message) {}

    /**
     * Where the application has got to.
     *
     * <p>Document numbers are not echoed back. A driver knows what they sent,
     * and an endpoint that reads out an identity document number is one a stolen
     * session can read it from.
     */
    public record Kyc(boolean documentsSubmitted, LocalDateTime submittedAt,
                      LocalDateTime reviewedAt, String rejectionReason, String whatIsNeeded) {}

    /**
     * How reliably a driver answers.
     *
     * <p>Shown to the driver because it affects what they are offered, and a
     * score somebody is judged by without being able to see it is not a score,
     * it is a secret.
     */
    public record Score(BigDecimal acceptancePercent, int offersReceived,
                        int accepted, int declined, String note) {}

    // ── Assignments ──────────────────────────────────────────────────────────

    public record Assignments(List<Assignment> offered, List<Assignment> accepted, String note) {}

    /**
     * One leg, as it looks before a driver has committed to it.
     *
     * <p>Carries a town, a distance and what it pays — everything needed to
     * decide — and no address. A driver deciding whether a job is worth taking
     * does not need to know where anybody lives, and offering it to five drivers
     * would otherwise hand a home address to four who never went.
     */
    public record Assignment(Long legId, Long shipmentId, String shipmentReference,
                             LegType legType, LegAssignmentStatus status, int sequence,
                             String pickupFrom, String dropTo,
                             BigDecimal distanceKm, BigDecimal earning, String earningCurrency,
                             int parcelCount,
                             LocalDateTime offeredAt, LocalDateTime offerExpiresAt,
                             Long secondsToDecide, String note) {}

    public record AssignmentAnswered(Long legId, LegAssignmentStatus status,
                                     LocalDateTime answeredAt, Score score, String message) {}

    // ── A shipment in the driver's hands ─────────────────────────────────────

    /**
     * What a driver sees about one parcel.
     *
     * <p>{@link #destination} is present only while custody is active. Its
     * absence is the privacy rule, and it is absent rather than blanked so that
     * a client rendering it cannot show empty fields where an address used to
     * be.
     */
    public record ShipmentDetail(Long id, String reference, ShipmentStatus status,
                                 int parcelCount, String contentsSummary,
                                 Origin origin, Destination destination,
                                 List<LegSummary> legs, List<ChainEntry> chain,
                                 int failedAttempts, LocalDateTime nextAttemptAfter,
                                 boolean custodyActive, String privacyNote,
                                 String whatToDoNext) {}

    /** The shop. Visible from the moment a leg is accepted — the driver has to get there. */
    public record Origin(String label, String city, Double lat, Double lng,
                         String storeName, String storePhone) {}

    /**
     * Where the parcel goes, and who to.
     *
     * <p>Only ever built while the driver is holding the goods. The recipient is
     * frequently not the buyer — she is the sister the parcel was sent to, and
     * she may have no account at all (C5) — so what is here is what a person
     * needs to put a box in somebody's hands and nothing else.
     */
    public record Destination(String recipientName, String recipientPhone,
                              String street, String city, String country,
                              Double lat, Double lng, String instructions) {}

    public record LegSummary(Long legId, int sequence, LegType legType,
                             LegAssignmentStatus status, boolean mine,
                             String from, String to,
                             BigDecimal earning, String earningCurrency,
                             LocalDateTime completedAt) {}

    /**
     * One link of the chain, as the driver sees it.
     *
     * <p>No codes. A driver looking at the history of a parcel they are carrying
     * must not be able to read the code that opens its next handover.
     */
    public record ChainEntry(CustodyEventType type, LocalDateTime occurredAt,
                             boolean attestedByPosition, BigDecimal metresFromExpected,
                             boolean capturedOffline, String note) {}

    // ── Custody results ──────────────────────────────────────────────────────

    public record CustodyRecorded(Long eventId, CustodyEventType type, Long shipmentId,
                                  ShipmentStatus shipmentStatus,
                                  LocalDateTime occurredAt,
                                  boolean attestedByPosition, BigDecimal metresFromExpected,
                                  boolean duplicate, String message) {}

    /** What a failed attempt produced: another try, or the parcel going back. */
    public record AttemptFailed(Long eventId, Long shipmentId, ShipmentStatus shipmentStatus,
                                int failedAttempts, int attemptsAllowed,
                                LocalDateTime nextAttemptAfter, boolean returning,
                                String message) {}

    /**
     * The result of asking for the recipient's code to be sent.
     *
     * <p>The code itself is never here. A driver who could read it would be a
     * driver who can mark a parcel delivered without meeting anybody, which is
     * the one thing the code exists to prevent.
     */
    public record RecipientCodeRequested(Long shipmentId, boolean sent, String sentTo,
                                         LocalDateTime expiresAt, int requestsRemaining,
                                         String message) {}

    /** One batch of offline events, entry by entry. */
    public record SyncResult(int received, int recorded, int duplicates, int rejected,
                             List<SyncOutcome> outcomes, String message) {}

    public record SyncOutcome(String clientEventId, Long shipmentId, boolean accepted,
                              boolean duplicate, Long eventId, String problem) {}

    // ── Money and history ────────────────────────────────────────────────────

    /**
     * What a driver has earned.
     *
     * <p>Per currency, for the same reason every other money figure on this
     * platform is: a driver who has worked legs paid in dalasi and in CFA has
     * two earnings, and adding them would need a rate nobody agreed to (C2).
     */
    public record Earnings(LocalDate from, LocalDate to,
                           List<CurrencyEarnings> byCurrency,
                           int deliveries, String note) {}

    public record CurrencyEarnings(String currency, BigDecimal total, int legs,
                                   BigDecimal averagePerLeg, List<EarningLine> lines) {}

    public record EarningLine(Long legId, Long shipmentId, String shipmentReference,
                              LegType legType, BigDecimal amount, String currency,
                              LocalDateTime completedAt, String dropTo) {}

    /**
     * Jobs a driver has finished.
     *
     * <p>Carries a town rather than an address, and no recipient name. A round
     * from three months ago is not a reason to still hold somebody's front door.
     */
    public record History(List<HistoryRow> rows, int page, int size,
                          long totalRows, int totalPages) {}

    public record HistoryRow(Long shipmentId, String reference, ShipmentStatus status,
                             LegType legType, String dropCity, String dropCountry,
                             BigDecimal earning, String earningCurrency,
                             LocalDateTime collectedAt, LocalDateTime completedAt) {}

    /** Where the driver's last ping put them. */
    public record LocationAccepted(LocalDateTime recordedAt, boolean throttled,
                                   Long nextPingInSeconds, String message) {}

    public record AvailabilitySet(boolean online, LocalDateTime since, String message) {}
}
