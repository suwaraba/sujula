package com.sujula.service.aftersales.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.aftersales.Dispute;
import com.sujula.model.aftersales.ReturnLine;
import com.sujula.model.aftersales.ReturnRequest;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.ReturnStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.aftersales.ReturnRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.aftersales.DisputeService;
import com.sujula.service.aftersales.ReturnService;
import com.sujula.service.reference.CurrencyCatalogue;

import lombok.extern.slf4j.Slf4j;

/**
 * The return flow, from both ends.
 *
 * <p>Ownership is always in the query: a buyer's return is found by the buyer's
 * id and a seller's by the seller's, and anything else is not found rather than
 * refused. "Forbidden" would confirm that a guessed id is a live return, and
 * these rows carry an order, photographs of somebody's home and what one party
 * said about the other.
 */
@Slf4j
@Service
public class ReturnServiceImpl implements ReturnService {

    /**
     * How long after delivery a return may be opened.
     *
     * <p>Counted from the parcel arriving rather than from the order, because on
     * this route those are three weeks apart and a window from the order would
     * have expired before the box did.
     */
    private static final int RETURN_WINDOW_DAYS = 14;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final ReturnRequestRepository returns;
    private final VendorOrderRepository vendorOrders;
    private final com.sujula.repository.shipment.ShipmentRepository shipments;
    private final VendorRepository vendors;
    private final UserRepository users;
    private final CurrencyCatalogue currencies;
    private final DisputeService disputes;

    public ReturnServiceImpl(ReturnRequestRepository returns, VendorOrderRepository vendorOrders,
                             com.sujula.repository.shipment.ShipmentRepository shipments,
                             VendorRepository vendors, UserRepository users,
                             CurrencyCatalogue currencies, DisputeService disputes) {
        this.returns = returns;
        this.vendorOrders = vendorOrders;
        this.shipments = shipments;
        this.vendors = vendors;
        this.users = users;
        this.currencies = currencies;
        this.disputes = disputes;
    }

