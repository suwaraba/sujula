package com.sujula.service.pickup.impl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.pickup.PickupRequests;
import com.sujula.dto.response.pickup.PickupResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.order.Order;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.EmailService;
import com.sujula.service.pickup.PickupCounter;
import com.sujula.service.pickup.PickupPointService;
import com.sujula.service.shipment.CustodyChain;

import lombok.extern.slf4j.Slf4j;

/**
 * A counter that holds parcels, and the people who run it.
 *
 * <p>Three things here are worth reading closely.
 *
 * <p><strong>Accepting a parcel is a link in the custody chain, not a status
 * change.</strong> The operator verifies the driver's code — the receiving party
 * checking the giving party, which is the only thing a code can prove — and the
 * chain records a transfer. The parcel's status follows from that event, as it
 * does everywhere else (C4).
 *
 * <p><strong>Releasing asks for two things.</strong> The recipient's code proves
 * somebody told them it; the name proves the operator looked at who was in front
 * of them. A code alone would let anybody who overheard it collect, and a name
 * alone would let anybody who read the label.
 *
 * <p><strong>The public reads carry a band, not a count.</strong> A shopper
 * needs to know whether their parcel can go to a counter. That this shop is
 * holding a hundred and ninety parcels is a fact about somebody's business and
 * is nobody else's.
 */
@Slf4j
@Service
public class PickupPointServiceImpl implements PickupPointService {

    /** Default radius when a shopper gives a position but no distance. */
    private static final double DEFAULT_RADIUS_KM = 10;

    /** How many results a public search returns at most. */
    private static final int MAX_RESULTS = 50;

    /** At or above this fraction of capacity, a counter reads as filling up. */
    private static final double LIMITED_AT = 0.85;

    /** How long a resent collection code stands. */
    private static final Duration CODE_LIFETIME = Duration.ofHours(48);

    /** How often a code may be resent, so the buyer is not spammed. */
    private static final Duration RESEND_WINDOW = Duration.ofHours(1);
    private static final int MAX_RESENDS_PER_WINDOW = 3;

    private final PickupPointRepository points;
    private final ShipmentRepository shipments;
    private final ShipmentLegRepository legs;
    private final CustodyEventRepository events;
    private final HandoverCodeRepository codes;
    private final UserRepository users;
    private final CustodyChain chain;
    private final PickupCounter counter;
    private final EmailService email;

    public PickupPointServiceImpl(PickupPointRepository points, ShipmentRepository shipments,
                                  ShipmentLegRepository legs, CustodyEventRepository events,
                                  HandoverCodeRepository codes, UserRepository users,
                                  CustodyChain chain, PickupCounter counter, EmailService email) {
        this.points = points;
        this.shipments = shipments;
        this.legs = legs;
        this.events = events;
        this.codes = codes;
        this.users = users;
        this.chain = chain;
        this.counter = counter;
        this.email = email;
    }

    // ── Public search ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PickupResponses.PublicPoints search(PickupRequests.NearbySearch request) {
        boolean hasPosition = request != null && request.lat() != null && request.lng() != null;
        boolean hasCity = request != null && request.city() != null && !request.city().isBlank();

        if (!hasPosition && !hasCity) {
            throw new BadRequestException(
                    "Say where to look: either lat and lng, or a town. A list of every counter in "
                            + "the country is not an answer to 'where can I collect this'.");
        }

        List<PickupResponses.PublicPoint> found = new ArrayList<>();

        if (hasPosition) {
            double radius = request.radius() == null ? DEFAULT_RADIUS_KM : request.radius();
            for (Object[] row : points.findNear(request.lat(), request.lng(), radius, MAX_RESULTS)) {
                // The native query returns the row and its computed distance;
                // the entity is re-read by id so the mapping below works on a
                // real object rather than a column array.
                Long id = ((Number) row[0]).longValue();
                Double distance = row[row.length - 1] == null ? null
                        : ((Number) row[row.length - 1]).doubleValue();
                points.findById(id).ifPresent(point ->
                        found.add(toPublic(point, round(distance))));
            }
        } else {
            for (PickupPoint point : points.findPublicInCity(request.city())) {
                // No position was given, so no distance is reported. A distance
                // from nowhere is not a number, and a client would sort by it.
                found.add(toPublic(point, null));
            }
        }

