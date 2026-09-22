package com.sujula.service.buyerorder;

import com.sujula.dto.request.buyerorder.BuyerOrderRequests;
import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.model.constant.OrderStatus;
import org.springframework.data.domain.Pageable;

/**
 * A buyer's own orders.
 *
 * <p>Every method takes {@code userId} first and that id comes from the
 * authenticated principal. The order id that follows is matched against it
 * inside the query, so another buyer's order is not found rather than found and
 * refused — confirming order 4102 exists tells whoever guessed it that somebody
 * bought something.
 *
 * <p>The one exception is {@link #publicTracking}, which has no owner by design:
 * the recipient may have nothing but a phone and a link, and the code is the
 * only credential. What it returns is bounded accordingly.
 */
public interface BuyerOrderService {

    BuyerOrderResponses.Page list(Long userId, OrderStatus status, Pageable pageable);

    BuyerOrderResponses.Detail detail(Long userId, Long orderId);

    /** Per-seller timelines, not one merged list — two parcels are two stories. */
    BuyerOrderResponses.Tracking tracking(Long userId, Long orderId);

    /**
     * Cancels the whole order.
     *
     * <p>Only while every slice is still pre-dispatch. Once one seller has
     * shipped, stopping the order would mean recalling goods already in transit,
     * which is a return rather than a cancellation — so the buyer is directed to
     * cancel the slices that can still be stopped.
     */
    BuyerOrderResponses.Cancelled cancel(Long userId, Long orderId,
                                         BuyerOrderRequests.Cancel request);

    /**
     * Cancels one seller's slice, leaving the rest of the order alone.
     *
     * <p>Raises a refund request rather than moving money. An administrator
     * decides: money leaving the platform is the one action no later API call
     * can undo.
     */
    BuyerOrderResponses.Cancelled cancelVendorOrder(Long userId, Long orderId, Long vendorOrderId,
                                                    BuyerOrderRequests.Cancel request);

    /**
     * The buyer says they have this seller's goods, releasing the funds early.
     *
     * <p>Optional. Money is otherwise held until delivery is proven through the
     * custody chain; this is the buyer short-circuiting that because the parcel
     * is in their hands and they would rather the seller were paid.
     */
    BuyerOrderResponses.ReceiptConfirmed confirmReceipt(Long userId, Long orderId,
                                                        Long vendorOrderId,
                                                        BuyerOrderRequests.ConfirmReceipt request);

    /** A review of something they actually bought and received. */
    BuyerOrderResponses.ReviewPosted review(Long userId, Long orderId, Long lineId,
                                            BuyerOrderRequests.PostReview request);

    /** A signed, expiring link to the invoice. */
    BuyerOrderResponses.DocumentLink invoiceLink(Long userId, Long orderId);

    /**
     * What anybody holding the tracking code may see.
     *
     * <p>No account, no owner, no PII. A code sent by SMS ends up forwarded and
     * screenshotted, so what it unlocks has to be safe for whoever ends up with
     * it.
     */
    BuyerOrderResponses.PublicTracking publicTracking(String trackingCode);
}
