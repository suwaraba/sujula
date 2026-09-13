package com.sujula.service.recipient;

import com.sujula.dto.request.recipient.RecipientRequests;
import com.sujula.dto.response.recipient.RecipientResponses;

/**
 * The surface for the person the parcel is actually for.
 *
 * <p>This is C5 with nothing else in the way. Every other actor on this platform
 * signs in: the buyer has an account, the seller has a store, the driver has an
 * app, the operator has a counter. The recipient has a phone number and a
 * message somebody forwarded her, and she is the one who has to be at home when
 * the driver arrives — so if she cannot change where the parcel goes without an
 * account, the system has put the decision in the hands of the only person who
 * is not there.
 *
 * <p>Two credentials, doing two different jobs:
 *
 * <ul>
 *   <li>The <b>tracking code</b> in the link. It reads the page, and nothing
 *       else. Bounded to what is safe for whoever ends up holding it, because a
 *       forwarded message goes further than the person who sent it intended.</li>
 *   <li>The <b>access code</b>, six digits, asked for on the page and delivered
 *       to the contact on the order. It authorises instructions. It is not the
 *       delivery code — see {@code ParcelAccessCode} for why those must never be
 *       the same number.</li>
 * </ul>
 *
 * <p>Nothing here moves a parcel. Every method that changes something writes a
 * {@code RecipientInstruction} through {@code RecipientDirectives}; custody
 * stays where it is until somebody hands the box to somebody else.
 */
public interface RecipientParcelService {

    /** The page. Open, and carries no more than a first name and a town. */
    RecipientResponses.Parcel parcel(String trackingCode);

    /**
     * Asks for the six digits.
     *
     * <p>Takes no destination and has nowhere to put one. Where it goes is the
     * contact on the order, read by the server — which is the whole security
     * property, because an endpoint that accepted an address would send somebody
     * else's code wherever it was told.
     */
    RecipientResponses.CodeSent requestCode(String trackingCode);

    /** Send it to a counter instead of the door. */
    RecipientResponses.InstructionRecorded choosePickupPoint(
            String trackingCode, RecipientRequests.ChoosePickupPoint request);

    /** Come on a different day. */
    RecipientResponses.InstructionRecorded reschedule(
            String trackingCode, RecipientRequests.Reschedule request);

    /**
     * Leave it with the neighbour.
     *
     * <p>The one instruction that changes what counts as proof of delivery, so
     * it is recorded with the code that was presented and the words that were
     * used, and it can be withdrawn.
     */
    RecipientResponses.InstructionRecorded authoriseSafeDrop(
            String trackingCode, RecipientRequests.AuthoriseSafeDrop request);
}
