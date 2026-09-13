package com.sujula.service.buyerorder.impl;

import com.sujula.dto.request.buyerorder.BuyerOrderRequests;
import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Review;
import com.sujula.model.constant.*;
import com.sujula.model.delivery.Delivery;
import com.sujula.model.delivery.DeliveryTracking;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.*;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.DeliveryRepository;
import com.sujula.service.invoice.InvoiceService;
import com.sujula.repository.delivery.DeliveryTrackingRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.OrderStatusHistoryRepository;
import com.sujula.repository.order.RefundRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ReviewRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.buyerorder.BuyerOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A buyer's own orders, and the one page that belongs to somebody without an
 * account.
 *
 * <p>Three ideas run through this class.
 *
 * <p><strong>Ownership is the query.</strong> Every read resolves the order by
 * id and buyer together, so a stranger's order is not found rather than found
 * and refused.
 *
 * <p><strong>Slices are independent.</strong> One payment becomes several
 * sub-orders that ship, cancel and refund on their own timetables. Cancelling
 * one seller's goods must leave the other seller's untouched, and the whole
 * order can only be stopped while every slice can still be stopped.
 *
 * <p><strong>The tracking page has no owner.</strong> The person waiting for the
 * parcel may have nothing but a phone and an SMS, so possession of the code is
 * the only credential — and what it unlocks is bounded to what is safe for
 * whoever ends up holding it.
 */
@Slf4j
@Service
public class BuyerOrderServiceImpl implements BuyerOrderService {

    private static final List<RefundRequestStatus> OPEN =
            List.of(RefundRequestStatus.REQUESTED, RefundRequestStatus.APPROVED);

    private final OrderRepository orders;
    private final VendorOrderRepository vendorOrders;
    private final RefundRequestRepository refunds;
    private final OrderStatusHistoryRepository history;
    private final DeliveryRepository deliveries;
    private final DeliveryTrackingRepository deliveryTracking;
    private final ReviewRepository reviews;
    private final ProductRepository products;
    private final UserRepository users;
    private final PickupPointRepository pickupPoints;
    private final InvoiceService invoices;