    // ── Opening one ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReturnDetail open(Long userId,
                                                 AfterSalesRequests.OpenReturn request) {
        VendorOrder slice = vendorOrders.findById(request.vendorOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order", request.vendorOrderId()));
        requireBuyerOf(slice, userId);
        requireInsideTheWindow(slice, deliveredAt(slice), request.reason());

        if (!returns.findOpenForSlice(slice.getId()).isEmpty()) {
            // Two returns on one slice is two refunds the day two people happen
            // to approve them.
            throw new BadRequestException(
                    "You already have a return open on this part of the order. Add to that one "
                            + "rather than starting another.");
        }

        String currency = slice.getOrder() == null ? null : slice.getOrder().getCurrency();
        String nativeCurrency = slice.getNativeCurrency();

        ReturnRequest returnRequest = ReturnRequest.builder()
                .reference(newReference("RTN"))
                .vendorOrder(slice)
                .requestedBy(users.findById(userId).orElse(null))
                .status(ReturnStatus.REQUESTED)
                .reason(request.reason())
                .description(request.description())
                .photoUrls(joinLines(request.photoUrls()))
                .currency(currency)
                // Frozen from the reason at the moment it is opened. A seller who
                // disagrees argues about the reason, not about a number that
                // moved after the buyer read it.
                .sellerPaysCarriage(request.reason().sellerPaysCarriage())
                // Carried from the order, never read again. A return settled next
                // month at next month's rate is one where somebody quietly lost
                // money on the exchange (C2).
                .fx(slice.getFx())
                .build();

        BigDecimal claimed = BigDecimal.ZERO;
        BigDecimal claimedNative = BigDecimal.ZERO;
        List<ReturnLine> lines = new ArrayList<>();

        for (AfterSalesRequests.ReturnItem item : request.items()) {
            OrderItem orderItem = itemOf(slice, item.orderItemId());
            requireQuantityAvailable(orderItem, item.quantity());

            BigDecimal unitNative = orderItem.getUnitPrice() == null
                    ? BigDecimal.ZERO : orderItem.getUnitPrice();
            BigDecimal unitDisplay = orderItem.getUnitPriceConverted() == null
                    ? BigDecimal.ZERO : orderItem.getUnitPriceConverted();
            BigDecimal quantity = BigDecimal.valueOf(item.quantity());

            claimed = claimed.add(unitDisplay.multiply(quantity));
            claimedNative = claimedNative.add(unitNative.multiply(quantity));

            lines.add(ReturnLine.builder()
                    .returnRequest(returnRequest)
                    .orderItem(orderItem)
                    .quantity(item.quantity())
                    // Snapshotted. A seller who later changes a price must not
                    // settle an open return at the new one.
                    .unitPrice(unitDisplay)
                    .unitPriceNative(unitNative)
                    .productName(orderItem.getProductName())
                    .assignedImeis(orderItem.getAssignedImeis())
                    .build());
        }

        returnRequest.setAmount(round(claimed, currency));
        returnRequest.setAmountNative(round(claimedNative, nativeCurrency));
        returnRequest.getLines().addAll(lines);

        ReturnRequest saved = returns.save(returnRequest);
        log.info("[Returns] {} opened on slice {} by user {} — {} line(s), {} {}",
                saved.getReference(), slice.getId(), userId, lines.size(),
                saved.getAmount(), currency);
        return detailOf(saved, userId);
    }

    /**
     * When the parcel actually reached somebody.
     *
     * <p>Read off the shipment rather than off the order, because on this
     * platform delivery is not a status anybody sets — it is what the custody
     * chain says happened (C4). Falls back to the buyer confirming receipt,
     * which is the other way a slice is known to have arrived.
     */
    private LocalDateTime deliveredAt(VendorOrder slice) {
        return shipments.findByVendorOrderId(slice.getId())
                .map(com.sujula.model.shipment.Shipment::getDeliveredAt)
                .filter(java.util.Objects::nonNull)
                .orElseGet(slice::getReceiptConfirmedAt);
    }

    /**
     * Refuses a return the window has closed on.
     *
     * <p>Counted from delivery, and deliberately generous where there is no
     * delivery to count from: a parcel that never arrived has no clock to start,
     * and a buyer who cannot open a "it never came" return because the order is
     * old has been beaten by the very problem they are reporting.
     */
    private static void requireInsideTheWindow(VendorOrder slice, LocalDateTime delivered,
                                               com.sujula.model.constant.ReturnReason reason) {
        if (reason == com.sujula.model.constant.ReturnReason.NEVER_ARRIVED) {
            return;
        }
        if (delivered == null) {
            // Not yet delivered. Returning something that has not arrived is not
            // a return — it is a cancellation, and that lives elsewhere.
            throw new BadRequestException(
                    "This part of the order has not been delivered yet. If you no longer want it, "
                            + "cancel it instead; if it has arrived and our records are wrong, open "
                            + "a return saying it never arrived.");
        }
        if (delivered.plusDays(RETURN_WINDOW_DAYS).isBefore(LocalDateTime.now())) {
            throw new BadRequestException(
                    "The " + RETURN_WINDOW_DAYS + "-day return window on this closed on "
                            + delivered.plusDays(RETURN_WINDOW_DAYS).toLocalDate()
                            + ". If something is wrong with it, message the seller — they can "
                            + "still help outside the window.");
        }
    }

    /** Refuses a quantity the buyer does not have left to return. */
    private void requireQuantityAvailable(OrderItem item, int wanted) {
        int bought = item.getQuantity() == null ? 0 : item.getQuantity();
        int claimed = returns.quantityAlreadyClaimed(item.getId());
        if (wanted > bought - claimed) {
            // Without this, three returns of one case each against two cases
            // bought refunds three of the two that were sold.
            throw new BadRequestException(
                    "You bought " + bought + " of " + item.getProductName()
                            + (claimed > 0 ? " and have already asked to return " + claimed : "")
                            + ", so you cannot return " + wanted + ".");
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public PagedResponse<AfterSalesResponses.ReturnSummary> list(Long userId, ReturnStatus status,
                                                                 Pageable pageable) {
        Optional<Vendor> asVendor = vendors.findByUserId(userId);
        Page<ReturnRequest> page = asVendor
                .map(vendor -> returns.findForVendor(vendor.getId(), status, pageable))
                .orElseGet(() -> returns.findForBuyer(userId, status, pageable));
        return PagedResponse.of(page.map(row -> summaryOf(row, userId)));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReturnDetail detail(Long userId, Long returnId) {
        return detailOf(requireParty(returnId, userId), userId);
    }

    // ── The seller's answers ─────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReturnDetail approve(Long userId, Long returnId,
                                                    AfterSalesRequests.ApproveReturn request) {
        ReturnRequest row = requireSeller(returnId, userId);
        requireStatus(row, "approve", ReturnStatus.REQUESTED);

        row.setStatus(ReturnStatus.APPROVED);
        row.setDecidedBy(users.findById(userId).orElse(null));
        row.setDecidedAt(LocalDateTime.now());
        row.setDecisionNote(request == null ? null : request.note());
        returns.save(row);

        log.info("[Returns] {} approved by seller {}", row.getReference(), userId);
        return detailOf(row, userId);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReturnDetail reject(Long userId, Long returnId,
                                                   AfterSalesRequests.RejectReturn request) {
        ReturnRequest row = requireSeller(returnId, userId);
        requireStatus(row, "reject", ReturnStatus.REQUESTED, ReturnStatus.APPROVED);

        row.setStatus(ReturnStatus.REJECTED);
        row.setDecidedBy(users.findById(userId).orElse(null));
        row.setDecidedAt(LocalDateTime.now());
        row.setDecisionNote(request.reason());
        returns.save(row);

        log.info("[Returns] {} rejected by seller {}", row.getReference(), userId);
        return detailOf(row, userId);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReturnDetail offerPartialRefund(
            Long userId, Long returnId, AfterSalesRequests.OfferPartialRefund request) {
        ReturnRequest row = requireSeller(returnId, userId);
        requireStatus(row, "make an offer on",
                ReturnStatus.REQUESTED, ReturnStatus.APPROVED, ReturnStatus.OFFER_MADE);

        BigDecimal offered = round(request.amount(), row.getCurrency());
        if (offered.compareTo(row.getAmount()) > 0) {
            throw new BadRequestException(
                    "You cannot offer more than the " + row.getAmount() + " " + row.getCurrency()
                            + " this return is for. If you want to give them more than they paid, "
                            + "that is a goodwill payment rather than a return.");
        }

        // The seller's half is computed at the order's own frozen rate rather
        // than sent. A seller who could name both figures independently could
        // name a rate, and the rate is not theirs to choose (C2).
        row.setOfferedAmount(offered);
        row.setOfferedAmountNative(toNative(offered, row));
        row.setOfferNote(request.note());
        row.setOfferedAt(LocalDateTime.now());
        // A new offer supersedes an unaccepted one. Accepting is what fixes it.
        row.setOfferAcceptedAt(null);
        row.setStatus(ReturnStatus.OFFER_MADE);
        returns.save(row);

        log.info("[Returns] {} — seller {} offered {} {} ({} {})", row.getReference(), userId,
                offered, row.getCurrency(), row.getOfferedAmountNative(), row.getVendorOrder().getNativeCurrency());
        return detailOf(row, userId);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReturnDetail markReceived(
            Long userId, Long returnId, AfterSalesRequests.ReturnReceived request) {
        ReturnRequest row = requireSeller(returnId, userId);
        requireStatus(row, "mark received", ReturnStatus.APPROVED);

        if (!row.getReason().hasGoodsToReturn()) {
            throw new BadRequestException(
                    "This return is about a parcel that never arrived, so there is nothing to "
                            + "receive. Settle it with a refund or escalate it.");
        }

        row.setStatus(ReturnStatus.RECEIVED);
        row.setReceivedAt(LocalDateTime.now());
        row.setReceivedBy(users.findById(userId).orElse(null));
        row.setReceivedNote(receivedNote(request));
        returns.save(row);

        log.info("[Returns] {} received by seller {}{}", row.getReference(), userId,
                request != null && Boolean.FALSE.equals(request.asExpected())
                        ? " — NOT as expected" : "");
        return detailOf(row, userId);
    }

    /**
     * What the seller said when they opened the box.
     *
     * <p>"Not as expected" is recorded as words rather than a flag, because it
     * is the sentence a dispute will turn on. A seller who receives a different
     * handset has to be able to say so at the moment they see it, and a system
     * that only offered "received" would make the next step an argument with
     * nothing behind it.
     */
    private static String receivedNote(AfterSalesRequests.ReturnReceived request) {
        if (request == null) {
            return null;
        }
        if (Boolean.FALSE.equals(request.asExpected())) {
            return "Seller reports what came back is not what was sent. "
                    + (request.note() == null ? "" : request.note());
        }
        return request.note();
    }

    // ── The buyer's answers ──────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReturnDetail acceptOffer(Long userId, Long returnId) {
        ReturnRequest row = requireBuyer(returnId, userId);
        requireStatus(row, "accept an offer on", ReturnStatus.OFFER_MADE);
        if (row.getOfferedAmount() == null) {
            throw new BadRequestException("There is no offer on this return to accept.");
        }

        row.setOfferAcceptedAt(LocalDateTime.now());
        row.setStatus(ReturnStatus.OFFER_ACCEPTED);
        returns.save(row);

        log.info("[Returns] {} — buyer {} accepted {} {}", row.getReference(), userId,
                row.getOfferedAmount(), row.getCurrency());
        return detailOf(row, userId);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReturnDetail escalate(Long userId, Long returnId,
                                                     AfterSalesRequests.EscalateReturn request) {
        ReturnRequest row = requireBuyer(returnId, userId);
        if (row.getStatus() == ReturnStatus.ESCALATED) {
            throw new BadRequestException(
                    "This is already with us. Add what you want us to know to the dispute rather "
                            + "than escalating again.");
        }
        if (row.getStatus().isFinished() && row.getStatus() != ReturnStatus.REJECTED) {
            throw new BadRequestException("This return is already settled.");
        }

        // Raising the dispute is what freezes the seller's money, and it is done
        // there rather than here — there is exactly one writer of that freeze and
        // this is not it.
        Dispute dispute = disputes.escalateFromReturn(userId, row, request.description(),
                reasonFor(row));

        row.setStatus(ReturnStatus.ESCALATED);
        row.setEscalatedAt(LocalDateTime.now());
        row.setDispute(dispute);
        returns.save(row);

        log.info("[Returns] {} escalated to dispute {} by buyer {}",
                row.getReference(), dispute.getReference(), userId);
        return detailOf(row, userId);
    }

    /** The dispute reason that follows from why the goods were going back. */
    private static DisputeReason reasonFor(ReturnRequest row) {
        return switch (row.getReason()) {
            case NEVER_ARRIVED -> DisputeReason.NOT_RECEIVED;
            case DAMAGED_IN_TRANSIT, FAULTY -> DisputeReason.DAMAGED;
            case NOT_AS_DESCRIBED, WRONG_ITEM, MISSING_PARTS -> DisputeReason.NOT_AS_DESCRIBED;
            // A change of mind that reached a dispute is not about the goods at
            // all — it is about the seller refusing the return.
            case NO_LONGER_WANTED -> DisputeReason.RETURN_REFUSED;
        };
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    private ReturnRequest requireParty(Long returnId, Long userId) {
        return returns.findByIdForParty(returnId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Return", returnId));
    }

    private ReturnRequest requireBuyer(Long returnId, Long userId) {
        return returns.findByIdAndBuyerId(returnId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Return", returnId));
    }

    private ReturnRequest requireSeller(Long returnId, Long userId) {
        Vendor vendor = vendors.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Return", returnId));
        return returns.findByIdAndVendorId(returnId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Return", returnId));
    }

    private static void requireBuyerOf(VendorOrder slice, Long userId) {
        Order order = slice.getOrder();
        boolean theirs = order != null && order.getCustomer() != null
                && userId.equals(order.getCustomer().getId());
        if (!theirs) {
            // Not-found rather than forbidden: "forbidden" would confirm that a
            // guessed id is somebody's live order.
            throw new ResourceNotFoundException("Order", slice.getId());
        }
    }

    private static OrderItem itemOf(VendorOrder slice, Long orderItemId) {
        for (OrderItem item : slice.getItems()) {
            if (item.getId().equals(orderItemId)) {
                return item;
            }
        }
        // Scoped to the slice, so an item id from another seller's part of the
        // same order is not found rather than quietly returned against the wrong
        // vendor.
        throw new ResourceNotFoundException("Order item", orderItemId);
    }

    private static void requireStatus(ReturnRequest row, String verb, ReturnStatus... allowed) {
        for (ReturnStatus candidate : allowed) {
            if (row.getStatus() == candidate) {
                return;
            }
        }
        throw new BadRequestException(
                "You cannot " + verb + " a return that is " + readable(row.getStatus()) + ".");
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private AfterSalesResponses.ReturnSummary summaryOf(ReturnRequest row, Long userId) {
        boolean seller = isSeller(row, userId);
        return new AfterSalesResponses.ReturnSummary(
                row.getId(), row.getReference(), row.getStatus(), row.getReason(),
                orderNumber(row), storeName(row),
                row.getLines() == null ? 0 : row.getLines().size(),
                money(row.getAmount(), row.getAmountNative(), row),
                money(row.settlementAmount(), row.settlementAmountNative(), row),
                waitingOn(row, seller), whatHappensNext(row, seller),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private AfterSalesResponses.ReturnDetail detailOf(ReturnRequest row, Long userId) {
        boolean seller = isSeller(row, userId);

        List<AfterSalesResponses.ReturnLineView> lines = new ArrayList<>();
        for (ReturnLine line : row.getLines()) {
            lines.add(new AfterSalesResponses.ReturnLineView(
                    line.getOrderItem() == null ? null : line.getOrderItem().getId(),
                    line.getProductName(), line.getQuantity(),
                    money(line.getUnitPrice(), line.getUnitPriceNative(), row),
                    splitLines(line.getAssignedImeis(), ",")));
        }

        AfterSalesResponses.Offer offer = row.getOfferedAt() == null ? null
                : new AfterSalesResponses.Offer(
                        money(row.getOfferedAmount(), row.getOfferedAmountNative(), row),
                        row.getOfferNote(), row.getOfferedAt(), row.getOfferAcceptedAt(),
                        row.getStatus() == ReturnStatus.OFFER_MADE);

        return new AfterSalesResponses.ReturnDetail(
                row.getId(), row.getReference(), row.getStatus(), row.getReason(),
                orderNumber(row), storeName(row),
                row.getDescription(), splitLines(row.getPhotoUrls(), "\n"),
                lines,
                money(row.getAmount(), row.getAmountNative(), row),
                offer,
                row.isSellerPaysCarriage(), carriageNote(row, seller),
                row.getDecisionNote(), row.getDecidedAt(),
                row.getReceivedAt(), row.getReceivedNote(),
                row.getDispute() == null ? null : row.getDispute().getId(),
                row.getDispute() == null ? null : row.getDispute().getReference(),
                row.getRefundReference(),
                waitingOn(row, seller), whatHappensNext(row, seller),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    /**
     * Who pays to get it back, said to whichever side is reading.
     *
     * <p>Two sentences rather than a boolean, because the same fact reads
     * differently from each end and getting it wrong costs somebody money they
     * did not expect to spend.
     */
    private static String carriageNote(ReturnRequest row, boolean seller) {
        if (row.getReason() == com.sujula.model.constant.ReturnReason.NEVER_ARRIVED) {
            return "Nothing is being sent back — this is about a parcel that did not arrive.";
        }
        if (row.isSellerPaysCarriage()) {
            return seller
                    ? "You pay the carriage on this one: the buyer is returning it because of "
                      + "something that went wrong, not because they changed their mind."
                    : "The seller pays the carriage on this one.";
        }
        return seller
                ? "The buyer pays the carriage: they have changed their mind rather than had "
                  + "something go wrong."
                : "You pay the carriage on this one, because nothing is wrong with the item.";
    }

    /** Whether the person reading is the one holding it up. */
    private static boolean waitingOn(ReturnRequest row, boolean seller) {
        return seller ? row.getStatus().awaitsSeller() : row.getStatus().awaitsBuyer();
    }

    /**
     * The next step in words, written for whichever side is reading.
     *
     * <p>A buyer in Madrid and a seller in Banjul are eight time zones apart and
     * neither can ask the other what is happening. A status word on its own makes
     * both of them guess.
     */
    private static String whatHappensNext(ReturnRequest row, boolean seller) {
        return switch (row.getStatus()) {
            case REQUESTED -> seller
                    ? "Decide whether to take it back, refuse it, or offer money instead."
                    : "The seller has been told. They will accept it, refuse it, or offer you "
                      + "money to keep it.";
            case APPROVED -> seller
                    ? "Waiting for the goods to come back. Mark them received when they do."
                    : (row.isSellerPaysCarriage()
                       ? "Send it back — the seller pays the carriage."
                       : "Send it back. The carriage is yours on this one.");
            case OFFER_MADE -> seller
                    ? "You have offered " + row.getOfferedAmount() + " " + row.getCurrency()
                      + ". Waiting for the buyer."
                    : "The seller has offered you " + row.getOfferedAmount() + " "
                      + row.getCurrency() + " to keep it. Take it, or escalate if it is not enough.";
            case OFFER_ACCEPTED -> seller
                    ? "The buyer accepted. The refund comes off your next payout."
                    : "You accepted. The money goes back the way you paid.";
            case RECEIVED -> seller
                    ? "You have the goods. The refund follows."
                    : "The seller has the goods back. Your refund follows.";
            case REFUNDED -> "Settled.";
            case REJECTED -> seller
                    ? "You refused this one. The buyer can ask us to look at it."
                    : "The seller refused. If you disagree, escalate it and somebody impartial "
                      + "will decide.";
            case ESCALATED -> "With us. Both of you can add to the dispute while we read it.";
            case WITHDRAWN -> "Withdrawn.";
        };
    }

    private boolean isSeller(ReturnRequest row, Long userId) {
        VendorOrder slice = row.getVendorOrder();
        return slice != null && slice.getVendor() != null && slice.getVendor().getUser() != null
                && userId.equals(slice.getVendor().getUser().getId());
    }

    private AfterSalesResponses.Money money(BigDecimal amount, BigDecimal amountNative,
                                            ReturnRequest row) {
        if (amount == null && amountNative == null) {
            return null;
        }
        FxSnapshot fx = row.getFx();
        return new AfterSalesResponses.Money(
                amount, row.getCurrency(),
                amountNative, row.getVendorOrder() == null ? null
                        : row.getVendorOrder().getNativeCurrency(),
                fx == null ? null : fx.getRate(),
                fx == null ? null : fx.getRateAt());
    }

    /**
     * A display figure in the seller's own currency, at the order's frozen rate.
     *
     * <p>Divided by the stored rate rather than looked up. The rate on the
     * snapshot is display-per-native, so going back the other way is a division —
     * and it is the rate from the day of the order, which is the only one either
     * party ever agreed to.
     */
    private BigDecimal toNative(BigDecimal display, ReturnRequest row) {
        FxSnapshot fx = row.getFx();
        String nativeCurrency = row.getVendorOrder() == null ? null
                : row.getVendorOrder().getNativeCurrency();
        if (fx == null || fx.getRate() == null || fx.getRate().signum() == 0) {
            return round(display, nativeCurrency);
        }
        // Eight places before rounding to the currency's real scale, because a
        // rate is not money and flattening it first would multiply the error.
        return round(display.divide(fx.getRate(), 8, RoundingMode.HALF_UP), nativeCurrency);
    }

    private BigDecimal round(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        // XOF has no minor units — 1250.50 CFA is not an amount that exists — so
        // this rounds to the currency's own scale rather than to two.
        return currency == null ? amount : currencies.round(amount, currency);
    }

    // ── Small things ─────────────────────────────────────────────────────────

    private static String orderNumber(ReturnRequest row) {
        VendorOrder slice = row.getVendorOrder();
        return slice == null || slice.getOrder() == null ? null : slice.getOrder().getOrderNumber();
    }

    private static String storeName(ReturnRequest row) {
        VendorOrder slice = row.getVendorOrder();
        return slice == null || slice.getVendor() == null ? null : slice.getVendor().getStoreName();
    }

    private static String readable(ReturnStatus status) {
        return status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static String joinLines(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("\n", values);
    }

    private static List<String> splitLines(String value, String separator) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(separator))
                .map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    private String newReference(String prefix) {
        for (int attempt = 0; attempt < 6; attempt++) {
            StringBuilder code = new StringBuilder(prefix).append('-');
            for (int i = 0; i < 10; i++) {
                code.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
            }
            String candidate = code.toString();
            if (!returns.existsByReference(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not mint a unique return reference");
    }
}
