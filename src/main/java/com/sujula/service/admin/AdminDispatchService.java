package com.sujula.service.admin;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.admin.AdminDispatchRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminDispatchResponses;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.user.User;

/**
 * Orders and parcels, as the people who have to unstick them see them.
 *
 * <p>Two things here are deliberately harder to do than the rest of the admin
 * surface, and both for the same reason: they are the two ways a status can be
 * reached without the thing behind it having happened.
 *
 * <ul>
 *   <li><b>Forcing a vendor order's status</b> demands a note of real length,
 *       because that note is the only evidence the status was ever justified.</li>
 *   <li><b>Overriding a handoff</b> demands step-up as well. It is the one way a
 *       parcel reaches DELIVERED without anybody presenting a code, which is the
 *       hole C4 exists to close — so it is closed by making it expensive and
 *       loud rather than by not existing, because a driver whose phone went into
 *       the river still has to be able to hand the parcel over.</li>
 * </ul>
 *
 * <p>Assignment is an <em>offer</em>. A platform that could put a job on
 * somebody's screen and call it theirs would be one where a driver who was
 * asleep is accountable for a parcel.
 */
public interface AdminDispatchService {

    // ── Orders ───────────────────────────────────────────────────────────────

    PagedResponse<AdminDispatchResponses.OrderRow> orders(
            User staff, String query, OrderStatus status, String destinationCountry,
            Long vendorId, Integer stuckForHours, Pageable pageable);

    /** One order with its slices, its ledger and its parcels, in one response. */
    AdminDispatchResponses.OrderDetail order(User staff, Long orderId);

    /** Cancels over the top of whatever it was doing — one slice, or all of them (C3). */
    AdminDispatchResponses.OrderCancelled forceCancel(
            User staff, Long orderId, AdminDispatchRequests.ForceCancelOrder request);

    /** Break-glass. Sets a status by hand, with the note that is its only justification. */
    AdminDispatchResponses.StatusForced forceStatus(
            User staff, Long orderId, Long vendorOrderId,
            AdminDispatchRequests.ForceStatus request);

    /** Places an order for somebody who telephoned. */
    AdminDispatchResponses.OrderPlaced placeOnBehalf(
            User staff, AdminDispatchRequests.PlaceOrderOnBehalf request);

    // ── The board ────────────────────────────────────────────────────────────

    PagedResponse<AdminDispatchResponses.ShipmentRow> shipments(
            User staff, ShipmentStatus status, String country, Long driverId,
            Integer waitingOverHours, Pageable pageable);

    /** Parcels nobody is carrying, each with a ranked list of who could. */
    java.util.List<AdminDispatchResponses.UnassignedShipment> unassigned(User staff, int limit);

    /**
     * Offers a parcel to a named driver.
     *
     * <p>Takes a row lock on the leg first. Two dispatchers assigning the same
     * parcel within a second of each other is the ordinary race on a busy
     * morning, and the loser has to be told rather than silently overwriting the
     * winner.
     */
    AdminDispatchResponses.AssignmentMade assign(
            User staff, Long shipmentId, AdminDispatchRequests.AssignShipment request);

    /** Takes a parcel back off a driver and returns it to the queue. */
    AdminDispatchResponses.AssignmentRemoved unassign(
            User staff, Long shipmentId, AdminDispatchRequests.UnassignShipment request);

    /**
     * Moves a parcel from one driver to another.
     *
     * <p>Only before anybody has picked it up, or after an attempt has failed.
     * A parcel in a driver's hands cannot be reassigned by an administrator —
     * that is a transfer between two people standing together, and it has its
     * own endpoint with both of them attesting.
     */
    AdminDispatchResponses.AssignmentMade reassign(
            User staff, Long shipmentId, AdminDispatchRequests.ReassignShipment request);

    /** Records a handover that could not be proven the ordinary way. Step-up. */
    AdminDispatchResponses.HandoffOverridden overrideHandoff(
            User staff, Long shipmentId, AdminDispatchRequests.OverrideHandoff request);

    AdminDispatchResponses.ShipmentCancelled cancelShipment(
            User staff, Long shipmentId, AdminDispatchRequests.CancelShipment request);

    /** The whole chain with its evidence — what a dispute is actually decided on. */
    AdminDispatchResponses.CustodyChainView custodyChain(User staff, Long shipmentId);
}
