package com.sujula.service.fulfilment.impl;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.fulfilment.FulfilmentRequests;
import com.sujula.dto.response.fulfilment.FulfilmentResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.constant.StockMovementReason;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.inventory.ImeiUnit;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.RefundRequest;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.inventory.ImeiUnitRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.RefundRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.fulfilment.ParcelLabelRenderer;
import com.sujula.service.fulfilment.VendorFulfilmentService;
import com.sujula.service.inventory.Imei;
import com.sujula.service.inventory.StockLedger;

import lombok.extern.slf4j.Slf4j;

/**
 * The seller's half of the custody chain.
 *
 * <p>Four rules shape every method here.
 *
 * <p><b>C4.</b> A seller reaches READY_FOR_PICKUP and stops. Nothing on this
 * class sets SHIPPED or DELIVERED, because both are things somebody else proves:
 * the first when a driver presents the release code, the second when the
 * recipient does. A status a seller could set directly is a custody chain with a
 * hole in it.
 *
 * <p><b>C3.</b> Rejecting cancels one vendor's slice. The rest of the payment is
 * untouched — another seller's lines ship as though nothing happened, and the
 * refund that is raised is for this slice's amount, never a proportion of the
 * order.
 *
 * <p><b>C1.</b> The only buyer information that crosses into a seller's hands
 * comes from the delivery snapshot, never the payer. A seller learns the
 * recipient's name and town; they do not learn who paid, from where, or in what
 * currency.
 *
 * <p><b>Money never moves here.</b> Rejecting raises a refund <em>request</em>.
 * An administrator decides, because money leaving the platform is the one action
 * no later call can undo.
 */
@Slf4j
@Service
public class VendorFulfilmentServiceImpl implements VendorFulfilmentService {

    /**
     * How long a release code stands.
     *
     * <p>Long enough for a driver who was routed this morning to arrive this
     * afternoon, short enough that a code written on a whiteboard last week does
     * not still open a parcel. Reissuing is cheap and is the answer when a
     * collection slips.
     */
    private static final Duration CODE_LIFETIME = Duration.ofHours(72);

    /** The window the reissue limit counts over, and the ceiling within it. */
    private static final Duration REISSUE_WINDOW = Duration.ofHours(1);
    private static final int MAX_CODES_PER_WINDOW = 5;

    /** Cryptographic, because the code is the only thing protecting the parcel. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VendorOrderRepository vendorOrders;
    private final VendorRepository vendors;
    private final OrderRepository orders;
    private final RefundRequestRepository refunds;
    private final UserRepository users;
    private final ImeiUnitRepository imeiUnits;
    private final HandoverCodeRepository handoverCodes;
    private final StockLedger ledger;
    private final ParcelLabelRenderer labels;

    /**
     * Shared with the seller's order detail screen, so the button the screen
     * offers and the rule this service enforces cannot drift apart.
     */
    private final com.sujula.service.fulfilment.FulfilmentView view;

    /** Where a rejected slice's goods go back to, phrased for the seller's ledger. */
    private static final String RETURNED_NOTE = "Returned to the shelf: the seller rejected the order";

    public VendorFulfilmentServiceImpl(VendorOrderRepository vendorOrders, VendorRepository vendors,
                                       OrderRepository orders, RefundRequestRepository refunds,
                                       UserRepository users, ImeiUnitRepository imeiUnits,
                                       HandoverCodeRepository handoverCodes, StockLedger ledger,
                                       ParcelLabelRenderer labels,
                                       com.sujula.service.fulfilment.FulfilmentView view) {
        this.vendorOrders = vendorOrders;
        this.vendors = vendors;
        this.orders = orders;
        this.refunds = refunds;
        this.users = users;
        this.imeiUnits = imeiUnits;
        this.handoverCodes = handoverCodes;
        this.ledger = ledger;
        this.labels = labels;
        this.view = view;
    }

    // ── Accept ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public FulfilmentResponses.Accepted accept(Long vendorUserId, Long vendorOrderId) {
        Vendor vendor = requireVendor(vendorUserId);
        VendorOrder slice = requireOwnSlice(vendorOrderId, vendor);

