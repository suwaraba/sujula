package com.sujula.service.driver.impl;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.driver.DriverRequests;
import com.sujula.dto.response.driver.DriverResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.delivery.Driver;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.order.Order;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.repository.delivery.DriverRepository;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.service.EmailService;
import com.sujula.service.driver.DriverCustodyService;
import com.sujula.service.platform.FeatureFlags;
import com.sujula.service.shipment.CustodyChain;
import com.sujula.service.shipment.Geofence;

import lombok.extern.slf4j.Slf4j;

/**
 * The driver's half of the custody chain.
 *
 * <p>Everything that moves a parcel here builds a {@link CustodyEvent} with its
 * proof and hands it to {@link CustodyChain}. Nothing sets a status; the status
 * is derived from the chain afterwards. That is why there is no method on this
 * class that takes one.
 *
 * <h2>The privacy window</h2>
 *
 * <p>A recipient's address and phone are attached to a response only while the
 * driver is carrying the parcel. The rule lives in one place — {@link
 * #destinationFor} — and returns null rather than a blanked object, so a client
 * cannot render empty fields where an address used to be and a future caller
 * cannot forget to check.
 */
@Slf4j
@Service
public class DriverCustodyServiceImpl implements DriverCustodyService {

    /** How long a recipient's code stands before it has to be asked for again. */
    private static final Duration RECIPIENT_CODE_LIFETIME = Duration.ofHours(48);

    /** How often a driver may have one sent, so the buyer is not spammed. */
    private static final Duration CODE_REQUEST_WINDOW = Duration.ofHours(1);
    private static final int MAX_CODE_REQUESTS_PER_WINDOW = 3;

    /** Attempts before a parcel goes back rather than being tried again. */
    private static final int MAX_DELIVERY_ATTEMPTS = 3;

    /** How long before the next try, so a driver does not loop on a closed door. */
    private static final Duration RETRY_AFTER = Duration.ofHours(4);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShipmentRepository shipments;
    private final ShipmentLegRepository legs;
    private final CustodyEventRepository events;
    private final HandoverCodeRepository codes;
    private final DriverRepository drivers;
    private final CustodyChain chain;
    private final EmailService email;

    private final com.sujula.service.platform.FeatureFlags flags;

    public DriverCustodyServiceImpl(ShipmentRepository shipments, ShipmentLegRepository legs,
                                    CustodyEventRepository events, HandoverCodeRepository codes,
                                    DriverRepository drivers, CustodyChain chain,
                                    EmailService email,
                                    com.sujula.service.platform.FeatureFlags flags) {
        this.shipments = shipments;
        this.legs = legs;
        this.events = events;
        this.codes = codes;
        this.drivers = drivers;
        this.chain = chain;
        this.email = email;
        this.flags = flags;
    }

    // ── Reading a parcel ─────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.ShipmentDetail shipment(Long userId, Long shipmentId) {
        Driver driver = requireDriver(userId);
        Shipment shipment = requireOwnShipment(shipmentId, driver);
        Optional<ShipmentLeg> active = legs.findActiveLeg(shipmentId, driver.getId());

        List<DriverResponses.LegSummary> legRows = new ArrayList<>();
        for (ShipmentLeg leg : legs.findByShipmentIdOrderBySequenceAsc(shipmentId)) {
            boolean mine = leg.getDriver() != null && driver.getId().equals(leg.getDriver().getId());
            legRows.add(new DriverResponses.LegSummary(
                    leg.getId(), leg.getSequence(), leg.getLegType(), leg.getAssignmentStatus(),
                    mine,
                    leg.getOriginLabel(), leg.getDestinationLabel(),
                    // Another driver's fee is not this driver's business.
                    mine ? leg.getEarning() : null, mine ? leg.getEarningCurrency() : null,
                    leg.getCompletedAt()));
        }

