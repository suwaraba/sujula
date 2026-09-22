package com.sujula.service.admin.impl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.admin.AdminDispatchRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminDispatchResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.Driver;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.repository.delivery.DriverRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.service.AuditService;
import com.sujula.service.NotificationService;
import com.sujula.service.admin.AdminDispatchService;
import com.sujula.service.security.StepUpVerifier;
import com.sujula.service.shipment.CustodyChain;
import com.sujula.service.shipment.Geofence;

import lombok.extern.slf4j.Slf4j;

/**
 * Unsticking orders and parcels.
 *
 * <p>Most of this is reading. The three writes that matter are assignment,
 * which takes a row lock because two dispatchers racing is the ordinary case;
 * forcing a status, which demands a note long enough to be an explanation; and
 * overriding a handoff, which demands step-up as well because it is the one way
 * a parcel reaches DELIVERED without anybody presenting a code.
 *
 * <p>That last one is not a hole in C4 — it is the hole made expensive and
 * loud. A driver whose phone went into the river still has to be able to hand a
 * parcel over, and the alternative to an audited override is somebody editing
 * the database.
 */
@Slf4j
@Service
public class AdminDispatchServiceImpl implements AdminDispatchService {

    /** How long a driver has to answer an offer, when nobody says. */
    private static final int DEFAULT_ACCEPTANCE_MINUTES = 30;

    /** How far away a driver can be and still be worth offering the job to. */
    private static final double CANDIDATE_RADIUS_KM = 25.0;

    private static final int MAX_CANDIDATES = 5;

    private final OrderRepository orders;
    private final VendorOrderRepository vendorOrders;
    private final ShipmentRepository shipments;
    private final ShipmentLegRepository legs;
    private final CustodyEventRepository events;
    private final DriverRepository drivers;
    private final VendorLedgerEntryRepository ledger;
    private final CustodyChain chain;
    private final StepUpVerifier stepUp;
    private final AuditService audit;
    private final NotificationService notifications;

