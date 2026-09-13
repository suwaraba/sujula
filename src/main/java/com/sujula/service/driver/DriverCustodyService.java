package com.sujula.service.driver;

import com.sujula.dto.request.driver.DriverRequests;
import com.sujula.dto.response.driver.DriverResponses;

/**
 * What a driver does to a parcel, and what they are allowed to know about it.
 *
 * <p>Every method that moves a parcel goes through {@code CustodyChain}, which
 * is the only thing that can change where a parcel is. Nothing here sets a
 * status; they record that something happened, with its proof, and the status
 * follows.
 *
 * <p><strong>The reads are the privacy surface.</strong> A recipient's address
 * and phone number are visible only while the driver is actually carrying the
 * goods. Before they accept, a driver gets a town; after they hand over, it goes
 * away. The recipient frequently has no account and never agreed to anything
 * (C5), so the only justification for a driver holding her address is that they
 * are on their way to it.
 */
public interface DriverCustodyService {

    /** One parcel, with as much of the destination as the driver currently needs. */
    DriverResponses.ShipmentDetail shipment(Long userId, Long shipmentId);

    /** At the shop. Nothing has changed hands, so no code — but the position is kept. */
    DriverResponses.CustodyRecorded arrivedAtOrigin(Long userId, Long shipmentId,
                                                    DriverRequests.Arrived request);

    /** The seller handed it over, and the driver presented the release code. */
    DriverResponses.CustodyRecorded collect(Long userId, Long shipmentId,
                                            DriverRequests.Handover request);

    /** Left at a hub, which acknowledged it with its own code. */
    DriverResponses.CustodyRecorded depositAtPickup(Long userId, Long shipmentId,
                                                    DriverRequests.Handover request);

    /**
     * The recipient has it.
     *
     * <p>The end of the chain and the only event that releases the seller's
     * money, so it asks for the most: the recipient's code, a position, and a
     * photograph. This is the link somebody would forge if any one of those were
     * enough alone.
     */
    DriverResponses.CustodyRecorded deliver(Long userId, Long shipmentId,
                                            DriverRequests.Handover request);

    /** An attempt that did not work. Custody does not move — the driver still has it. */
    DriverResponses.AttemptFailed deliveryFailed(Long userId, Long shipmentId,
                                                 DriverRequests.DeliveryFailed request);

    /**
     * Sends the recipient's code to the person who paid, to pass on.
     *
     * <p>Not to the recipient: she may have no account, no app and no email,
     * which is the case this marketplace exists to serve. The buyer has all
     * three, and telling their sister a number is something people already do.
     * The driver never sees it — a driver who could read the code could mark a
     * parcel delivered without meeting anybody.
     */
    DriverResponses.RecipientCodeRequested requestRecipientCode(Long userId, Long shipmentId);

    /** Driver to driver, with both of them attesting. */
    DriverResponses.CustodyRecorded transfer(Long userId, Long shipmentId,
                                             DriverRequests.Transfer request);

    /**
     * A batch of events captured with no signal.
     *
     * <p>Deduplicated by the id the driver's app gave each one, so a phone that
     * uploads, loses signal before the reply and uploads again records each
     * event once.
     */
    DriverResponses.SyncResult sync(Long userId, DriverRequests.SyncBatch request);
}
