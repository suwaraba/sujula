package com.sujula.service.pickup;

import java.time.LocalDate;

import com.sujula.dto.request.pickup.PickupRequests;
import com.sujula.dto.response.pickup.PickupResponses;

/**
 * A counter that holds parcels for people.
 *
 * <p>Two audiences, and the split runs through the whole interface. The public
 * methods take no user at all and return a counter's address, hours and a
 * capacity <em>band</em> — what a shopper needs to choose where to collect. The
 * operator methods resolve the point from the authenticated user and carry
 * recipients' names, because handing a parcel over means checking who is in
 * front of you.
 *
 * <p>Everything that moves a parcel goes through {@code CustodyChain}. A pickup
 * point accepting a parcel is the receiving party verifying the giving party's
 * code — the same shape as every other link — and releasing one to a recipient
 * ends the chain exactly as a doorstep delivery does, which is why both produce
 * events rather than status changes (C4).
 */
public interface PickupPointService {

    // ── Public ───────────────────────────────────────────────────────────────

    /**
     * Counters near a place.
     *
     * <p>Open to anybody: a shopper chooses where to collect before they sign
     * in, and frequently before they have an account at all. Only approved,
     * active, open points are listed — sending somebody to a shuttered counter
     * is worse than showing them nothing.
     */
    PickupResponses.PublicPoints search(PickupRequests.NearbySearch request);

    /** One counter's hours and how full it is. Open, and carries no operator identity. */
    PickupResponses.PublicPoint publicPoint(Long id);

    // ── Becoming one ─────────────────────────────────────────────────────────

    PickupResponses.ApplicationSubmitted apply(Long userId, PickupRequests.Apply request);

    // ── Running one ──────────────────────────────────────────────────────────

    /** Every point this operator runs. A list, because a good one is asked to run a second. */
    PickupResponses.OperatorPoints myPoints(Long userId);

    /** Hours, capacity, storage window, and closing for a while. */
    PickupResponses.OperatorPoint updatePoint(Long userId, Long pointId,
                                              PickupRequests.UpdatePoint request);

    /** What is coming, what is on the shelf, and what has to go back. */
    PickupResponses.Parcels parcels(Long userId, Long pointId);

    // ── The counter ──────────────────────────────────────────────────────────

    /** Takes a parcel in from a driver, verifying their code, and gives it a shelf. */
    PickupResponses.ParcelAccepted accept(Long userId, Long pointId, Long shipmentId,
                                          PickupRequests.AcceptParcel request);

    /** Turns one away. Custody stays with the driver, which is the point of recording it. */
    PickupResponses.ParcelRejected reject(Long userId, Long pointId, Long shipmentId,
                                          PickupRequests.RejectParcel request);

    /**
     * Hands a parcel to the person it is for.
     *
     * <p>The end of the chain and the thing that releases the seller's money, so
     * it asks for both a code and a name. A code alone would let anybody who
     * overheard it collect; a name alone would let anybody who read the label.
     */
    PickupResponses.ParcelReleased release(Long userId, Long pointId, Long shipmentId,
                                           PickupRequests.ReleaseParcel request);

    /** Sends a parcel back once nobody has come for it. */
    PickupResponses.ParcelReturning returnToVendor(Long userId, Long pointId, Long shipmentId,
                                                   PickupRequests.ReturnParcel request);

    /**
     * Has the collection code sent again.
     *
     * <p>To the buyer, who passes it on — the recipient may have no account, no
     * app and no email (C5). The operator never sees it: one who could read it
     * could hand the parcel to whoever is standing there.
     */
    PickupResponses.CodeResent resendCode(Long userId, Long pointId, Long shipmentId);

    /** What the counter has earned, per currency and per parcel. */
    PickupResponses.Earnings earnings(Long userId, Long pointId, LocalDate from, LocalDate to);
}