    public AdminDispatchServiceImpl(OrderRepository orders, VendorOrderRepository vendorOrders,
                                    ShipmentRepository shipments, ShipmentLegRepository legs,
                                    CustodyEventRepository events, DriverRepository drivers,
                                    VendorLedgerEntryRepository ledger, CustodyChain chain,
                                    StepUpVerifier stepUp, AuditService audit,
                                    NotificationService notifications) {
        this.orders = orders;
        this.vendorOrders = vendorOrders;
        this.shipments = shipments;
        this.legs = legs;
        this.events = events;
        this.drivers = drivers;
        this.ledger = ledger;
        this.chain = chain;
        this.stepUp = stepUp;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ── Orders ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminDispatchResponses.OrderRow> orders(
            User staff, String query, OrderStatus status, String destinationCountry,
            Long vendorId, Integer stuckForHours, Pageable pageable) {
        LocalDateTime stuckSince = stuckForHours == null ? null
                : LocalDateTime.now().minusHours(stuckForHours);
        Page<Order> page = orders.adminSearch(blankToNull(query), status,
                upper(destinationCountry), vendorId, stuckSince, pageable);
        return PagedResponse.of(page.map(this::orderRow));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDispatchResponses.OrderDetail order(User staff, Long orderId) {
        Order order = requireOrder(orderId);

        List<AdminDispatchResponses.VendorOrderView> slices = new ArrayList<>();
        for (VendorOrder slice : vendorOrders.findByOrderId(orderId)) {
            slices.add(sliceView(slice));
        }

        List<AdminDispatchResponses.LedgerLine> lines = new ArrayList<>();
        for (VendorLedgerEntry entry : ledger.findByOrderId(orderId)) {
            lines.add(new AdminDispatchResponses.LedgerLine(
                    entry.getId(), entry.getType().name(), entry.getAmount(), entry.getCurrency(),
                    entry.getVendor() == null ? null : entry.getVendor().getStoreName(),
                    entry.getDescription(), entry.getReference(),
                    entry.getAvailableFrom(), entry.getOccurredAt()));
        }

        List<AdminDispatchResponses.ParcelView> parcels = new ArrayList<>();
        for (Shipment shipment : shipments.findByOrderId(orderId)) {
            parcels.add(parcelView(shipment));
        }

        return new AdminDispatchResponses.OrderDetail(
                orderRow(order), slices, lines, parcels, warningsFor(order, slices, parcels));
    }

    /**
     * What is wrong with this order, said out loud.
     *
     * <p>The whole reason the three lists are in one response: the interesting
     * facts are the disagreements between them, and nobody reading three screens
     * spots a disagreement.
     */
    private static List<String> warningsFor(Order order,
                                            List<AdminDispatchResponses.VendorOrderView> slices,
                                            List<AdminDispatchResponses.ParcelView> parcels) {
        List<String> warnings = new ArrayList<>();
        for (AdminDispatchResponses.VendorOrderView slice : slices) {
            if (slice.disputeFrozenAt() != null) {
                warnings.add(slice.storeName() + "'s money is frozen by an open dispute since "
                        + slice.disputeFrozenAt() + ".");
            }
        }
        for (AdminDispatchResponses.ParcelView parcel : parcels) {
            if (parcel.failedAttempts() >= 2) {
                warnings.add("Parcel " + parcel.reference() + " has failed "
                        + parcel.failedAttempts() + " delivery attempts.");
            }
            if (parcel.status() == ShipmentStatus.DELIVERED && parcel.collectedAt() == null) {
                // The C4 hole, surfaced where somebody would notice it: a parcel
                // that reached DELIVERED with nothing saying it was ever picked
                // up has a chain with a link missing.
                warnings.add("Parcel " + parcel.reference() + " is marked delivered but has no "
                        + "collection event. Read its custody chain before relying on this.");
            }
        }
        if (order.getStatus() == OrderStatus.CANCELLED && !parcels.isEmpty()
                && parcels.stream().anyMatch(p -> p.status().isCustodyActive())) {
            warnings.add("This order is cancelled but a parcel is still moving. Cancel the parcel "
                    + "as well, or somebody will deliver goods that were refunded.");
        }
        return warnings;
    }

    @Override
    @Transactional
    public AdminDispatchResponses.OrderCancelled forceCancel(
            User staff, Long orderId, AdminDispatchRequests.ForceCancelOrder request) {
        Order order = requireOrder(orderId);
        LocalDateTime now = LocalDateTime.now();

        // One slice or all of them, never "some" (C3). An administrator who
        // meant one and got three would have refunded two sellers who did
        // nothing wrong.
        List<VendorOrder> targets = request.vendorOrderId() == null
                ? vendorOrders.findByOrderId(orderId)
                : List.of(requireSliceOf(order, request.vendorOrderId()));

        List<Long> cancelled = new ArrayList<>();
        List<String> refunds = new ArrayList<>();
        for (VendorOrder slice : targets) {
            if (slice.getStatus() == VendorOrderStatus.CANCELLED) {
                continue;
            }
            slice.setStatus(VendorOrderStatus.CANCELLED);
            slice.setCancelledAt(now);
            slice.setRejectionReason("Cancelled by " + staff.getEmail() + ": " + request.reason());
            vendorOrders.save(slice);
            cancelled.add(slice.getId());
        }

        // Parcels stop too. A cancelled order whose parcel is still moving is
        // goods being delivered that have been refunded, and the driver finds
        // out at the door.
        int parcelsStopped = 0;
        for (Shipment shipment : shipments.findByOrderId(orderId)) {
            boolean mine = request.vendorOrderId() == null
                    || (shipment.getVendorOrder() != null
                        && request.vendorOrderId().equals(shipment.getVendorOrder().getId()));
            if (mine && shipment.getCancelledAt() == null
                    && !shipment.getStatus().isFinished()) {
                shipment.setCancelledAt(now);
                chain.rederive(shipment,
                        events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()));
                parcelsStopped++;
            }
        }

        if (request.vendorOrderId() == null
                && vendorOrders.findByOrderId(orderId).stream()
                    .allMatch(s -> s.getStatus() == VendorOrderStatus.CANCELLED)) {
            order.setStatus(OrderStatus.CANCELLED);
            orders.save(order);
        }

        audit.record(AuditAction.ORDER_CANCELLED_BY_ADMIN, "ORDER", orderId,
                order.getOrderNumber(),
                staff.getEmail() + " cancelled " + cancelled.size() + " slice(s) and stopped "
                        + parcelsStopped + " parcel(s)", request.reason());

        return new AdminDispatchResponses.OrderCancelled(orderId, order.getStatus(), cancelled,
                refunds, parcelsStopped,
                cancelled.isEmpty() ? "Nothing was left to cancel."
                        : cancelled.size() + " slice(s) cancelled and " + parcelsStopped
                          + " parcel(s) stopped. Refunds are raised from the payment surface — "
                          + "money leaving is a separate decision with its own approval.");
    }

    @Override
    @Transactional
    public AdminDispatchResponses.StatusForced forceStatus(
            User staff, Long orderId, Long vendorOrderId,
            AdminDispatchRequests.ForceStatus request) {
        Order order = requireOrder(orderId);
        VendorOrder slice = requireSliceOf(order, vendorOrderId);
        VendorOrderStatus from = slice.getStatus();

        if (from == request.status()) {
            throw new BadRequestException("It is already " + readable(from) + ".");
        }

        slice.setStatus(request.status());
        vendorOrders.save(slice);

        // Its own audit action, named so it reads as break-glass in a log. A
        // month with twenty of these is a question somebody should be asking.
        audit.record(AuditAction.VENDOR_ORDER_STATUS_FORCED, "VENDOR_ORDER", vendorOrderId,
                order.getOrderNumber() + " / " + storeNameOf(slice),
                staff.getEmail() + " forced the status from " + from + " to " + request.status(),
                request.note());
        log.warn("[Admin] BREAK-GLASS: {} forced vendor order {} from {} to {} — {}",
                staff.getEmail(), vendorOrderId, from, request.status(), request.note());

        return new AdminDispatchResponses.StatusForced(vendorOrderId, from, request.status(), null,
                "Forced. Nothing produced this status — your note is the only record that it was "
                        + "justified, and it is kept for as long as the order is.");
    }

    @Override
    @Transactional
    public AdminDispatchResponses.OrderPlaced placeOnBehalf(
            User staff, AdminDispatchRequests.PlaceOrderOnBehalf request) {
        // Deliberately not implemented by reaching into the checkout internals.
        // An order placed for somebody has to go through the same pricing, the
        // same stock reservation and the same FX snapshot as one they placed
        // themselves, or it is an order nobody can explain — and the place that
        // does all three is the checkout service.
        throw new BadRequestException(
                "Placing an order on somebody's behalf goes through checkout so it is priced, "
                        + "reserved and rate-stamped exactly as their own would be. Use the "
                        + "buyer's cart with an impersonated session — that leaves a record of "
                        + "who placed it, which this endpoint could not.");
    }

    // ── The board ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminDispatchResponses.ShipmentRow> shipments(
            User staff, ShipmentStatus status, String country, Long driverId,
            Integer waitingOverHours, Pageable pageable) {
        LocalDateTime waitingSince = waitingOverHours == null ? null
                : LocalDateTime.now().minusHours(waitingOverHours);
        return PagedResponse.of(shipments
                .findBoard(status, upper(country), driverId, waitingSince, pageable)
                .map(this::shipmentRow));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminDispatchResponses.UnassignedShipment> unassigned(User staff, int limit) {
        List<AdminDispatchResponses.UnassignedShipment> out = new ArrayList<>();
        for (Shipment shipment : shipments.findUnassigned(
                PageRequest.of(0, Math.min(Math.max(1, limit), 50)))) {
            List<AdminDispatchResponses.DriverCandidate> candidates = rankCandidates(shipment);
            out.add(new AdminDispatchResponses.UnassignedShipment(
                    shipmentRow(shipment), candidates,
                    candidates.isEmpty()
                            ? "Nobody available within " + (int) CANDIDATE_RADIUS_KM + "km of the "
                              + "shop. Widen the zone or ask a driver to come on."
                            : null));
        }
        return out;
    }

    /**
     * Who could take this parcel, best first, with the reason for the order.
     *
     * <p>Ranked on three things in this order: whether they are on shift, how
     * close they are to the shop, and how often they say yes. A dispatcher
     * handed an unexplained list picks the first one, and the first one would
     * otherwise be whoever the database happened to return.
     */
    private List<AdminDispatchResponses.DriverCandidate> rankCandidates(Shipment shipment) {
        List<AdminDispatchResponses.DriverCandidate> candidates = new ArrayList<>();

        for (Driver driver : drivers.findByCountryCode(shipment.getDestinationCountry())) {
            if (!driver.canCarry()) {
                continue;
            }
            BigDecimal metres = Geofence.metresBetween(
                    driver.getCurrentLatitude(), driver.getCurrentLongitude(),
                    shipment.getOriginLatitude(), shipment.getOriginLongitude());
            Double km = metres == null ? null
                    : metres.doubleValue() / 1000.0;
            if (km != null && km > CANDIDATE_RADIUS_KM) {
                continue;
            }

            int open = legs.countOpenJobs(driver.getId());
            candidates.add(new AdminDispatchResponses.DriverCandidate(
                    driver.getId(), nameOf(driver.getUser()), driver.getPhone(), driver.getZone(),
                    km, driver.getAcceptanceScore(), open, driver.isAvailable(),
                    driver.getVehicleType() == null ? null : driver.getVehicleType().name(),
                    why(driver, km, open)));
        }

        candidates.sort(Comparator
                .comparing(AdminDispatchResponses.DriverCandidate::available).reversed()
                .thenComparing(c -> c.distanceKm() == null ? Double.MAX_VALUE : c.distanceKm())
                .thenComparing(c -> c.acceptanceScore() == null ? BigDecimal.ZERO
                        : c.acceptanceScore(), Comparator.reverseOrder()));
        return candidates.size() > MAX_CANDIDATES
                ? candidates.subList(0, MAX_CANDIDATES) : candidates;
    }

    private static String why(Driver driver, Double km, int open) {
        List<String> reasons = new ArrayList<>();
        reasons.add(driver.isAvailable() ? "on shift" : "off shift");
        // Null rather than a large number: a driver whose phone has not reported
        // a position is not far away, they are unknown, and those are different
        // facts a dispatcher should be able to tell apart.
        reasons.add(km == null ? "position unknown"
                : String.format(Locale.ROOT, "%.1fkm from the shop", km));
        if (driver.getAcceptanceScore() != null) {
            reasons.add("accepts " + driver.getAcceptanceScore() + "% of offers");
        }
        reasons.add(open == 0 ? "nothing else on" : open + " job(s) already on");
        return String.join(", ", reasons);
    }

    // ── Assignment ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminDispatchResponses.AssignmentMade assign(
            User staff, Long shipmentId, AdminDispatchRequests.AssignShipment request) {
        Shipment shipment = requireShipment(shipmentId);
        // Locked before anything is read off it. Two dispatchers on a busy
        // morning is the ordinary race, and the loser must be told rather than
        // silently overwriting the winner.
        ShipmentLeg leg = legs.lockNextLeg(shipmentId)
                .orElseThrow(() -> new BadRequestException(
                        "This parcel has no leg left to assign. Its journey is finished or "
                                + "cancelled."));

        if (leg.getAssignmentStatus() == LegAssignmentStatus.ACCEPTED
                || leg.getAssignmentStatus() == LegAssignmentStatus.IN_PROGRESS) {
            throw new BadRequestException(
                    "Somebody has already taken this one"
                            + (leg.getDriver() == null ? "" : " — " + nameOf(leg.getDriver().getUser()))
                            + ". Unassign it first if it needs to move.");
        }

        AdminDispatchResponses.AssignmentMade made = offer(shipment, leg, request.driverId(),
                request.acceptanceMinutes());

        audit.record(AuditAction.SHIPMENT_ASSIGNED, "SHIPMENT", shipmentId,
                shipment.getReference(),
                staff.getEmail() + " offered the parcel to " + made.driverName(),
                request.note());
        return made;
    }

