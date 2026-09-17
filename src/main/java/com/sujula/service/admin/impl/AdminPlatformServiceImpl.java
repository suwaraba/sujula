package com.sujula.service.admin.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.admin.AdminPlatformRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminPlatformResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.aftersales.Dispute;
import com.sujula.model.aftersales.DisputeMessage;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.CallbackOutcome;
import com.sujula.model.constant.DisputeOutcome;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.JobRunStatus;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.platform.Announcement;
import com.sujula.model.platform.CallbackRequest;
import com.sujula.model.platform.FeatureFlag;
import com.sujula.model.platform.JobRun;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.user.User;
import com.sujula.repository.aftersales.DisputeMessageRepository;
import com.sujula.repository.AuditLogRepository;
import com.sujula.repository.aftersales.DisputeRepository;
import com.sujula.repository.admin.ModerationCaseRepository;
import com.sujula.repository.finance.PayoutBatchRepository;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.platform.AnnouncementRepository;
import com.sujula.repository.platform.FeatureFlagRepository;
import com.sujula.repository.platform.JobRunRepository;
import com.sujula.repository.store.KycDocumentRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.repository.platform.CallbackRequestRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.AuditService;
import com.sujula.service.NotificationService;
import com.sujula.service.admin.AdminPlatformService;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.platform.JobRegistry;
import com.sujula.service.platform.ManagedJob;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.security.StepUpVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * Disputes, comms and the platform's own switches.
 *
 * <p>The dispute half is the part worth reading twice. A dispute is about one
 * seller's goods (C3), so resolving it moves that seller's money and nobody
 * else's. The figures are in the seller's own currency because that is what the
 * ledger holds; the buyer's side comes from the rate the order was placed at,
 * never a fresh one (C2).
 *
 * <p>And a resolution is two writes that must agree: the hold comes off the
 * seller's balance, and where the buyer won, a refund takes money back. Both as
 * their own rows, never netted — a seller who cannot tell "the hold lifted and
 * then you were refunded" from "you were never held" has been told two different
 * stories about the same month.
 */
@Slf4j
@Service
public class AdminPlatformServiceImpl implements AdminPlatformService {

    /** How long an ordinary dispute may sit before the promise is broken. */
    private static final Duration DISPUTE_SLA = Duration.ofHours(72);

    /** A call promised is a person waiting by a phone. */
    private static final Duration CALLBACK_WINDOW = Duration.ofHours(24);

    /** Above this many, a broadcast is refused rather than half-sent. */
    private static final int MAX_SEGMENT = 20_000;

    private final DisputeRepository disputes;
    private final DisputeMessageRepository disputeMessages;
    private final VendorOrderRepository vendorOrders;
    private final ShipmentRepository shipments;
    private final CallbackRequestRepository callbacks;
    private final UserRepository users;
    private final MoneyLedger money;
    private final CurrencyCatalogue currencies;
    private final StepUpVerifier stepUp;
    private final AuditService audit;
    private final NotificationService notifications;
    private final AnnouncementRepository announcements;
    private final FeatureFlagRepository featureFlags;
    private final JobRunRepository jobRuns;
    private final JobRegistry jobs;
    private final AuditLogRepository auditLogs;
    private final OrderRepository orders;
    private final ModerationCaseRepository moderationCases;
    private final KycDocumentRepository kycDocuments;
    private final VendorRepository vendors;
    private final PayoutRepository payouts;
    private final PayoutBatchRepository batches;
    private final com.sujula.service.platform.FeatureFlags flagCache;

    public AdminPlatformServiceImpl(DisputeRepository disputes,
                                    DisputeMessageRepository disputeMessages,
                                    VendorOrderRepository vendorOrders,
                                    ShipmentRepository shipments,
                                    CallbackRequestRepository callbacks, UserRepository users,
                                    MoneyLedger money, CurrencyCatalogue currencies,
                                    StepUpVerifier stepUp, AuditService audit,
                                    NotificationService notifications,
                                    AnnouncementRepository announcements,
                                    FeatureFlagRepository featureFlags,
                                    JobRunRepository jobRuns, JobRegistry jobs,
                                    AuditLogRepository auditLogs, OrderRepository orders,
                                    ModerationCaseRepository moderationCases,
                                    KycDocumentRepository kycDocuments,
                                    VendorRepository vendors, PayoutRepository payouts,
                                    PayoutBatchRepository batches,
                                    com.sujula.service.platform.FeatureFlags flagCache) {
        this.disputes = disputes;
        this.disputeMessages = disputeMessages;
        this.vendorOrders = vendorOrders;
        this.shipments = shipments;
        this.callbacks = callbacks;
        this.users = users;
        this.money = money;
        this.currencies = currencies;
        this.stepUp = stepUp;
        this.audit = audit;
        this.notifications = notifications;
        this.announcements = announcements;
        this.featureFlags = featureFlags;
        this.jobRuns = jobRuns;
        this.jobs = jobs;
        this.auditLogs = auditLogs;
        this.orders = orders;
        this.moderationCases = moderationCases;
        this.kycDocuments = kycDocuments;
        this.vendors = vendors;
        this.payouts = payouts;
        this.batches = batches;
        this.flagCache = flagCache;
    }

