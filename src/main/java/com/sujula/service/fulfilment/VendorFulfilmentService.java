package com.sujula.service.fulfilment;

import com.sujula.dto.request.fulfilment.FulfilmentRequests;
import com.sujula.dto.response.fulfilment.FulfilmentResponses;

/**
 * A seller working one slice of an order from acceptance to the shop door.
 *
 * <p>Everything here stops at the door. The seller accepts, packs, binds
 * handsets and hands over; what happens to the parcel afterwards is the custody
 * chain's business, and no method on this interface can say a parcel was
 * collected or delivered. That is C4 stated as a signature: the status a seller
 * reaches by packing is READY_FOR_PICKUP, and SHIPPED is what presenting the
 * release code produces. A seller who could set it directly could report a
 * parcel collected that is still on their shelf.
 *
 * <p>Scoping is by the authenticated user throughout, and the vendor id goes
 * into every query rather than being compared afterwards, so another seller's
 * slice is reported as not found rather than fetched and refused.
 */
public interface VendorFulfilmentService {

    /**
     * The seller commits to fulfilling this slice.
     *
     * <p>PENDING to PREPARING, and nothing else. Accepting twice is the same
     * answer as accepting once — a double-tapped button on a bad connection is
     * the normal case here, not an error.
     */
    FulfilmentResponses.Accepted accept(Long vendorUserId, Long vendorOrderId);

    /**
     * The seller cannot fulfil it, and says why.
     *
     * <p>Three things happen and they are not separable: the slice is cancelled,
     * the goods go back on the shelf through the ledger, and a refund is
     * <em>requested</em>. Only this seller's slice moves — another vendor's
     * lines on the same payment ship as though nothing happened (C3).
     */
    FulfilmentResponses.Rejected reject(Long vendorUserId, Long vendorOrderId,
                                        FulfilmentRequests.Reject request);

    /**
     * The parcel is packed and waiting for a driver.
     *
     * <p>Refused while any serialised line still has a handset to bind. That
     * refusal is the point of the endpoint: it is the last moment at which the
     * platform can still tell which physical phone is in the box, and a parcel
     * that leaves without that is a warranty claim nobody can settle and a
     * stolen-handset report nobody can answer.
     */
    FulfilmentResponses.Ready ready(Long vendorUserId, Long vendorOrderId);

    /** The live release code, for the seller's eyes. Never logged, never cached. */
    FulfilmentResponses.ReleaseCode releaseCode(Long vendorUserId, Long vendorOrderId);

    /**
     * Issues a fresh code and kills the old one.
     *
     * <p>Rate limited, because the reason to reissue — somebody saw it — is also
     * the reason an attacker would want a stream of them. The limit counts the
     * codes actually issued rather than a counter on the order, so nothing that
     * forgets to increment can reset it.
     */
    FulfilmentResponses.ReleaseCode regenerateReleaseCode(Long vendorUserId, Long vendorOrderId);

    /** The parcel label, as a PDF, with a signed QR the driver scans. */
    ParcelLabel label(Long vendorUserId, Long vendorOrderId);

    /**
     * Binds one physical handset to one line.
     *
     * <p>The handset must be this seller's, must be on the shelf, and must match
     * the variant the line was bought as. Each of those is refused separately
     * and by name: a seller scanning the wrong phone off a bench of twenty needs
     * to be told which wrong it was.
     */
    FulfilmentResponses.ImeiAssigned assignImei(Long vendorUserId, Long vendorOrderId, Long lineId,
                                                FulfilmentRequests.AssignImei request);

    /** A rendered label, ready to stream. */
    record ParcelLabel(byte[] content, String filename, String contentType) {}
}