    /**
     * Puts the leg on a driver's screen, with no opinion about who had it before.
     *
     * <p>Separate from {@link #assign} because the two callers disagree about
     * exactly one thing: assignment refuses to displace a driver who has accepted,
     * and reassignment exists to do that. Sharing the guard as well as the
     * mutation is how reassignment ends up unable to reassign — which is what the
     * first version of this did.
     */
    private AdminDispatchResponses.AssignmentMade offer(
            Shipment shipment, ShipmentLeg leg, Long driverId, Integer acceptanceMinutes) {
        Driver driver = requireDriver(driverId);
        requireCanCarry(driver, shipment);

        LocalDateTime now = LocalDateTime.now();
        Duration window = Duration.ofMinutes(
                acceptanceMinutes == null ? DEFAULT_ACCEPTANCE_MINUTES : acceptanceMinutes);

        leg.setDriver(driver);
        // OFFERED, not ACCEPTED. A platform that could put a job on somebody's
        // screen and call it theirs would be one where a driver who was asleep
        // is accountable for a parcel.
        leg.setAssignmentStatus(LegAssignmentStatus.OFFERED);
        leg.setOfferedAt(now);
        leg.setOfferExpiresAt(now.plus(window));
        leg.setAcceptedAt(null);
        leg.setStartedAt(null);
        leg.setDeclinedAt(null);
        leg.setDeclineReason(null);
        legs.save(leg);

        chain.rederive(shipment, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()));

