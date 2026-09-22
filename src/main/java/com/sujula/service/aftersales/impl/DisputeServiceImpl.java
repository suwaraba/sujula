package com.sujula.service.aftersales.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
import com.sujula.model.aftersales.DisputeEvidence;
import com.sujula.model.aftersales.DisputeMessage;
import com.sujula.model.aftersales.ReturnRequest;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;
import com.sujula.repository.aftersales.DisputeEvidenceRepository;
import com.sujula.repository.aftersales.DisputeMessageRepository;
import com.sujula.repository.aftersales.DisputeRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.aftersales.DisputeService;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.reference.CurrencyCatalogue;

import lombok.extern.slf4j.Slf4j;

/**
 * The dispute flow, and the only place a freeze is applied or lifted.
 *
 * <p>A freeze is three things and they are done together here:
 *
 * <ol>
 *   <li>{@code VendorOrder.disputeFrozenAt} is stamped, which is what
 *       {@code MoneyLedger} reads before releasing escrow — so a delivery
 *       recorded <em>after</em> the dispute was raised cannot pay the seller in
 *       the middle of the argument about it.</li>
 *   <li>Where the money had already left escrow, a hold entry takes it back out
 *       of the available balance, with a reason the seller can read.</li>
 *   <li>Both are undone on closing, in the same order.</li>
 * </ol>
 *
 * <p>Splitting those across two classes is how one of them ends up not
 * happening.
 */
@Slf4j
@Service
public class DisputeServiceImpl implements DisputeService {

    /**
     * How long after a slice settles a dispute may still be raised.
     *
     * <p>Longer than the return window, because the cases that reach a dispute
     * are the slow ones — a parcel that never arrived is not noticed on the day
     * it did not arrive, and a recipient who has no account tells the buyer a
     * fortnight later.
     */
    private static final int DISPUTE_WINDOW_DAYS = 60;

    /** How many messages one party may add in an hour. */
    private static final int MAX_MESSAGES_PER_HOUR = 20;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final DisputeRepository disputes;
    private final DisputeMessageRepository messages;
    private final DisputeEvidenceRepository evidence;
    private final VendorOrderRepository vendorOrders;
    private final UserRepository users;
    private final MoneyLedger ledger;
    private final CurrencyCatalogue currencies;

    public DisputeServiceImpl(DisputeRepository disputes, DisputeMessageRepository messages,
                              DisputeEvidenceRepository evidence,
                              VendorOrderRepository vendorOrders, UserRepository users,
                              MoneyLedger ledger, CurrencyCatalogue currencies) {
        this.disputes = disputes;
        this.messages = messages;
        this.evidence = evidence;
        this.vendorOrders = vendorOrders;
        this.users = users;
        this.ledger = ledger;
        this.currencies = currencies;
    }

