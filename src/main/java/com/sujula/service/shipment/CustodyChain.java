package com.sujula.service.shipment;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The only thing that moves a parcel, and the only thing that sets its status.
 *
 * <p>This class is C4. Everywhere else in the system a status is read; here it
 * is <em>derived</em>, from the events recorded against a shipment, every time
 * one is appended. {@link Shipment} has no public status setter, so there is no
 * path through the codebase by which a parcel reaches DELIVERED other than an
 * event saying who handed it to whom, where, and with what proof.
 *
 * <p>Put the other way round: if this class were deleted, nothing could change
 * where a parcel is. That is the property worth having.
 *
 * <h2>The rules an event has to pass</h2>
 *
 * <ul>
 *   <li><b>Sequence.</b> A parcel cannot be delivered before it is collected.
 *       The chain is checked against what has already happened rather than
 *       against a status column, because the column is the thing being derived.</li>
 *   <li><b>Proof.</b> Every transfer carries the code the receiving party
 *       presented. No code, no transfer — and the code is burned in the same
 *       transaction, so it cannot open a second handover.</li>
 *   <li><b>Time.</b> A device clock is something its holder can set, so an event
 *       from the future is refused and one from implausibly far back is refused
 *       too. Everything in between is believed, because a driver out of signal
 *       for six hours is Tuesday here rather than an attack.</li>
 * </ul>
 */
@Slf4j
@Component
public class CustodyChain {

    /**
     * How far ahead of the server a device's clock may be.
     *
     * <p>Small but not zero: phones drift, and refusing a clock a minute fast
     * would reject honest events all day. Generous the other way, because a
     * genuinely offline capture is hours old by the time it arrives.
     */
    private static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(5);

    /** Older than this and the event is not a late upload, it is a mistake. */
    private static final Duration MAX_BACKDATE = Duration.ofDays(14);

    private final CustodyEventRepository events;
    private final ShipmentRepository shipments;

    public CustodyChain(CustodyEventRepository events, ShipmentRepository shipments) {
        this.events = events;
        this.shipments = shipments;
    }

    /**
     * Appends an event and re-derives everything that follows from it.
     *
     * <p>The single door. The event is validated against the chain so far, saved,
     * and then the shipment's status, attempt count and timestamps are recomputed
     * from the whole chain — not patched. Recomputing means the status is a pure
     * function of the events and cannot drift from them: replay the chain and
     * you get the same answer.
     */
    @Transactional
    public CustodyEvent append(Shipment shipment, CustodyEvent event) {
        requireSaneClock(event);

        List<CustodyEvent> chain = events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId());
        requireAllowed(shipment, chain, event.getType());

        event.setShipment(shipment);
        CustodyEvent saved = events.save(event);

        chain.add(saved);
        rederive(shipment, chain);