        return new PickupResponses.PublicPoints(found,
                hasPosition ? request.lat() : null,
                hasPosition ? request.lng() : null,
                hasPosition ? (request.radius() == null ? DEFAULT_RADIUS_KM : request.radius()) : null,
                found.isEmpty()
                        ? "No counters here. Try a wider radius, or have it delivered to a door."
                        : null);
    }

    @Override
    @Transactional(readOnly = true)
    public PickupResponses.PublicPoint publicPoint(Long id) {
        // Only approved, active points are found at all. A suspended counter is
        // not found rather than shown as unavailable: it is not a place anybody
        // should be sent, and its state is its own business.
        return points.findPublicById(id)
                .map(point -> toPublic(point, null))
                .orElseThrow(() -> new ResourceNotFoundException("Pickup point", id));
    }

    /**
     * A counter as the public sees it.
     *
     * <p>No operator name, no contact email, no parcel counts, no earnings. A
     * competitor reading this learns where the counter is and when it opens,
     * which is what a counter wants known.
     */
    private static PickupResponses.PublicPoint toPublic(PickupPoint point, Double distanceKm) {
        return new PickupResponses.PublicPoint(
                point.getId(), point.getName(), point.getAddressStreet(), point.getCity(),
                point.getCountryCode(), point.getLatitude(), point.getLongitude(), distanceKm,
                point.getOpeningHours(), bandFor(point),
                point.canAcceptParcels(),
                point.isTemporarilyClosed() ? point.getClosedUntil() : null,
                point.isTemporarilyClosed() ? point.getClosureReason() : null,
                point.getContactPhone(), point.getProfileImageUrl());
    }

    /** How full, as a band. The count is the operator's business. */
    private static PickupResponses.Capacity bandFor(PickupPoint point) {
        if (point.getStatus() != PartnerStatus.APPROVED || !point.isActive()
                || point.isTemporarilyClosed()) {
            return PickupResponses.Capacity.CLOSED;
        }
        if (!point.hasSpace()) {
            return PickupResponses.Capacity.FULL;
        }
        int capacity = point.getCapacity() == null ? 0 : point.getCapacity();
        int stored = point.getStoredParcels() == null ? 0 : point.getStoredParcels();
        if (capacity > 0 && (double) stored / capacity >= LIMITED_AT) {
            return PickupResponses.Capacity.LIMITED;
        }
        return PickupResponses.Capacity.AVAILABLE;
    }

    // ── Applying ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public PickupResponses.ApplicationSubmitted apply(Long userId, PickupRequests.Apply request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (request.contactEmail() != null && !request.contactEmail().isBlank()
                && points.existsByContactEmailIgnoreCase(request.contactEmail())) {
            throw new BadRequestException(
                    "A pickup point is already registered with that email address.");
        }

        PickupPoint point = points.save(PickupPoint.builder()
                .operatorUser(user)
                .status(PartnerStatus.PENDING)
                .name(request.name().trim())
                .addressStreet(request.addressStreet())
                .addressApartment(request.addressApartment())
                .city(request.city())
                .state(request.state())
                .postalCode(request.postalCode())
                .countryCode(request.countryCode() == null ? null
                        : request.countryCode().toUpperCase(java.util.Locale.ROOT))
                .latitude(request.lat())
                .longitude(request.lng())
                .contactPhone(request.contactPhone())
                .contactEmail(request.contactEmail())
                .managerName(request.managerName())
                .openingHours(request.openingHours())
                .capacity(request.capacity() == null ? 50 : request.capacity())
                .profileImageUrl(request.profileImageUrl())
                // Off until somebody has looked at it. A counter that started
                // accepting parcels the moment it was typed in would be a
                // counter nobody had checked was real.
                .active(false)
                .build());

        log.info("[Pickup] User {} applied to run a pickup point in {} ({})",
                userId, request.city(), request.countryCode());

        return new PickupResponses.ApplicationSubmitted(point.getId(), point.getName(),
                point.getStatus(), point.getCreatedAt(),
                "Application received. Somebody will check the address and the counter before you "
                        + "can hold parcels — people's goods end up on your shelf, so this is not "
                        + "automatic.");
    }

    // ── Running a point ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.OperatorPoints myPoints(Long userId) {
        List<PickupResponses.OperatorPoint> rows = new ArrayList<>();
        for (PickupPoint point : points.findByOperatorUserIdOrderByNameAsc(userId)) {
            rows.add(toOperator(point, null));
        }
        return new PickupResponses.OperatorPoints(rows,
                rows.isEmpty() ? "You do not run a pickup point yet." : null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.OperatorPoint updatePoint(Long userId, Long pointId,
                                                     PickupRequests.UpdatePoint request) {
        PickupPoint point = requireOwnPoint(pointId, userId);

        // Only what an operator may say about their own counter. Nothing here
        // touches status, active or earnings: an operator who could approve
        // themselves would make the review meaningless.
        if (notBlank(request.name())) {
            point.setName(request.name().trim());
        }
        if (notBlank(request.openingHours())) {
            point.setOpeningHours(request.openingHours());
        }
        if (notBlank(request.contactPhone())) {
            point.setContactPhone(request.contactPhone());
        }
        if (notBlank(request.contactEmail())) {
            point.setContactEmail(request.contactEmail());
        }
        if (notBlank(request.profileImageUrl())) {
            point.setProfileImageUrl(request.profileImageUrl());
        }
        if (request.storageDays() != null) {
            point.setStorageDays(request.storageDays());
        }
        if (request.capacity() != null) {
            int stored = point.getStoredParcels() == null ? 0 : point.getStoredParcels();
            if (request.capacity() < stored) {
                // Refused rather than accepted and left inconsistent. A capacity
                // below what is already on the shelf would make the counter
                // permanently "full" and leave the operator unable to explain
                // why, and the parcels do not go away because a number changed.
                throw new BadRequestException(
                        "You are holding " + stored + " parcels, so the capacity cannot be set to "
                                + request.capacity() + ". Release some first, or set it to " + stored
                                + " or more.");
            }
            point.setCapacity(request.capacity());
        }

        // Null clears the closure and reopens, which is how an operator comes
        // back early. Passing a past moment does the same thing.
        point.setClosedUntil(request.closedUntil());
        point.setClosureReason(request.closedUntil() == null ? null : request.closureReason());

        points.save(point);

        return toOperator(point,
                point.isTemporarilyClosed()
                        ? "Closed until " + point.getClosedUntil() + ". The parcels you already "
                          + "hold stay yours to release — the people waiting on them did not "
                          + "choose the closure."
                        : "Saved.");
    }

    private PickupResponses.OperatorPoint toOperator(PickupPoint point, String message) {
        LocalDateTime now = LocalDateTime.now();
        int overdue = points.findById(point.getId()).isPresent()
                ? shipments.findOverdueAt(point.getId(), now).size() : 0;
        int incoming = shipments.findIncomingTo(point.getId()).size();

        return new PickupResponses.OperatorPoint(
                point.getId(), point.getName(), point.getStatus(), point.isActive(),
                point.getAddressStreet(), point.getCity(), point.getCountryCode(),
                point.getLatitude(), point.getLongitude(),
                point.getOpeningHours(), point.getCapacity(), point.getStoredParcels(),
                overdue, incoming, bandFor(point),
                point.getClosedUntil(), point.getClosureReason(),
                point.getStorageDays(),
                point.getCommissionPerParcel(), point.getCommissionCurrency(),
                point.getContactPhone(), point.getContactEmail(), point.getManagerName(),
                point.getCreatedAt(), message);
    }

    // ── The three piles ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.Parcels parcels(Long userId, Long pointId) {
        PickupPoint point = requireOwnPoint(pointId, userId);
        LocalDateTime now = LocalDateTime.now();

        List<PickupResponses.IncomingParcel> incoming = new ArrayList<>();
        for (Shipment shipment : shipments.findIncomingTo(pointId)) {
            incoming.add(new PickupResponses.IncomingParcel(
                    shipment.getId(), shipment.getReference(), shipment.getStatus(),
                    parcelCount(shipment), storeName(shipment),
                    // No recipient name and no number. It is not here yet, and
                    // an operator expecting six parcels needs to know that
                    // rather than who they are for.
                    shipment.getOriginAddress()));
        }

        List<PickupResponses.StoredParcel> stored = new ArrayList<>();
        List<PickupResponses.StoredParcel> overdue = new ArrayList<>();
        for (Shipment shipment : shipments.findByHeldAtPickupPointIdOrderByStoredAtAsc(pointId)) {
            PickupResponses.StoredParcel row = toStored(shipment, now);
            if (row.overdue()) {
                overdue.add(row);
            } else {
                stored.add(row);
            }
        }

        return new PickupResponses.Parcels(incoming, stored, overdue,
                stored.size() + overdue.size(),
                point.getCapacity() == null ? 0 : point.getCapacity(),
                bandFor(point),
                overdue.isEmpty() ? null
                        : overdue.size() + " parcel(s) are past their collection date and should "
                          + "go back to the seller.");
    }

    private static PickupResponses.StoredParcel toStored(Shipment shipment, LocalDateTime now) {
        boolean isOverdue = shipment.isOverdue(now);
        long daysLeft = shipment.getStorageDeadline() == null ? 0
                : ChronoUnit.DAYS.between(now, shipment.getStorageDeadline());

        return new PickupResponses.StoredParcel(
                shipment.getId(), shipment.getReference(), shipment.getShelfCode(),
                // The name is needed: releasing means checking that the person
                // at the counter is the person it is for.
                shipment.getRecipientName(),
                // The number is masked. Confirming the right parcel does not
                // require being able to ring somebody you have never met.
                phoneHint(shipment.getRecipientPhone()),
                parcelCount(shipment), storeName(shipment),
                shipment.getStoredAt(), shipment.getStorageDeadline(),
                isOverdue, Math.max(0, daysLeft),
                shipment.getPickupCommission(), shipment.getPickupCommissionCurrency(),
                isOverdue
                        ? "Past its collection date. Send it back to the seller."
                        : null);
    }

    // ── Accepting ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.ParcelAccepted accept(Long userId, Long pointId, Long shipmentId,
                                                 PickupRequests.AcceptParcel request) {
        PickupPoint point = requireOwnPoint(pointId, userId);

        CustodyEvent already = replayed(userId, request.clientEventId());
        if (already != null) {
            Shipment existing = shipments.findById(shipmentId).orElseThrow();
            return new PickupResponses.ParcelAccepted(shipmentId, existing.getReference(),
                    existing.getShelfCode(), existing.getStoredAt(), existing.getStorageDeadline(),
                    point.getStoredParcels(), point.getCapacity(),
                    existing.getPickupCommission(), existing.getPickupCommissionCurrency(),
                    true, "You had already taken this one in.");
        }

        if (!point.canAcceptParcels()) {
            // Said separately so the operator knows which of the four it is.
            throw new BadRequestException(refusalReason(point));
        }

        Shipment shipment = shipments.findIncomingById(shipmentId, pointId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That parcel is not on its way to this counter.", shipmentId));

        ShipmentLeg leg = legs.findByShipmentIdOrderBySequenceAsc(shipmentId).stream()
                .filter(l -> l.getDestinationPickupPoint() != null
                        && pointId.equals(l.getDestinationPickupPoint().getId()))
                .reduce((first, second) -> second)
                .orElse(null);

        // The operator verifies the DRIVER's code: the receiving party checking
        // the giving party, which is the same shape as every other link and the
        // only thing a code can prove.
        HandoverCode code = burnCode(shipment, HandoverCodeType.VENDOR_TO_PICKUP,
                request.cleanedCode());

        String shelfCode = counter.allocateShelfCode(point, request.shelfCode());
        LocalDateTime now = LocalDateTime.now();

        // DEPOSITED rather than a status change, and rather than a second event
        // beside the driver's. One transfer is one link: the parcel moved from
        // the driver's hands to this counter's, and whichever side records it
        // first is the record of it.
        chain.append(shipment, CustodyEvent.builder()
                .type(CustodyEventType.DEPOSITED)
                .leg(leg)
                .recordedByUserId(userId)
                .codePresented(request.cleanedCode())
                .handoverCodeId(code == null ? null : code.getId())
                .latitude(point.getLatitude()).longitude(point.getLongitude())
                .metresFromExpected(BigDecimal.ZERO)
                // The counter is where it says it is: a fixed, reviewed address
                // rather than a phone's guess at one.
                .withinGeofence(true)
                .note(request.note())
                .occurredAt(now)
                .clientEventId(request.clientEventId())
                .build());

        counter.store(shipment, point, shelfCode, now);

        if (leg != null) {
            leg.setAssignmentStatus(com.sujula.model.constant.LegAssignmentStatus.COMPLETED);
            leg.setCompletedAt(now);
            legs.save(leg);
        }

        log.info("[Pickup] Point {} took in parcel {} onto shelf {}",
                pointId, shipment.getReference(), shelfCode);

        return new PickupResponses.ParcelAccepted(shipmentId, shipment.getReference(), shelfCode,
                now, shipment.getStorageDeadline(),
                point.getStoredParcels(), point.getCapacity(),
                shipment.getPickupCommission(), shipment.getPickupCommissionCurrency(),
                false,
                "Taken in. Write " + shelfCode + " on the parcel and put it there. The recipient "
                        + "has until " + shipment.getStorageDeadline().toLocalDate()
                        + " to collect it.");
    }

    /** Which of the four reasons a counter cannot take a parcel. */
    private static String refusalReason(PickupPoint point) {
        if (point.getStatus() != PartnerStatus.APPROVED) {
            return "This counter is " + point.getStatus() + " and cannot take parcels.";
        }
        if (!point.isActive()) {
            return "This counter is switched off.";
        }
        if (point.isTemporarilyClosed()) {
            return "You are closed until " + point.getClosedUntil()
                    + ". Reopen first if you want to take this in.";
        }
        return "Your shelf is full — " + point.getStoredParcels() + " of " + point.getCapacity()
                + ". Release or return some parcels before taking more.";
    }

    // ── Rejecting ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.ParcelRejected reject(Long userId, Long pointId, Long shipmentId,
                                                 PickupRequests.RejectParcel request) {
        PickupPoint point = requireOwnPoint(pointId, userId);

        CustodyEvent already = replayed(userId, request.clientEventId());
        if (already != null) {
            Shipment existing = shipments.findById(shipmentId).orElseThrow();
            return new PickupResponses.ParcelRejected(shipmentId, existing.getReference(),
                    existing.getStatus(), request.reason().name(), true,
                    "You had already turned this one away.");
        }

        Shipment shipment = shipments.findIncomingById(shipmentId, pointId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That parcel is not on its way to this counter.", shipmentId));

        // Custody does NOT move. The driver still has the parcel in their hands,
        // which is the whole reason this is recorded as a failed attempt rather
        // than as the end of the chain — somebody is still accountable for it.
        chain.append(shipment, CustodyEvent.builder()
                .type(CustodyEventType.FAILED_ATTEMPT)
                .recordedByUserId(userId)
                .latitude(point.getLatitude()).longitude(point.getLongitude())
                .metresFromExpected(BigDecimal.ZERO)
                .withinGeofence(true)
                .photoUrl(request.photoUrl())
                .reasonCode(request.reason().name())
                .note(request.note())
                .occurredAt(LocalDateTime.now())
                .clientEventId(request.clientEventId())
                .build());

        log.info("[Pickup] Point {} turned away parcel {} — {}",
                pointId, shipment.getReference(), request.reason());

        return new PickupResponses.ParcelRejected(shipmentId, shipment.getReference(),
                shipment.getStatus(), request.reason().name(), false,
                request.reason().isParcelsFault()
                        ? "Turned away. The driver still has it and it goes back with your note "
                          + "as the reason."
                        : "Turned away. The driver still has it and dispatch will find another "
                          + "counter.");
    }

    // ── Releasing ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.ParcelReleased release(Long userId, Long pointId, Long shipmentId,
                                                  PickupRequests.ReleaseParcel request) {
        PickupPoint point = requireOwnPoint(pointId, userId);

        CustodyEvent already = replayed(userId, request.clientEventId());
        if (already != null) {
            Shipment existing = shipments.findById(shipmentId).orElseThrow();
            return new PickupResponses.ParcelReleased(shipmentId, existing.getReference(),
                    existing.getStatus(), already.getOccurredAt(), request.collectedByName(),
                    true, point.getStoredParcels(), true, "You had already handed this one over.");
        }

        Shipment shipment = shipments.findAtPickupPoint(shipmentId, pointId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That parcel is not on this counter's shelf.", shipmentId));

        // The recipient's code. She was told it by whoever sent the parcel; she
        // may have no account, no app and no email of her own (C5), and this is
        // the only thing she has to produce.
        HandoverCode code = burnCode(shipment, HandoverCodeType.RECIPIENT_RELEASE,
                request.cleanedCode());

        // The name is the second check and is deliberately soft: it is recorded
        // as matched or not rather than enforced. Names are spelled differently
        // on a passport and in a shop, and a sister collecting for a sister is
        // the ordinary case here — refusing on a spelling would refuse the thing
        // this marketplace is for. What matters is that somebody looked and
        // wrote down what they saw.
        boolean nameMatched = namesLookAlike(shipment.getRecipientName(),
                request.collectedByName());

        LocalDateTime now = LocalDateTime.now();

        chain.append(shipment, CustodyEvent.builder()
                .type(CustodyEventType.RELEASED)
                .recordedByUserId(userId)
                .codePresented(request.cleanedCode())
                .handoverCodeId(code == null ? null : code.getId())
                .latitude(point.getLatitude()).longitude(point.getLongitude())
                .metresFromExpected(BigDecimal.ZERO)
                .withinGeofence(true)
                .photoUrl(request.photoUrl())
                .signatureUrl(request.signatureUrl())
                .note(buildReleaseNote(request, nameMatched))
                .occurredAt(now)
                .clientEventId(request.clientEventId())
                .build());

        counter.release(shipment, point);

        if (!nameMatched) {
            // Recorded and flagged rather than refused. Somebody collecting for
            // a relative is normal; a mismatch nobody noticed is not.
            log.warn("[Pickup] Parcel {} released at point {} to '{}' rather than '{}'",
                    shipment.getReference(), pointId, request.collectedByName(),
                    shipment.getRecipientName());
        }

        log.info("[Pickup] Point {} released parcel {} from shelf {}",
                pointId, shipment.getReference(), shipment.getShelfCode());

        return new PickupResponses.ParcelReleased(shipmentId, shipment.getReference(),
                shipment.getStatus(), now, request.collectedByName(), nameMatched,
                point.getStoredParcels(), false,
                nameMatched
                        ? "Handed over. Shelf " + shipment.getShelfCode() + " is free."
                        : "Handed over, and recorded as collected by somebody other than the "
                          + "named recipient. That is normal when a relative collects, and it is "
                          + "written down either way.");
    }

    private static String buildReleaseNote(PickupRequests.ReleaseParcel request,
                                           boolean nameMatched) {
        StringBuilder note = new StringBuilder("Collected by ").append(request.collectedByName());
        if (!nameMatched) {
            note.append(" (not the named recipient)");
        }
        if (notBlank(request.identityShown())) {
            note.append(", identity shown: ").append(request.identityShown());
        }
        if (notBlank(request.note())) {
            note.append(" — ").append(request.note());
        }
        return note.length() > 400 ? note.substring(0, 400) : note.toString();
    }

    /**
     * Whether the name given looks like the name on the parcel.
     *
     * <p>Deliberately forgiving. Names here are transliterated inconsistently —
     * Isatou and Isatu are the same woman — and somebody collecting for a
     * relative is the ordinary case rather than a red flag. This decides what is
     * written in the record, never whether the parcel is handed over.
     */
    private static boolean namesLookAlike(String onParcel, String atCounter) {
        if (onParcel == null || atCounter == null) {
            return false;
        }
        String a = simplify(onParcel);
        String b = simplify(atCounter);
        if (a.equals(b)) {
            return true;
        }
        // A shared surname is what makes "her brother came" legible as such.
        for (String part : a.split(" ")) {
            if (part.length() >= 3 && b.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static String simplify(String name) {
        return java.text.Normalizer.normalize(name.trim().toLowerCase(java.util.Locale.ROOT),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z ]", "")
                .replaceAll("\\s+", " ");
    }

    // ── Returning ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.ParcelReturning returnToVendor(Long userId, Long pointId,
                                                          Long shipmentId,
                                                          PickupRequests.ReturnParcel request) {
        PickupPoint point = requireOwnPoint(pointId, userId);

        CustodyEvent already = replayed(userId, request.clientEventId());
        if (already != null) {
            Shipment existing = shipments.findById(shipmentId).orElseThrow();
            return new PickupResponses.ParcelReturning(shipmentId, existing.getReference(),
                    existing.getStatus(), existing.getStorageDeadline(), 0, true,
                    "You had already sent this one back.");
        }

        Shipment shipment = shipments.findAtPickupPoint(shipmentId, pointId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That parcel is not on this counter's shelf.", shipmentId));

        LocalDateTime now = LocalDateTime.now();
        if (!shipment.isOverdue(now)) {
            // Refused rather than allowed early. Somebody is waiting on this
            // parcel and may be travelling to collect it; sending it back before
            // the date they were told is taking a decision that is not the
            // counter's to take.
            throw new BadRequestException(
                    "That parcel can still be collected until "
                            + shipment.getStorageDeadline().toLocalDate()
                            + ". It cannot be sent back before then — somebody may be on their way.");
        }

        long daysOverdue = ChronoUnit.DAYS.between(shipment.getStorageDeadline(), now);

        chain.append(shipment, CustodyEvent.builder()
                .type(CustodyEventType.RETURNED)
                .recordedByUserId(userId)
                .latitude(point.getLatitude()).longitude(point.getLongitude())
                .metresFromExpected(BigDecimal.ZERO)
                .withinGeofence(true)
                .reasonCode("UNCOLLECTED")
                .note(notBlank(request.note()) ? request.note()
                        : "Not collected within " + daysOverdue + " days of the deadline")
                .occurredAt(now)
                .clientEventId(request.clientEventId())
                .build());

        counter.release(shipment, point);

        log.info("[Pickup] Point {} returned parcel {} — {} days overdue",
                pointId, shipment.getReference(), daysOverdue);

        return new PickupResponses.ParcelReturning(shipmentId, shipment.getReference(),
                shipment.getStatus(), shipment.getStorageDeadline(), daysOverdue, false,
                "Recorded as going back to the seller. Shelf " + shipment.getShelfCode()
                        + " is free.");
    }

    // ── Resending the code ───────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.CodeResent resendCode(Long userId, Long pointId, Long shipmentId) {
        requireOwnPoint(pointId, userId);

        Shipment shipment = shipments.findAtPickupPoint(shipmentId, pointId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That parcel is not on this counter's shelf.", shipmentId));

        LocalDateTime now = LocalDateTime.now();
        long recent = codes.countForShipmentSince(shipmentId, HandoverCodeType.RECIPIENT_RELEASE,
                now.minus(RESEND_WINDOW));
        if (recent >= MAX_RESENDS_PER_WINDOW) {
            throw new BadRequestException(
                    "The code has already been sent " + recent + " times in the last hour. Sending "
                            + "it again will not reach them any faster — the buyer has to pass it "
                            + "on. Contact support if they cannot be reached.");
        }

        // The old one dies. Somebody holding two working codes is two chances to
        // read the wrong one out at a counter.
        for (HandoverCode old : codes.findLiveForShipment(shipmentId,
                HandoverCodeType.RECIPIENT_RELEASE, now)) {
            old.setInvalidatedAt(now);
            codes.save(old);
        }

        HandoverCode fresh = codes.save(HandoverCode.builder()
                .shipment(shipment)
                .codeType(HandoverCodeType.RECIPIENT_RELEASE)
                .code(sixDigits())
                .used(false)
                .expiresAt(now.plus(CODE_LIFETIME))
                .build());

        // To the buyer, who passes it on. Not an SMS to the recipient: she may
        // have no account, no app and no email, and the person who paid has all
        // three.
        String sentTo = buyerEmail(shipment);
        boolean sent = false;
        if (sentTo != null) {
            try {
                email.sendRecipientReleaseCode(sentTo, buyerName(shipment),
                        shipment.getRecipientName(), orderNumber(shipment),
                        fresh.getCode(), fresh.getExpiresAt());
                sent = true;
            } catch (RuntimeException failed) {
                log.warn("[Pickup] Could not email a fresh code for parcel {}: {}",
                        shipment.getReference(), failed.toString());
            }
        }

        log.info("[Pickup] Fresh collection code issued for parcel {} and {} to the buyer",
                shipment.getReference(), sent ? "sent" : "NOT sent");

        // The code is never in this response. An operator who could read it
        // could hand the parcel to whoever is standing there.
        return new PickupResponses.CodeResent(shipmentId, sent, mask(sentTo),
                fresh.getExpiresAt(),
                (int) Math.max(0, MAX_RESENDS_PER_WINDOW - recent - 1),
                sent
                        ? "Sent to the buyer, who passes it to the person collecting. Any code "
                          + "they were given before this no longer works."
                        : "A new code was issued but we could not email the buyer. Contact "
                          + "support rather than leaving them at the counter.");
    }

    // ── Earnings ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('PICKUP_OPERATOR') and #userId == authentication.principal.id)")
    public PickupResponses.Earnings earnings(Long userId, Long pointId,
                                             LocalDate from, LocalDate to) {
        requireOwnPoint(pointId, userId);

        LocalDate end = to != null ? to : LocalDate.now().plusDays(1);
        LocalDate start = from != null ? from : end.minusDays(30);
        if (!start.isBefore(end)) {
            throw new BadRequestException("The start of the period must come before the end.");
        }

        Map<String, List<PickupResponses.EarningLine>> byCurrency = new LinkedHashMap<>();
        for (Shipment shipment : shipments.findHandledBy(pointId,
                start.atStartOfDay(), end.atStartOfDay())) {
            String currency = shipment.getPickupCommissionCurrency() == null
                    ? "GMD" : shipment.getPickupCommissionCurrency();
            byCurrency.computeIfAbsent(currency, key -> new ArrayList<>())
                    .add(new PickupResponses.EarningLine(
                            shipment.getId(), shipment.getReference(),
                            shipment.getPickupCommission() == null
                                    ? BigDecimal.ZERO : shipment.getPickupCommission(),
                            currency, shipment.getStoredAt(),
                            shipment.getDeliveredAt() != null
                                    ? shipment.getDeliveredAt() : shipment.getReturnedAt(),
                            outcomeOf(shipment)));
        }

        List<PickupResponses.CurrencyEarnings> summaries = new ArrayList<>();
        int handled = 0;
        for (Map.Entry<String, List<PickupResponses.EarningLine>> entry : byCurrency.entrySet()) {
            BigDecimal total = entry.getValue().stream()
                    .map(PickupResponses.EarningLine::commission)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            summaries.add(new PickupResponses.CurrencyEarnings(entry.getKey(), total,
                    entry.getValue().size(), entry.getValue()));
            handled += entry.getValue().size();
        }

        return new PickupResponses.Earnings(start, end, summaries, handled,
                summaries.size() > 1
                        ? "You have handled parcels paid in more than one currency. They are kept "
                          + "apart rather than added: no single rate is true of all of them."
                        : null);
    }

    private static String outcomeOf(Shipment shipment) {
        if (shipment.getDeliveredAt() != null) {
            return "Collected";
        }
        if (shipment.getReturnedAt() != null) {
            return "Returned to the seller";
        }
        return shipment.getHeldAtPickupPoint() != null ? "On the shelf" : "In progress";
    }

    // ── Shared plumbing ──────────────────────────────────────────────────────

    /**
     * Finds the code, checks it, and spends it.
     *
     * <p>The same arrangement as the driver surface: a wrong-code count on the
     * row rather than in memory, so it survives a restart and cannot be reset by
     * trying from another device.
     */
    private HandoverCode burnCode(Shipment shipment, HandoverCodeType codeType, String presented) {
        LocalDateTime now = LocalDateTime.now();
        List<HandoverCode> live = codes.findLiveForShipment(shipment.getId(), codeType, now);

        if (live.isEmpty()) {
            throw new BadRequestException(codeType == HandoverCodeType.RECIPIENT_RELEASE
                    ? "There is no collection code for this parcel. Send one to the buyer first — "
                      + "they pass it to whoever is collecting."
                    : "The driver has no handover code for this parcel. They cannot leave it here "
                      + "without one.");
        }

        for (HandoverCode candidate : live) {
            if (candidate.getCode().equals(presented)) {
                candidate.setUsed(true);
                candidate.setUsedAt(now);
                codes.save(candidate);
                return candidate;
            }
        }

        for (HandoverCode candidate : live) {
            int failures = (candidate.getFailedAttempts() == null ? 0
                    : candidate.getFailedAttempts()) + 1;
            candidate.setFailedAttempts(failures);
            if (failures >= 5) {
                candidate.setInvalidatedAt(now);
                log.warn("[Pickup] Code on parcel {} burned after {} wrong attempts",
                        shipment.getReference(), failures);
            }
            codes.save(candidate);
        }
        throw new BadRequestException(
                "That code is not right. Ask them to read it out again — it is six digits.");
    }

    private CustodyEvent replayed(Long userId, String clientEventId) {
        if (clientEventId == null || clientEventId.isBlank()) {
            return null;
        }
        return events.findByRecordedByUserIdAndClientEventId(userId, clientEventId).orElse(null);
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static Double round(Double km) {
        return km == null ? null
                : BigDecimal.valueOf(km).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static int parcelCount(Shipment shipment) {
        return shipment.getParcelCount() == null ? 1 : shipment.getParcelCount();
    }

    private static String storeName(Shipment shipment) {
        return shipment.getVendorOrder() == null || shipment.getVendorOrder().getVendor() == null
                ? null : shipment.getVendorOrder().getVendor().getStoreName();
    }

    /** Enough of a number to confirm the right parcel, not enough to ring somebody. */
    private static String phoneHint(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() < 3 ? null : "••• " + digits.substring(digits.length() - 3);
    }

    private static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        String name = email.substring(0, email.indexOf('@'));
        String domain = email.substring(email.indexOf('@'));
        return (name.length() <= 2 ? name.charAt(0) + "•"
                : name.charAt(0) + "•••" + name.charAt(name.length() - 1)) + domain;
    }

    private static Order orderOf(Shipment shipment) {
        return shipment.getVendorOrder() == null ? null : shipment.getVendorOrder().getOrder();
    }

    private static String orderNumber(Shipment shipment) {
        Order order = orderOf(shipment);
        return order == null ? shipment.getReference() : order.getOrderNumber();
    }

    private static String buyerEmail(Shipment shipment) {
        Order order = orderOf(shipment);
        if (order == null) {
            return null;
        }
        if (order.getCustomer() != null && order.getCustomer().getEmail() != null) {
            return order.getCustomer().getEmail();
        }
        return order.getGuestEmail();
    }

    private static String buyerName(Shipment shipment) {
        Order order = orderOf(shipment);
        if (order == null) {
            return "there";
        }
        if (order.getCustomer() != null && order.getCustomer().getFirstName() != null) {
            return order.getCustomer().getFirstName();
        }
        return order.getGuestName() != null ? order.getGuestName() : "there";
    }

    /**
     * A point this operator runs. Another operator's is not found.
     *
     * <p>The operator goes into the query rather than being compared afterwards,
     * and not-found rather than forbidden matters here: these rows lead to
     * recipients' names and phone numbers.
     */
    private PickupPoint requireOwnPoint(Long pointId, Long userId) {
        return points.findByIdAndOperatorUserId(pointId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Pickup point", pointId));
    }
}