    // ── Raising one ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.DisputeDetail open(Long userId,
                                                  AfterSalesRequests.OpenDispute request) {
        VendorOrder slice = vendorOrders.findById(request.vendorOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", request.vendorOrderId()));
        requireBuyerOf(slice, userId);
        requireInsideTheWindow(slice);

        if (!disputes.findFreezingSlice(slice.getId()).isEmpty()) {
            throw new BadRequestException(
                    "There is already an open dispute on this part of the order. Add what you want "
                            + "us to know to that one.");
        }

        BigDecimal claimed = cap(request.amount(), slice);
        Dispute dispute = disputes.save(Dispute.builder()
                .reference(newReference())
                .vendorOrder(slice)
                .raisedBy(users.findById(userId).orElse(null))
                .status(DisputeStatus.OPEN)
                .reason(request.reason())
                .description(request.description())
                .amount(claimed)
                .currency(slice.getOrder() == null ? null : slice.getOrder().getCurrency())
                .amountNative(toNative(claimed, slice))
                // The rate from the day of the order, carried rather than read
                // again. A dispute decided next month at next month's rate is
                // one where the answer depends on when somebody got round to it.
                .fx(slice.getFx())
                .build());

        freeze(dispute, slice, "a dispute was raised");

        // The opening statement is the first message, so the case file reads in
        // one sequence rather than starting with a field the rest of the
        // conversation does not use.
        write(dispute, users.findById(userId).orElse(null), sideOf(slice, userId),
                request.description(), false);

        log.info("[Disputes] {} opened on slice {} by user {} — {}",
                dispute.getReference(), slice.getId(), userId, request.reason());
        return detailOf(dispute, userId);
    }

    @Override
    @Transactional
    public Dispute escalateFromReturn(Long userId, ReturnRequest returnRequest, String description,
                                      DisputeReason reason) {
        VendorOrder slice = returnRequest.getVendorOrder();

        // Deliberately no window check here. The buyer opened the return inside
        // the window and has been waiting on the seller ever since; a clock that
        // ran out while they waited would reward a seller for not answering.
        List<Dispute> existing = disputes.findFreezingSlice(slice.getId());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        BigDecimal claimed = returnRequest.settlementAmount();
        Dispute dispute = disputes.save(Dispute.builder()
                .reference(newReference())
                .vendorOrder(slice)
                .raisedBy(users.findById(userId).orElse(null))
                .status(DisputeStatus.OPEN)
                .reason(reason)
                .description(description)
                .returnRequest(returnRequest)
                .amount(claimed)
                .currency(returnRequest.getCurrency())
                .amountNative(returnRequest.settlementAmountNative())
                .fx(returnRequest.getFx())
                .build());

        freeze(dispute, slice, "a return was escalated");

        write(dispute, users.findById(userId).orElse(null), sideOf(slice, userId),
                description, false);
        // The seller's refusal is part of the case file, so a moderator reads
        // both sides in order rather than one side and a status column.
        if (returnRequest.getDecisionNote() != null && !returnRequest.getDecisionNote().isBlank()) {
            write(dispute, null, "VENDOR",
                    "From the return " + returnRequest.getReference() + ": "
                            + returnRequest.getDecisionNote(), false);
        }
        return dispute;
    }

    // ── The freeze ───────────────────────────────────────────────────────────

    /**
     * Holds this slice's money still, both ways it can be held.
     *
     * <p>The stamp on the slice is what stops escrow releasing later; the ledger
     * entry is what claws back money that has already been released. Which of
     * the two bites depends on whether the parcel had been delivered when the
     * argument started, and both are applied so it does not matter.
     */
    private void freeze(Dispute dispute, VendorOrder slice, String why) {
        LocalDateTime now = LocalDateTime.now();
        slice.setDisputeFrozenAt(now);
        vendorOrders.save(slice);

        VendorLedgerEntry hold = ledger.holdForDispute(slice, dispute.getReference(), why);
        dispute.setFrozenAt(now);
        dispute.setHoldReference(hold == null ? null : hold.getReference());
        disputes.save(dispute);

        log.info("[Disputes] {} froze slice {} — {}", dispute.getReference(), slice.getId(),
                hold == null ? "still in escrow, nothing to hold" : "held on the ledger");
    }

    /**
     * Lifts the freeze, unless another dispute is still holding the same slice.
     *
     * <p>Two disputes on one sub-order is unusual and entirely possible: a buyer
     * disputing delivery and then disputing the refund that followed. Closing
     * the first must not pay the seller while the second is open.
     */
    private void unfreeze(Dispute dispute, VendorOrder slice, String why) {
        LocalDateTime now = LocalDateTime.now();
        boolean othersHolding = disputes.findFreezingSlice(slice.getId()).stream()
                .anyMatch(other -> !other.getId().equals(dispute.getId()));

        dispute.setUnfrozenAt(now);
        disputes.save(dispute);

        if (othersHolding) {
            log.info("[Disputes] {} closed but slice {} stays frozen — another dispute is open",
                    dispute.getReference(), slice.getId());
            return;
        }

        slice.setDisputeFrozenAt(null);
        vendorOrders.save(slice);
        ledger.releaseDisputeHold(slice, dispute.getReference(), why);
        log.info("[Disputes] {} lifted the freeze on slice {}", dispute.getReference(), slice.getId());
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public PagedResponse<AfterSalesResponses.DisputeSummary> list(Long userId, DisputeStatus status,
                                                                  Pageable pageable) {
        Page<Dispute> page = disputes.findForParty(userId, status, pageable);
        return PagedResponse.of(page.map(this::summaryOf));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.DisputeDetail detail(Long userId, Long disputeId) {
        return detailOf(requireParty(disputeId, userId), userId);
    }

    // ── Adding to it ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.DisputeDetail addMessage(Long userId, Long disputeId,
                                                        AfterSalesRequests.DisputeMessage request) {
        Dispute dispute = requireParty(disputeId, userId);
        requireStillArguable(dispute);
        requireNotShouting(dispute, userId);

        write(dispute, users.findById(userId).orElse(null),
                sideOf(dispute.getVendorOrder(), userId), request.body(), false);
        return detailOf(dispute, userId);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.DisputeDetail addEvidence(
            Long userId, Long disputeId, AfterSalesRequests.DisputeEvidence request) {
        Dispute dispute = requireParty(disputeId, userId);
        requireStillArguable(dispute);

        evidence.save(DisputeEvidence.builder()
                .dispute(dispute)
                .uploadedBy(users.findById(userId).orElse(null))
                .uploadedBySide(sideOf(dispute.getVendorOrder(), userId))
                .url(request.url())
                .contentType(request.contentType())
                .caption(request.caption())
                .build());

        log.info("[Disputes] {} — evidence added by user {}", dispute.getReference(), userId);
        return detailOf(dispute, userId);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.DisputeDetail withdraw(Long userId, Long disputeId,
                                                      AfterSalesRequests.WithdrawDispute request) {
        Dispute dispute = requireParty(disputeId, userId);
        if (dispute.getRaisedBy() == null || !userId.equals(dispute.getRaisedBy().getId())) {
            // A seller who could close a dispute against themselves is a seller
            // who is never disputed.
            throw new BadRequestException(
                    "Only the person who raised this can withdraw it. If you think it is settled, "
                            + "say so on the dispute and they can close it.");
        }
        if (dispute.getStatus().isClosed()) {
            throw new BadRequestException("This dispute is already closed.");
        }

        dispute.setStatus(DisputeStatus.WITHDRAWN);
        dispute.setOutcome(com.sujula.model.constant.DisputeOutcome.NO_DECISION);
        dispute.setWithdrawnAt(LocalDateTime.now());
        dispute.setResolutionNote(request == null ? null : request.reason());
        disputes.save(dispute);

        write(dispute, users.findById(userId).orElse(null),
                sideOf(dispute.getVendorOrder(), userId),
                request != null && request.reason() != null && !request.reason().isBlank()
                        ? "Withdrawn: " + request.reason()
                        : "Withdrawn.",
                false);

        unfreeze(dispute, dispute.getVendorOrder(), "the buyer withdrew the dispute");

        log.info("[Disputes] {} withdrawn by user {}", dispute.getReference(), userId);
        return detailOf(dispute, userId);
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private Dispute requireParty(Long disputeId, Long userId) {
        return disputes.findByIdForParty(disputeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute", disputeId));
    }

    private static void requireStillArguable(Dispute dispute) {
        if (dispute.getStatus().isClosed()) {
            throw new BadRequestException(
                    "This dispute is closed, so nothing more can be added to it. If something new "
                            + "has happened, that is a new case.");
        }
    }

    /**
     * Stops one party burying the other.
     *
     * <p>A moderator reading forty messages from one side and two from the other
     * is not reading a case, and the person who wrote two is the one who loses
     * by it.
     */
    private void requireNotShouting(Dispute dispute, Long userId) {
        long recent = messages.findByDisputeIdOrderByCreatedAtAscIdAsc(dispute.getId()).stream()
                .filter(row -> row.getAuthor() != null && userId.equals(row.getAuthor().getId()))
                .filter(row -> row.getCreatedAt() != null
                        && row.getCreatedAt().isAfter(LocalDateTime.now().minusHours(1)))
                .count();
        if (recent >= MAX_MESSAGES_PER_HOUR) {
            throw new BadRequestException(
                    "You have added " + recent + " messages in the last hour. Somebody will read "
                            + "all of them — adding more will not make that happen sooner.");
        }
    }

    private static void requireBuyerOf(VendorOrder slice, Long userId) {
        Order order = slice.getOrder();
        boolean theirs = order != null && order.getCustomer() != null
                && userId.equals(order.getCustomer().getId());
        if (!theirs) {
            throw new ResourceNotFoundException("Order", slice.getId());
        }
    }

    private static void requireInsideTheWindow(VendorOrder slice) {
        LocalDateTime placed = slice.getCreatedAt();
        if (placed != null && placed.plusDays(DISPUTE_WINDOW_DAYS).isBefore(LocalDateTime.now())) {
            throw new BadRequestException(
                    "This order is more than " + DISPUTE_WINDOW_DAYS + " days old, which is past "
                            + "the point where we can hold the seller's money against it. Contact "
                            + "support if something is still wrong.");
        }
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    private void write(Dispute dispute, User author, String side, String body, boolean internal) {
        if (body == null || body.isBlank()) {
            return;
        }
        messages.save(DisputeMessage.builder()
                .dispute(dispute)
                .author(author)
                .authorSide(side)
                .body(body)
                .internal(internal)
                .build());
    }

    /**
     * Which side somebody is on, worked out from the slice.
     *
     * <p>Frozen onto the row when it is written rather than derived on every
     * read: a buyer who later opens a shop is not retroactively the seller in an
     * old argument.
     */
    private static String sideOf(VendorOrder slice, Long userId) {
        boolean seller = slice != null && slice.getVendor() != null
                && slice.getVendor().getUser() != null
                && userId.equals(slice.getVendor().getUser().getId());
        return seller ? "VENDOR" : "BUYER";
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private AfterSalesResponses.DisputeSummary summaryOf(Dispute dispute) {
        return new AfterSalesResponses.DisputeSummary(
                dispute.getId(), dispute.getReference(), dispute.getStatus(), dispute.getReason(),
                orderNumber(dispute), storeName(dispute),
                money(dispute.getAmount(), dispute.getAmountNative(), dispute),
                dispute.getOutcome(),
                dispute.freezesMoney(),
                (int) messages.countByDisputeId(dispute.getId()),
                (int) evidence.countByDisputeId(dispute.getId()),
                dispute.getCreatedAt(), dispute.getUpdatedAt());
    }

    private AfterSalesResponses.DisputeDetail detailOf(Dispute dispute, Long userId) {
        boolean seller = "VENDOR".equals(sideOf(dispute.getVendorOrder(), userId));

        List<AfterSalesResponses.DisputeMessageView> messageViews = new ArrayList<>();
        // Moderators' notes are filtered in the query rather than here, so a
        // mapper somebody writes later cannot forget to leave them out.
        for (DisputeMessage row : messages.findVisibleToParties(dispute.getId())) {
            messageViews.add(new AfterSalesResponses.DisputeMessageView(
                    row.getId(), row.getAuthorSide(), displayName(row.getAuthor(), row.getAuthorSide()),
                    row.getBody(), row.getCreatedAt()));
        }

        List<AfterSalesResponses.DisputeEvidenceView> evidenceViews = new ArrayList<>();
        for (DisputeEvidence row : evidence.findByDisputeIdOrderByCreatedAtAscIdAsc(dispute.getId())) {
            evidenceViews.add(new AfterSalesResponses.DisputeEvidenceView(
                    row.getId(), row.getUploadedBySide(), row.getUrl(),
                    row.getContentType(), row.getCaption(), row.getCreatedAt()));
        }

        return new AfterSalesResponses.DisputeDetail(
                dispute.getId(), dispute.getReference(), dispute.getStatus(), dispute.getReason(),
                orderNumber(dispute), storeName(dispute),
                dispute.getDescription(),
                money(dispute.getAmount(), dispute.getAmountNative(), dispute),
                dispute.getOutcome(),
                money(dispute.getAwardedToBuyer(), dispute.getAwardedToBuyerNative(), dispute),
                dispute.getResolutionNote(), dispute.getResolvedAt(),
                dispute.getReturnRequest() == null ? null : dispute.getReturnRequest().getId(),
                dispute.getReturnRequest() == null ? null : dispute.getReturnRequest().getReference(),
                dispute.getRefundReference(),
                freezeView(dispute, seller),
                messageViews, evidenceViews,
                whatHappensNext(dispute, seller),
                dispute.getCreatedAt(), dispute.getUpdatedAt());
    }

    /**
     * The freeze, explained to whichever side is reading.
     *
     * <p>Both need to be told and for opposite reasons. The seller's balance has
     * changed and they will otherwise think it is a mistake; the buyer needs to
     * know the money is being held, because "it is held until this is sorted" is
     * what stops somebody trying to arrange a private settlement instead.
     */
    private static AfterSalesResponses.Freeze freezeView(Dispute dispute, boolean seller) {
        boolean active = dispute.freezesMoney();
        String explanation;
        if (active && seller) {
            explanation = "The money for this part of the order is held while we look at it. It is "
                    + "not gone — it is off your available balance and shows on your transactions "
                    + "as a hold. Nothing else you have sold is affected.";
        } else if (active) {
            explanation = "The seller has not been paid for this part of your order while we look "
                    + "at it. Anything you bought from other sellers on the same payment is "
                    + "unaffected.";
        } else if (dispute.getUnfrozenAt() != null) {
            explanation = seller
                    ? "The hold has been lifted and this is back on your balance."
                    : "The hold has been lifted.";
        } else {
            explanation = null;
        }
        return new AfterSalesResponses.Freeze(active, dispute.getFrozenAt(),
                dispute.getUnfrozenAt(), dispute.getHoldReference(), explanation);
    }

    private static String whatHappensNext(Dispute dispute, boolean seller) {
        return switch (dispute.getStatus()) {
            case OPEN -> seller
                    ? "Add anything that helps — the delivery record is already with us, so what "
                      + "we need from you is your side of it."
                    : "We have it. Add photographs or anything else that shows what happened.";
            case UNDER_REVIEW -> "Somebody is reading it now.";
            case RESOLVED -> dispute.getResolutionNote() == null
                    ? "Decided." : dispute.getResolutionNote();
            case WITHDRAWN -> "Withdrawn. The hold has been lifted.";
        };
    }

    private static String displayName(User user, String side) {
        if (user == null) {
            return "VENDOR".equals(side) ? "The seller" : "The buyer";
        }
        // A first name only. A dispute is read by somebody on the other side of
        // an argument, and a surname is not needed to follow who said what.
        return user.getFirstName() != null ? user.getFirstName()
                : ("VENDOR".equals(side) ? "The seller" : "The buyer");
    }

    private AfterSalesResponses.Money money(BigDecimal amount, BigDecimal amountNative,
                                            Dispute dispute) {
        if (amount == null && amountNative == null) {
            return null;
        }
        FxSnapshot fx = dispute.getFx();
        return new AfterSalesResponses.Money(
                amount, dispute.getCurrency(),
                amountNative,
                dispute.getVendorOrder() == null ? null : dispute.getVendorOrder().getNativeCurrency(),
                fx == null ? null : fx.getRate(),
                fx == null ? null : fx.getRateAt());
    }

    // ── Money ────────────────────────────────────────────────────────────────

    /**
     * What can be claimed, which is never more than the slice was worth.
     *
     * <p>Absent means the whole slice, which is what somebody who leaves the
     * field blank means.
     */
    private BigDecimal cap(BigDecimal requested, VendorOrder slice) {
        BigDecimal total = slice.getTotal();
        if (requested == null || requested.signum() <= 0) {
            return total;
        }
        String currency = slice.getOrder() == null ? null : slice.getOrder().getCurrency();
        BigDecimal rounded = round(requested, currency);
        return total != null && rounded.compareTo(total) > 0 ? total : rounded;
    }

    private BigDecimal toNative(BigDecimal display, VendorOrder slice) {
        FxSnapshot fx = slice.getFx();
        if (display == null) {
            return null;
        }
        if (fx == null || fx.getRate() == null || fx.getRate().signum() == 0) {
            return round(display, slice.getNativeCurrency());
        }
        return round(display.divide(fx.getRate(), 8, RoundingMode.HALF_UP),
                slice.getNativeCurrency());
    }

    private BigDecimal round(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        return currency == null ? amount : currencies.round(amount, currency);
    }

    private static String orderNumber(Dispute dispute) {
        VendorOrder slice = dispute.getVendorOrder();
        return slice == null || slice.getOrder() == null ? null : slice.getOrder().getOrderNumber();
    }

    private static String storeName(Dispute dispute) {
        VendorOrder slice = dispute.getVendorOrder();
        return slice == null || slice.getVendor() == null ? null : slice.getVendor().getStoreName();
    }

    private String newReference() {
        for (int attempt = 0; attempt < 6; attempt++) {
            StringBuilder code = new StringBuilder("DSP-");
            for (int i = 0; i < 10; i++) {
                code.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
            }
            String candidate = code.toString();
            if (!disputes.existsByReference(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not mint a unique dispute reference");
    }
}
