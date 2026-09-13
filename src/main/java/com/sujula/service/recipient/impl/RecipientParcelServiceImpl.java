package com.sujula.service.recipient.impl;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.recipient.RecipientRequests;
import com.sujula.dto.response.recipient.RecipientResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.RecipientInstructionType;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.order.Order;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.ParcelAccessCode;
import com.sujula.model.shipment.RecipientInstruction;
import com.sujula.model.shipment.Shipment;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ParcelAccessCodeRepository;
import com.sujula.repository.shipment.RecipientInstructionRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.service.EmailService;
import com.sujula.service.recipient.RecipientParcelService;
import com.sujula.service.shipment.RecipientDirectives;

import lombok.extern.slf4j.Slf4j;

/**
 * The page and the three decisions, for somebody with no account.
 *
 * <p>Everything in here is written against one reader: a woman in Serrekunda
 * holding a forwarded message on a phone that is not hers, on a connection that
 * drops. So the page is small, the phrases are fixed, and every refusal says
 * what to do instead — telephoning support across an ocean is the failure mode
 * this surface exists to avoid.
 */
@Slf4j
@Service
public class RecipientParcelServiceImpl implements RecipientParcelService {

    /**
     * How long the six digits last.
     *
     * <p>Short, because it is an access credential rather than a delivery proof
     * and it is being read out over the telephone from another continent. Long
     * enough to choose a counter, look at where it is, and change your mind
     * once.
     */
    private static final Duration ACCESS_CODE_LIFETIME = Duration.ofMinutes(15);

    /** Wrong guesses before the code is dead and a new one has to be asked for. */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** How many codes may be asked for, and over what window. */
    private static final Duration REQUEST_WINDOW = Duration.ofHours(1);
    private static final int MAX_REQUESTS_PER_WINDOW = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShipmentRepository shipments;
    private final CustodyEventRepository events;
    private final ParcelAccessCodeRepository accessCodes;
    private final RecipientInstructionRepository instructions;
    private final PickupPointRepository pickupPoints;
    private final RecipientDirectives directives;
    private final EmailService email;

    public RecipientParcelServiceImpl(ShipmentRepository shipments, CustodyEventRepository events,
                                      ParcelAccessCodeRepository accessCodes,
                                      RecipientInstructionRepository instructions,
                                      PickupPointRepository pickupPoints,
                                      RecipientDirectives directives, EmailService email) {
        this.shipments = shipments;
        this.events = events;
        this.accessCodes = accessCodes;
        this.instructions = instructions;
        this.pickupPoints = pickupPoints;
        this.directives = directives;
        this.email = email;
    }