    // ── The queue ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminPlatformResponses.DisputeRow> queue(
            DisputeStatus status, boolean openOnly, Long assigneeUserId, boolean unassignedOnly,
            Long vendorId, DisputeReason reason, boolean overdueOnly, Pageable pageable) {

        Page<Dispute> page = disputes.queue(status, openOnly, assigneeUserId, unassignedOnly,
                vendorId, reason, overdueOnly, LocalDateTime.now(), pageable);
        return PagedResponse.of(page.map(this::toDisputeRow));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPlatformResponses.DisputeRow readDispute(Long disputeId) {
        return toDisputeRow(requireDispute(disputeId));
    }

    private AdminPlatformResponses.DisputeRow toDisputeRow(Dispute dispute) {
        VendorOrder slice = dispute.getVendorOrder();
        LocalDateTime now = LocalDateTime.now();

        Long hoursLeft = dispute.getDueBy() == null
                ? null : Duration.between(now, dispute.getDueBy()).toHours();
        boolean overdue = dispute.getDueBy() != null && dispute.getDueBy().isBefore(now)
                && !dispute.getStatus().name().equals(DisputeStatus.RESOLVED.name())
                && dispute.getStatus() != DisputeStatus.WITHDRAWN;

        boolean callOwed = callbacks.findByDisputeIdOrderByRequestedAtDesc(dispute.getId()).stream()
                .anyMatch(CallbackRequest::isOutstanding);

        return new AdminPlatformResponses.DisputeRow(
                dispute.getId(), dispute.getReference(), dispute.getStatus(), dispute.getReason(),
                slice == null ? null : slice.getId(),
                slice == null || slice.getOrder() == null ? null : slice.getOrder().getId(),
                slice == null || slice.getOrder() == null
                        ? null : slice.getOrder().getOrderNumber(),
                slice == null || slice.getVendor() == null ? null : slice.getVendor().getId(),
                slice == null || slice.getVendor() == null
                        ? null : slice.getVendor().getStoreName(),
                dispute.getRaisedBy() == null ? null : nameOf(dispute.getRaisedBy()),
                dispute.getCreatedAt(),
                dispute.getAmountNative(),
                slice == null ? null : slice.getNativeCurrency(),
                dispute.getAmount(), dispute.getCurrency(),
                dispute.getFrozenAt() != null && dispute.getUnfrozenAt() == null,
                dispute.getFrozenAt(),
                dispute.getAssignedToUserId(), emailOf(dispute.getAssignedToUserId()),
                dispute.getAssignedAt(),
                dispute.getDueBy(), hoursLeft, overdue, callOwed,
                (int) disputeMessages.countByDisputeId(dispute.getId()),
                dispute.getEvidence() == null ? 0 : dispute.getEvidence().size(),
                dispute.getOutcome(), dispute.getResolvedAt());
    }

    @Override
    @Transactional
    public AdminPlatformResponses.DisputeRow assign(
            User staff, Long disputeId, AdminPlatformRequests.AssignDispute request) {

        Dispute dispute = requireDispute(disputeId);
        if (dispute.getStatus() == DisputeStatus.RESOLVED
                || dispute.getStatus() == DisputeStatus.WITHDRAWN) {
            throw new BadRequestException(
                    "That dispute is " + dispute.getStatus() + ". There is nothing left to work "
                            + "on it.");
        }

        // Null takes it yourself, which is what an agent picking one off the
        // queue actually does.
        Long assignee = request.assigneeUserId() == null ? staff.getId() : request.assigneeUserId();
        User person = users.findById(assignee).orElseThrow(
                () -> new ResourceNotFoundException("No such user to assign this to."));
        if (person.getRole() == null || !person.getRole().isStaff()) {
            throw new BadRequestException(
                    nameOf(person) + " is not support or an administrator. A dispute assigned to "
                            + "somebody who cannot open it is a dispute nobody is working on.");
        }

        String previous = dispute.getAssignedToUserId() == null
                ? null : emailOf(dispute.getAssignedToUserId());

        dispute.setAssignedToUserId(assignee);
        dispute.setAssignedAt(LocalDateTime.now());
        // Picking one up moves it out of OPEN, so the queue distinguishes
        // "nobody has looked" from "somebody is on it".
        if (dispute.getStatus() == DisputeStatus.OPEN) {
            dispute.setStatus(DisputeStatus.UNDER_REVIEW);
        }
        disputes.save(dispute);

        audit.record(AuditAction.DISPUTE_ASSIGNED, "DISPUTE", dispute.getId(),
                dispute.getReference(),
                staff.getEmail() + " assigned it to " + person.getEmail()
                        + (previous == null ? "" : " (was " + previous + ")"),
                request.note());

        return toDisputeRow(dispute);
    }

    @Override
    @Transactional
    public AdminPlatformResponses.NoteAdded addNote(
            User staff, Long disputeId, AdminPlatformRequests.AddNote request) {

        Dispute dispute = requireDispute(disputeId);

        // Internal, always, and not a flag the caller can set. This endpoint
        // exists for the note an agent writes to the next agent — "buyer has
        // filed three of these" — and a switch that could make it visible is one
        // somebody eventually leaves in the wrong position.
        DisputeMessage note = disputeMessages.save(DisputeMessage.builder()
                .dispute(dispute)
                .author(staff)
                .authorSide("PLATFORM")
                .body(request.body())
                .internal(true)
                .build());

        audit.record(AuditAction.DISPUTE_NOTE_ADDED, "DISPUTE", dispute.getId(),
                dispute.getReference(),
                staff.getEmail() + " added an internal note", null);

        return new AdminPlatformResponses.NoteAdded(note.getId(), dispute.getId(), true,
                note.getCreatedAt(),
                "Internal. Neither the buyer nor the seller can see this — use the dispute's own "
                        + "message thread to write to them.");
    }

    // ── Deciding ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminPlatformResponses.DisputeDecided resolve(
            User staff, Long disputeId, AdminPlatformRequests.ResolveDispute request) {

        Dispute dispute = requireDispute(disputeId);
        if (dispute.getStatus() == DisputeStatus.RESOLVED) {
            throw new BadRequestException(
                    "That dispute was already decided " + dispute.getResolvedAt()
                            + " as " + dispute.getOutcome() + ". Deciding it twice would move the "
                            + "money twice.");
        }
        if (dispute.getStatus() == DisputeStatus.WITHDRAWN) {
            throw new BadRequestException(
                    "That dispute was withdrawn. Nothing is in dispute to decide.");
        }

        VendorOrder slice = dispute.getVendorOrder();
        if (slice == null) {
            throw new BadRequestException(
                    "That dispute is not attached to a sub-order, so there is no seller whose "
                            + "money it could move.");
        }
        String currency = slice.getNativeCurrency();
        BigDecimal disputed = currencies.round(orZero(dispute.getAmountNative()), currency);

        BigDecimal toBuyer = resolveAward(request, disputed, currency);
        BigDecimal toVendor = currencies.round(disputed.subtract(toBuyer), currency);

        // This moves money. An admin session left open on a desk must not be
        // enough on its own.
        stepUp.verify(staff, request.password(), request.totpCode(),
                "deciding dispute " + dispute.getReference());

        LocalDateTime now = LocalDateTime.now();
        List<String> written = new ArrayList<>();

        // The hold comes off first, always and in full. Where the buyer won, the
        // refund below takes money back off a released balance — two rows telling
        // the true sequence, rather than one netted row that says the seller was
        // never held.
        if (money.releaseDisputeHold(slice, dispute.getReference(),
                "decided " + request.outcome()) != null) {
            written.add("DISPUTE_HOLD_RELEASE +" + disputed + " " + currency);
        }

        if (request.outcome().refundsTheBuyer() && toBuyer.signum() > 0) {
            BigDecimal commissionBack = commissionShareOf(slice, toBuyer, currency);
            money.postRefund(slice, toBuyer, commissionBack, dispute.getReference(),
                    "dispute " + dispute.getReference() + " decided for the buyer", now);
            written.add("REFUND -" + toBuyer + " " + currency);
            if (commissionBack.signum() > 0) {
                written.add("COMMISSION_REVERSAL +" + commissionBack + " " + currency);
            }
        }

        // The slice stops being frozen whatever the outcome: the question has
        // been answered, and escrow release is no longer waiting on it.
        slice.setDisputeFrozenAt(null);
        vendorOrders.save(slice);

        dispute.setStatus(DisputeStatus.RESOLVED);
        dispute.setOutcome(request.outcome());
        dispute.setAwardedToBuyerNative(toBuyer);
        dispute.setAwardedToBuyer(convertAtOrdersOwnRate(dispute, toBuyer));
        dispute.setResolvedBy(staff);
        dispute.setResolvedAt(now);
        dispute.setResolutionNote(request.resolutionNote());
        dispute.setUnfrozenAt(now);
        disputes.save(dispute);

        audit.record(AuditAction.DISPUTE_RESOLVED, "DISPUTE", dispute.getId(),
                dispute.getReference(),
                staff.getEmail() + " decided " + request.outcome()
                        + " — " + toBuyer + " " + currency + " to the buyer, "
                        + toVendor + " kept by " + storeNameOf(slice)
                        + (request.returnRequired() ? ", goods to be returned first" : ""),
                request.resolutionNote());

        notifyBothSides(dispute, slice, request, toBuyer, toVendor, currency);

        return new AdminPlatformResponses.DisputeDecided(
                dispute.getId(), dispute.getReference(), dispute.getStatus(), request.outcome(),
                toBuyer, toVendor, currency, written, request.returnRequired(),
                (request.returnRequired()
                        ? "Decided, and the buyer has been told to send the goods back first. "
                        : "Decided. ")
                        + "The hold came off as its own row and the refund as another — a seller "
                        + "who could not tell those apart would have been told two different "
                        + "stories about the same month.");
    }

    /**
     * How much goes back to the buyer, in the seller's own currency.
     *
     * <p>Only SPLIT takes a figure. FOR_BUYER and FOR_VENDOR already say what
     * happens to the whole amount, and accepting a number alongside them would
     * let the two disagree — somebody would eventually trust the wrong one.
     */
    private BigDecimal resolveAward(AdminPlatformRequests.ResolveDispute request,
                                    BigDecimal disputed, String currency) {
        return switch (request.outcome()) {
            case FOR_BUYER -> {
                requireNoAmount(request, "FOR_BUYER returns the whole disputed amount");
                yield disputed;
            }
            case FOR_VENDOR, NO_DECISION -> {
                requireNoAmount(request, request.outcome() + " returns nothing to the buyer");
                yield BigDecimal.ZERO;
            }
            case SPLIT -> {
                if (request.awardedToBuyerNative() == null) {
                    throw new BadRequestException(
                            "A split needs the buyer's share, in " + currency + " — the seller's "
                                    + "own currency, which is what the ledger holds.");
                }
                BigDecimal share = currencies.round(request.awardedToBuyerNative(), currency);
                if (share.signum() <= 0 || share.compareTo(disputed) >= 0) {
                    throw new BadRequestException(String.format(
                            "A split has to be between nothing and the whole %s %s in dispute. "
                                    + "For %s, use FOR_VENDOR or FOR_BUYER — those say it plainly "
                                    + "and cannot disagree with a figure beside them.",
                            disputed, currency,
                            share.signum() <= 0 ? "nothing" : "all of it"));
                }
                yield share;
            }
        };
    }

    private static void requireNoAmount(AdminPlatformRequests.ResolveDispute request, String why) {
        if (request.awardedToBuyerNative() != null) {
            throw new BadRequestException(
                    "Do not send an amount with this outcome: " + why + ". A figure beside it "
                            + "could disagree with it, and somebody would trust the wrong one.");
        }
    }

    /**
     * The buyer's-currency figure, from the rate the order was placed at.
     *
     * <p>Never a fresh rate. The buyer paid at one rate and is owed at that same
     * one; converting again here would hand back a different number, and the
     * difference would come out of the seller or the platform depending which way
     * the currency had moved (C2).
     */
    private BigDecimal convertAtOrdersOwnRate(Dispute dispute, BigDecimal nativeAmount) {
        FxSnapshot fx = dispute.getFx();
        if (fx == null || fx.getRate() == null || fx.getDisplayCurrency() == null
                || fx.getDisplayCurrency().equalsIgnoreCase(fx.getNativeCurrency())) {
            return nativeAmount;
        }
        return currencies.round(nativeAmount.multiply(fx.getRate()), fx.getDisplayCurrency());
    }

    /** The commission that came off this much of the sub-order, handed back with it. */
    private BigDecimal commissionShareOf(VendorOrder slice, BigDecimal refundedNative,
                                         String currency) {
        BigDecimal total = orZero(slice.getTotalNative());
        BigDecimal commission = orZero(slice.getCommissionNative()).abs();
        if (total.signum() == 0 || commission.signum() == 0) {
            return BigDecimal.ZERO;
        }
        // A proportion of ONE seller's own figures, which is the one place a
        // proportion is the right shape (C3).
        return currencies.round(
                commission.multiply(refundedNative).divide(total, 8, RoundingMode.HALF_UP),
                currency);
    }

    private void notifyBothSides(Dispute dispute, VendorOrder slice,
                                 AdminPlatformRequests.ResolveDispute request,
                                 BigDecimal toBuyer, BigDecimal toVendor, String currency) {
        // The seller, in their own currency. Telling a Gambian seller their
        // balance moved by 42 EUR is telling them nothing they can check.
        if (slice.getVendor() != null && slice.getVendor().getUser() != null) {
            notifications.send(slice.getVendor().getUser().getId(),
                    "A dispute on one of your orders has been decided",
                    "Dispute " + dispute.getReference() + ": " + request.resolutionNote()
                            + " " + toVendor + " " + currency + " stays with you"
                            + (toBuyer.signum() > 0
                                    ? " and " + toBuyer + " " + currency + " goes back to the buyer."
                                    : ".")
                            + " The hold on this order has been lifted.",
                    NotificationEvent.DISPUTE_UPDATE, String.valueOf(dispute.getId()));
        }
        // The buyer, in the currency they were charged in.
        if (dispute.getRaisedBy() != null) {
            BigDecimal theirs = convertAtOrdersOwnRate(dispute, toBuyer);
            notifications.send(dispute.getRaisedBy().getId(),
                    "Your dispute has been decided",
                    request.resolutionNote()
                            + (toBuyer.signum() > 0
                                    ? " " + theirs + " " + displayCurrencyOf(dispute)
                                      + " is being returned to you."
                                    : " No money is being returned.")
                            + (request.returnRequired()
                                    ? " Send the goods back first — the refund follows once the "
                                      + "seller has them."
                                    : ""),
                    NotificationEvent.DISPUTE_UPDATE, String.valueOf(dispute.getId()));
        }
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminPlatformResponses.CallbackRow requestCallback(
            User staff, Long disputeId, AdminPlatformRequests.RequestCallback request) {

        Dispute dispute = requireDispute(disputeId);

        // The number on the parcel, when the caller did not name one. The person
        // who most needs the call is often the recipient, who has no account at
        // all and whose number lives on the shipment rather than on a user (C5).
        String phone = request.phone();
        String name = request.contactName();
        if (phone == null || phone.isBlank()) {
            Shipment parcel = dispute.getVendorOrder() == null ? null
                    : shipments.findByVendorOrderId(dispute.getVendorOrder().getId()).orElse(null);
            if (parcel != null && parcel.getRecipientPhone() != null) {
                phone = parcel.getRecipientPhone();
                if (name == null || name.isBlank()) {
                    name = parcel.getRecipientName();
                }
            } else if (dispute.getRaisedBy() != null && dispute.getRaisedBy().getPhone() != null) {
                phone = dispute.getRaisedBy().getPhone();
                if (name == null || name.isBlank()) {
                    name = nameOf(dispute.getRaisedBy());
                }
            }
        }
        if (phone == null || phone.isBlank()) {
            throw new BadRequestException(
                    "No number to ring. There is none on the parcel and none on the account that "
                            + "raised this — give one, or the call cannot be made.");
        }

        CallbackRequest callback = callbacks.save(CallbackRequest.builder()
                .disputeId(dispute.getId())
                .phone(phone.trim())
                .contactName(name)
                .preferredLanguage(request.preferredLanguage())
                .reason(request.reason())
                .requestedByUserId(staff.getId())
                .callBy(request.callBy() == null
                        ? LocalDateTime.now().plus(CALLBACK_WINDOW) : request.callBy())
                .build());

        dispute.setCallbackRequestedAt(LocalDateTime.now());
        disputes.save(dispute);

        audit.record(AuditAction.DISPUTE_CALLBACK_REQUESTED, "DISPUTE", dispute.getId(),
                dispute.getReference(),
                staff.getEmail() + " asked for a call to " + masked(phone)
                        + (request.preferredLanguage() == null ? ""
                                : " in " + request.preferredLanguage()),
                request.reason());

        return toCallbackRow(callback, dispute.getReference(),
                "A call is owed by " + callback.getCallBy() + ". Record what came of it — "
                        + "\"we said we would call\" and \"we called and she did not answer\" are "
                        + "different states, and only the second tells a supervisor this one is "
                        + "still open.");
    }

    @Override
    @Transactional
    public AdminPlatformResponses.CallbackRow recordCallback(
            User staff, Long callbackId, AdminPlatformRequests.RecordCallback request) {

        CallbackRequest callback = callbacks.findById(callbackId).orElseThrow(
                () -> new ResourceNotFoundException("No such callback."));

        callback.setOutcome(request.outcome());
        callback.setCalledByUserId(staff.getId());
        callback.setCalledAt(LocalDateTime.now());
        callback.setAttempts(callback.getAttempts() + 1);
        if (request.notes() != null && !request.notes().isBlank()) {
            callback.setNotes(callback.getNotes() == null
                    ? request.notes()
                    : callback.getNotes() + "\n— " + request.notes());
        }
        if (request.outcome() == CallbackOutcome.RESCHEDULED) {
            if (request.callBy() == null) {
                throw new BadRequestException(
                        "They asked to be rung back — say when. A reschedule with no time on it "
                                + "is a call nobody will make.");
            }
            callback.setCallBy(request.callBy());
        }
        callbacks.save(callback);

        String reference = callback.getDisputeId() == null ? null
                : disputes.findById(callback.getDisputeId()).map(Dispute::getReference).orElse(null);

        audit.record(AuditAction.DISPUTE_CALLBACK_RECORDED, "CALLBACK", callback.getId(), reference,
                staff.getEmail() + " rang " + masked(callback.getPhone()) + ": "
                        + request.outcome() + " (attempt " + callback.getAttempts() + ")",
                request.notes());

        return toCallbackRow(callback, reference,
                callback.isOutstanding()
                        ? "Still owed. Attempt " + callback.getAttempts() + " so far."
                        : "Closed after " + callback.getAttempts() + " attempt(s).");
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminPlatformResponses.CallbackRow> outstandingCallbacks(
            Pageable pageable) {
        return PagedResponse.of(callbacks.findOutstanding(pageable).map(callback -> {
            String reference = callback.getDisputeId() == null ? null
                    : disputes.findById(callback.getDisputeId())
                            .map(Dispute::getReference).orElse(null);
            return toCallbackRow(callback, reference,
                    callback.isOverdue() ? "Overdue — this person is waiting by a phone." : null);
        }));
    }

    private AdminPlatformResponses.CallbackRow toCallbackRow(CallbackRequest callback,
                                                             String disputeReference,
                                                             String message) {
        return new AdminPlatformResponses.CallbackRow(
                callback.getId(), callback.getDisputeId(), disputeReference,
                callback.getPhone(), callback.getContactName(), callback.getPreferredLanguage(),
                callback.getReason(), callback.getRequestedByUserId(), callback.getRequestedAt(),
                callback.getCallBy(), callback.getOutcome(), callback.getCalledByUserId(),
                callback.getCalledAt(), callback.getNotes(), callback.getAttempts(),
                callback.isOutstanding(), callback.isOverdue(), message);
    }

    // ── Comms ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminPlatformResponses.AnnouncementSent announce(
            User staff, AdminPlatformRequests.Announce request) {

        String country = request.countryCode() == null
                ? null : request.countryCode().toUpperCase(java.util.Locale.ROOT);
        NotificationEvent event = request.event() == null
                ? NotificationEvent.PLATFORM_NOTICE : request.event();

        if (event == NotificationEvent.PROMOTION) {
            // Not a hard refusal — a genuine campaign is a legitimate thing to
            // send — but it must be a deliberate choice, because it is silently
            // narrower than it looks: everybody who switched marketing off
            // receives nothing.
            log.info("[Comms] {} is broadcasting as a PROMOTION, which everybody who switched "
                    + "marketing off will not receive", staff.getEmail());
        }

        // Blocked accounts are excluded. Somebody whose account is shut is not
        // somebody the platform should be broadcasting to, and a suspended
        // seller reading a campaign about the new fee schedule is a support
        // ticket.
        List<User> segment = users.search(null, request.audienceRole(), country, false, null,
                        LocalDateTime.now(), PageRequest.of(0, MAX_SEGMENT + 1))
                .getContent();
        if (segment.size() > MAX_SEGMENT) {
            // Refused rather than half-sent. A broadcast that stopped partway
            // through leaves nobody able to say who was told, and re-sending it
            // tells the first half twice.
            throw new BadRequestException(
                    "That segment is larger than " + MAX_SEGMENT + " people. Narrow it by role or "
                            + "country — a broadcast that fails partway through leaves nobody able "
                            + "to say who was told.");
        }
        if (segment.isEmpty()) {
            throw new BadRequestException(
                    "Nobody matches that segment"
                            + (request.audienceRole() == null ? "" : " (" + request.audienceRole() + ")")
                            + (country == null ? "" : " in " + country) + ".");
        }

        Announcement announcement = announcements.save(Announcement.builder()
                .reference(reference("ANN"))
                .title(request.title())
                .body(request.body())
                .audienceRole(request.audienceRole())
                .countryCode(country)
                .event(event)
                .sentByUserId(staff.getId())
                .sentAt(LocalDateTime.now())
                .build());

        int reached = 0;
        for (User person : segment) {
            // Through the ordinary dispatch, so each person's own preferences
            // decide which channels it reaches them on. An announcement is not a
            // licence to reach somebody who switched this event off.
            if (notifications.send(person.getId(), request.title(), request.body(), event,
                    announcement.getReference()) != null) {
                reached++;
            }
        }
        announcement.applyReach(segment.size(), reached);
        announcements.save(announcement);

        audit.record(AuditAction.ANNOUNCEMENT_SENT, "ANNOUNCEMENT", announcement.getId(),
                announcement.getReference(),
                staff.getEmail() + " told " + reached + " of " + segment.size() + " "
                        + (request.audienceRole() == null ? "people" : request.audienceRole() + "s")
                        + (country == null ? " everywhere" : " in " + country),
                request.title());

        return new AdminPlatformResponses.AnnouncementSent(
                announcement.getId(), announcement.getReference(), request.title(),
                request.audienceRole(), country, event,
                segment.size(), reached, announcement.getSentAt(),
                reached == segment.size()
                        ? "Reached everybody in the segment."
                        : "Reached " + reached + " of " + segment.size() + ". The rest have this "
                          + "event switched off on every channel — the segment size is not the "
                          + "reach, and reporting it as one would be reporting something untrue.");
    }

    @Override
    @Transactional
    public AdminPlatformResponses.NotificationSent notify(
            User staff, AdminPlatformRequests.SendNotification request) {

        User recipient = users.findById(request.userId()).orElseThrow(
                () -> new ResourceNotFoundException("No such user."));

        var sent = notifications.send(recipient.getId(), request.title(), request.message(),
                request.event(), request.referenceId());

        audit.record(AuditAction.NOTIFICATION_SENT_BY_ADMIN, "USER", recipient.getId(),
                recipient.getEmail(),
                staff.getEmail() + " sent a " + request.event() + " message", request.title());

        return new AdminPlatformResponses.NotificationSent(
                sent == null ? null : sent.getId(), recipient.getId(), request.event(),
                sent != null,
                sent != null
                        ? "Sent, on whichever channels they have left switched on."
                        : "Nothing was delivered: they have " + request.event() + " switched off "
                          + "everywhere. That is theirs to choose — this endpoint does not "
                          + "override it. Use a mandatory event if the message is one they cannot "
                          + "afford to miss.");
    }

    // ── Audit ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminPlatformResponses.AuditRow> auditLog(
            Long actorUserId, AuditAction action, String targetType, Long targetId,
            String q, LocalDate from, LocalDate to, Pageable pageable) {

        return PagedResponse.of(auditLogs.search(actorUserId, action, blankToNull(targetType),
                        targetId, blankToNull(q),
                        from == null ? null : from.atStartOfDay(),
                        to == null ? null : to.plusDays(1).atStartOfDay(),
                        pageable)
                .map(row -> new AdminPlatformResponses.AuditRow(
                        row.getId(), row.getCreatedAt(),
                        row.getActor() == null ? null : row.getActor().getId(),
                        row.getActorEmail(), row.getActorName(), row.getAction(),
                        row.getTargetType(), row.getTargetId(), row.getTargetLabel(),
                        row.getSummary(), row.getDetails(), row.getIpAddress())));
    }

    // ── Feature flags ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<AdminPlatformResponses.FlagRow> flags() {
        List<AdminPlatformResponses.FlagRow> rows = new ArrayList<>();
        for (FeatureFlag flag : featureFlags.findAllByOrderByFlagKeyAsc()) {
            rows.add(toFlagRow(flag));
        }
        return rows;
    }

    @Override
    @Transactional
    public AdminPlatformResponses.FlagRow setFlag(
            User staff, String flagKey, AdminPlatformRequests.SetFeatureFlag request) {

        FeatureFlag flag = featureFlags.findByFlagKey(flagKey).orElseThrow(
                () -> new ResourceNotFoundException(
                        "No flag called \"" + flagKey + "\". Flags are declared in code and seeded "
                                + "— this endpoint moves one, it does not invent one, because a "
                                + "flag nothing reads is a switch that does nothing."));

        if (flag.isEnabled() == request.enabled()
                && (request.clientVisible() == null
                    || request.clientVisible() == flag.isClientVisible())) {
            return toFlagRow(flag);   // nothing to record
        }

        boolean was = flag.isEnabled();
        flag.setEnabled(request.enabled());
        if (request.clientVisible() != null) {
            flag.setClientVisible(request.clientVisible());
        }
        flag.setLastChangedByUserId(staff.getId());
        flag.setLastChangedAt(LocalDateTime.now());
        // On the flag rather than only in the audit log, because the person who
        // finds it off in six months reads the flag, not the log. A flag with no
        // reason beside it is one nobody dares turn back on.
        flag.setLastChangeReason(request.reason());
        featureFlags.save(flag);

        // Drop the held values, so a switch thrown during an incident takes
        // effect on the next request rather than at the end of the cache window.
        flagCache.invalidate();

        audit.record(AuditAction.FEATURE_FLAG_CHANGED, "FEATURE_FLAG", flag.getId(),
                flag.getFlagKey(),
                staff.getEmail() + " turned " + flag.getFlagKey()
                        + (was ? " off" : " on") + (was == request.enabled() ? " (unchanged)" : ""),
                request.reason());

        return toFlagRow(flag);
    }

    private static AdminPlatformResponses.FlagRow toFlagRow(FeatureFlag flag) {
        return new AdminPlatformResponses.FlagRow(
                flag.getId(), flag.getFlagKey(), flag.getLabel(), flag.getDescription(),
                flag.isEnabled(), flag.isClientVisible(), flag.getLastChangedByUserId(),
                flag.getLastChangedAt(), flag.getLastChangeReason());
    }

    // ── Jobs ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<AdminPlatformResponses.JobRow> jobs() {
        List<AdminPlatformResponses.JobRow> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (JobRegistry.Described described : jobs.describeAll()) {
            ManagedJob job = described.job();
            JobRun last = described.lastRun();

            // Overdue is measured against the job's OWN interval. Without that,
            // "last ran an hour ago" is uninterpretable: fine for a daily job,
            // alarming for one that runs every thirty seconds.
            boolean overdue = job.isEnabled()
                    && (last == null
                        || Duration.between(last.getStartedAt(), now).toMillis()
                           > job.intervalMs() * 3);

            List<String> warnings = new ArrayList<>();
            if (!job.isEnabled()) {
                warnings.add("Switched off in this deployment.");
            } else if (last == null) {
                warnings.add("Has never run. If the application has been up longer than "
                        + Duration.ofMillis(job.intervalMs()).toSeconds()
                        + " seconds, the scheduler is not running it.");
            } else if (overdue) {
                warnings.add("Last started " + last.getStartedAt() + ", which is more than three "
                        + "times its own interval ago.");
            }
            if (last != null && last.getStatus() == JobRunStatus.ABANDONED) {
                warnings.add("The last pass never finished — the process died mid-run.");
            }
            if (described.failuresInLastDay() > 0) {
                warnings.add(described.failuresInLastDay() + " failure(s) in the last day.");
            }
            if (described.lastSuccess() != null && last != null
                    && described.lastSuccess().getId() < last.getId()) {
                warnings.add("Last clean finish was " + described.lastSuccess().getStartedAt()
                        + " — it has run since without succeeding.");
            }

            rows.add(new AdminPlatformResponses.JobRow(
                    job.jobName(), job.description(), job.isEnabled(), job.intervalMs(),
                    last == null ? null : last.getStartedAt(),
                    last == null ? null : last.getFinishedAt(),
                    last == null ? null : last.getStatus(),
                    last == null ? null : last.durationMs(),
                    last == null ? null : last.getItemsProcessed(),
                    last == null ? null : last.getFailureReason(),
                    described.lastSuccess() == null ? null : described.lastSuccess().getStartedAt(),
                    described.failuresInLastDay(), overdue, warnings));
        }
        return rows;
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminPlatformResponses.JobRow> jobHistory(
            String jobName, JobRunStatus status, Pageable pageable) {

        // Each row is one PASS rather than one job, so the same shape carries
        // the history: what an operator wants here is the sequence, and a gap in
        // it is itself the signal that the scheduler stopped.
        return PagedResponse.of(jobRuns.search(blankToNull(jobName), status, pageable)
                .map(run -> {
                    ManagedJob job = jobs.find(run.getJobName()).orElse(null);
                    return new AdminPlatformResponses.JobRow(
                            run.getJobName(),
                            job == null ? "This job no longer exists in the code." : job.description(),
                            job != null && job.isEnabled(),
                            job == null ? 0 : job.intervalMs(),
                            run.getStartedAt(), run.getFinishedAt(), run.getStatus(),
                            run.durationMs(), run.getItemsProcessed(), run.getFailureReason(),
                            null, 0, false,
                            run.getTriggeredByUserId() == null
                                    ? List.of()
                                    : List.of("Run by hand by "
                                            + emailOf(run.getTriggeredByUserId())));
                }));
    }

    @Override
    @Transactional
    public AdminPlatformResponses.JobTriggered triggerJob(
            User staff, String jobName, AdminPlatformRequests.TriggerJob request) {

        ManagedJob job = jobs.require(jobName);
        if (!job.isEnabled()) {
            throw new BadRequestException(
                    "\"" + jobName + "\" is switched off in this deployment. Running it by hand "
                            + "would do the work the configuration says should not happen here — "
                            + "usually because another instance is doing it.");
        }

        JobRun run;
        try {
            run = jobs.run(job, staff.getId());
        } catch (RuntimeException e) {
            // The registry has already written the failure to a row; this turns
            // it into an answer rather than a stack trace.
            audit.record(AuditAction.JOB_TRIGGERED, "JOB", null, jobName,
                    staff.getEmail() + " ran it by hand and it failed", request.reason());
            throw new BadRequestException(
                    "\"" + jobName + "\" failed: " + e.getMessage()
                            + " The failure is on the run history with your name against it.");
        }

        audit.record(AuditAction.JOB_TRIGGERED, "JOB", run == null ? null : run.getId(), jobName,
                staff.getEmail() + " ran it by hand — "
                        + (run == null ? "no run recorded" : run.getItemsProcessed() + " handled"),
                request.reason());

        return new AdminPlatformResponses.JobTriggered(
                jobName,
                run == null ? null : run.getStatus(),
                run == null ? 0 : run.getItemsProcessed(),
                run == null ? null : run.durationMs(),
                run == null ? null : run.getFailureReason(),
                run != null && run.getItemsProcessed() == 0
                        ? "Nothing to do, which is the healthy answer for a queue drainer — the "
                          + "pass is on the history either way."
                        : "Done. The run is on the history, marked as one a person started rather "
                          + "than the clock.");
    }

    // ── Dashboard ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AdminPlatformResponses.Dashboard dashboard() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();

        List<AdminPlatformResponses.GmvLine> gmv = new ArrayList<>();
        for (Object[] row : vendorOrders.gmvByCurrency(monthStart, dayStart)) {
            String code = (String) row[0];
            gmv.add(new AdminPlatformResponses.GmvLine(code,
                    currencies.round(orZero((BigDecimal) row[1]), code),
                    currencies.round(orZero((BigDecimal) row[2]), code),
                    ((Number) row[3]).longValue()));
        }

        long delivered = shipments.countByStatusSince(ShipmentStatus.DELIVERED, monthStart);
        long failed = shipments.countByStatusSince(ShipmentStatus.ATTEMPT_FAILED, monthStart);
        long attempted = delivered + failed;
        BigDecimal successRate = attempted == 0 ? null
                : BigDecimal.valueOf(delivered)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(attempted), 1, RoundingMode.HALF_UP);

        // A parcel that has not moved in three days is somebody waiting, often
        // for goods a relative abroad paid for.
        long stuck = shipments.countStuckSince(now.minusDays(3));

        long disputesOpen = disputes.countOpen();
        long disputesOverdue = disputes.countOverdue(now);
        long callsOwed = callbacks.countOverdue(now);

        List<String> attention = new ArrayList<>();
        if (disputesOverdue > 0) {
            attention.add(disputesOverdue + " dispute(s) past the deadline promised when they were "
                    + "raised. Both sides have money tied up behind each one.");
        }
        if (callsOwed > 0) {
            attention.add(callsOwed + " call(s) promised and not made. Somebody is waiting by a "
                    + "phone.");
        }
        if (stuck > 0) {
            attention.add(stuck + " parcel(s) have not moved in three days.");
        }
        long awaitingApproval = batches.findByStatusOrderByPreparedAtAsc(
                PayoutBatchStatus.AWAITING_APPROVAL).size();
        if (awaitingApproval > 0) {
            attention.add(awaitingApproval + " payout run(s) waiting on a second person. Sellers "
                    + "are not paid until somebody releases them.");
        }
        long failedPayouts = payouts.countByStatus(PayoutStatus.FAILED);
        if (failedPayouts > 0) {
            attention.add(failedPayouts + " transfer(s) the bank sent back.");
        }
        for (AdminPlatformResponses.JobRow job : jobs()) {
            if (!job.warnings().isEmpty() && job.enabled()) {
                attention.add("Job " + job.name() + ": " + job.warnings().get(0));
            }
        }

        return new AdminPlatformResponses.Dashboard(
                now, gmv,
                orders.countByCreatedAtBetween(dayStart, now),
                orders.countByCreatedAtBetween(monthStart, now),
                shipments.countInFlight(), delivered, failed, successRate,
                disputesOpen, disputesOverdue, callsOwed,
                moderationCases.countByStatus(
                        com.sujula.model.constant.ModerationCaseStatus.OPEN)
                        + moderationCases.countByStatus(
                                com.sujula.model.constant.ModerationCaseStatus.IN_REVIEW),
                kycDocuments.countWaiting(),
                vendors.countByStatus(PartnerStatus.PENDING),
                stuck, awaitingApproval, failedPayouts,
                attention);
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private Dispute requireDispute(Long id) {
        return disputes.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("No such dispute."));
    }

    private String emailOf(Long userId) {
        if (userId == null) return null;
        return users.findById(userId).map(User::getEmail).orElse(null);
    }

    private static String storeNameOf(VendorOrder slice) {
        return slice.getVendor() == null ? "the seller" : slice.getVendor().getStoreName();
    }

    private static String displayCurrencyOf(Dispute dispute) {
        return dispute.getCurrency() != null ? dispute.getCurrency()
                : dispute.getFx() == null ? "" : dispute.getFx().getDisplayCurrency();
    }

    /**
     * A number in the audit log, with most of it withheld.
     *
     * <p>The log is read far more widely than the dispute is, and a recipient's
     * telephone number is the one piece of her the platform holds — she never
     * signed up for anything. The last four are enough to tell two callbacks
     * apart.
     */
    private static String masked(String phone) {
        if (phone == null || phone.length() <= 4) return "a number";
        return "…" + phone.substring(phone.length() - 4);
    }

    private static String nameOf(User user) {
        if (user == null) return "unknown";
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getEmail() : full;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String reference(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
