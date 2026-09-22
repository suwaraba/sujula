package com.sujula.service.shipment;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.RecipientInstructionType;
import com.sujula.model.shipment.RecipientInstruction;
import com.sujula.model.shipment.Shipment;
import com.sujula.repository.shipment.RecipientInstructionRepository;
import com.sujula.repository.shipment.ShipmentRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The only thing that changes what a parcel has been told to do.
 *
 * <p>{@code CustodyChain}'s sibling, and deliberately the same shape. There the
 * events are the record and the status is the consequence; here the instructions
 * are the record and the five directive fields on {@link Shipment} are the
 * consequence. Both re-derive rather than patch, so neither summary can come to
 * disagree with what justifies it.
 *
 * <p>The reason it matters here rather than being a convenience: one of these
 * instructions — safe drop — replaces the recipient standing at the door with a
 * recorded authorisation. If that were a boolean anybody could set, the custody
 * chain would have a hole exactly the shape of C4: a parcel that reached
 * DELIVERED because a field said it was allowed to, rather than because somebody
 * who held the code said so, at a time, in words.
 */
@Slf4j
@Component
public class RecipientDirectives {

    /**
     * How far out a recipient may push a delivery.
     *
     * <p>Long enough for somebody travelling upcountry for a funeral, short
     * enough that a parcel is not being stored indefinitely at a driver's
     * expense. Past this the answer is a pickup point, which is what the counter
     * network is for.
     */
    private static final int MAX_RESCHEDULE_DAYS = 21;

    /** No point accepting a window that has already begun to pass. */
    private static final int MIN_RESCHEDULE_MINUTES = 30;

    private final RecipientInstructionRepository instructions;
    private final ShipmentRepository shipments;

    public RecipientDirectives(RecipientInstructionRepository instructions,
                               ShipmentRepository shipments) {
        this.instructions = instructions;
        this.shipments = shipments;
    }

    /**
     * Records an instruction and re-derives everything that follows from it.
     *
     * <p>The single door. The instruction is checked against where the parcel
     * actually is, any earlier instruction of the same kind is marked superseded
     * rather than edited, the new row is saved, and the shipment's directives are
     * recomputed from the whole record.
     */
    @Transactional
    public RecipientInstruction record(Shipment shipment, RecipientInstruction instruction) {
        requireParcelCanStillBeRedirected(shipment, instruction.getType());
        validate(shipment, instruction);

        LocalDateTime now = LocalDateTime.now();
        instructions.findInForceOfType(shipment.getId(), instruction.getType())
                .ifPresent(previous -> {
                    previous.setSupersededAt(now);
                    instructions.save(previous);
                });

        instruction.setShipment(shipment);
        RecipientInstruction saved = instructions.save(instruction);

        rederive(shipment);

        log.info("[Recipient] {} recorded on parcel {} — {}",
                saved.getType(), shipment.getReference(), saved.getSummary());
        return saved;
    }

    /**
     * Withdraws the instruction of a kind currently in force, if any.
     *
     * <p>Supersedes rather than deletes: a safe-drop authorisation that was live
     * for an hour is a thing that happened, and the row is how anybody later
     * works out whether the driver was acting on it at the time.
     *
     * @return whether there was anything to withdraw
     */
    @Transactional
    public boolean withdraw(Shipment shipment, RecipientInstructionType type) {
        return instructions.findInForceOfType(shipment.getId(), type)
                .map(previous -> {
                    previous.setSupersededAt(LocalDateTime.now());
                    instructions.save(previous);
                    rederive(shipment);
                    log.info("[Recipient] {} withdrawn on parcel {}", type, shipment.getReference());
                    return true;
                })
                .orElse(false);
    }

    /**
     * Recomputes a parcel's directives from its whole instruction record.
     *
     * <p>Public so a repair job or a test can re-run it over existing rows and
     * assert it changes nothing. If it ever does, the summary had drifted and the
     * instructions were right.
     */
    @Transactional
    public void rederive(Shipment shipment) {
        List<RecipientInstruction> inForce = instructions.findInForce(shipment.getId());

        RecipientInstruction pickup = latestOf(inForce, RecipientInstructionType.CHOOSE_PICKUP_POINT);
        RecipientInstruction reschedule = latestOf(inForce, RecipientInstructionType.RESCHEDULE);
        RecipientInstruction safeDrop = latestOf(inForce, RecipientInstructionType.AUTHORISE_SAFE_DROP);

        shipment.applyDerivedDirectives(
                pickup == null ? null : pickup.getPickupPoint(),
                reschedule == null ? null : reschedule.getWindowFrom(),
                reschedule == null ? null : reschedule.getWindowUntil(),
                safeDrop != null,
                safeDrop == null ? null : safeDrop.getSafeDropLocation(),
                safeDrop == null ? null : safeDrop.getSafeDropPerson());
        shipments.save(shipment);
    }