        if (slice.getStatus() == VendorOrderStatus.PREPARING) {
            // Accepting twice is a double-tapped button on a bad connection,
            // which on this marketplace is the ordinary case rather than an
            // error. Same answer, no second acceptance.
            return new FulfilmentResponses.Accepted(slice.getId(), orderNumber(slice),
                    slice.getStatus(), slice.getAcceptedAt(),
                    "You had already accepted this order.");
        }
        if (slice.getStatus() != VendorOrderStatus.PENDING) {
            throw new BadRequestException(
                    "Only a new order can be accepted, and this one is " + slice.getStatus() + ".");
        }

        slice.setStatus(VendorOrderStatus.PREPARING);
        slice.setAcceptedAt(LocalDateTime.now());
        vendorOrders.save(slice);

        log.info("[Fulfilment] Vendor {} accepted slice {} of order {}",
                vendor.getId(), slice.getId(), orderNumber(slice));

        return new FulfilmentResponses.Accepted(slice.getId(), orderNumber(slice),
                slice.getStatus(), slice.getAcceptedAt(),
                "Accepted. Pack the items, then mark the order ready for collection.");
    }

    // ── Reject ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public FulfilmentResponses.Rejected reject(Long vendorUserId, Long vendorOrderId,
                                               FulfilmentRequests.Reject request) {
        String reason = request == null ? null : request.cleaned();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(
                    "Say why you cannot fulfil this order — the buyer is told, and has already paid.");
        }

        Vendor vendor = requireVendor(vendorUserId);
        VendorOrder slice = requireOwnSlice(vendorOrderId, vendor);

        if (slice.getStatus() == VendorOrderStatus.CANCELLED) {
            return new FulfilmentResponses.Rejected(slice.getId(), orderNumber(slice),
                    slice.getStatus(), slice.getCancelledAt(), slice.getRejectionReason(),
                    openRefundReference(slice), isOrderFullyCancelled(slice), 0,
                    "You had already rejected this order.");
        }
        if (!slice.getStatus().isPreDispatch()) {
            throw new BadRequestException(
                    "These goods are already with a courier, so they cannot be rejected. "
                            + "Contact support to arrange a return.");
        }

        Order order = slice.getOrder();

        slice.setStatus(VendorOrderStatus.CANCELLED);
        slice.setCancelledAt(LocalDateTime.now());
        slice.setRejectionReason(reason);
        vendorOrders.save(slice);

        // A packed parcel that is no longer going anywhere must not still have a
        // code that opens it.
        invalidateLiveCodes(slice, "the order was rejected");

        int returned = returnGoodsToShelf(slice, order, vendorUserId);
        String refundReference = requestRefund(order, slice, reason, vendorUserId);
        boolean fullyCancelled = closeOrderIfNothingLeft(order, slice);

        log.info("[Fulfilment] Vendor {} rejected slice {} of order {}; {} lines restocked, refund {}",
                vendor.getId(), slice.getId(), orderNumber(slice), returned,
                refundReference == null ? "not required" : refundReference);

        return new FulfilmentResponses.Rejected(slice.getId(), orderNumber(slice),
                slice.getStatus(), slice.getCancelledAt(), reason, refundReference,
                fullyCancelled, returned,
                refundReference == null
                        ? "Order rejected. Nothing had been charged, so there is nothing to refund."
                        : "Order rejected. The buyer's refund has been requested and is with the "
                                + "platform to approve — reference " + refundReference + ".");
    }

    /**
     * Puts the slice's goods back, through the ledger rather than around it.
     *
     * <p>Checkout took this stock when the order was placed, so rejecting has to
     * give it back or the shelf drifts from the screen by exactly the orders
     * sellers turned down. Handsets bound to the lines are released too: a
     * handset held for an order that is not happening is a phone the shop cannot
     * sell and cannot explain.
     */
    private int returnGoodsToShelf(VendorOrder slice, Order order, Long actorUserId) {
        User actor = users.findById(actorUserId).orElse(null);
        String reference = order != null ? order.getOrderNumber() : null;
        int restocked = 0;

        for (OrderItem item : slice.getItems()) {
            int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
            if (quantity <= 0) {
                continue;
            }

            List<ImeiUnit> bound = imeiUnits.findByOrderItemId(item.getId());
            for (ImeiUnit unit : bound) {
                // Back on the shelf, and no longer claiming to be on an order.
                unit.setStatus(ImeiStatus.IN_STOCK);
                unit.setOrderItem(null);
                unit.setAssignedAt(null);
                imeiUnits.save(unit);
            }
            item.setAssignedImeis(null);
            item.setImeiAssignedAt(null);

            if (!bound.isEmpty()) {
                // A serialised variant's stock is the count of sellable units, so
                // releasing the units above is the restock. Adding to the figure
                // as well would count the same phones twice.
                restocked++;
                continue;
            }

            if (item.getVariant() != null) {
                ledger.adjustVariant(item.getVariant(), quantity, StockMovementReason.RETURN,
                        reference, RETURNED_NOTE, actor);
            } else if (item.getProduct() != null) {
                ledger.adjustProduct(item.getProduct(), quantity, StockMovementReason.RETURN,
                        reference, RETURNED_NOTE, actor);
            } else {
                continue;
            }
            restocked++;
        }
        return restocked;
    }

    /**
     * Asks for the buyer's money back. Does not send it.
     *
     * <p>Returns null when nothing was ever taken, and the existing reference
     * when a refund is already open — asking twice must not queue two refunds
     * against the same goods, one of which an administrator might approve after
     * the other has already paid.
     */
    private String requestRefund(Order order, VendorOrder slice, String reason, Long actorUserId) {
        if (order == null || !wasCharged(order)) {
            return null;
        }
        Optional<RefundRequest> open = refunds.findOpenForVendorOrder(
                slice.getId(), List.of(RefundRequestStatus.REQUESTED, RefundRequestStatus.APPROVED));
        if (open.isPresent()) {
            return open.get().getReference();
        }

        String reference = newRefundReference();
        refunds.save(RefundRequest.builder()
                .reference(reference)
                .order(order)
                .vendorOrder(slice)
                .requestedBy(users.findById(actorUserId).orElse(null))
                .status(RefundRequestStatus.REQUESTED)
                // What the buyer paid for this slice, in what they paid it in.
                .amount(slice.getTotal())
                .currency(order.getCurrency())
                // And the same sum in the seller's currency, which is what is
                // clawed back from their payout. Carried, never re-derived: by
                // the time a refund settles the rate has moved.
                .amountNative(slice.getTotalNative())
                .fx(slice.getFx())
                .reason("The seller could not fulfil this order: " + reason)
                .createdAt(LocalDateTime.now())
                .build());
        return reference;
    }

    /**
     * Closes the whole order only when every slice of it is cancelled.
     *
     * <p>One seller pulling out does not cancel another's shipment — that is the
     * entire point of splitting a payment into sub-orders.
     */
    private boolean closeOrderIfNothingLeft(Order order, VendorOrder justCancelled) {
        if (order == null) {
            return false;
        }
        boolean anythingLeft = vendorOrders.findByOrderId(order.getId()).stream()
                .filter(other -> !other.getId().equals(justCancelled.getId()))
                .anyMatch(other -> other.getStatus() != VendorOrderStatus.CANCELLED);
        if (anythingLeft) {
            return false;
        }
        order.setStatus(OrderStatus.CANCELLED);
        orders.save(order);
        return true;
    }

    private boolean isOrderFullyCancelled(VendorOrder slice) {
        Order order = slice.getOrder();
        return order != null && order.getStatus() == OrderStatus.CANCELLED;
    }

    // ── Ready ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public FulfilmentResponses.Ready ready(Long vendorUserId, Long vendorOrderId) {
        Vendor vendor = requireVendor(vendorUserId);
        VendorOrder slice = requireOwnSlice(vendorOrderId, vendor);

        if (slice.getStatus() == VendorOrderStatus.READY_FOR_PICKUP) {
            HandoverCode live = handoverCodes.findLiveReleaseCode(slice.getId())
                    .orElseGet(() -> issueCode(slice, false));
            return new FulfilmentResponses.Ready(slice.getId(), orderNumber(slice), slice.getStatus(),
                    slice.getReadyAt(), asReleaseCode(live, slice, false),
                    "This order was already ready. The collection code is unchanged.");
        }
        if (slice.getStatus() != VendorOrderStatus.PREPARING) {
            throw new BadRequestException(
                    "Accept the order before marking it ready. It is currently " + slice.getStatus() + ".");
        }

        requireHandsetsBound(slice);

        slice.setStatus(VendorOrderStatus.READY_FOR_PICKUP);
        slice.setReadyAt(LocalDateTime.now());
        HandoverCode code = issueCode(slice, false);
        vendorOrders.save(slice);

        log.info("[Fulfilment] Slice {} of order {} is ready for collection",
                slice.getId(), orderNumber(slice));

        return new FulfilmentResponses.Ready(slice.getId(), orderNumber(slice), slice.getStatus(),
                slice.getReadyAt(), asReleaseCode(code, slice, false),
                "Ready for collection. Give the collection code to the driver — do not write it "
                        + "on the parcel.");
    }

    /**
     * Refuses to pack a serialised line whose handsets are not bound.
     *
     * <p>This is the last moment at which the platform can still say which
     * physical phone is in which box. After it, a warranty claim is
     * unanswerable and a stolen-handset report names an order rather than a
     * handset. The message says which line and how many, because a seller with a
     * bench of twenty phones needs to know which one to scan.
     */
    private void requireHandsetsBound(VendorOrder slice) {
        List<String> outstanding = new ArrayList<>();
        for (OrderItem item : slice.getItems()) {
            int required = view.requiredHandsets(item);
            if (required <= 0) {
                continue;
            }
            int bound = item.assignedImeiList().size();
            if (bound < required) {
                outstanding.add(item.getProductName() + " (" + (required - bound)
                        + " of " + required + " still to scan)");
            }
        }
        if (!outstanding.isEmpty()) {
            throw new BadRequestException(
                    "Scan the handsets before packing: " + String.join("; ", outstanding)
                            + ". Once the parcel has gone nobody can say which phone was in it.");
        }
    }

    // ── The release code ─────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public FulfilmentResponses.ReleaseCode releaseCode(Long vendorUserId, Long vendorOrderId) {
        Vendor vendor = requireVendor(vendorUserId);
        VendorOrder slice = requireOwnSlice(vendorOrderId, vendor);

        HandoverCode code = handoverCodes.findLiveReleaseCode(slice.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "There is no collection code for this order yet. Mark it ready for "
                                + "collection and one is issued."));

        if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException(
                    "This collection code expired on " + code.getExpiresAt()
                            + ". Generate a new one before the driver arrives.");
        }
        return asReleaseCode(code, slice, false);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public FulfilmentResponses.ReleaseCode regenerateReleaseCode(Long vendorUserId, Long vendorOrderId) {
        Vendor vendor = requireVendor(vendorUserId);
        VendorOrder slice = requireOwnSlice(vendorOrderId, vendor);

        if (slice.getStatus() != VendorOrderStatus.READY_FOR_PICKUP) {
            throw new BadRequestException(
                    "A collection code belongs to a packed parcel, and this order is "
                            + slice.getStatus() + ".");
        }

        long issued = handoverCodes.countReleaseCodesSince(
                slice.getId(), LocalDateTime.now().minus(REISSUE_WINDOW));
        if (issued >= MAX_CODES_PER_WINDOW) {
            // The reason to reissue — somebody saw it — is also the reason
            // somebody would want a stream of them.
            throw new BadRequestException(
                    "This order's collection code has been reissued " + issued
                            + " times in the last hour, which is the limit. Wait, or contact "
                            + "support if the driver still cannot collect.");
        }

        invalidateLiveCodes(slice, "a new code was issued");
        HandoverCode code = issueCode(slice, true);
        vendorOrders.save(slice);

        // The code itself is not logged, here or anywhere. That it was reissued
        // is: a seller reissuing constantly is worth seeing.
        log.info("[Fulfilment] Release code reissued for slice {} of order {} ({} in the last hour)",
                slice.getId(), orderNumber(slice), issued + 1);

        return asReleaseCode(code, slice, true);
    }

    /** Mints a code and stamps the slice's issue count. */
    private HandoverCode issueCode(VendorOrder slice, boolean reissue) {
        LocalDateTime now = LocalDateTime.now();
        HandoverCode code = handoverCodes.save(HandoverCode.builder()
                .vendorOrder(slice)
                .codeType(HandoverCodeType.VENDOR_RELEASE)
                .code(sixDigits())
                .used(false)
                .expiresAt(now.plus(CODE_LIFETIME))
                .build());

        slice.setReleaseCodeIssueCount(
                (slice.getReleaseCodeIssueCount() == null ? 0 : slice.getReleaseCodeIssueCount()) + 1);
        slice.setReleaseCodeIssuedAt(now);
        if (reissue) {
            log.debug("[Fulfilment] Slice {} now on issue {}", slice.getId(), slice.getReleaseCodeIssueCount());
        }
        return code;
    }

    /**
     * Kills every live code on the slice.
     *
     * <p>Plural on purpose. There should only ever be one, but "should" is not a
     * guarantee across two concurrent reissues, and a second code still opening
     * the parcel is exactly the thing reissuing exists to prevent.
     */
    private void invalidateLiveCodes(VendorOrder slice, String why) {
        List<HandoverCode> live = handoverCodes.findLiveReleaseCodes(slice.getId());
        if (live.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (HandoverCode code : live) {
            code.setInvalidatedAt(now);
        }
        handoverCodes.saveAll(live);
        log.info("[Fulfilment] {} release code(s) invalidated on slice {}: {}",
                live.size(), slice.getId(), why);
    }

    private FulfilmentResponses.ReleaseCode asReleaseCode(HandoverCode code, VendorOrder slice,
                                                          boolean reissued) {
        int times = slice.getReleaseCodeIssueCount() == null ? 1 : slice.getReleaseCodeIssueCount();
        return new FulfilmentResponses.ReleaseCode(
                code.getCode(), code.getCreatedAt() != null ? code.getCreatedAt() : LocalDateTime.now(),
                code.getExpiresAt(), times, reissued,
                reissued
                        ? "The previous code no longer works. Read this one out to the driver — "
                                + "do not write it on the parcel."
                        : "Read this out to the driver when they collect. Do not write it on the "
                                + "parcel: anyone who can see the box could then take it.");
    }

    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    // ── Label ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ParcelLabel label(Long vendorUserId, Long vendorOrderId) {
        Vendor vendor = requireVendor(vendorUserId);
        VendorOrder slice = requireOwnSlice(vendorOrderId, vendor);

        if (slice.getStatus() == VendorOrderStatus.PENDING) {
            throw new BadRequestException(
                    "Accept the order before printing a label for it.");
        }
        if (slice.getStatus() == VendorOrderStatus.CANCELLED
                || slice.getStatus() == VendorOrderStatus.REFUNDED) {
            throw new BadRequestException(
                    "This order is " + slice.getStatus() + ", so there is no parcel to label.");
        }
        return labels.render(slice, vendor, view.shippingFor(slice));
    }

    // ── Handsets ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public FulfilmentResponses.ImeiAssigned assignImei(Long vendorUserId, Long vendorOrderId,
                                                       Long lineId,
                                                       FulfilmentRequests.AssignImei request) {
        Vendor vendor = requireVendor(vendorUserId);
        VendorOrder slice = requireOwnSlice(vendorOrderId, vendor);

        if (!slice.getStatus().isPreDispatch()) {
            throw new BadRequestException(
                    "This parcel has already left, so the handsets on it cannot be changed.");
        }
        if (slice.getStatus() == VendorOrderStatus.PENDING) {
            throw new BadRequestException("Accept the order before scanning handsets onto it.");
        }

        OrderItem line = slice.getItems().stream()
                .filter(item -> item.getId() != null && item.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Order line", lineId));

        int required = view.requiredHandsets(line);
        if (required <= 0) {
            throw new BadRequestException(
                    line.getProductName() + " is not sold handset by handset, so there is no IMEI "
                            + "to record against it.");
        }
        List<String> alreadyBound = line.assignedImeiList();
        if (alreadyBound.size() >= required) {
            throw new BadRequestException(
                    "All " + required + " handset(s) for this line are already scanned. Remove one "
                            + "first if you picked the wrong phone.");
        }

        // Length and check digit, with different messages: a wrong length is a
        // shape mistake and a bad check digit is a transcription mistake.
        String imei = Imei.normalise(request == null ? null : request.cleaned());

        if (alreadyBound.contains(imei)) {
            throw new BadRequestException(
                    "That handset is already on this line. Scan the other one.");
        }

        ImeiUnit unit = imeiUnits.findByImei(imei)
                .orElseThrow(() -> new BadRequestException(
                        "Handset " + imei + " is not registered in your shop. Register it under "
                                + "the product first, then scan it onto the order."));

        requireOwnUnit(unit, vendor, imei);
        requireUnitMatchesLine(unit, line, imei);
        requireUnitAvailable(unit, line, imei);

        unit.setStatus(ImeiStatus.RESERVED);
        unit.setOrderItem(line);
        unit.setAssignedAt(LocalDateTime.now());
        unit.setSoldOnOrderNumber(orderNumber(slice));
        imeiUnits.save(unit);

        line.bindImei(imei);

        int bound = line.assignedImeiList().size();
        boolean lineComplete = bound >= required;
        boolean orderReady = lineComplete && view.everyLineBound(slice);

        // The IMEI is the identity of a physical object worth more than most of
        // what this marketplace moves, so it is logged by unit id rather than by
        // number.
        log.info("[Fulfilment] Handset unit {} bound to line {} of slice {} ({} of {})",
                unit.getId(), line.getId(), slice.getId(), bound, required);

        return new FulfilmentResponses.ImeiAssigned(slice.getId(), line.getId(), imei,
                bound, required, lineComplete, line.assignedImeiList(), orderReady,
                lineComplete
                        ? (orderReady
                            ? "Every handset is scanned. You can mark the order ready for collection."
                            : "This line is complete. Other lines still need handsets scanning.")
                        : "Scanned. " + (required - bound) + " still to go on this line.");
    }

    private void requireOwnUnit(ImeiUnit unit, Vendor vendor, String imei) {
        Long owner = unit.getVendor() == null ? null : unit.getVendor().getId();
        if (owner == null || !owner.equals(vendor.getId())) {
            // Another shop's handset. Phrased as not-yours rather than
            // whose-it-is: confirming which shop holds a given IMEI would make
            // this endpoint a lookup service for stolen handsets.
            throw new BadRequestException(
                    "Handset " + imei + " is not registered in your shop.");
        }
    }

    private void requireUnitMatchesLine(ImeiUnit unit, OrderItem line, String imei) {
        Long unitVariant = unit.getVariant() == null ? null : unit.getVariant().getId();
        Long lineVariant = line.getVariant() == null ? null : line.getVariant().getId();
        if (unitVariant == null || !unitVariant.equals(lineVariant)) {
            throw new BadRequestException(
                    "Handset " + imei + " is not the model this line was bought as. The buyer "
                            + "ordered " + line.getProductName()
                            + (line.getVariantSku() == null ? "" : " (" + line.getVariantSku() + ")")
                            + " — sending a different one is a dispute you will lose.");
        }
    }

    private void requireUnitAvailable(ImeiUnit unit, OrderItem line, String imei) {
        ImeiStatus status = unit.getStatus();
        if (status == ImeiStatus.IN_STOCK) {
            return;
        }
        if (status == ImeiStatus.RESERVED && unit.getOrderItem() != null
                && unit.getOrderItem().getId() != null
                && unit.getOrderItem().getId().equals(line.getId())) {
            return;   // already this line's; the caller retried
        }
        String why = switch (status) {
            case RESERVED -> "already held for another order";
            case SOLD -> "already sold";
            case RETURNED -> "back from a buyer and not yet checked";
            case IN_REPAIR -> "away for repair";
            case WRITTEN_OFF -> "written off";
            case BLOCKED -> "reported stolen and cannot be sold";
            case IN_STOCK -> "available";
        };
        throw new BadRequestException("Handset " + imei + " is " + why + ".");
    }

    // ── Scoping and small helpers ────────────────────────────────────────────

    private Vendor requireVendor(Long vendorUserId) {
        return vendors.findByUserId(vendorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor for user", vendorUserId));
    }

    /**
     * The vendor id is part of the lookup, so another seller's slice never comes
     * back at all. Not found rather than forbidden: a probe must not confirm the
     * id was real.
     */
    private VendorOrder requireOwnSlice(Long vendorOrderId, Vendor vendor) {
        return vendorOrders.findByIdAndVendorId(vendorOrderId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("VendorOrder", vendorOrderId));
    }

    private static String orderNumber(VendorOrder slice) {
        return slice.getOrder() == null ? null : slice.getOrder().getOrderNumber();
    }

    private static boolean wasCharged(Order order) {
        PaymentStatus status = order.getPaymentStatus();
        return status == PaymentStatus.PAID || status == PaymentStatus.PARTIALLY_REFUNDED;
    }

    private String openRefundReference(VendorOrder slice) {
        return refunds.findOpenForVendorOrder(slice.getId(),
                        List.of(RefundRequestStatus.REQUESTED, RefundRequestStatus.APPROVED))
                .map(RefundRequest::getReference)
                .orElse(null);
    }

    private String newRefundReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = "REF-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 10).toUpperCase();
            if (!refunds.existsByReference(reference)) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not allocate a refund reference");
    }
}