        log.info("[Custody] {} on shipment {} by user {} — {} ({}m from expected)",
                saved.getType(), shipment.getReference(), saved.getRecordedByUserId(),
                saved.isWithinGeofence() ? "attested" : "position not corroborated",
                saved.getMetresFromExpected());
        return saved;
    }

    /**
     * Recomputes a shipment's whole derived state from its chain.
     *
     * <p>Public so a repair job or a test can re-run it over existing rows and
     * assert it changes nothing. If it ever does change something, the status
     * had drifted and the events were right.
     */
    @Transactional
    public ShipmentStatus rederive(Shipment shipment, List<CustodyEvent> chain) {
        ShipmentStatus status = deriveStatus(shipment, chain);

        int failed = (int) chain.stream()
                .filter(e -> e.getType() == CustodyEventType.FAILED_ATTEMPT).count();

        LocalDateTime collected = firstTimeOf(chain, CustodyEventType.COLLECTED);
        LocalDateTime delivered = firstTimeOf(chain, CustodyEventType.RELEASED);
        LocalDateTime returned = firstTimeOf(chain, CustodyEventType.RETURNED);

        shipment.applyDerivedState(status, failed, collected, delivered, returned);
        shipments.save(shipment);
        return status;
    }

    /**
     * Where the parcel is, from what has happened to it.
     *
     * <p>Reads the chain backwards: the most recent event that moved the parcel
     * decides. Everything before it is history, and the status is the answer to
     * "who has it now" rather than "what is the furthest it got".
     */
    private static ShipmentStatus deriveStatus(Shipment shipment, List<CustodyEvent> chain) {
        if (shipment.getCancelledAt() != null) {
            return ShipmentStatus.CANCELLED;
        }

        for (int i = chain.size() - 1; i >= 0; i--) {
            CustodyEvent event = chain.get(i);
            switch (event.getType()) {
                case RELEASED:
                    return ShipmentStatus.DELIVERED;
                case RETURNED:
                    return ShipmentStatus.RETURNED;
                case DEPOSITED:
                    return ShipmentStatus.AT_PICKUP_POINT;
                case FAILED_ATTEMPT:
                    // The driver still has it. That is the reason this is an
                    // event rather than the end of the chain.
                    return ShipmentStatus.ATTEMPT_FAILED;
                case COLLECTED:
                case REDISPATCHED:
                case TRANSFERRED:
                    return lastLegEndsWithRecipient(shipment)
                            ? ShipmentStatus.OUT_FOR_DELIVERY
                            : ShipmentStatus.IN_TRANSIT;
                case ARRIVED_AT_ORIGIN:
                    return ShipmentStatus.AT_ORIGIN;
                default:
                    break;
            }
        }

        // Nothing has happened yet, so the answer is about the legs rather than
        // the chain: has anybody been asked, and did they say yes.
        return fromLegs(shipment);
    }

    private static ShipmentStatus fromLegs(Shipment shipment) {
        List<ShipmentLeg> legs = shipment.getLegs();
        if (legs == null || legs.isEmpty()) {
            return ShipmentStatus.AWAITING_COLLECTION;
        }
        boolean accepted = legs.stream().anyMatch(
                l -> l.getAssignmentStatus() == LegAssignmentStatus.ACCEPTED
                        || l.getAssignmentStatus() == LegAssignmentStatus.IN_PROGRESS);
        if (accepted) {
            return ShipmentStatus.DRIVER_ASSIGNED;
        }
        boolean offered = legs.stream().anyMatch(
                l -> l.getAssignmentStatus() == LegAssignmentStatus.OFFERED);
        return offered ? ShipmentStatus.DRIVER_OFFERED : ShipmentStatus.AWAITING_COLLECTION;
    }

    /** Whether the leg currently being carried is the one that ends at the door. */
    private static boolean lastLegEndsWithRecipient(Shipment shipment) {
        List<ShipmentLeg> legs = shipment.getLegs();
        if (legs == null || legs.isEmpty()) {
            return true;   // no legs modelled: treat it as a straight run
        }
        return legs.stream()
                .filter(l -> l.getAssignmentStatus() == LegAssignmentStatus.IN_PROGRESS
                        || l.getAssignmentStatus() == LegAssignmentStatus.ACCEPTED)
                .findFirst()
                .map(l -> l.getLegType().endsWithRecipient())
                .orElseGet(() -> legs.get(legs.size() - 1).getLegType().endsWithRecipient());
    }

    private static LocalDateTime firstTimeOf(List<CustodyEvent> chain, CustodyEventType type) {
        return chain.stream()
                .filter(e -> e.getType() == type)
                .map(CustodyEvent::getOccurredAt)
                .findFirst()
                .orElse(null);
    }

    // ── What may follow what ─────────────────────────────────────────────────

    /**
     * Refuses an event the chain cannot have reached.
     *
     * <p>Checked against the events rather than against the status column,
     * because the column is the thing being derived from them — validating
     * against it would be checking an answer against itself.
     */
    private static void requireAllowed(Shipment shipment, List<CustodyEvent> chain,
                                       CustodyEventType next) {
        boolean collected = chain.stream().anyMatch(e -> e.getType() == CustodyEventType.COLLECTED);
        boolean finished = chain.stream().anyMatch(e -> e.getType().isTerminal());

        if (finished) {
            throw new BadRequestException(
                    "This parcel's journey is already finished. If something is wrong with it, "
                            + "that is a new case rather than a new event on this one.");
        }

        switch (next) {
            case COLLECTED -> {
                if (collected) {
                    throw new BadRequestException("This parcel has already been collected.");
                }
            }
            case ARRIVED_AT_ORIGIN -> {
                if (collected) {
                    throw new BadRequestException(
                            "You have already collected this parcel, so you cannot arrive at the "
                                    + "shop for it again.");
                }
            }
            case DEPOSITED, RELEASED, TRANSFERRED, FAILED_ATTEMPT, REDISPATCHED -> {
                if (!collected) {
                    // The hole C4 exists to close: a parcel reaching the end of
                    // the chain without anybody having handed it over at the start.
                    throw new BadRequestException(
                            "Nobody has collected this parcel yet, so it cannot be "
                                    + describe(next) + ". Collect it from the shop first.");
                }
            }
            default -> { }
        }
    }

    private static String describe(CustodyEventType type) {
        return switch (type) {
            case DEPOSITED -> "left at a pickup point";
            case RELEASED -> "delivered";
            case TRANSFERRED -> "handed to another driver";
            case FAILED_ATTEMPT -> "recorded as a failed attempt";
            case REDISPATCHED -> "sent onward";
            case RETURNED -> "returned";
            case COLLECTED -> "collected";
            case ARRIVED_AT_ORIGIN -> "marked as arrived";
        };
    }

    /**
     * Refuses a device clock that cannot be right.
     *
     * <p>Believed within limits rather than trusted or ignored. The whole point
     * of {@code occurredAt} is that it is the driver's clock — an offline
     * capture has no other — so the server's job is to bound it, not to
     * overwrite it with a time the event did not happen at.
     */
    private static void requireSaneClock(CustodyEvent event) {
        LocalDateTime when = event.getOccurredAt();
        if (when == null) {
            throw new BadRequestException("An event must say when it happened.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (when.isAfter(now.plus(MAX_CLOCK_AHEAD))) {
            throw new BadRequestException(
                    "That event is timed in the future, which usually means the phone's clock is "
                            + "wrong. Check the date and time on the device.");
        }
        if (when.isBefore(now.minus(MAX_BACKDATE))) {
            throw new BadRequestException(
                    "That event is more than " + MAX_BACKDATE.toDays() + " days old. Anything that "
                            + "far back has to be sorted out by support rather than uploaded.");
        }
    }
}