    private static RecipientInstruction latestOf(List<RecipientInstruction> inForce,
                                                 RecipientInstructionType type) {
        RecipientInstruction found = null;
        for (RecipientInstruction candidate : inForce) {
            if (candidate.getType() == type) {
                found = candidate;   // ordered oldest first, so the last one wins
            }
        }
        return found;
    }

    // ── What may still be asked for ──────────────────────────────────────────

    /**
     * Refuses an instruction the parcel is past acting on.
     *
     * <p>Checked against the custody chain's answer rather than against the
     * directives, because the directives are what is being derived. A parcel that
     * has been handed over cannot be redirected by anybody, and saying so plainly
     * is better than accepting an instruction nothing will ever read.
     */
    private static void requireParcelCanStillBeRedirected(Shipment shipment,
                                                          RecipientInstructionType type) {
        if (shipment.getStatus() != null && shipment.getStatus().isFinished()) {
            throw new BadRequestException(switch (shipment.getStatus()) {
                case DELIVERED -> "This parcel has already been handed over, so there is nothing "
                        + "left to redirect. If something is wrong with what arrived, open a case "
                        + "about the order instead.";
                case RETURNED -> "This parcel has gone back to the seller. Contact them about "
                        + "sending it again.";
                default -> "This parcel was cancelled, so it is not going anywhere.";
            });
        }

        if (shipment.getHeldAtPickupPoint() != null) {
            // It is on a shelf with a code against it. Redirecting it from here
            // is a second delivery leg somebody has to be paid for, not a
            // preference — and a safe drop at a counter means nothing at all.
            throw new BadRequestException(
                    "This parcel is already waiting for you at " 
                            + shipment.getHeldAtPickupPoint().getName()
                            + ". Bring the collection code and they will hand it over. If you "
                            + "cannot get there, contact support rather than changing it here.");
        }
    }

    private static void validate(Shipment shipment, RecipientInstruction instruction) {
        switch (instruction.getType()) {
            case CHOOSE_PICKUP_POINT -> {
                if (instruction.getPickupPoint() == null) {
                    throw new BadRequestException("Choose a collection point.");
                }
            }
            case RESCHEDULE -> requireSaneWindow(instruction);
            case AUTHORISE_SAFE_DROP -> {
                boolean somewhere = notBlank(instruction.getSafeDropLocation());
                boolean somebody = notBlank(instruction.getSafeDropPerson());
                if (!somewhere && !somebody) {
                    // "Leave it if I am out" is not an instruction a driver can
                    // act on, and a parcel left on the strength of one is a
                    // parcel nobody can account for.
                    throw new BadRequestException(
                            "Say where it should be left, or who may take it. A driver cannot act "
                                    + "on \"leave it somewhere\".");
                }
            }
        }
    }

    private static void requireSaneWindow(RecipientInstruction instruction) {
        LocalDateTime from = instruction.getWindowFrom();
        LocalDateTime until = instruction.getWindowUntil();
        if (from == null || until == null) {
            throw new BadRequestException("A new time needs a day and a window on it.");
        }
        if (!until.isAfter(from)) {
            throw new BadRequestException("The window has to end after it starts.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (from.isBefore(now.plusMinutes(MIN_RESCHEDULE_MINUTES))) {
            throw new BadRequestException(
                    "Choose a time at least half an hour from now — the driver needs long enough "
                            + "to see it.");
        }
        if (from.isAfter(now.plusDays(MAX_RESCHEDULE_DAYS))) {
            throw new BadRequestException(
                    "That is more than " + MAX_RESCHEDULE_DAYS + " days away. For anything that "
                            + "far off, send it to a collection point instead — they will hold it "
                            + "for you.");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