        List<DriverResponses.ChainEntry> chainRows = new ArrayList<>();
        for (CustodyEvent event : events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipmentId)) {
            chainRows.add(new DriverResponses.ChainEntry(
                    event.getType(), event.getOccurredAt(),
                    event.isWithinGeofence(), event.getMetresFromExpected(),
                    event.isCapturedOffline(), event.getNote()));
            // Deliberately no code: a driver reading the history of a parcel
            // they are carrying must not be able to read the code that opens
            // its next handover.
        }

        boolean carrying = isCarrying(shipment, active);

        return new DriverResponses.ShipmentDetail(
                shipment.getId(), shipment.getReference(), shipment.getStatus(),
                shipment.getParcelCount() == null ? 1 : shipment.getParcelCount(),
                // What is in the box, in words that do not tell a thief which
                // box to take.
                (shipment.getParcelCount() == null ? 1 : shipment.getParcelCount()) + " item(s)",
                originFor(shipment, active),
                destinationFor(shipment, carrying),
                legRows, chainRows,
                shipment.getFailedAttempts(), shipment.getNextAttemptAfter(),
                carrying,
                carrying
                        ? "You are carrying this parcel, so you can see where it goes."
                        : "The delivery address and the recipient's number appear once you are "
                                + "carrying this parcel, and go again once you have handed it over.",
                whatToDoNext(shipment, active));
    }

    /**
     * Where the parcel goes, or null when the driver has no business knowing.
     *
     * <p>The one place this decision is made. Returning null rather than an
     * object with blanked fields matters: a client cannot render an empty
     * address where a real one used to be, and a future caller cannot forget to
     * check a flag.
     */
    private static DriverResponses.Destination destinationFor(Shipment shipment, boolean carrying) {
        if (!carrying) {
            return null;
        }
        return new DriverResponses.Destination(
                shipment.getRecipientName(), shipment.getRecipientPhone(),
                shipment.getDestinationStreet(), shipment.getDestinationCity(),
                shipment.getDestinationCountry(),
                shipment.getDestinationLatitude(), shipment.getDestinationLongitude(),
                standingInstructions(shipment));
    }

    /**
     * What the recipient has asked for, in the words she used.
     *
     * <p>Read from the shipment's derived directives, which {@code
     * RecipientDirectives} recomputes from the instruction record. The driver
     * has to see these or they mean nothing: a safe-drop authorisation nobody
     * shows the driver is a permission that changes only what the database
     * thinks.
     */
    private static String standingInstructions(Shipment shipment) {
        List<String> lines = new ArrayList<>();
        if (shipment.getRequestedPickupPoint() != null) {
            lines.add("She has asked for it to go to " + shipment.getRequestedPickupPoint().getName()
                    + ", " + shipment.getRequestedPickupPoint().getCity()
                    + " instead of the door.");
        }
        if (shipment.getRequestedWindowFrom() != null) {
            lines.add("She has asked for delivery between " + shipment.getRequestedWindowFrom()
                    + " and " + shipment.getRequestedWindowUntil() + ".");
        }
        if (shipment.isSafeDropAuthorised()) {
            // Spelled out rather than reduced to a flag, because this is the one
            // instruction that replaces her standing at the door with a record.
            String where = shipment.getSafeDropLocation();
            String who = shipment.getSafeDropPerson();
            StringBuilder authorised = new StringBuilder("She has authorised you to leave it");
            if (who != null && !who.isBlank()) {
                authorised.append(" with ").append(who);
            }
            if (where != null && !where.isBlank()) {
                authorised.append(who != null && !who.isBlank() ? " at " : " at ").append(where);
            }
            authorised.append(" without a code. Photograph it where you leave it — that photograph "
                    + "is the only proof there will be.");
            lines.add(authorised.toString());
        }
        return lines.isEmpty() ? null : String.join(" ", lines);
    }

    /**
     * The shop, visible from the moment a leg is accepted.
     *
     * <p>Not gated like the destination is, and for a reason: a driver who has
     * accepted a collection has to be able to get there, and a shop's address is
     * a business address that the seller publishes anyway.
     */
    private static DriverResponses.Origin originFor(Shipment shipment,
                                                    Optional<ShipmentLeg> active) {
        if (active.isEmpty()) {
            return null;
        }
        return new DriverResponses.Origin(
                shipment.getOriginAddress(), null,
                shipment.getOriginLatitude(), shipment.getOriginLongitude(),
                shipment.getVendorOrder() != null && shipment.getVendorOrder().getVendor() != null
                        ? shipment.getVendorOrder().getVendor().getStoreName() : null,
                shipment.getVendorOrder() != null && shipment.getVendorOrder().getVendor() != null
                        ? shipment.getVendorOrder().getVendor().getStorePhone() : null);
    }

    private static boolean isCarrying(Shipment shipment, Optional<ShipmentLeg> active) {
        // Both halves matter. A leg in progress with a delivered shipment is a
        // stale row; a live shipment with no leg of this driver's is somebody
        // else's parcel.
        return active.isPresent() && shipment.getStatus() != null
                && shipment.getStatus().isCustodyActive();
    }

    private static String whatToDoNext(Shipment shipment, Optional<ShipmentLeg> active) {
        if (active.isEmpty()) {
            return "This parcel is not currently yours to move.";
        }
        return switch (shipment.getStatus()) {
            case DRIVER_ASSIGNED -> "Go to the shop and mark yourself arrived.";
            case AT_ORIGIN -> "Ask the seller for the collection code and collect the parcel.";
            case IN_TRANSIT -> "Take it to the next stop and record the handover there.";
            case OUT_FOR_DELIVERY -> shipment.getRequestedPickupPoint() != null
                    ? "She has asked for it to go to " + shipment.getRequestedPickupPoint().getName()
                      + " instead. Take it there and record the deposit."
                    : shipment.isSafeDropAuthorised()
                    ? "She has authorised you to leave it without a code. Take a photograph where "
                      + "you leave it and record the delivery."
                    : "Ask the recipient for their code, take a photograph, and record the "
                      + "delivery.";
            case ATTEMPT_FAILED -> "Try again after " + shipment.getNextAttemptAfter()
                    + ", or hand it back if it cannot be delivered.";
            default -> null;
        };
    }

    // ── Arriving ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.CustodyRecorded arrivedAtOrigin(Long userId, Long shipmentId,
                                                           DriverRequests.Arrived request) {
        Driver driver = requireDriver(userId);
        Shipment shipment = requireOwnShipment(shipmentId, driver);
        ShipmentLeg leg = requireActiveLeg(shipment, driver);

        Optional<CustodyEvent> already = replayed(userId, request.clientEventId());
        if (already.isPresent()) {
            return replayOf(already.get(), shipment);
        }

        BigDecimal distance = Geofence.metresBetween(request.lat(), request.lng(),
                shipment.getOriginLatitude(), shipment.getOriginLongitude());

        CustodyEvent event = chain.append(shipment, CustodyEvent.builder()
                .type(CustodyEventType.ARRIVED_AT_ORIGIN)
                .leg(leg)
                .recordedByUserId(userId)
                .latitude(request.lat()).longitude(request.lng())
                .accuracyMetres(request.accuracy())
                .metresFromExpected(distance)
                .withinGeofence(Geofence.isWithin(distance, request.accuracy(),
                        Geofence.DEFAULT_RADIUS_M))
                .occurredAt(when(request.capturedAt()))
                .capturedOffline(request.capturedAt() != null)
                .clientEventId(request.clientEventId())
                .build());

        return new DriverResponses.CustodyRecorded(event.getId(), event.getType(),
                shipmentId, shipment.getStatus(), event.getOccurredAt(),
                event.isWithinGeofence(), event.getMetresFromExpected(), false,
                event.isWithinGeofence()
                        ? "Arrival recorded. Ask the seller for the collection code."
                        : "Arrival recorded, but your position does not look like the shop. Check "
                                + "you are at the right address before collecting.");
    }

    // ── The three handovers ──────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.CustodyRecorded collect(Long userId, Long shipmentId,
                                                   DriverRequests.Handover request) {
        return handover(userId, shipmentId, request, CustodyEventType.COLLECTED,
                HandoverCodeType.VENDOR_RELEASE, false,
                "Collected. The parcel is yours now — take it to the next stop.");
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.CustodyRecorded depositAtPickup(Long userId, Long shipmentId,
                                                           DriverRequests.Handover request) {
        return handover(userId, shipmentId, request, CustodyEventType.DEPOSITED,
                HandoverCodeType.VENDOR_TO_PICKUP, false,
                "Left at the pickup point. It is no longer in your custody.");
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.CustodyRecorded deliver(Long userId, Long shipmentId,
                                                   DriverRequests.Handover request) {
        if (request.photoUrl() == null || request.photoUrl().isBlank()) {
            // Asked for here and nowhere else. This is the link that releases
            // the seller's money and ends the chain, so it carries the most
            // evidence: a code, a position and a picture.
            throw new BadRequestException(
                    "A photograph is required on delivery. It is what settles a dispute months "
                            + "later about whether a parcel actually arrived.");
        }
        return handover(userId, shipmentId, request, CustodyEventType.RELEASED,
                HandoverCodeType.RECIPIENT_RELEASE, true,
                "Delivered. Thank you.");
    }

    /**
     * Whether this delivery is standing on an authorisation instead of a code.
     *
     * <p>The narrow exception C4 tolerates, and only because the evidence is
     * replaced rather than dropped. Normally the proof that the right person got
     * the parcel is that they read out six digits; when the recipient has
     * authorised a safe drop, the proof is her authorisation — recorded against
     * the parcel, at a time, with the words she used and the id of the code she
     * held when she said it — plus the photograph and the position below.
     *
     * <p>Narrow on purpose: a driver who presents a code takes the ordinary
     * path even when a safe drop is authorised, because a code that was actually
     * read out is better evidence than a standing permission.
     */
    private boolean isSafeDrop(Shipment shipment, DriverRequests.Handover request) {
        // The flag is checked here rather than where the recipient authorises it,
        // so switching it off stops drops happening TODAY without erasing the
        // instruction she already gave. She said what she wanted; the platform
        // has stopped honouring it for now, which is a different fact and one
        // she can be told.
        return flags.isOn(FeatureFlags.SAFE_DROP)
                && shipment.isSafeDropAuthorised()
                && (request.cleanedCode() == null || request.cleanedCode().isBlank());
    }

    /**
     * The shape every handover shares.
     *
     * <p>Find the code, check it, burn it, build the event with its proof, and
     * let the chain decide what it means. The code is spent in the same
     * transaction as the event, so a code cannot open two handovers even if two
     * requests arrive at once.
     */
    private DriverResponses.CustodyRecorded handover(Long userId, Long shipmentId,
                                                     DriverRequests.Handover request,
                                                     CustodyEventType type,
                                                     HandoverCodeType codeType,
                                                     boolean againstDestination,
                                                     String success) {
        Driver driver = requireDriver(userId);
        Shipment shipment = requireOwnShipment(shipmentId, driver);
        ShipmentLeg leg = requireActiveLeg(shipment, driver);

        Optional<CustodyEvent> already = replayed(userId, request.clientEventId());
        if (already.isPresent()) {
            return replayOf(already.get(), shipment);
        }

        boolean safeDrop = type == CustodyEventType.RELEASED && isSafeDrop(shipment, request);
        if (!safeDrop && (request.cleanedCode() == null || request.cleanedCode().isBlank())) {
            // The check the DTO no longer makes. Every handover but an
            // authorised safe drop carries a code, and a missing one is the
            // whole of C4 going quietly missing.
            throw new BadRequestException(
                    "The code is required — it is the proof this handover happened. Ask them to "
                            + "read out the six digits.");
        }
        HandoverCode code = safeDrop
                ? null
                : burnCode(shipment, leg, codeType, request.cleanedCode());

        Double expectedLat = againstDestination
                ? shipment.getDestinationLatitude() : shipment.getOriginLatitude();
        Double expectedLng = againstDestination
                ? shipment.getDestinationLongitude() : shipment.getOriginLongitude();
        if (!againstDestination && type != CustodyEventType.COLLECTED) {
            // A deposit is measured against where the leg ends, not the shop.
            expectedLat = leg.getDestinationLatitude();
            expectedLng = leg.getDestinationLongitude();
        }

        BigDecimal distance = Geofence.metresBetween(
                request.lat(), request.lng(), expectedLat, expectedLng);
        boolean corroborated = Geofence.isWithin(distance, request.accuracy(),
                Geofence.DEFAULT_RADIUS_M);

        if (safeDrop && !corroborated) {
            // Everywhere else a position that does not match is recorded and
            // flagged, because the parcel may genuinely have changed hands and
            // the code proves it did. Here there is no code, so the position is
            // the only thing corroborating that the driver was at the address
            // the recipient authorised — and a safe drop nowhere near it is a
            // parcel left somewhere nobody agreed to.
            throw new BadRequestException(distance == null
                    ? "A safe drop needs your position. She authorised leaving it at a particular "
                      + "place, and without a position there is nothing to show you were there."
                    : "You are " + distance.longValue() + "m from the delivery address. A safe "
                      + "drop can only be recorded at the place she authorised — ask her for the "
                      + "delivery code instead, or record a failed attempt.");
        }

        CustodyEvent event = chain.append(shipment, CustodyEvent.builder()
                .type(type)
                .leg(leg)
                .recordedByUserId(userId)
                .codePresented(request.cleanedCode())
                .handoverCodeId(code == null ? null : code.getId())
                .latitude(request.lat()).longitude(request.lng())
                .accuracyMetres(request.accuracy())
                .metresFromExpected(distance)
                .withinGeofence(corroborated)
                .photoUrl(request.photoUrl())
                .signatureUrl(request.signatureUrl())
                // Written into the chain rather than inferred later. Months on,
                // "why is there no code against this delivery" has to have an
                // answer in the row itself.
                .reasonCode(safeDrop ? "SAFE_DROP" : null)
                .note(safeDrop ? safeDropNote(shipment, request.note()) : request.note())
                .occurredAt(when(request.capturedAt()))
                .capturedOffline(request.capturedAt() != null)
                .clientEventId(request.clientEventId())
                .build());

        advanceLeg(leg, type);

        if (Geofence.isImplausible(distance)) {
            // Recorded and flagged rather than refused: the parcel may genuinely
            // have changed hands. But a handover two kilometres from where it
            // should be is the shape of a round being closed from home.
            log.warn("[Custody] {} on shipment {} recorded {}m from where it was expected",
                    type, shipment.getReference(), distance);
        }

        String outcome = safeDrop
                ? "Left as she authorised. Recorded against her instruction, with your photograph."
                : success;
        return new DriverResponses.CustodyRecorded(event.getId(), type, shipmentId,
                shipment.getStatus(), event.getOccurredAt(),
                event.isWithinGeofence(), distance, false,
                event.isWithinGeofence() ? outcome
                        : outcome + " Your position does not match where this was expected, so it "
                                + "has been flagged for somebody to look at.");
    }

    /** The authorisation, copied into the event so the chain carries its own justification. */
    private static String safeDropNote(Shipment shipment, String driverNote) {
        StringBuilder note = new StringBuilder("Safe drop authorised by the recipient:");
        if (shipment.getSafeDropPerson() != null && !shipment.getSafeDropPerson().isBlank()) {
            note.append(" leave with ").append(shipment.getSafeDropPerson()).append('.');
        }
        if (shipment.getSafeDropLocation() != null && !shipment.getSafeDropLocation().isBlank()) {
            note.append(" leave at ").append(shipment.getSafeDropLocation()).append('.');
        }
        if (driverNote != null && !driverNote.isBlank()) {
            note.append(' ').append(driverNote);
        }
        return note.toString();
    }

    /**
     * Finds the code, checks it, and spends it.
     *
     * <p>The wrong-code count is on the row rather than in memory, so it
     * survives a restart and cannot be reset by trying from another device. Six
     * digits is a hundred thousand guesses to somebody determined and three to
     * somebody who misheard, and the count is what tells them apart.
     */
    private HandoverCode burnCode(Shipment shipment, ShipmentLeg leg,
                                  HandoverCodeType codeType, String presented) {
        LocalDateTime now = LocalDateTime.now();

        List<HandoverCode> live = codes.findLiveForShipment(shipment.getId(), codeType, now);
        if (live.isEmpty()) {
            throw new BadRequestException(switch (codeType) {
                case VENDOR_RELEASE -> "The seller has not produced a collection code for this "
                        + "parcel yet. Ask them to mark it ready for collection.";
                case RECIPIENT_RELEASE -> "There is no delivery code for this parcel yet. Ask for "
                        + "one to be sent to the buyer, who will pass it to the recipient.";
                default -> "There is no code for this handover yet.";
            });
        }

        for (HandoverCode candidate : live) {
            if (candidate.getCode().equals(presented)) {
                candidate.setUsed(true);
                candidate.setUsedAt(now);
                codes.save(candidate);
                return candidate;
            }
        }

        // Wrong. Count it against every live code, because a guesser does not
        // know which one they are aiming at either.
        for (HandoverCode candidate : live) {
            int failures = (candidate.getFailedAttempts() == null ? 0 : candidate.getFailedAttempts()) + 1;
            candidate.setFailedAttempts(failures);
            if (failures >= 5) {
                candidate.setInvalidatedAt(now);
                log.warn("[Custody] Code on shipment {} burned after {} wrong attempts",
                        shipment.getReference(), failures);
            }
            codes.save(candidate);
        }
        throw new BadRequestException(
                "That code is not right. Ask them to read it out again — it is six digits.");
    }

    /** Moves the leg along once its handover has been recorded. */
    private void advanceLeg(ShipmentLeg leg, CustodyEventType type) {
        LocalDateTime now = LocalDateTime.now();
        if (type == CustodyEventType.COLLECTED) {
            leg.setAssignmentStatus(LegAssignmentStatus.IN_PROGRESS);
            leg.setStartedAt(now);
        } else if (type == CustodyEventType.DEPOSITED || type == CustodyEventType.RELEASED
                || type == CustodyEventType.TRANSFERRED) {
            leg.setAssignmentStatus(LegAssignmentStatus.COMPLETED);
            leg.setCompletedAt(now);
        }
        legs.save(leg);
    }

    // ── A failed attempt ─────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.AttemptFailed deliveryFailed(Long userId, Long shipmentId,
                                                        DriverRequests.DeliveryFailed request) {
        Driver driver = requireDriver(userId);
        Shipment shipment = requireOwnShipment(shipmentId, driver);
        ShipmentLeg leg = requireActiveLeg(shipment, driver);

        Optional<CustodyEvent> already = replayed(userId, request.clientEventId());
        if (already.isPresent()) {
            return new DriverResponses.AttemptFailed(already.get().getId(), shipmentId,
                    shipment.getStatus(), shipment.getFailedAttempts(), MAX_DELIVERY_ATTEMPTS,
                    shipment.getNextAttemptAfter(), false, "Already recorded.");
        }

        BigDecimal distance = Geofence.metresBetween(request.lat(), request.lng(),
                shipment.getDestinationLatitude(), shipment.getDestinationLongitude());

        CustodyEvent event = chain.append(shipment, CustodyEvent.builder()
                .type(CustodyEventType.FAILED_ATTEMPT)
                .leg(leg)
                .recordedByUserId(userId)
                .latitude(request.lat()).longitude(request.lng())
                .accuracyMetres(request.accuracy())
                .metresFromExpected(distance)
                .withinGeofence(Geofence.isWithin(distance, request.accuracy(),
                        Geofence.DEFAULT_RADIUS_M))
                .photoUrl(request.photoUrl())
                .reasonCode(request.reason().name())
                .note(request.note())
                .occurredAt(when(request.capturedAt()))
                .capturedOffline(request.capturedAt() != null)
                .clientEventId(request.clientEventId())
                .build());

        int attempts = shipment.getFailedAttempts();
        boolean exhausted = attempts >= MAX_DELIVERY_ATTEMPTS;
        boolean returning = request.reason().returnsParcel() || exhausted
                || !request.reason().retryable();

        String message;
        if (returning) {
            shipment.setNextAttemptAfter(null);
            message = request.reason().returnsParcel()
                    ? "Recorded. The recipient refused it, so it goes back to the seller rather "
                      + "than being tried again."
                    : exhausted
                        ? "Recorded. That was attempt " + attempts + " of " + MAX_DELIVERY_ATTEMPTS
                          + ", so this parcel goes back rather than being tried again."
                        : "Recorded. This one needs somebody to look at it before another attempt.";
        } else {
            LocalDateTime next = LocalDateTime.now().plus(RETRY_AFTER);
            shipment.setNextAttemptAfter(next);
            message = "Recorded. Try again after " + next + ". You still have the parcel.";
        }
        shipments.save(shipment);

        log.info("[Custody] Attempt {} failed on shipment {} — {} ({})",
                attempts, shipment.getReference(), request.reason(),
                returning ? "returning" : "will retry");

        return new DriverResponses.AttemptFailed(event.getId(), shipmentId, shipment.getStatus(),
                attempts, MAX_DELIVERY_ATTEMPTS, shipment.getNextAttemptAfter(), returning,
                message);
    }

    // ── The recipient's code ─────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.RecipientCodeRequested requestRecipientCode(Long userId, Long shipmentId) {
        Driver driver = requireDriver(userId);
        Shipment shipment = requireOwnShipment(shipmentId, driver);
        requireActiveLeg(shipment, driver);

        if (shipment.getCollectedAt() == null) {
            // Accepting a job is not holding the parcel. Emailing a delivery
            // code for something still on the seller's shelf tells the buyer
            // their parcel is on its way when it is not, and burns one of the
            // few sends before the driver is anywhere near the door.
            throw new BadRequestException(
                    "You have not collected this parcel yet. The code is for handing it over, so "
                            + "ask for it when you are on your way.");
        }

        LocalDateTime now = LocalDateTime.now();
        long recent = codes.countForShipmentSince(shipmentId, HandoverCodeType.RECIPIENT_RELEASE,
                now.minus(CODE_REQUEST_WINDOW));
        if (recent >= MAX_CODE_REQUESTS_PER_WINDOW) {
            throw new BadRequestException(
                    "A code has already been sent " + recent + " times in the last hour. The buyer "
                            + "has to pass it to the recipient, and sending more will not make that "
                            + "faster. Contact support if they cannot be reached.");
        }

        // Any previous code dies. A recipient holding two codes that both work
        // is two chances for the wrong one to be read out.
        for (HandoverCode old : codes.findLiveForShipment(shipmentId,
                HandoverCodeType.RECIPIENT_RELEASE, now)) {
            old.setInvalidatedAt(now);
            codes.save(old);
        }

        HandoverCode code = codes.save(HandoverCode.builder()
                .shipment(shipment)
                .codeType(HandoverCodeType.RECIPIENT_RELEASE)
                .code(sixDigits())
                .used(false)
                .expiresAt(now.plus(RECIPIENT_CODE_LIFETIME))
                .build());

        // Sent to the person who paid, not to the person receiving. She may
        // have no account, no app and no email — that is the case this whole
        // marketplace exists to serve — and he has all three. He passes it on
        // the way anybody passes on a Western Union reference.
        String sentTo = buyerEmail(shipment);
        boolean sent = false;
        if (sentTo != null) {
            try {
                email.sendRecipientReleaseCode(sentTo, buyerName(shipment),
                        shipment.getRecipientName(), orderNumber(shipment), code.getCode(),
                        code.getExpiresAt());
                sent = true;
            } catch (RuntimeException failed) {
                // The code exists either way. A send that failed is a support
                // problem, not a reason to leave the parcel unopenable.
                log.warn("[Custody] Could not email the release code for shipment {}: {}",
                        shipment.getReference(), failed.toString());
            }
        }

        // The code itself is never in this response. A driver who could read it
        // could mark a parcel delivered without meeting anybody.
        log.info("[Custody] Release code issued for shipment {} and {} to the buyer",
                shipment.getReference(), sent ? "sent" : "NOT sent");

        return new DriverResponses.RecipientCodeRequested(shipmentId, sent, mask(sentTo),
                code.getExpiresAt(),
                (int) Math.max(0, MAX_CODE_REQUESTS_PER_WINDOW - recent - 1),
                sent
                        ? "Sent to the buyer, who will pass it to the recipient. Ask her to read "
                          + "you the six digits when you arrive."
                        : "A code has been issued but we could not email the buyer. Contact "
                          + "support rather than waiting at the door.");
    }

    // ── Driver to driver ─────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.CustodyRecorded transfer(Long userId, Long shipmentId,
                                                    DriverRequests.Transfer request) {
        Driver from = requireDriver(userId);
        Shipment shipment = requireOwnShipment(shipmentId, from);
        ShipmentLeg leg = requireActiveLeg(shipment, from);

        Optional<CustodyEvent> already = replayed(userId, request.clientEventId());
        if (already.isPresent()) {
            return replayOf(already.get(), shipment);
        }

        Driver to = drivers.findById(request.toDriverId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver", request.toDriverId()));
        if (to.getId().equals(from.getId())) {
            throw new BadRequestException("You cannot hand a parcel to yourself.");
        }
        if (!to.canCarry()) {
            throw new BadRequestException(
                    "That driver cannot take parcels at the moment, so this transfer would leave "
                            + "the parcel with nobody accountable for it.");
        }

        // Both sides present something. A transfer attested by one person is a
        // link nobody can corroborate, and this is the link at which a parcel
        // would go missing if either half could be forged.
        HandoverCode mine = burnCode(shipment, leg, HandoverCodeType.DRIVER_TO_DRIVER,
                request.myCode());
        if (!request.receivingDriverCode().equals(request.myCode())) {
            // Two distinct codes is the stronger arrangement, but a single
            // shared code read aloud by one and typed by the other still proves
            // they were together. What is refused is one person supplying both
            // halves from memory.
            HandoverCode theirs = codes.findLiveForShipment(shipmentId,
                            HandoverCodeType.DRIVER_TO_DRIVER, LocalDateTime.now()).stream()
                    .filter(c -> c.getCode().equals(request.receivingDriverCode()))
                    .findFirst().orElse(null);
            if (theirs == null) {
                throw new BadRequestException(
                        "The receiving driver's code is not right. Both of you have to be here for "
                                + "this.");
            }
            theirs.setUsed(true);
            theirs.setUsedAt(LocalDateTime.now());
            codes.save(theirs);
        }

        BigDecimal distance = Geofence.metresBetween(request.lat(), request.lng(),
                shipment.getDestinationLatitude(), shipment.getDestinationLongitude());

        CustodyEvent event = chain.append(shipment, CustodyEvent.builder()
                .type(CustodyEventType.TRANSFERRED)
                .leg(leg)
                .recordedByUserId(userId)
                .counterpartyUserId(to.getUser() == null ? null : to.getUser().getId())
                .codePresented(request.receivingDriverCode())
                .handoverCodeId(mine.getId())
                .latitude(request.lat()).longitude(request.lng())
                .accuracyMetres(request.accuracy())
                .metresFromExpected(distance)
                .withinGeofence(Geofence.isWithin(distance, request.accuracy(),
                        Geofence.DEFAULT_RADIUS_M))
                .photoUrl(request.photoUrl())
                .note(request.note())
                .occurredAt(when(request.capturedAt()))
                .capturedOffline(request.capturedAt() != null)
                .clientEventId(request.clientEventId())
                .build());

        // The old leg closes and a new one opens for the driver taking it over,
        // so the chain still has exactly one person accountable at every moment.
        advanceLeg(leg, CustodyEventType.TRANSFERRED);
        ShipmentLeg onward = legs.save(ShipmentLeg.builder()
                .shipment(shipment)
                .sequence(leg.getSequence() + 1)
                .legType(leg.getLegType())
                .assignmentStatus(LegAssignmentStatus.IN_PROGRESS)
                .driver(to)
                .startedAt(LocalDateTime.now())
                .originLatitude(request.lat()).originLongitude(request.lng())
                .originLabel("Handed over by another driver")
                .destinationLatitude(leg.getDestinationLatitude())
                .destinationLongitude(leg.getDestinationLongitude())
                .destinationLabel(leg.getDestinationLabel())
                .earning(BigDecimal.ZERO)
                .earningCurrency(leg.getEarningCurrency())
                .build());

        log.info("[Custody] Shipment {} transferred from driver {} to {} (new leg {})",
                shipment.getReference(), from.getId(), to.getId(), onward.getId());

        return new DriverResponses.CustodyRecorded(event.getId(), CustodyEventType.TRANSFERRED,
                shipmentId, shipment.getStatus(), event.getOccurredAt(),
                event.isWithinGeofence(), distance, false,
                "Handed over. It is no longer your parcel, and the other driver has it on their "
                        + "list.");
    }

    // ── Offline ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.SyncResult sync(Long userId, DriverRequests.SyncBatch request) {
        Driver driver = requireDriver(userId);

        List<DriverResponses.SyncOutcome> outcomes = new ArrayList<>();
        int recorded = 0;
        int duplicates = 0;
        int rejected = 0;

        // Oldest first, whatever order the phone sent them in. A batch applied
        // out of sequence would have a delivery arriving before its collection
        // and the chain would refuse it - correctly, and for the wrong reason.
        List<DriverRequests.SyncEntry> ordered = new ArrayList<>(request.events());
        ordered.sort(java.util.Comparator.comparing(DriverRequests.SyncEntry::capturedAt));

        for (DriverRequests.SyncEntry entry : ordered) {
            try {
                Optional<CustodyEvent> already = replayed(userId, entry.clientEventId());
                if (already.isPresent()) {
                    duplicates++;
                    outcomes.add(new DriverResponses.SyncOutcome(entry.clientEventId(),
                            entry.shipmentId(), true, true, already.get().getId(), null));
                    continue;
                }

                Shipment shipment = requireOwnShipment(entry.shipmentId(), driver);
                ShipmentLeg leg = legs.findActiveLeg(entry.shipmentId(), driver.getId())
                        .orElse(null);
                CustodyEventType type = parseType(entry.type());

                BigDecimal distance = Geofence.metresBetween(entry.lat(), entry.lng(),
                        shipment.getDestinationLatitude(), shipment.getDestinationLongitude());

                CustodyEvent saved = chain.append(shipment, CustodyEvent.builder()
                        .type(type)
                        .leg(leg)
                        .recordedByUserId(userId)
                        .codePresented(entry.code())
                        .latitude(entry.lat()).longitude(entry.lng())
                        .accuracyMetres(entry.accuracy())
                        .metresFromExpected(distance)
                        .withinGeofence(Geofence.isWithin(distance, entry.accuracy(),
                                Geofence.DEFAULT_RADIUS_M))
                        .photoUrl(entry.photoUrl())
                        .reasonCode(entry.reasonCode())
                        .note(entry.note())
                        .occurredAt(entry.capturedAt())
                        .capturedOffline(true)
                        .clientEventId(entry.clientEventId())
                        .build());

                if (leg != null) {
                    advanceLeg(leg, type);
                }
                recorded++;
                outcomes.add(new DriverResponses.SyncOutcome(entry.clientEventId(),
                        entry.shipmentId(), true, false, saved.getId(), null));

            } catch (RuntimeException refused) {
                // One bad entry must not throw away the rest of a day's work.
                // The batch is reported entry by entry so the app knows exactly
                // what to keep and what to stop retrying.
                rejected++;
                outcomes.add(new DriverResponses.SyncOutcome(entry.clientEventId(),
                        entry.shipmentId(), false, false, null, refused.getMessage()));
            }
        }

        log.info("[Custody] Driver {} synced {} offline events: {} recorded, {} already had, {} refused",
                driver.getId(), ordered.size(), recorded, duplicates, rejected);

        return new DriverResponses.SyncResult(ordered.size(), recorded, duplicates, rejected,
                outcomes,
                rejected == 0
                        ? "Everything is in."
                        : rejected + " of " + ordered.size() + " could not be recorded. They are "
                          + "listed with the reason — stop retrying those, they will not change.");
    }

    private static CustodyEventType parseType(String raw) {
        try {
            return CustodyEventType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("'" + raw + "' is not a kind of custody event.");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** An event this device already filed, if it did. */
    private Optional<CustodyEvent> replayed(Long userId, String clientEventId) {
        if (clientEventId == null || clientEventId.isBlank()) {
            return Optional.empty();
        }
        return events.findByRecordedByUserIdAndClientEventId(userId, clientEventId);
    }

    private static DriverResponses.CustodyRecorded replayOf(CustodyEvent event, Shipment shipment) {
        return new DriverResponses.CustodyRecorded(event.getId(), event.getType(),
                shipment.getId(), shipment.getStatus(), event.getOccurredAt(),
                event.isWithinGeofence(), event.getMetresFromExpected(), true,
                "You had already sent this one. Nothing was recorded twice.");
    }

    /** The device's clock where it gave one, ours otherwise. */
    private static LocalDateTime when(LocalDateTime capturedAt) {
        return capturedAt != null ? capturedAt : LocalDateTime.now();
    }

    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** Enough of an address to confirm where it went, not enough to read it out. */
    private static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        String name = email.substring(0, email.indexOf('@'));
        String domain = email.substring(email.indexOf('@'));
        return (name.length() <= 2 ? name.charAt(0) + "•" : name.charAt(0) + "•••"
                + name.charAt(name.length() - 1)) + domain;
    }

    private static Order order(Shipment shipment) {
        return shipment.getVendorOrder() == null ? null : shipment.getVendorOrder().getOrder();
    }

    private static String orderNumber(Shipment shipment) {
        Order order = order(shipment);
        return order == null ? shipment.getReference() : order.getOrderNumber();
    }

    private static String buyerEmail(Shipment shipment) {
        Order order = order(shipment);
        if (order == null) {
            return null;
        }
        if (order.getCustomer() != null && order.getCustomer().getEmail() != null) {
            return order.getCustomer().getEmail();
        }
        return order.getGuestEmail();
    }

    private static String buyerName(Shipment shipment) {
        Order order = order(shipment);
        if (order == null) {
            return "there";
        }
        if (order.getCustomer() != null && order.getCustomer().getFirstName() != null) {
            return order.getCustomer().getFirstName();
        }
        return order.getGuestName() != null ? order.getGuestName() : "there";
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    private Driver requireDriver(Long userId) {
        return drivers.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "You do not have a driver profile."));
    }

    /**
     * A shipment this driver is on. Anything else is not found.
     *
     * <p>Not-found rather than forbidden matters more here than almost anywhere:
     * this row carries a recipient's home address and phone number, and
     * "forbidden" would confirm both that the shipment is real and that the
     * prober guessed a live id.
     */
    private Shipment requireOwnShipment(Long shipmentId, Driver driver) {
        return shipments.findByIdAndDriverId(shipmentId, driver.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
    }

    /** The leg this driver is currently on for this parcel. */
    private ShipmentLeg requireActiveLeg(Shipment shipment, Driver driver) {
        return legs.findActiveLeg(shipment.getId(), driver.getId())
                .orElseThrow(() -> new BadRequestException(
                        "This parcel is not currently yours to move. Accept the job first, or it "
                                + "has already been handed on."));
    }
}