    // ── The page ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public RecipientResponses.Parcel parcel(String trackingCode) {
        Shipment shipment = requireParcel(trackingCode);
        return view(shipment);
    }

    /**
     * The whole page, built from what is safe to publish.
     *
     * <p>The omissions are the design. No surname, because a first name is
     * enough for the recipient to recognise her own parcel and not enough for a
     * stranger to use. No street, because a street names a home. No phone
     * number, no order number, no prices, no seller — each of those would turn a
     * forwarded link into a way of learning something about the household.
     */
    private RecipientResponses.Parcel view(Shipment shipment) {
        List<CustodyEvent> chain = events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId());

        List<RecipientResponses.Step> history = new ArrayList<>();
        LocalDateTime lastUpdate = shipment.getUpdatedAt();
        for (CustodyEvent event : chain) {
            history.add(new RecipientResponses.Step(
                    stageOf(event.getType()),
                    // Written here rather than taken from the driver's note. A
                    // note is free text typed by somebody else, and free text on
                    // a page anybody can open is how a name gets published.
                    describe(event.getType()),
                    event.getOccurredAt()));
            if (event.getOccurredAt() != null
                    && (lastUpdate == null || event.getOccurredAt().isAfter(lastUpdate))) {
                lastUpdate = event.getOccurredAt();
            }
        }

        PickupPoint counter = shipment.getHeldAtPickupPoint() != null
                ? shipment.getHeldAtPickupPoint()
                : shipment.getRequestedPickupPoint();

        return new RecipientResponses.Parcel(
                shipment.getTrackingCode(),
                shipment.getStatus() == null ? null : shipment.getStatus().name(),
                describe(shipment.getStatus()),
                firstNameOf(shipment.getRecipientName()),
                shipment.getDestinationCity(),
                shipment.getDestinationCountry(),
                shipment.getParcelCount() == null ? 1 : shipment.getParcelCount(),
                shipment.getRequestedWindowFrom(),
                shipment.getRequestedWindowUntil(),
                lastUpdate,
                counterView(counter, shipment),
                standingOf(shipment),
                actionsFor(shipment),
                history);
    }

    private static RecipientResponses.Counter counterView(PickupPoint point, Shipment shipment) {
        if (point == null) {
            return null;
        }
        // A pickup point is a shop with a sign over the door and hours on a
        // website; publishing its address tells nobody anything they could not
        // read walking past. The recipient's own street is a different thing
        // entirely, which is why it is nowhere on this page.
        return new RecipientResponses.Counter(
                point.getId(), point.getName(), point.getAddressStreet(), point.getCity(),
                point.getOpeningHours(), point.getContactPhone(),
                point.getLatitude(), point.getLongitude(),
                shipment.getHeldAtPickupPoint() != null ? shipment.getStorageDeadline() : null);
    }

    private RecipientResponses.Standing standingOf(Shipment shipment) {
        List<RecipientInstruction> inForce = instructions.findInForce(shipment.getId());
        LocalDateTime lastChanged = inForce.stream()
                .map(RecipientInstruction::getVerifiedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        PickupPoint chosen = shipment.getRequestedPickupPoint();
        return new RecipientResponses.Standing(
                chosen == null ? null : chosen.getId(),
                chosen == null ? null : chosen.getName(),
                shipment.getRequestedWindowFrom(),
                shipment.getRequestedWindowUntil(),
                shipment.isSafeDropAuthorised(),
                shipment.getSafeDropLocation(),
                shipment.getSafeDropPerson(),
                lastChanged);
    }

    /**
     * Which buttons the page shows, and why the missing ones are missing.
     *
     * <p>Computed rather than left to the client. A page that offers a choice and
     * then refuses it has wasted the one interaction somebody on a bad connection
     * was going to get.
     */
    private static RecipientResponses.Actions actionsFor(Shipment shipment) {
        ShipmentStatus status = shipment.getStatus();
        if (status != null && status.isFinished()) {
            return new RecipientResponses.Actions(false, false, false, switch (status) {
                case DELIVERED -> "This parcel has been handed over.";
                case RETURNED -> "This parcel has gone back to the seller.";
                default -> "This parcel was cancelled.";
            });
        }
        if (shipment.getHeldAtPickupPoint() != null) {
            return new RecipientResponses.Actions(false, false, false,
                    "It is waiting for you at " + shipment.getHeldAtPickupPoint().getName()
                            + ". Bring the collection code.");
        }
        return new RecipientResponses.Actions(true, true, true, null);
    }

    // ── The code ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public RecipientResponses.CodeSent requestCode(String trackingCode) {
        Shipment shipment = requireParcel(trackingCode);

        if (shipment.getStatus() != null && shipment.getStatus().isFinished()) {
            throw new BadRequestException(
                    "There is nothing left to change about this parcel, so there is no code to "
                            + "send.");
        }

        LocalDateTime now = LocalDateTime.now();
        long recent = accessCodes.countSince(shipment.getId(), now.minus(REQUEST_WINDOW));
        if (recent >= MAX_REQUESTS_PER_WINDOW) {
            // The limit is on the parcel rather than on whoever is asking,
            // because whoever is asking is anonymous by design. Somebody who
            // found a tracking code must not be able to have the buyer's inbox
            // filled by holding down a button.
            throw new BadRequestException(
                    "A code has already been sent " + recent + " times in the last hour. Wait for "
                            + "it to arrive, or contact support if it has not.");
        }

        // Any earlier code dies. Two live codes for one parcel is two chances
        // for the wrong one to be read out over a bad line.
        for (ParcelAccessCode old : accessCodes.findLive(shipment.getId(), now)) {
            old.setInvalidatedAt(now);
            accessCodes.save(old);
        }

        // Where it goes is read from the order, never from the request. This
        // method takes a tracking code and nothing else, so there is no way to
        // ask for somebody else's code to be sent to you.
        String destination = contactOnFile(shipment);
        String masked = mask(destination);

        ParcelAccessCode code = accessCodes.save(ParcelAccessCode.builder()
                .shipment(shipment)
                .code(sixDigits())
                .expiresAt(now.plus(ACCESS_CODE_LIFETIME))
                .sentTo(masked)
                .build());

        boolean sent = false;
        if (destination != null) {
            try {
                email.sendParcelAccessCode(destination, buyerName(shipment),
                        firstNameOf(shipment.getRecipientName()), shipment.getTrackingCode(),
                        code.getCode(), code.getExpiresAt());
                sent = true;
            } catch (RuntimeException failed) {
                // The code exists either way. A send that failed is a support
                // problem rather than a reason to leave somebody unable to
                // redirect her own parcel.
                log.warn("[Recipient] Could not send the access code for parcel {}: {}",
                        shipment.getReference(), failed.toString());
            }
        }

        log.info("[Recipient] Access code issued for parcel {} and {}",
                shipment.getReference(), sent ? "sent to " + masked : "NOT sent");

        return new RecipientResponses.CodeSent(sent, masked, code.getExpiresAt(),
                (int) Math.max(0, MAX_REQUESTS_PER_WINDOW - recent - 1),
                sent
                        ? "We have sent a six-digit code to the person who ordered this parcel. "
                          + "Ask them to read it to you, then enter it here."
                        : "We could not reach the person who ordered this parcel. Contact support "
                          + "and quote the code in your link.");
    }

    /**
     * Who gets told the six digits.
     *
     * <p>The buyer, not the recipient — which reads backwards until you remember
     * who these two people are. She may have no account, no app and no email;
     * he paid for the parcel from Madrid and has all three. He reads her the
     * number the way anybody reads out a Western Union reference, and that is a
     * thing families on this route already do every week.
     */
    private static String contactOnFile(Shipment shipment) {
        Order order = orderOf(shipment);
        if (order == null) {
            return null;
        }
        if (order.getCustomer() != null && order.getCustomer().getEmail() != null) {
            return order.getCustomer().getEmail();
        }
        return order.getGuestEmail();
    }

    // ── The three instructions ───────────────────────────────────────────────

    @Override
    @Transactional
    public RecipientResponses.InstructionRecorded choosePickupPoint(
            String trackingCode, RecipientRequests.ChoosePickupPoint request) {
        Shipment shipment = requireParcel(trackingCode);
        ParcelAccessCode code = requireCode(shipment, request.code());

        PickupPoint point = pickupPoints.findById(request.pickupPointId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Pickup point", request.pickupPointId()));

        requireUsableCounter(shipment, point);

        RecipientInstruction recorded = directives.record(shipment, RecipientInstruction.builder()
                .type(RecipientInstructionType.CHOOSE_PICKUP_POINT)
                .pickupPoint(point)
                .verifiedByCodeId(code.getId())
                .verifiedAt(LocalDateTime.now())
                .summary("Collect from " + point.getName() + ", " + point.getCity())
                .build());

        // A parcel going to a counter cannot also be left behind a shop: the
        // counter releases it against a code, and an authorisation that nobody
        // will ever read is worse than none, because it is still on the record
        // as permission.
        if (directives.withdraw(shipment, RecipientInstructionType.AUTHORISE_SAFE_DROP)) {
            log.info("[Recipient] Safe drop withdrawn on parcel {} — it is going to a counter",
                    shipment.getReference());
        }

        spend(code);
        return recordedView(shipment, recorded,
                "The driver will take it to " + point.getName() + ". Bring the collection code "
                        + "when you go — they will not hand it over without it.");
    }

    @Override
    @Transactional
    public RecipientResponses.InstructionRecorded reschedule(
            String trackingCode, RecipientRequests.Reschedule request) {
        Shipment shipment = requireParcel(trackingCode);
        ParcelAccessCode code = requireCode(shipment, request.code());

        RecipientInstruction recorded = directives.record(shipment, RecipientInstruction.builder()
                .type(RecipientInstructionType.RESCHEDULE)
                .windowFrom(request.from())
                .windowUntil(request.until())
                .verifiedByCodeId(code.getId())
                .verifiedAt(LocalDateTime.now())
                .summary(request.note() == null || request.note().isBlank()
                        ? "Deliver between " + request.from() + " and " + request.until()
                        : "Deliver between " + request.from() + " and " + request.until()
                          + " — " + request.note())
                .build());

        spend(code);
        return recordedView(shipment, recorded,
                "The driver will come in that window instead. If nobody is there, they will try "
                        + "again or leave it at a collection point.");
    }

    @Override
    @Transactional
    public RecipientResponses.InstructionRecorded authoriseSafeDrop(
            String trackingCode, RecipientRequests.AuthoriseSafeDrop request) {
        Shipment shipment = requireParcel(trackingCode);
        ParcelAccessCode code = requireCode(shipment, request.code());

        if (!request.isGranting()) {
            boolean withdrawn = directives.withdraw(
                    shipment, RecipientInstructionType.AUTHORISE_SAFE_DROP);
            spend(code);
            return new RecipientResponses.InstructionRecorded(
                    shipment.getTrackingCode(), "AUTHORISE_SAFE_DROP_WITHDRAWN",
                    LocalDateTime.now(), standingOf(shipment),
                    withdrawn
                            ? "The driver will hand it to you and nobody else. You will need to be "
                              + "there, with the delivery code."
                            : "There was no permission to withdraw — the driver was already going "
                              + "to hand it to you directly.");
        }

        RecipientInstruction recorded = directives.record(shipment, RecipientInstruction.builder()
                .type(RecipientInstructionType.AUTHORISE_SAFE_DROP)
                .safeDropLocation(blankToNull(request.location()))
                .safeDropPerson(blankToNull(request.person()))
                .verifiedByCodeId(code.getId())
                .verifiedAt(LocalDateTime.now())
                .summary(safeDropSummary(request))
                .build());

        spend(code);
        return recordedView(shipment, recorded,
                "The driver may leave it as you have said. Once they do, the parcel counts as "
                        + "delivered and the seller is paid — so this cannot be undone afterwards.");
    }

    private static String safeDropSummary(RecipientRequests.AuthoriseSafeDrop request) {
        boolean somewhere = request.location() != null && !request.location().isBlank();
        boolean somebody = request.person() != null && !request.person().isBlank();
        if (somewhere && somebody) {
            return "Leave with " + request.person() + " at " + request.location();
        }
        return somebody ? "Leave with " + request.person() : "Leave at " + request.location();
    }

    private RecipientResponses.InstructionRecorded recordedView(
            Shipment shipment, RecipientInstruction recorded, String message) {
        return new RecipientResponses.InstructionRecorded(
                shipment.getTrackingCode(), recorded.getType().name(), recorded.getVerifiedAt(),
                standingOf(shipment), message);
    }

    // ── C1: a counter is a delivery answer ───────────────────────────────────

    /**
     * Refuses a counter the parcel cannot actually reach.
     *
     * <p>Checked against the parcel's destination country, which is where the
     * goods are going — never against anything about the person who paid. A
     * buyer in Madrid redirecting his sister's parcel must not be offered a
     * counter in Madrid, and a counter is chosen against the delivery context or
     * it is chosen against nothing.
     */
    private static void requireUsableCounter(Shipment shipment, PickupPoint point) {
        if (!point.isActive() || point.getStatus() != com.sujula.model.constant.PartnerStatus.APPROVED) {
            throw new BadRequestException(
                    point.getName() + " is not taking parcels at the moment. Choose another "
                            + "collection point.");
        }
        if (point.isTemporarilyClosed()) {
            throw new BadRequestException(
                    point.getName() + " is closed until " + point.getClosedUntil()
                            + ". Choose another collection point.");
        }
        if (!point.hasSpace()) {
            throw new BadRequestException(
                    point.getName() + " is full. Choose another collection point — there is "
                            + "usually one nearby.");
        }
        String destination = shipment.getDestinationCountry();
        if (destination != null && point.getCountryCode() != null
                && !destination.equalsIgnoreCase(point.getCountryCode())) {
            throw new BadRequestException(
                    "That collection point is in " + point.getCountryCode() + " and this parcel is "
                            + "going to " + destination + ". Choose one in the country the parcel "
                            + "is being delivered to.");
        }
    }

    // ── Scoping and the code check ───────────────────────────────────────────

    /**
     * The parcel this code names, or nothing.
     *
     * <p>Not-found for a code that does not match, always. A different answer for
     * "wrong code" and "code for a parcel you may not see" would turn this into a
     * way of confirming that a guessed code is live.
     */
    private Shipment requireParcel(String trackingCode) {
        return shipments.findByTrackingCode(trackingCode)
                .orElseThrow(() -> new ResourceNotFoundException("Parcel", trackingCode));
    }

    /**
     * Checks the six digits, and counts the wrong ones.
     *
     * <p>A miss is recorded against every live code for the parcel, because the
     * thing being defended against is somebody working through numbers rather
     * than somebody mistyping a particular one. Five wrong and the code is dead;
     * asking for another is one tap, and asking for a fourth in an hour is not.
     */
    private ParcelAccessCode requireCode(Shipment shipment, String presented) {
        LocalDateTime now = LocalDateTime.now();
        Optional<ParcelAccessCode> match = accessCodes.findPresented(
                shipment.getId(), presented, now, MAX_FAILED_ATTEMPTS);
        if (match.isPresent()) {
            return match.get();
        }

        List<ParcelAccessCode> live = accessCodes.findLive(shipment.getId(), now);
        for (ParcelAccessCode code : live) {
            code.setFailedAttempts(code.getFailedAttempts() + 1);
            if (code.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                code.setInvalidatedAt(now);
            }
            accessCodes.save(code);
        }

        if (!live.isEmpty()) {
            boolean allBurned = live.stream().allMatch(code -> code.getInvalidatedAt() != null);
            throw new BadRequestException(allBurned
                    ? burnedMessage()
                    : "That is not the right code. Check the six digits and try again.");
        }

        // Nothing live. Two very different reasons for that, and telling
        // somebody who has just mistyped five times that their code "expired"
        // sends them away to wait when what they should do is ask for another.
        if (!accessCodes.findBurned(shipment.getId(), MAX_FAILED_ATTEMPTS, now).isEmpty()) {
            throw new BadRequestException(burnedMessage());
        }
        throw new BadRequestException(
                "That code has expired, or none was ever sent. Ask for a new one — it lasts "
                        + ACCESS_CODE_LIFETIME.toMinutes() + " minutes.");
    }

    private static String burnedMessage() {
        return "That code has been entered wrongly too many times and no longer works. Ask for a "
                + "new one — it takes a moment and the old one is dead either way.";
    }

    /** Records that a code did something, without killing it. */
    private void spend(ParcelAccessCode code) {
        code.setLastUsedAt(LocalDateTime.now());
        code.setInstructionsGiven(code.getInstructionsGiven() + 1);
        accessCodes.save(code);
    }

    // ── Phrasing, fixed rather than typed ────────────────────────────────────

    private static String stageOf(CustodyEventType type) {
        return switch (type) {
            case ARRIVED_AT_ORIGIN -> "AT_ORIGIN";
            case COLLECTED, REDISPATCHED, TRANSFERRED -> "IN_TRANSIT";
            case DEPOSITED -> "AT_PICKUP_POINT";
            case RELEASED -> "DELIVERED";
            case RETURNED -> "RETURNED";
            case FAILED_ATTEMPT -> "ATTEMPT_FAILED";
        };
    }

    private static String describe(CustodyEventType type) {
        return switch (type) {
            case ARRIVED_AT_ORIGIN -> "The driver reached the shop.";
            case COLLECTED -> "Collected from the shop.";
            case DEPOSITED -> "Left at a collection point.";
            case REDISPATCHED -> "Sent onward.";
            case RELEASED -> "Handed over.";
            case TRANSFERRED -> "Passed to another driver.";
            case RETURNED -> "Sent back to the seller.";
            case FAILED_ATTEMPT -> "A delivery attempt did not succeed.";
        };
    }

    private static String describe(ShipmentStatus status) {
        if (status == null) {
            return "Being prepared.";
        }
        return switch (status) {
            case AWAITING_COLLECTION -> "Packed, waiting for a driver.";
            case DRIVER_OFFERED, DRIVER_ASSIGNED -> "A driver is on the way to the shop.";
            case AT_ORIGIN -> "The driver is at the shop.";
            case IN_TRANSIT -> "On its way.";
            case AT_PICKUP_POINT -> "Ready to collect.";
            case OUT_FOR_DELIVERY -> "Out for delivery today.";
            case ATTEMPT_FAILED -> "Nobody was there. The driver will try again.";
            case DELIVERED -> "Handed over.";
            case RETURNED -> "Gone back to the seller.";
            case CANCELLED -> "Cancelled.";
        };
    }

    // ── Small things ─────────────────────────────────────────────────────────

    /**
     * The first name and no more.
     *
     * <p>Enough for the person waiting to know the parcel is hers; not enough for
     * somebody who found the link to put a household to a name.
     */
    private static String firstNameOf(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        String trimmed = fullName.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /** Enough of an address to confirm where it went, not enough to read it out. */
    private static String mask(String address) {
        if (address == null || !address.contains("@")) {
            return null;
        }
        String name = address.substring(0, address.indexOf('@'));
        String domain = address.substring(address.indexOf('@'));
        return (name.length() <= 2 ? name.charAt(0) + "•" : name.charAt(0) + "•••"
                + name.charAt(name.length() - 1)) + domain;
    }

    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static Order orderOf(Shipment shipment) {
        return shipment.getVendorOrder() == null ? null : shipment.getVendorOrder().getOrder();
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