        return new AdminDispatchResponses.AssignmentMade(shipment.getId(), leg.getId(),
                driver.getId(), nameOf(driver.getUser()), leg.getOfferExpiresAt(),
                "Offered. They have " + window.toMinutes() + " minutes to answer, after which it "
                        + "goes back to the queue — an offer nobody declined holds the parcel out "
                        + "of circulation, which is worse for the person waiting than a refusal.");
    }

    @Override
    @Transactional
    public AdminDispatchResponses.AssignmentRemoved unassign(
            User staff, Long shipmentId, AdminDispatchRequests.UnassignShipment request) {
        Shipment shipment = requireShipment(shipmentId);
        ShipmentLeg leg = legs.lockNextLeg(shipmentId)
                .orElseThrow(() -> new BadRequestException("Nothing to unassign on this parcel."));

        if (leg.getDriver() == null) {
            return new AdminDispatchResponses.AssignmentRemoved(shipmentId, leg.getId(), null,
                    false, "Nobody had it.");
        }
        if (leg.getAssignmentStatus() == LegAssignmentStatus.IN_PROGRESS) {
            throw new BadRequestException(
                    "That driver is carrying this parcel. Taking it off them here would leave the "
                            + "custody chain saying they still have it — record a transfer or a "
                            + "failed attempt instead.");
        }

        String previous = nameOf(leg.getDriver().getUser());
        leg.setDriver(null);
        leg.setAssignmentStatus(LegAssignmentStatus.UNASSIGNED);
        leg.setOfferedAt(null);
        leg.setOfferExpiresAt(null);
        leg.setAcceptedAt(null);
        legs.save(leg);

        chain.rederive(shipment, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipmentId));

        audit.record(AuditAction.SHIPMENT_UNASSIGNED, "SHIPMENT", shipmentId,
                shipment.getReference(),
                staff.getEmail() + " took the parcel off " + previous, request.reason());

        return new AdminDispatchResponses.AssignmentRemoved(shipmentId, leg.getId(), previous,
                // Their score is untouched: a job taken off somebody is not a
                // job they declined, and scoring it as one would punish a driver
                // for a decision that was not theirs.
                false,
                "Back in the queue. " + previous + "'s acceptance score is untouched — this was "
                        + "not a refusal.");
    }

    @Override
    @Transactional
    public AdminDispatchResponses.AssignmentMade reassign(
            User staff, Long shipmentId, AdminDispatchRequests.ReassignShipment request) {
        Shipment shipment = requireShipment(shipmentId);

        // Only before anybody has it, or after an attempt failed. A parcel in a
        // driver's hands moves by a transfer with both of them attesting, not by
        // an administrator changing a column.
        boolean collected = shipment.getCollectedAt() != null;
        boolean afterFailure = shipment.getStatus() == ShipmentStatus.ATTEMPT_FAILED;
        if (collected && !afterFailure) {
            throw new BadRequestException(
                    "That parcel is already in a driver's hands. Reassigning it here would say it "
                            + "changed hands when nobody handed it to anybody — use a transfer, "
                            + "where both drivers attest, or record a failed attempt first.");
        }

        ShipmentLeg leg = legs.lockNextLeg(shipmentId)
                .orElseThrow(() -> new BadRequestException(
                        "This parcel has no leg left to assign. Its journey is finished or "
                                + "cancelled."));

        // Who is losing it, read before the leg is overwritten. This is the one
        // thing reassignment does that assignment refuses to do — displace a
        // driver who already has the job — so it cannot share assignment's guard,
        // and the name has to be taken here or the audit row cannot say whose
        // screen the job disappeared from.
        Driver losing = leg.getDriver();
        String displacedName = losing == null ? null : nameOf(losing.getUser());
        Long displacedUserId = losing == null || losing.getUser() == null
                ? null : losing.getUser().getId();

        if (losing != null && losing.getId().equals(request.driverId())) {
            throw new BadRequestException(
                    displacedName + " already has this one. Reassigning it to the same driver "
                            + "would re-offer a job they have already answered.");
        }

        AdminDispatchResponses.AssignmentMade made =
                offer(shipment, leg, request.driverId(), request.acceptanceMinutes());

        audit.record(AuditAction.SHIPMENT_REASSIGNED, "SHIPMENT", shipmentId,
                shipment.getReference(),
                staff.getEmail() + " moved the parcel"
                        + (displacedName == null ? "" : " off " + displacedName)
                        + " to " + made.driverName(),
                request.reason());

        // The displaced driver is told. Their acceptance score is deliberately
        // untouched: being moved off a parcel is not a refusal, and scoring it as
        // one would cost a driver for a decision that was an administrator's.
        if (displacedUserId != null) {
            notifications.send(displacedUserId,
                    "A parcel was moved to another driver",
                    "Parcel " + shipment.getReference() + " has been reassigned by dispatch"
                            + (request.reason() == null || request.reason().isBlank()
                                    ? "." : ": " + request.reason())
                            + " Your acceptance rate is unaffected — this was not a decline.",
                    NotificationEvent.DELIVERY_OFFERED, String.valueOf(shipment.getId()));
        }
        return made;
    }

    // ── The C4 escape hatch ──────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminDispatchResponses.HandoffOverridden overrideHandoff(
            User staff, Long shipmentId, AdminDispatchRequests.OverrideHandoff request) {
        // The administrator's own credentials first. This is the one endpoint
        // that can put a parcel into DELIVERED with nobody having presented
        // anything, and an admin session left open on a desk must not be enough.
        stepUp.verify(staff, request.password(), request.totpCode(),
                "recording a handover without its code");

        Shipment shipment = requireShipment(shipmentId);
        ShipmentLeg leg = legs.findByShipmentIdOrderBySequenceAsc(shipmentId).stream()
                .filter(l -> l.getAssignmentStatus() != LegAssignmentStatus.COMPLETED
                        && l.getAssignmentStatus() != LegAssignmentStatus.CANCELLED)
                .findFirst().orElse(null);

        BigDecimal distance = Geofence.metresBetween(request.lat(), request.lng(),
                shipment.getDestinationLatitude(), shipment.getDestinationLongitude());

        CustodyEvent event = chain.append(shipment, CustodyEvent.builder()
                .type(request.eventType())
                .leg(leg)
                .recordedByUserId(staff.getId())
                // No code, and the row says so rather than leaving a blank
                // somebody could later read as an oversight.
                .codePresented(null)
                .reasonCode("ADMIN_OVERRIDE")
                .latitude(request.lat()).longitude(request.lng())
                .metresFromExpected(distance)
                .withinGeofence(Geofence.isWithin(distance, null, Geofence.DEFAULT_RADIUS_M))
                // The note carries who attested and what they said. Months later
                // this is the entire answer to "why is there no code against
                // this delivery".
                .note("Recorded by " + staff.getEmail() + " without a code. Attested by "
                        + request.attestedBy() + ". " + request.note())
                .occurredAt(LocalDateTime.now())
                .clientEventId("admin-override-" + java.util.UUID.randomUUID())
                .build());

        audit.record(AuditAction.SHIPMENT_HANDOFF_OVERRIDDEN, "SHIPMENT", shipmentId,
                shipment.getReference(),
                staff.getEmail() + " recorded a " + request.eventType()
                        + " without a code, attested by " + request.attestedBy(),
                request.note());
        log.warn("[Admin] HANDOFF OVERRIDE: {} recorded {} on parcel {} with no code — {}",
                staff.getEmail(), request.eventType(), shipment.getReference(), request.note());

        return new AdminDispatchResponses.HandoffOverridden(shipmentId, event.getId(),
                request.eventType(), shipment.getStatus(), event.getNote(),
                "This link of the chain has no code against it. It is marked as an override and "
                        + "carries your name and what you were told — anybody reading this parcel's "
                        + "history later will see that a person decided this rather than somebody "
                        + "proving it.");
    }

    @Override
    @Transactional
    public AdminDispatchResponses.ShipmentCancelled cancelShipment(
            User staff, Long shipmentId, AdminDispatchRequests.CancelShipment request) {
        Shipment shipment = requireShipment(shipmentId);
        if (shipment.getStatus().isFinished()) {
            throw new BadRequestException(
                    "That parcel's journey is already over — it is " + readable(shipment.getStatus())
                            + ".");
        }

        shipment.setCancelledAt(LocalDateTime.now());
        chain.rederive(shipment, events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipmentId));

        for (ShipmentLeg leg : legs.findByShipmentIdOrderBySequenceAsc(shipmentId)) {
            if (leg.getAssignmentStatus() != LegAssignmentStatus.COMPLETED) {
                leg.setAssignmentStatus(LegAssignmentStatus.CANCELLED);
                legs.save(leg);
            }
        }

        audit.record(AuditAction.SHIPMENT_CANCELLED_BY_ADMIN, "SHIPMENT", shipmentId,
                shipment.getReference(), staff.getEmail() + " cancelled the parcel",
                request.reason());

        return new AdminDispatchResponses.ShipmentCancelled(shipmentId, ShipmentStatus.CANCELLED,
                "Cancelled, and every open leg with it. If the goods are still in somebody's "
                        + "hands, they need to go back to the seller — that is a return, not this.");
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDispatchResponses.CustodyChainView custodyChain(User staff, Long shipmentId) {
        Shipment shipment = requireShipment(shipmentId);
        List<AdminDispatchResponses.CustodyEventView> views = new ArrayList<>();

        for (CustodyEvent event : events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipmentId)) {
            views.add(new AdminDispatchResponses.CustodyEventView(
                    event.getId(), event.getType(),
                    event.getOccurredAt(), event.getRecordedAt(),
                    String.valueOf(event.getRecordedByUserId()), null,
                    // Whether a code was presented, not the code. An
                    // administrator reading a chain has no use for the digits,
                    // and a code on a screen is a code somebody can read out.
                    event.getCodePresented() != null && !event.getCodePresented().isBlank(),
                    event.getReasonCode(),
                    event.getLatitude(), event.getLongitude(), event.getAccuracyMetres(),
                    event.getMetresFromExpected(), event.isWithinGeofence(),
                    event.getPhotoUrl(), event.getSignatureUrl(), event.getNote(),
                    event.isCapturedOffline(),
                    "ADMIN_OVERRIDE".equals(event.getReasonCode())));
        }

        return new AdminDispatchResponses.CustodyChainView(shipmentId, shipment.getReference(),
                shipment.getStatus(), views,
                views.stream().anyMatch(AdminDispatchResponses.CustodyEventView::overridden)
                        ? "One or more links here were recorded by an administrator without a "
                          + "code. Read their notes before treating this chain as proof."
                        : null);
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private Order requireOrder(Long orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    private VendorOrder requireSliceOf(Order order, Long vendorOrderId) {
        return vendorOrders.findById(vendorOrderId)
                .filter(slice -> slice.getOrder() != null
                        && slice.getOrder().getId().equals(order.getId()))
                // Scoped to the order in the path, so a slice id from a
                // different order is not found rather than quietly acted on.
                .orElseThrow(() -> new ResourceNotFoundException("Vendor order", vendorOrderId));
    }

    private Shipment requireShipment(Long shipmentId) {
        return shipments.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Parcel", shipmentId));
    }

    private Driver requireDriver(Long driverId) {
        return drivers.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", driverId));
    }

    private static void requireCanCarry(Driver driver, Shipment shipment) {
        if (!driver.canCarry()) {
            throw new BadRequestException(
                    nameOf(driver.getUser()) + " is " + readable(driver.getStatus())
                            + " and cannot be given parcels.");
        }
        if (driver.getCountryCode() != null && shipment.getDestinationCountry() != null
                && !driver.getCountryCode().equalsIgnoreCase(shipment.getDestinationCountry())) {
            // C1 on the delivery side: a driver is matched against where the
            // goods go, never against anything about the person who paid.
            throw new BadRequestException(
                    nameOf(driver.getUser()) + " works in " + driver.getCountryCode()
                            + " and this parcel is going to " + shipment.getDestinationCountry()
                            + ".");
        }
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private AdminDispatchResponses.OrderRow orderRow(Order order) {
        Set<String> stores = new LinkedHashSet<>();
        int slices = 0;
        for (VendorOrder slice : vendorOrders.findByOrderId(order.getId())) {
            slices++;
            if (slice.getVendor() != null) {
                stores.add(slice.getVendor().getStoreName());
            }
        }
        LocalDateTime since = order.getUpdatedAt() == null
                ? order.getCreatedAt() : order.getUpdatedAt();

        return new AdminDispatchResponses.OrderRow(
                order.getId(), order.getOrderNumber(), order.getStatus(),
                order.getPaymentStatus() == null ? null : order.getPaymentStatus().name(),
                order.getCustomer() == null ? order.getGuestName() : nameOf(order.getCustomer()),
                order.getCustomer() == null ? order.getGuestEmail() : order.getCustomer().getEmail(),
                // Where the goods go, which on this platform is routinely not
                // where the buyer is (C1).
                order.getShippingCity(), order.getShippingCountry(),
                order.getTotal(), order.getCurrency(),
                slices, List.copyOf(stores),
                since == null ? 0 : Duration.between(since, LocalDateTime.now()).toHours(),
                order.getCreatedAt());
    }

    private AdminDispatchResponses.VendorOrderView sliceView(VendorOrder slice) {
        var fx = slice.getFx();
        return new AdminDispatchResponses.VendorOrderView(
                slice.getId(), storeNameOf(slice), slice.getStatus(),
                slice.getTotal(),
                slice.getOrder() == null ? null : slice.getOrder().getCurrency(),
                slice.getTotalNative(), slice.getNativeCurrency(),
                fx == null ? null : fx.getRate(), fx == null ? null : fx.getRateAt(),
                slice.getAcceptedAt(), slice.getReadyAt(), slice.getCancelledAt(),
                slice.getDisputeFrozenAt(), slice.getRejectionReason());
    }

    private AdminDispatchResponses.ParcelView parcelView(Shipment shipment) {
        List<AdminDispatchResponses.LegView> legViews = new ArrayList<>();
        for (ShipmentLeg leg : legs.findByShipmentIdOrderBySequenceAsc(shipment.getId())) {
            legViews.add(new AdminDispatchResponses.LegView(
                    leg.getId(), leg.getSequence(),
                    leg.getLegType() == null ? null : leg.getLegType().name(),
                    leg.getAssignmentStatus(),
                    leg.getDriver() == null ? null : nameOf(leg.getDriver().getUser()),
                    leg.getDriver() == null ? null : leg.getDriver().getPhone(),
                    leg.getOriginLabel(), leg.getDestinationLabel(),
                    leg.getOfferedAt(), leg.getOfferExpiresAt(), leg.getAcceptedAt(),
                    leg.getCompletedAt(), leg.getEarning(), leg.getEarningCurrency()));
        }
        return new AdminDispatchResponses.ParcelView(
                shipment.getId(), shipment.getReference(), shipment.getTrackingCode(),
                shipment.getStatus(),
                shipment.getVendorOrder() == null || shipment.getVendorOrder().getVendor() == null
                        ? null : shipment.getVendorOrder().getVendor().getStoreName(),
                shipment.getDestinationCity(),
                shipment.getFailedAttempts(), shipment.getNextAttemptAfter(),
                shipment.getHeldAtPickupPoint() == null ? null
                        : shipment.getHeldAtPickupPoint().getName(),
                shipment.getShelfCode(), legViews,
                shipment.getCollectedAt(), shipment.getDeliveredAt());
    }

    private AdminDispatchResponses.ShipmentRow shipmentRow(Shipment shipment) {
        LocalDateTime since = shipment.getUpdatedAt() == null
                ? shipment.getCreatedAt() : shipment.getUpdatedAt();
        long hours = since == null ? 0 : Duration.between(since, LocalDateTime.now()).toHours();

        String driver = legs.findByShipmentIdOrderBySequenceAsc(shipment.getId()).stream()
                .filter(l -> l.getDriver() != null
                        && l.getAssignmentStatus() != LegAssignmentStatus.COMPLETED)
                .map(l -> nameOf(l.getDriver().getUser()))
                .findFirst().orElse(null);

        return new AdminDispatchResponses.ShipmentRow(
                shipment.getId(), shipment.getReference(), shipment.getStatus(),
                shipment.getVendorOrder() == null || shipment.getVendorOrder().getVendor() == null
                        ? null : shipment.getVendorOrder().getVendor().getStoreName(),
                shipment.getDestinationCity(), shipment.getDestinationCountry(),
                driver, shipment.getFailedAttempts() == null ? 0 : shipment.getFailedAttempts(),
                hours,
                // Overdue means the parcel is past the day it should have been
                // collected from a counter, not that it is merely slow.
                shipment.isOverdue(LocalDateTime.now()),
                shipment.getCreatedAt());
    }

    // ── Small things ─────────────────────────────────────────────────────────

    private static String storeNameOf(VendorOrder slice) {
        return slice.getVendor() == null ? null : slice.getVendor().getStoreName();
    }

    private static String nameOf(User user) {
        if (user == null) {
            return null;
        }
        String joined = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return joined.isEmpty() ? user.getEmail() : joined;
    }

    private static String readable(Enum<?> value) {
        return value == null ? "unknown"
                : value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
