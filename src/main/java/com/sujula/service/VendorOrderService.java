package com.sujula.service;

import com.sujula.dto.response.vendor.VendorOrderDetailResponse;
import com.sujula.dto.response.vendor.VendorOrderStatsResponse;
import com.sujula.dto.response.vendor.VendorOrderSummaryResponse;
import com.sujula.model.constant.VendorOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * A vendor's own fulfilment queue.
 *
 * <p>The isolation rule this exists to enforce: a vendor reads their slice of an
 * order and nothing else. From the database they get identifiers — the vendor
 * order, the buyer-facing order number, and the product ids they have to pick —
 * and every amount is computed here in the vendor's own settlement currency,
 * frozen when the order was placed.
 *
 * <p>What never crosses this boundary: who the buyer is, where they are, what
 * currency they shopped in, what they were charged, and any figure that would
 * let one be compared against the other. A seller in Banjul listing in dalasi
 * sees dalasi, whether the buyer paid in dalasi or in pounds.
 *
 * <p>Scoping is by the authenticated user throughout. No method takes a vendor
 * id from a caller — it is resolved from the principal — so there is no
 * parameter to tamper with.
 */
public interface VendorOrderService {

    /** The queue, oldest first: the order the seller should work in. */
    Page<VendorOrderSummaryResponse> findMyOrders(Long vendorUserId, VendorOrderStatus status, Pageable pageable);

    /** One order to pack. Another vendor's slice is reported as not found. */
    VendorOrderDetailResponse findMyOrder(Long vendorUserId, Long vendorOrderId);

    /**
     * Moves the order along.
     *
     * <p>Forward only, and only through statuses a vendor is entitled to set:
     * they say what they have done, not that the buyer received it or that money
     * went back. An illegal move is refused with the reason, not silently
     * ignored.
     */
    VendorOrderDetailResponse updateStatus(Long vendorUserId, Long vendorOrderId, VendorOrderStatus next);

    /** Dashboard counts and earnings, in the vendor's own currency. */
    VendorOrderStatsResponse stats(Long vendorUserId);
}
