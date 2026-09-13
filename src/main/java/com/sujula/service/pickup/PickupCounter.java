package com.sujula.service.pickup;

import java.security.SecureRandom;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.shipment.Shipment;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.shipment.ShipmentRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * What is on a shelf, and where.
 *
 * <p>The same shape as the other ledgers here: a count nobody writes by hand.
 * {@code storedParcels} is <em>recounted</em> from the parcels actually held
 * rather than incremented and decremented, so it cannot drift — and what it
 * would drift into is a counter accepting parcels it has no room for, or
 * refusing ones it does.
 *
 * <p>Shelf codes are allocated here too. They are location labels rather than
 * credentials: anybody who can see one can already see the parcel, and what they
 * still cannot do is take it, because that needs the recipient's code.
 */
@Slf4j
@Component
public class PickupCounter {

    /**
     * Letters that cannot be misread on a handwritten label.
     *
     * <p>No I, O, S or Z — they are 1, 0, 5 and 2 in somebody's handwriting, and
     * a shelf code read wrong sends an operator to the wrong parcel in front of
     * a customer.
     */
    private static final String SHELF_LETTERS = "ABCDEFGHJKLMNPQRTUVWXY";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Attempts at a free code before falling back to something certainly unique. */
    private static final int SHELF_ATTEMPTS = 40;

    private final ShipmentRepository shipments;
    private final PickupPointRepository points;

    public PickupCounter(ShipmentRepository shipments, PickupPointRepository points) {
        this.shipments = shipments;
        this.points = points;
    }

    /**
     * Recounts what a point is holding and writes the figure down.
     *
     * <p>Called after anything that puts a parcel on a shelf or takes one off.
     * Recounting rather than adjusting is the whole point: the parcels are the
     * record and the number is a consequence, so the two cannot disagree.
     */
    @Transactional
    public int recount(PickupPoint point) {
        int counted = (int) shipments.countByHeldAtPickupPointId(point.getId());
        point.applyStoredCount(counted);
        points.save(point);
        return counted;
    }

    /**
     * A free shelf code at this point.
     *
     * <p>Short enough to write on a parcel in marker pen and read back across a
     * counter. Random rather than sequential, because sequential codes get
     * guessed at and, more practically, because two operators writing at once
     * would pick the same next number.
     */
    @Transactional(readOnly = true)
    public String allocateShelfCode(PickupPoint point, String requested) {
        if (requested != null && !requested.isBlank()) {
            String cleaned = requested.trim().toUpperCase(java.util.Locale.ROOT);
            if (shipments.existsByHeldAtPickupPointIdAndShelfCode(point.getId(), cleaned)) {
                throw new com.sujula.exceptions.BadRequestException(
                        "Shelf " + cleaned + " already has a parcel on it. Use another, or leave "
                                + "it blank and one will be chosen.");
            }
            return cleaned;
        }

        for (int attempt = 0; attempt < SHELF_ATTEMPTS; attempt++) {
            String candidate = randomShelfCode();
            if (!shipments.existsByHeldAtPickupPointIdAndShelfCode(point.getId(), candidate)) {
                return candidate;
            }
        }
        // A counter this full is a counter with a capacity problem, but the
        // parcel in the operator's hand still needs somewhere to go, so this
        // falls back to something certainly free rather than refusing.
        String fallback = "X" + System.currentTimeMillis() % 100000;
        log.warn("[Pickup] Point {} could not find a short shelf code in {} tries; used {}",
                point.getId(), SHELF_ATTEMPTS, fallback);
        return fallback;
    }

    /** Puts a parcel on the shelf and freezes what handling it pays. */
    @Transactional
    public void store(Shipment shipment, PickupPoint point, String shelfCode, LocalDateTime now) {
        shipment.setHeldAtPickupPoint(point);
        shipment.setShelfCode(shelfCode);
        shipment.setStoredAt(now);
        // Frozen from the point's window at the moment of acceptance. An
        // operator who later shortens their storage days must not retroactively
        // make somebody's parcel overdue.
        shipment.setStorageDeadline(now.plusDays(
                point.getStorageDays() == null ? 7 : point.getStorageDays()));
        shipment.setPickupCommission(point.getCommissionPerParcel());
        shipment.setPickupCommissionCurrency(point.getCommissionCurrency());
        shipments.save(shipment);
        recount(point);
    }

    /** Takes it off the shelf, keeping what it earned and where it sat. */
    @Transactional
    public void release(Shipment shipment, PickupPoint point) {
        // The shelf code and commission stay: they are the record of where this
        // parcel was and what handling it was worth, and a statement next month
        // needs both. Only the holding is cleared.
        shipment.setHeldAtPickupPoint(null);
        shipments.save(shipment);
        recount(point);
    }

    private static String randomShelfCode() {
        char letter = SHELF_LETTERS.charAt(RANDOM.nextInt(SHELF_LETTERS.length()));
        return letter + "-" + (RANDOM.nextInt(899) + 100);
    }
}