    public BuyerOrderServiceImpl(OrderRepository orders, VendorOrderRepository vendorOrders,
                                 RefundRequestRepository refunds,
                                 OrderStatusHistoryRepository history,
                                 DeliveryRepository deliveries,
                                 DeliveryTrackingRepository deliveryTracking,
                                 ReviewRepository reviews, ProductRepository products,
                                 UserRepository users, PickupPointRepository pickupPoints,
                                 InvoiceService invoices) {
        this.orders = orders;
        this.vendorOrders = vendorOrders;
        this.refunds = refunds;
        this.history = history;
        this.deliveries = deliveries;
        this.deliveryTracking = deliveryTracking;
        this.reviews = reviews;
        this.products = products;
        this.users = users;
        this.pickupPoints = pickupPoints;
        this.invoices = invoices;
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BuyerOrderResponses.Page list(Long userId, OrderStatus status, Pageable pageable) {
        Page<Order> page = orders.findForBuyer(userId, status, pageable);

        return new BuyerOrderResponses.Page(
                page.getContent().stream().map(BuyerOrderServiceImpl::toSummary).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public BuyerOrderResponses.Detail detail(Long userId, Long orderId) {
        Order order = requireOwn(orderId, userId);
        return toDetail(order, reviewedProductIds(userId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>One timeline per seller. Two sellers ship two parcels on two days
     * through two drivers, and flattening that into one list of events produces
     * a story that never happened — "picked up, picked up, delivered, in
     * transit" reads as a system malfunction rather than as two journeys.
     */
    @Override
    @Transactional(readOnly = true)
    public BuyerOrderResponses.Tracking tracking(Long userId, Long orderId) {
        Order order = requireOwn(orderId, userId);

        List<Delivery> all = deliveries.findByOrderItemOrderId(orderId);
        Map<Long, List<Delivery>> byVendorOrder = all.stream()
                .filter(delivery -> delivery.getOrderItem() != null
                        && delivery.getOrderItem().getVendorOrder() != null)
                .collect(Collectors.groupingBy(
                        delivery -> delivery.getOrderItem().getVendorOrder().getId()));

        Map<Long, List<DeliveryTracking>> trail = trailFor(all);

        List<BuyerOrderResponses.VendorTimeline> timelines = order.getVendorOrders().stream()
                .map(slice -> toTimeline(slice, byVendorOrder.getOrDefault(slice.getId(), List.of()), trail))
                .toList();

        return new BuyerOrderResponses.Tracking(order.getId(), order.getOrderNumber(),
                order.getTrackingCode(), order.getStatus(), timelines);
    }

    // ── Cancellation ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>All or nothing, and only while nothing has left a seller. A buyer who
     * cancels an order where one parcel is already with a driver would otherwise
     * be told the whole thing stopped while a courier is still carrying half of
     * it to another country.
     */
    @Override
    @Transactional
    public BuyerOrderResponses.Cancelled cancel(Long userId, Long orderId,
                                                BuyerOrderRequests.Cancel request) {
        Order order = requireOwn(orderId, userId);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            // Cancelling twice is not an error. A retried tap should not fail.
            return new BuyerOrderResponses.Cancelled(order.getId(), order.getStatus(),
                    List.of(), refundsFor(order), "This order was already cancelled.");
        }

        List<VendorOrder> shipped = order.getVendorOrders().stream()
                .filter(slice -> !slice.isPreDispatch() && !isCancelled(slice))
                .toList();

        if (!shipped.isEmpty()) {
            String names = shipped.stream()
                    .map(slice -> slice.getVendor() == null ? "a seller" : slice.getVendor().getStoreName())
                    .collect(Collectors.joining(", "));
            throw new BadRequestException(
                    "Goods from " + names + " have already been dispatched, so the whole order "
                            + "cannot be cancelled. Cancel the parts that have not shipped, or "
                            + "request a return for the rest.");
        }

        List<Long> cancelled = new ArrayList<>();
        for (VendorOrder slice : order.getVendorOrders()) {
            if (isCancelled(slice)) {
                continue;
            }
            cancelSlice(order, slice, userId, request.reason());
            cancelled.add(slice.getId());
        }

        OrderStatus was = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        orders.save(order);
        record(order, was, OrderStatus.CANCELLED, "Cancelled by the buyer", userId);

        log.info("[Order] {} cancelled by user {} — {} slice(s)",
                order.getOrderNumber(), userId, cancelled.size());

        return new BuyerOrderResponses.Cancelled(order.getId(), OrderStatus.CANCELLED, cancelled,
                refundsFor(order),
                wasCharged(order)
                        ? "Cancelled. A refund has been requested and will be reviewed."
                        : "Cancelled. Nothing was charged.");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The point of this endpoint is what it does <em>not</em> touch. One
     * seller's slice is cancelled; every other slice keeps its own status, its
     * own timeline and its own payout. The order as a whole is only marked
     * cancelled if nothing is left standing.
     */
    @Override
    @Transactional
    public BuyerOrderResponses.Cancelled cancelVendorOrder(Long userId, Long orderId,
                                                           Long vendorOrderId,
                                                           BuyerOrderRequests.Cancel request) {
        Order order = requireOwn(orderId, userId);
        VendorOrder slice = requireSlice(order, vendorOrderId);

        if (isCancelled(slice)) {
            return new BuyerOrderResponses.Cancelled(order.getId(), order.getStatus(),
                    List.of(), refundsFor(order), "That part of the order was already cancelled.");
        }
        if (!slice.isPreDispatch()) {
            throw new BadRequestException(
                    "Those goods have already been dispatched. Ask for a return instead — the "
                            + "parcel is with a courier.");
        }

        cancelSlice(order, slice, userId, request.reason());

        // The order is cancelled only when nothing is left standing. One seller
        // pulling out does not cancel another's shipment.
        boolean anythingLeft = order.getVendorOrders().stream().anyMatch(other -> !isCancelled(other));
        if (!anythingLeft) {
            OrderStatus was = order.getStatus();
            order.setStatus(OrderStatus.CANCELLED);
            record(order, was, OrderStatus.CANCELLED, "Every part of the order was cancelled", userId);
        }
        orders.save(order);

        log.info("[Order] Slice {} of {} cancelled by user {}; order {}",
                vendorOrderId, order.getOrderNumber(), userId,
                anythingLeft ? "continues" : "fully cancelled");

        return new BuyerOrderResponses.Cancelled(order.getId(), order.getStatus(),
                List.of(vendorOrderId), refundsFor(order),
                anythingLeft
                        ? "That seller's items were cancelled. The rest of your order is unaffected."
                        : "Every part of the order has now been cancelled.");
    }

    /**
     * Cancels one slice and, where money has been taken, asks for it back.
     *
     * <p>A request rather than a transfer. An administrator decides: money
     * leaving the platform is the one action no later API call can undo, and on
     * a marketplace where a shipment may already be halfway to another country an
     * automatic refund is how you lose the goods and the money.
     */
    private void cancelSlice(Order order, VendorOrder slice, Long userId, String reason) {
        slice.setStatus(VendorOrderStatus.CANCELLED);
        slice.setCancelledAt(LocalDateTime.now());
        vendorOrders.save(slice);

        if (!wasCharged(order)) {
            return;   // nothing was taken, so there is nothing to give back
        }
        if (refunds.findOpenForVendorOrder(slice.getId(), OPEN).isPresent()) {
            // Asking twice must not queue two refunds against the same goods,
            // one of which an administrator might approve after the other paid.
            return;
        }

        BigDecimal owed = slice.getTotal() == null ? BigDecimal.ZERO : slice.getTotal();

        refunds.save(RefundRequest.builder()
                .reference(newRefundReference())
                .order(order)
                .vendorOrder(slice)
                .requestedBy(users.findById(userId).orElse(null))
                .status(RefundRequestStatus.REQUESTED)
                .amount(owed)
                .currency(order.getCurrency())
                .amountNative(slice.getTotalNative())
                // The rate the two figures were reconciled at, carried rather
                // than re-derived: by the time a refund settles the rate has
                // moved, and re-deriving would make the two sides disagree.
                .fx(slice.getFx())
                .reason(reason)
                .createdAt(LocalDateTime.now())
                .build());

        log.info("[Order] Refund requested for slice {} of {} — {} {}",
                slice.getId(), order.getOrderNumber(), owed, order.getCurrency());
    }

    // ── Receipt ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BuyerOrderResponses.ReceiptConfirmed confirmReceipt(
            Long userId, Long orderId, Long vendorOrderId,
            BuyerOrderRequests.ConfirmReceipt request) {

        Order order = requireOwn(orderId, userId);
        VendorOrder slice = requireSlice(order, vendorOrderId);

        if (slice.isReceiptConfirmed()) {
            return new BuyerOrderResponses.ReceiptConfirmed(slice.getId(), slice.getStatus(),
                    slice.getReceiptConfirmedAt(), slice.getEscrowReleasedAt() != null,
                    "You had already confirmed receipt of these items.");
        }
        if (isCancelled(slice)) {
            throw new BadRequestException("Those items were cancelled, so there is nothing to receive.");
        }
        if (slice.isPreDispatch()) {
            // Confirming receipt of something nobody has sent would release funds
            // for goods still on a shelf.
            throw new BadRequestException(
                    "Those items have not been dispatched yet, so they cannot have arrived.");
        }

        LocalDateTime now = LocalDateTime.now();
        User confirmedBy = users.findById(userId).orElse(null);

        // The status is the consequence, not the input. What actually happened
        // is that a named, authenticated buyer stated the goods had arrived, so
        // that statement is written into the custody trail first and the slice
        // follows from it. Without this the chain shows a parcel reaching
        // DELIVERED with nobody having handed it to anybody.
        for (Delivery parcel : deliveries.findByOrderItemOrderId(order.getId())) {
            if (parcel.getOrderItem() == null || parcel.getOrderItem().getVendorOrder() == null
                    || !slice.getId().equals(parcel.getOrderItem().getVendorOrder().getId())) {
                continue;
            }
            deliveryTracking.save(DeliveryTracking.builder()
                    .delivery(parcel)
                    .status(DeliveryStatus.DELIVERED)
                    .description("Receipt confirmed by the buyer")
                    .recordedBy(confirmedBy)
                    .build());

            if (parcel.getStatus() != DeliveryStatus.DELIVERED) {
                parcel.setStatus(DeliveryStatus.DELIVERED);
                parcel.setDeliveredAt(now);
                parcel.setActualDeliveredAt(now);
                deliveries.save(parcel);
            }
        }

        slice.setReceiptConfirmedAt(now);
        slice.setEscrowReleasedAt(now);
        slice.setStatus(VendorOrderStatus.DELIVERED);
        vendorOrders.save(slice);

        log.info("[Order] Buyer {} confirmed receipt of slice {} — escrow released",
                userId, slice.getId());

        return new BuyerOrderResponses.ReceiptConfirmed(slice.getId(), slice.getStatus(), now, true,
                "Thank you. The seller will be paid for these items.");
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Verified purchase, enforced from the order rather than trusted from the
     * request: the line must be on an order this buyer owns, and the goods must
     * have arrived. A review of something nobody received is the oldest way to
     * manipulate a rating.
     */
    @Override
    @Transactional
    public BuyerOrderResponses.ReviewPosted review(Long userId, Long orderId, Long lineId,
                                                   BuyerOrderRequests.PostReview request) {
        Order order = requireOwn(orderId, userId);

        OrderItem line = order.getItems().stream()
                .filter(item -> item.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Order line", lineId));

        VendorOrder slice = line.getVendorOrder();
        if (slice == null || slice.getStatus() != VendorOrderStatus.DELIVERED) {
            throw new BadRequestException(
                    "You can review these once they have arrived.");
        }
        if (line.getProduct() == null) {
            throw new BadRequestException("That product is no longer available to review.");
        }

        Long productId = line.getProduct().getId();
        if (reviewedProductIds(userId).contains(productId)) {
            throw new BadRequestException(
                    "You have already reviewed this product. Edit your existing review instead.");
        }

        User author = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Review review = new Review();
        review.setUser(author);
        review.setProduct(line.getProduct());
        review.setRating(request.rating());
        review.setTitle(trimToNull(request.title()));
        review.setComment(trimToNull(request.comment()));
        // Verified because it was established above, not because the client said so.
        review.setVerified(true);
        Review saved = reviews.save(review);

        refreshProductRating(productId);

        log.info("[Order] Review {} posted by user {} on product {}", saved.getId(), userId, productId);
        return new BuyerOrderResponses.ReviewPosted(saved.getId(), productId, request.rating(),
                "Thank you — your review is on the listing.");
    }

    /**
     * Recomputes the product's average and count from its reviews.
     *
     * <p>Derived from the rows rather than incremented, because an increment that
     * is missed once stays wrong for ever, and the aggregate is what every other
     * buyer reads.
     */
    private void refreshProductRating(Long productId) {
        long total = 0;
        long weighted = 0;
        for (Object[] row : reviews.ratingHistogram(productId)) {
            long count = ((Number) row[1]).longValue();
            total += count;
            weighted += ((Number) row[0]).longValue() * count;
        }
        final long reviewCount = total;
        final long weightedSum = weighted;

        products.findById(productId).ifPresent(product -> {
            product.setTotalReviews((int) reviewCount);
            product.setRating(reviewCount == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(weightedSum)
                        .divide(BigDecimal.valueOf(reviewCount), 2, java.math.RoundingMode.HALF_UP));
            products.save(product);
        });
    }

    // ── Invoice ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BuyerOrderResponses.DocumentLink invoiceLink(Long userId, Long orderId) {
        Order order = requireOwn(orderId, userId);

        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw new BadRequestException(
                    "An invoice is issued once the order is paid.");
        }
        return invoices.link(order);
    }

    // ── Public tracking ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Everything omitted here is omitted on purpose. A code arrives by SMS to
     * somebody with no account; it gets forwarded, screenshotted and read over a
     * shoulder. So there is no name, no street, no phone number, no price and no
     * order number — a stranger who finds this link learns that a parcel is on
     * its way to a city, and nothing else.
     */
    @Override
    @Transactional(readOnly = true)
    public BuyerOrderResponses.PublicTracking publicTracking(String trackingCode) {
        Order order = orders.findByTrackingCode(trackingCode)
                .orElseThrow(() -> new ResourceNotFoundException("Tracking code", trackingCode));

        List<Delivery> parcels = deliveries.findByOrderItemOrderId(order.getId());
        long delivered = parcels.stream()
                .filter(parcel -> parcel.getStatus() == DeliveryStatus.DELIVERED)
                .count();

        Map<Long, List<DeliveryTracking>> trail = trailFor(parcels);

        List<BuyerOrderResponses.PublicEvent> events = new ArrayList<>();
        LocalDateTime lastUpdate = order.getUpdatedAt();
        LocalDateTime eta = null;

        for (Delivery parcel : parcels) {
            if (parcel.getEstimatedDeliveryAt() != null
                    && (eta == null || parcel.getEstimatedDeliveryAt().isAfter(eta))) {
                eta = parcel.getEstimatedDeliveryAt();
            }
            for (DeliveryTracking step : trail.getOrDefault(parcel.getId(), List.of())) {
                events.add(new BuyerOrderResponses.PublicEvent(
                        step.getStatus() == null ? null : step.getStatus().name(),
                        // The description is written by the platform, not by a
                        // driver typing free text, so it carries no names.
                        publicDescription(step.getStatus()),
                        step.getRecordedAt()));
                if (step.getRecordedAt() != null
                        && (lastUpdate == null || step.getRecordedAt().isAfter(lastUpdate))) {
                    lastUpdate = step.getRecordedAt();
                }
            }
        }
        events.sort(Comparator.comparing(BuyerOrderResponses.PublicEvent::at,
                Comparator.nullsLast(Comparator.naturalOrder())));

        String stage = publicStage(order, parcels.size(), (int) delivered);

        return new BuyerOrderResponses.PublicTracking(
                trackingCode, stage, publicStageDescription(stage),
                parcels.size(), (int) delivered,
                // City and country only. A street address would identify a home.
                order.getShippingCity(), order.getShippingCountry(),
                pickupPoint(order, parcels), pickupPointAddress(order, parcels),
                eta, lastUpdate, events);
    }

    private static String publicStage(Order order, int parcels, int delivered) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return "CANCELLED";
        }
        if (parcels > 0 && delivered == parcels) {
            return "DELIVERED";
        }
        if (delivered > 0) {
            return "PARTIALLY_DELIVERED";
        }
        if (order.getStatus() == OrderStatus.SHIPPED) {
            return "IN_TRANSIT";
        }
        return "PREPARING";
    }

    private static String publicStageDescription(String stage) {
        return switch (stage) {
            case "DELIVERED" -> "Everything has been delivered.";
            case "PARTIALLY_DELIVERED" -> "Some of this order has been delivered; the rest is on its way.";
            case "IN_TRANSIT" -> "On its way.";
            case "CANCELLED" -> "This order was cancelled.";
            default -> "Being prepared for dispatch.";
        };
    }

    /**
     * A fixed phrase per status rather than whatever a driver typed.
     *
     * <p>Free text on a page anybody can read is a way for a name or a phone
     * number to end up published by accident.
     */
    private static String publicDescription(DeliveryStatus status) {
        if (status == null) {
            return "Updated.";
        }
        return switch (status) {
            case PENDING, PREPARED -> "Being prepared.";
            case ASSIGNED -> "A driver has been assigned.";
            case PICKED_UP -> "Collected from the seller.";
            case IN_TRANSIT, OUT_FOR_DELIVERY -> "On its way.";
            case AT_PICKUP_POINT -> "Ready to collect at the pickup point.";
            case DELIVERED -> "Delivered.";
            case FAILED -> "A delivery attempt did not succeed.";
            case RETURNED -> "Returned to the seller.";
        };
    }

    /**
     * The hub the parcel is waiting at, when there is one.
     *
     * <p>Safe to publish where the recipient's own address is not: a pickup
     * point is a shop with a sign over the door and opening hours on a website,
     * whereas a street address names a home. This is also the one piece of the
     * public page that is genuinely useful to the person with the SMS — it tells
     * them where to walk to.
     */
    private String pickupPoint(Order order, List<Delivery> parcels) {
        if (order.getDeliveryMode() != DeliveryMode.PICKUP_POINT) {
            return null;
        }
        return parcels.stream()
                .map(Delivery::getPickupPoint)
                .filter(Objects::nonNull)
                .findFirst()
                .map(PickupPoint::getName)
                .or(() -> order.getPickupPointId() == null
                        ? Optional.empty()
                        : pickupPoints.findById(order.getPickupPointId()).map(PickupPoint::getName))
                .orElse(null);
    }

    /** The hub's public address — the street of a shop, not of a home. */
    private String pickupPointAddress(Order order, List<Delivery> parcels) {
        if (order.getDeliveryMode() != DeliveryMode.PICKUP_POINT) {
            return null;
        }
        Optional<PickupPoint> hub = parcels.stream()
                .map(Delivery::getPickupPoint)
                .filter(Objects::nonNull)
                .findFirst()
                .or(() -> order.getPickupPointId() == null
                        ? Optional.empty()
                        : pickupPoints.findById(order.getPickupPointId()));

        return hub.map(point -> Stream.of(point.getAddressStreet(), point.getCity(), point.getCountryCode())
                        .filter(part -> part != null && !part.isBlank())
                        .collect(Collectors.joining(", ")))
                .filter(address -> !address.isBlank())
                .orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Assembly
    // ─────────────────────────────────────────────────────────────────────────

    private static BuyerOrderResponses.Summary toSummary(Order order) {
        int items = order.getItems() == null ? 0
                : order.getItems().stream().mapToInt(item ->
                        item.getQuantity() == null ? 0 : item.getQuantity()).sum();

        return new BuyerOrderResponses.Summary(
                order.getId(), order.getOrderNumber(), order.getStatus(), order.getPaymentStatus(),
                order.getCurrency(), order.getTotal(), items,
                order.getVendorOrders() == null ? 0 : order.getVendorOrders().size(),
                leadImage(order),
                isWhollyCancellable(order),
                order.getCreatedAt());
    }

    private BuyerOrderResponses.Detail toDetail(Order order, Set<Long> reviewedProducts) {
        List<BuyerOrderResponses.VendorGroup> groups = order.getVendorOrders().stream()
                .map(slice -> toGroup(order, slice, reviewedProducts))
                .toList();

        String blocked = isWhollyCancellable(order) ? null
                : order.getVendorOrders().stream()
                    .filter(slice -> !slice.isPreDispatch() && !isCancelled(slice))
                    .map(slice -> slice.getVendor() == null ? "a seller" : slice.getVendor().getStoreName())
                    .findFirst()
                    .map(name -> "Goods from " + name + " have already been dispatched.")
                    .orElse("This order can no longer be cancelled.");

        return new BuyerOrderResponses.Detail(
                order.getId(), order.getOrderNumber(), order.getTrackingCode(),
                order.getStatus(), order.getPaymentStatus(), order.getPaymentMethod(),
                order.getDeliveryMode(), order.getCurrency(),
                order.getSubtotal(), order.getDiscount(), order.getShippingCost(),
                order.getTaxAmount(), order.getTotal(), order.getCouponCode(),
                new BuyerOrderResponses.ShippingTo(
                        order.getShippingFullName(), order.getShippingPhone(),
                        order.getShippingStreet(), order.getShippingApartment(),
                        order.getShippingCity(), order.getShippingState(),
                        order.getShippingPostalCode(), order.getShippingCountry(),
                        order.getDeliveryInstructions()),
                groups, refundsFor(order),
                isWhollyCancellable(order), blocked,
                order.getCreatedAt(), order.getPaidAt());
    }

    private BuyerOrderResponses.VendorGroup toGroup(Order order, VendorOrder slice,
                                                    Set<Long> reviewedProducts) {
        Vendor vendor = slice.getVendor();

        List<BuyerOrderResponses.Line> lines = slice.getItems().stream()
                .map(item -> toLine(order, slice, item, reviewedProducts))
                .toList();

        return new BuyerOrderResponses.VendorGroup(
                slice.getId(),
                vendor == null ? null : vendor.getId(),
                vendor == null ? null : vendor.getStoreName(),
                vendor == null ? null : vendor.getStoreSlug(),
                slice.getStatus(), order.getCurrency(),
                slice.getSubtotal(), slice.getDiscount(),
                // The buyer's share of delivery for this seller's goods, summed
                // from the lines — the same figures they were quoted.
                slice.getItems().stream()
                        .map(OrderItem::getDeliveryCost)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                slice.getTotal(), lines,
                slice.isPreDispatch() && !isCancelled(slice),
                !slice.isPreDispatch() && !isCancelled(slice) && !slice.isReceiptConfirmed(),
                slice.isReceiptConfirmed(), slice.getReceiptConfirmedAt(),
                slice.getCancelledAt());
    }

    private static BuyerOrderResponses.Line toLine(Order order, VendorOrder slice, OrderItem item,
                                                   Set<Long> reviewedProducts) {
        Long productId = item.getProduct() == null ? null : item.getProduct().getId();
        boolean delivered = slice.getStatus() == VendorOrderStatus.DELIVERED;

        return new BuyerOrderResponses.Line(
                item.getId(), productId,
                item.getProduct() == null ? null : item.getProduct().getSlug(),
                item.getProductName(), item.getVariantSku(), item.getSelectedOptions(),
                item.getProductImageUrl(),
                item.getQuantity() == null ? 0 : item.getQuantity(),
                // What the buyer actually paid, in their currency — not the
                // vendor's listing price.
                item.getUnitPriceConverted() != null ? item.getUnitPriceConverted() : item.getUnitPrice(),
                item.getTotalPriceConverted() != null ? item.getTotalPriceConverted() : item.getTotalPrice(),
                item.getDeliveryCost(), order.getCurrency(),
                delivered && productId != null && !reviewedProducts.contains(productId),
                null);
    }

    /**
     * Every parcel's custody trail in one query, keyed by parcel.
     *
     * <p>An order is as many parcels as it has lines, so asking per parcel is a
     * query per line — on a five-seller basket that is five round trips to
     * render one page.
     */
    private Map<Long, List<DeliveryTracking>> trailFor(List<Delivery> parcels) {
        List<Long> ids = parcels.stream().map(Delivery::getId).filter(Objects::nonNull).toList();
        return deliveryTracking.findTrail(ids).stream()
                .filter(step -> step.getDelivery() != null)
                .collect(Collectors.groupingBy(step -> step.getDelivery().getId()));
    }

    private BuyerOrderResponses.VendorTimeline toTimeline(VendorOrder slice, List<Delivery> parcels,
                                                          Map<Long, List<DeliveryTracking>> trail) {
        List<BuyerOrderResponses.Event> events = new ArrayList<>();

        for (Delivery parcel : parcels) {
            for (DeliveryTracking step : trail.getOrDefault(parcel.getId(), List.of())) {
                events.add(new BuyerOrderResponses.Event(
                        step.getStatus() == null ? null : step.getStatus().name(),
                        step.getDescription(),
                        step.getLatitude() == null ? null
                                : step.getLatitude() + "," + step.getLongitude(),
                        // Proof, where the custody chain recorded any. An event
                        // without evidence is a claim rather than a fact.
                        parcel.getDeliveryProofImageUrl() != null ? "photo"
                                : parcel.getRecipientSignatureUrl() != null ? "signature" : null,
                        step.getRecordedAt()));
            }
        }
        events.sort(Comparator.comparing(BuyerOrderResponses.Event::at,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Delivery lead = parcels.isEmpty() ? null : parcels.get(0);

        return new BuyerOrderResponses.VendorTimeline(
                slice.getId(),
                slice.getVendor() == null ? null : slice.getVendor().getId(),
                slice.getVendor() == null ? null : slice.getVendor().getStoreName(),
                slice.getStatus(),
                lead == null ? null : lead.getTrackingNumber(),
                lead == null || lead.getDriver() == null ? null : "Sujula delivery",
                lead == null || lead.getPickupPoint() == null ? null : lead.getPickupPoint().getName(),
                lead == null ? null : lead.getEstimatedDeliveryAt(),
                lead == null ? null : lead.getDeliveredAt(),
                events);
    }

    private List<BuyerOrderResponses.Refund> refundsFor(Order order) {
        return refunds.findByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                .map(refund -> new BuyerOrderResponses.Refund(
                        refund.getId(), refund.getReference(),
                        refund.getVendorOrder() == null ? null : refund.getVendorOrder().getId(),
                        refund.getVendorOrder() == null || refund.getVendorOrder().getVendor() == null
                                ? null : refund.getVendorOrder().getVendor().getStoreName(),
                        refund.getStatus(), refund.getAmount(), refund.getCurrency(),
                        refund.getReason(), refund.getDecisionNote(),
                        refund.getCreatedAt(), refund.getDecidedAt(), refund.getCompletedAt()))
                .toList();
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    private Order requireOwn(Long orderId, Long userId) {
        return orders.findByIdAndCustomerId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    private static VendorOrder requireSlice(Order order, Long vendorOrderId) {
        return order.getVendorOrders().stream()
                .filter(slice -> slice.getId().equals(vendorOrderId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Vendor order", vendorOrderId));
    }

    private Set<Long> reviewedProductIds(Long userId) {
        return Set.copyOf(reviews.findProductIdsReviewedBy(userId));
    }

    /**
     * Appends a status transition to the order's history.
     *
     * <p>{@code from} is passed in rather than read off the order, because by
     * the time this is called the order is already carrying the new status —
     * reading it here would write a row saying CANCELLED became CANCELLED, and
     * the history is the only record of what it was before.
     */
    private void record(Order order, OrderStatus from, OrderStatus to, String note, Long userId) {
        history.save(OrderStatusHistory.builder()
                .order(order)
                .fromStatus(from)
                .toStatus(to)
                .notes(note)
                .changedBy(users.findById(userId).orElse(null))
                .changedAt(LocalDateTime.now())
                .build());
    }

    /**
     * Whether the buyer's money was actually taken for this order.
     *
     * <p>Not simply {@code == PAID}. Cancelling a second seller's goods after
     * the first has been refunded finds the order at PARTIALLY_REFUNDED, and
     * reading that as "never paid" would cancel the slice and quietly raise no
     * refund at all — the buyer keeps neither the goods nor the money, and
     * nothing in the system is left saying so.
     */
    private static boolean wasCharged(Order order) {
        return order.getPaymentStatus() == PaymentStatus.PAID
                || order.getPaymentStatus() == PaymentStatus.PARTIALLY_REFUNDED
                || order.getPaymentStatus() == PaymentStatus.AUTHORIZED;
    }

    private static boolean isCancelled(VendorOrder slice) {
        return slice.getStatus() == VendorOrderStatus.CANCELLED
                || slice.getStatus() == VendorOrderStatus.REFUNDED;
    }

    /** The whole order can only be stopped while every part of it can be. */
    private static boolean isWhollyCancellable(Order order) {
        if (order.getStatus() == OrderStatus.CANCELLED || order.getVendorOrders() == null) {
            return false;
        }
        return order.getVendorOrders().stream()
                .allMatch(slice -> slice.isPreDispatch() || isCancelled(slice));
    }

    private static String leadImage(Order order) {
        return order.getItems() == null ? null : order.getItems().stream()
                .map(OrderItem::getProductImageUrl)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String newRefundReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = "REF-" + UUID.randomUUID().toString()
                    .substring(0, 8).toUpperCase(Locale.ROOT);
            if (!refunds.existsByReference(reference)) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not allocate a refund reference");
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
