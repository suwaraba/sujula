package com.sujula.dto.response.buyerorder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.constant.VendorOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What a buyer sees of their own orders.
 *
 * <p>Records rather than entities, and that matters more here than anywhere
 * else on the API: an {@code Order} carries {@code internalNotes} written by
 * staff about the buyer, and its {@code Payment} carries a gateway client
 * secret. Serialising either would hand both to the person they were kept from.
 */
public final class BuyerOrderResponses {

    private BuyerOrderResponses() {}

    /** One row in "my orders". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(
            Long id,
            String orderNumber,
            OrderStatus status,
            PaymentStatus paymentStatus,
            String currency,
            BigDecimal total,
            int itemCount,
            int vendorCount,
            String leadImageUrl,
            boolean cancellable,
            LocalDateTime placedAt) {}

    public record Page(List<Summary> items, int page, int size,
                       long totalElements, int totalPages) {}

    /**
     * The full order.
     *
     * @param vendorOrders the slices. One payment, several sub-orders that ship,
     *                     cancel, refund and pay out independently — a client
     *                     rendering one status for the whole thing will be wrong
     *                     about half of it as soon as one seller ships
     * @param cancellable  whether the whole order can still be stopped, which is
     *                     true only while every slice is pre-dispatch
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(
            Long id,
            String orderNumber,
            String trackingCode,
            OrderStatus status,
            PaymentStatus paymentStatus,
            PaymentMethod paymentMethod,
            DeliveryMode deliveryMode,
            String currency,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal shipping,
            BigDecimal tax,
            BigDecimal total,
            String couponCode,
            ShippingTo shippingTo,
            List<VendorGroup> vendorOrders,
            List<Refund> refunds,
            boolean cancellable,
            String cancellableBlockedBy,
            LocalDateTime placedAt,
            LocalDateTime paidAt) {}

    /**
     * Where it is going, as the buyer wrote it.
     *
     * <p>Their own snapshot, so it is theirs to see. The public tracking page
     * carries none of this.
     */
    public record ShippingTo(
            String fullName,
            String phone,
            String street,
            String apartment,
            String city,
            String state,
            String postalCode,
            String country,
            String instructions) {}

    /**
     * One seller's slice.
     *
     * @param payout deliberately absent — what a vendor is paid is between the
     *               platform and the vendor, and showing a buyer the seller's
     *               margin is not the buyer's business
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VendorGroup(
            Long vendorOrderId,
            Long vendorId,
            String storeName,
            String storeSlug,
            VendorOrderStatus status,
            String currency,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal shipping,
            BigDecimal total,
            List<Line> lines,
            boolean cancellable,
            boolean receiptConfirmable,
            boolean receiptConfirmed,
            LocalDateTime receiptConfirmedAt,
            LocalDateTime cancelledAt) {}

    /**
     * One product on the order.
     *
     * @param reviewable whether this buyer may review it — bought, received, and
     *                   not already reviewed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            Long lineId,
            Long productId,
            String productSlug,
            String productName,
            String variantSku,
            String selectedOptions,
            String imageUrl,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal deliveryCost,
            String currency,
            boolean reviewable,
            Long reviewId) {}

    /** A refund the buyer asked for, and where it has got to. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Refund(
            Long id,
            String reference,
            Long vendorOrderId,
            String storeName,
            RefundRequestStatus status,
            BigDecimal amount,
            String currency,
            String reason,
            String decisionNote,
            LocalDateTime requestedAt,
            LocalDateTime decidedAt,
            LocalDateTime completedAt) {}

    // ── Tracking ─────────────────────────────────────────────────────────────

    /**
     * The buyer's tracking view: one timeline per seller.
     *
     * <p>Aggregated rather than merged into one. Two sellers ship two parcels on
     * two days through two drivers, and flattening that into a single list of
     * events produces a story that never happened.
     */
    public record Tracking(
            Long orderId,
            String orderNumber,
            String trackingCode,
            OrderStatus status,
            List<VendorTimeline> timelines) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VendorTimeline(
            Long vendorOrderId,
            Long vendorId,
            String storeName,
            VendorOrderStatus status,
            String trackingNumber,
            String courierName,
            String pickupPointName,
            LocalDateTime estimatedDeliveryAt,
            LocalDateTime deliveredAt,
            List<Event> events) {}

    /**
     * One thing that happened.
     *
     * @param proof what evidences it — a handover code, a signature, a
     *              photograph. Custody is a chain of verified events, so an event
     *              without evidence is a claim rather than a fact
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(
            String status,
            String description,
            String location,
            String proof,
            LocalDateTime at) {}

    /**
     * What anybody holding the tracking code may see.
     *
     * <p><strong>Defined by what it leaves out.</strong> The recipient may have
     * nothing but a phone and a link, so there is no account to authenticate
     * against and possession of the code is the only credential. A code sent by
     * SMS will end up forwarded, screenshotted and read over somebody's shoulder
     * — so this carries no name, no address, no phone number, no prices, no
     * order number and nothing identifying the buyer.
     *
     * <p>What is left is what the person waiting for a parcel actually needs:
     * whether it is coming, how far along it is, and roughly when.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicTracking(
            String trackingCode,
            String stage,
            String description,
            int parcels,
            int parcelsDelivered,
            String destinationCity,
            String destinationCountry,
            String pickupPointName,
            String pickupPointAddress,
            LocalDateTime estimatedDeliveryAt,
            LocalDateTime lastUpdatedAt,
            List<PublicEvent> events) {}

    /** A tracking step, with nothing in it that names anybody. */
    public record PublicEvent(String stage, String description, LocalDateTime at) {}

    // ── Acknowledgements ─────────────────────────────────────────────────────

    public record Cancelled(
            Long orderId,
            OrderStatus status,
            List<Long> cancelledVendorOrderIds,
            List<Refund> refunds,
            String message) {}

    public record ReceiptConfirmed(
            Long vendorOrderId,
            VendorOrderStatus status,
            LocalDateTime confirmedAt,
            boolean escrowReleased,
            String message) {}

    /** A signed, expiring link to a generated document. */
    public record DocumentLink(String url, LocalDateTime expiresAt, String contentType) {}

    public record ReviewPosted(Long reviewId, Long productId, int rating, String message) {}
}
