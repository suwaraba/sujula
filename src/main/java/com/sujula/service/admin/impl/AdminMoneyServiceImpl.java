package com.sujula.service.admin.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.admin.AdminMoneyRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminMoneyResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.constant.ReportType;
import com.sujula.model.finance.FxSpread;
import com.sujula.model.finance.PayoutBatch;
import com.sujula.model.finance.ReportExport;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.model.order.RefundRequest;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.BankAccount;
import com.sujula.model.user.Payout;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.ExchangeRateRepository;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.finance.FxSpreadRepository;
import com.sujula.repository.finance.PayoutBatchRepository;
import com.sujula.repository.finance.ReportExportRepository;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.RefundRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.BankAccountRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.NotificationService;
import com.sujula.service.admin.AdminMoneyService;
import com.sujula.service.money.FxSpreadRegistry;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.security.StepUpVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * The platform's money.
 *
 * <p>Four things in here are not negotiable and are worth reading before
 * changing any of them.
 *
 * <p><b>A refund is a sub-order, never a proportion.</b> The endpoint takes a
 * payment id because that is what an agent is looking at, and immediately
 * demands which seller's part of it (C3). A payment for three vendors that could
 * be refunded 30% would produce three figures none of which corresponds to
 * anything anybody ordered.
 *
 * <p><b>Nothing re-converts.</b> A refund in the buyer's currency is derived
 * from the sub-order's own snapshotted rate, not from today's. Converting again
 * gives the buyer a different number from the one they paid and the difference
 * comes out of the seller or the platform depending which way the currency
 * moved (C2).
 *
 * <p><b>A payout run needs two people.</b> Money leaving is the one action no
 * later call can undo. The person who assembles a batch cannot release it, and
 * both actions demand the actor's own password.
 *
 * <p><b>Nothing here sums two currencies.</b> Every total carries its currency,
 * and a question spanning several comes back as several answers.
 */
@Slf4j
@Service
public class AdminMoneyServiceImpl implements AdminMoneyService {

    /**
     * Above this, in the buyer's currency, a refund demands the administrator's
     * own password again.
     *
     * <p>A threshold rather than always, because an agent settling a dozen small
     * complaints in an afternoon who must retype a password each time will end up
     * with the password on a sticky note, and that is worse than the risk it was
     * guarding against.
     */
    private static final BigDecimal REFUND_STEP_UP_ABOVE = new BigDecimal("5000.00");

    /** A transfer costs a fee whatever it carries. Below this it costs more than it moves. */
    private static final BigDecimal DEFAULT_PAYOUT_FLOOR = new BigDecimal("500.00");

    /** How many attempts a failed transfer gets before the answer is a different account. */
    private static final int MAX_PAYOUT_ATTEMPTS = 3;

    /** Exports per person per window. The file is every vendor's earnings. */
    private static final int EXPORT_LIMIT = 5;
    private static final java.time.Duration EXPORT_WINDOW = java.time.Duration.ofHours(1);

    /** How long an export's link lives. */
    private static final java.time.Duration EXPORT_TTL = java.time.Duration.ofHours(24);

    private final PaymentRepository payments;
    private final VendorOrderRepository vendorOrders;
    private final RefundRequestRepository refunds;
    private final VendorLedgerEntryRepository ledger;
    private final VendorRepository vendors;
    private final PayoutRepository payouts;
    private final PayoutBatchRepository batches;
    private final BankAccountRepository bankAccounts;
    private final FxSpreadRepository spreads;
    private final ExchangeRateRepository rates;
    private final ReportExportRepository exports;
    private final MoneyLedger money;
    private final FxSpreadRegistry spreadRegistry;
    private final ExchangeRateService exchangeRates;
    private final CurrencyCatalogue currencies;
    private final StepUpVerifier stepUp;
    private final AuditService audit;
    private final NotificationService notifications;
    private final com.sujula.repository.user.UserRepository users;

    @Value("${sujula.money.payout-floor:}")
    private String configuredFloor;

    public AdminMoneyServiceImpl(PaymentRepository payments, VendorOrderRepository vendorOrders,
                                 RefundRequestRepository refunds,
                                 VendorLedgerEntryRepository ledger, VendorRepository vendors,
                                 PayoutRepository payouts, PayoutBatchRepository batches,
                                 BankAccountRepository bankAccounts, FxSpreadRepository spreads,
                                 ExchangeRateRepository rates, ReportExportRepository exports,
                                 MoneyLedger money, FxSpreadRegistry spreadRegistry,
                                 ExchangeRateService exchangeRates, CurrencyCatalogue currencies,
                                 StepUpVerifier stepUp, AuditService audit,
                                 NotificationService notifications,
                                 com.sujula.repository.user.UserRepository users) {
        this.payments = payments;
        this.vendorOrders = vendorOrders;
        this.refunds = refunds;
        this.ledger = ledger;
        this.vendors = vendors;
        this.payouts = payouts;
        this.batches = batches;
        this.bankAccounts = bankAccounts;
        this.spreads = spreads;
        this.rates = rates;
        this.exports = exports;
        this.money = money;
        this.spreadRegistry = spreadRegistry;
        this.exchangeRates = exchangeRates;
        this.currencies = currencies;
        this.stepUp = stepUp;
        this.audit = audit;
        this.notifications = notifications;
        this.users = users;
    }

    // ── Payments ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminMoneyResponses.PaymentRow> listPayments(
            String q, PaymentStatus status, String currency, String transactionId,
            LocalDate from, LocalDate to, Pageable pageable) {

        Page<Payment> page = payments.adminSearch(blankToNull(q), status, blankToNull(currency),
                blankToNull(transactionId),
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                pageable);
        return PagedResponse.of(page.map(this::toPaymentRow));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminMoneyResponses.PaymentRow readPayment(Long paymentId) {
        return toPaymentRow(requirePayment(paymentId));
    }

    private AdminMoneyResponses.PaymentRow toPaymentRow(Payment payment) {
        Order order = payment.getOrder();
        List<VendorOrder> slices = order == null
                ? List.of() : vendorOrders.findByOrderId(order.getId());

        BigDecimal charged = payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount();
        BigDecimal returned = payment.getAmountRefunded() == null
                ? BigDecimal.ZERO : payment.getAmountRefunded();

        List<AdminMoneyResponses.SliceRow> sliceRows = new ArrayList<>();
        for (VendorOrder slice : slices) {
            sliceRows.add(toSliceRow(slice));
        }

        List<String> flags = new ArrayList<>();
        if (payment.getStatus() == PaymentStatus.PAID && payment.getPaidAt() == null) {
            flags.add("Marked paid with no time on it");
        }
        if (returned.compareTo(charged) > 0) {
            flags.add("More has been refunded than was charged");
        }
        if (payment.isPaid() && payment.getTransactionId() == null) {
            // A card payment with no provider reference cannot be looked up on
            // the PSP's side, which is exactly what somebody needs when a buyer
            // says the money left their account and the order says otherwise.
            flags.add("No provider reference — this cannot be traced on the PSP's side");
        }
        if (order != null && order.getBillingCountry() != null
                && order.getShippingCountry() != null
                && !order.getBillingCountry().equalsIgnoreCase(order.getShippingCountry())) {
            // Not a problem. Stated because it is the normal shape of this
            // marketplace and an agent unfamiliar with it reads it as fraud.
            flags.add("Paid from " + order.getBillingCountry() + ", delivered to "
                    + order.getShippingCountry() + " — ordinary here");
        }

        return new AdminMoneyResponses.PaymentRow(
                payment.getId(),
                order == null ? null : order.getId(),
                order == null ? null : order.getOrderNumber(),
                payment.getStatus(), payment.getMethod(),
                charged, payment.getCurrency(), returned,
                charged.subtract(returned).max(BigDecimal.ZERO),
                payment.getTransactionId(), payment.getReference(),
                order == null ? null : order.getBillingFullName(),
                order == null ? null : buyerEmail(order),
                order == null ? null : order.getBillingCountry(),
                order == null ? null : order.getShippingCountry(),
                payment.getPaidAt(), payment.getCreatedAt(),
                sliceRows, flags);
    }

    private AdminMoneyResponses.SliceRow toSliceRow(VendorOrder slice) {
        FxSnapshot fx = slice.getFx();
        BigDecimal refundedNative = refunds.findByOrderIdOrderByCreatedAtDesc(
                        slice.getOrder() == null ? null : slice.getOrder().getId()).stream()
                .filter(r -> r.getVendorOrder() != null
                        && r.getVendorOrder().getId().equals(slice.getId()))
                .filter(r -> r.getStatus() == RefundRequestStatus.APPROVED
                        || r.getStatus() == RefundRequestStatus.COMPLETED)
                .map(r -> r.getAmountNative() == null ? BigDecimal.ZERO : r.getAmountNative())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new AdminMoneyResponses.SliceRow(
                slice.getId(),
                slice.getVendor() == null ? null : slice.getVendor().getId(),
                slice.getVendor() == null ? null : slice.getVendor().getStoreName(),
                slice.getStatus() == null ? null : slice.getStatus().name(),
                slice.getTotal(),
                fx == null ? null : fx.getDisplayCurrency(),
                slice.getTotalNative(), slice.getNativeCurrency(),
                fx == null ? null : fx.getRate(),
                fx == null ? null : fx.getRateAt(),
                refundedNative,
                slice.getEscrowReleasedAt() != null,
                slice.getDisputeFrozenAt() != null);
    }

    // ── Refunds ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminMoneyResponses.RefundMade refund(
            User staff, Long paymentId, AdminMoneyRequests.Refund request) {

        Payment payment = requirePayment(paymentId);
        if (!payment.isPaid() && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BadRequestException(
                    "That payment is " + payment.getStatus() + ". There is nothing to give back "
                            + "until money has actually been taken.");
        }

        VendorOrder slice = vendorOrders.findById(request.vendorOrderId()).orElseThrow(
                () -> new ResourceNotFoundException("No such sub-order."));
        if (slice.getOrder() == null || payment.getOrder() == null
                || !slice.getOrder().getId().equals(payment.getOrder().getId())) {
            // Not found rather than forbidden: confirming the sub-order exists
            // tells the caller something about an order that is not theirs.
            throw new ResourceNotFoundException("No such sub-order on this payment.");
        }

        String nativeCurrency = slice.getNativeCurrency();
        FxSnapshot fx = slice.getFx();
        String displayCurrency = fx != null && fx.getDisplayCurrency() != null
                ? fx.getDisplayCurrency() : payment.getCurrency();

        Amounts amounts = resolveRefundAmounts(slice, request, nativeCurrency, displayCurrency, fx);

        BigDecimal alreadyNative = refundedNativeFor(slice);
        BigDecimal sliceTotalNative = slice.getTotalNative() == null
                ? BigDecimal.ZERO : slice.getTotalNative();
        if (alreadyNative.add(amounts.nativeAmount()).compareTo(sliceTotalNative) > 0) {
            throw new BadRequestException(String.format(
                    "That sub-order is worth %s %s and %s has already gone back. Refunding %s more "
                            + "would return more than the seller was ever paid.",
                    sliceTotalNative, nativeCurrency, alreadyNative, amounts.nativeAmount()));
        }

        // Above the threshold, the administrator's own password. An admin session
        // left open on a desk must not be able to move a large sum on its own.
        boolean stepUpRequired = amounts.displayAmount().compareTo(REFUND_STEP_UP_ABOVE) > 0;
        if (stepUpRequired) {
            stepUp.verify(staff, request.password(), request.totpCode(),
                    "refunding " + amounts.displayAmount() + " " + displayCurrency);
        }

        RefundRequest record = RefundRequest.builder()
                .reference(reference("RFD"))
                .order(slice.getOrder())
                .vendorOrder(slice)
                .requestedBy(staff)
                .status(RefundRequestStatus.APPROVED)
                .amount(amounts.displayAmount())
                .currency(displayCurrency)
                .amountNative(amounts.nativeAmount())
                .fx(fx)
                .reason(request.reason())
                .decidedBy(staff)
                .decidedAt(LocalDateTime.now())
                .decisionNote("Refunded by " + staff.getEmail())
                .paymentId(payment.getId())
                // Set by hand: the column is NOT NULL and the entity carries no
                // creation timestamp, so every caller states it. The other two
                // refund paths do the same.
                .createdAt(LocalDateTime.now())
                .build();
        refunds.save(record);

        // The commission on refunded goods goes back to the seller, as its own
        // row rather than netted in: a seller checking a refund is specifically
        // looking to see they were not charged commission on a sale that did not
        // happen.
        BigDecimal commissionBack = commissionShareOf(slice, amounts.nativeAmount(), nativeCurrency);
        money.postRefund(slice, amounts.nativeAmount(), commissionBack, record.getReference(),
                request.reason(), LocalDateTime.now());

        BigDecimal refundedSoFar = (payment.getAmountRefunded() == null
                ? BigDecimal.ZERO : payment.getAmountRefunded()).add(amounts.displayAmount());
        payment.setAmountRefunded(refundedSoFar);
        payment.setStatus(refundedSoFar.compareTo(payment.getAmount()) >= 0
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        if (payment.getRefundedAt() == null) {
            payment.setRefundedAt(LocalDateTime.now());
        }
        payments.save(payment);

        audit.record(AuditAction.PAYMENT_REFUNDED, "PAYMENT", payment.getId(),
                payment.getReference(),
                staff.getEmail() + " refunded " + amounts.displayAmount() + " " + displayCurrency
                        + " (" + amounts.nativeAmount() + " " + nativeCurrency + ") on sub-order "
                        + slice.getId() + (stepUpRequired ? ", with step-up" : ""),
                request.reason());

        notifyRefund(slice, amounts, displayCurrency, nativeCurrency, request.reason());

        return new AdminMoneyResponses.RefundMade(
                record.getId(), record.getReference(), slice.getId(),
                slice.getVendor() == null ? null : slice.getVendor().getStoreName(),
                amounts.displayAmount(), displayCurrency,
                amounts.nativeAmount(), nativeCurrency,
                fx == null ? null : fx.getRate(), fx == null ? null : fx.getRateAt(),
                stepUpRequired,
                (request.isFullRefund() ? "The whole sub-order has gone back. "
                                        : "A partial refund has gone back. ")
                        + "Converted at the rate this order was placed at"
                        + (fx == null || fx.getRateAt() == null ? "" : " on " + fx.getRateAt().toLocalDate())
                        + ", not today's — the buyer gets back what they paid.");
    }

    /** What is going back, in both currencies, and never re-converted. */
    private record Amounts(BigDecimal nativeAmount, BigDecimal displayAmount) {}

    private Amounts resolveRefundAmounts(VendorOrder slice, AdminMoneyRequests.Refund request,
                                         String nativeCurrency, String displayCurrency,
                                         FxSnapshot fx) {
        if (request.isFullRefund()) {
            return new Amounts(
                    currencies.round(orZero(slice.getTotalNative()), nativeCurrency),
                    currencies.round(orZero(slice.getTotal()), displayCurrency));
        }
        if (request.amount() == null || request.amountNative() == null) {
            // Deriving the missing half would mean converting, and converting
            // means reading a rate — today's rate, which is not the rate this
            // order was placed at. The caller is asked for both so the pair is
            // explicit and checkable rather than silently re-priced.
            throw new BadRequestException(
                    "Send both amounts or neither. A partial refund has a figure in the buyer's "
                            + "currency and a figure in the seller's, and working one out from the "
                            + "other here would use today's exchange rate rather than the one this "
                            + "order was placed at — which is how a buyer gets back a different "
                            + "number from the one they paid.");
        }

        BigDecimal nativeAmount = currencies.round(request.amountNative(), nativeCurrency);
        BigDecimal displayAmount = currencies.round(request.amount(), displayCurrency);

        // The two halves have to be consistent at the order's own rate. A
        // tolerance rather than equality because rounding to two different
        // currency scales genuinely produces a small difference.
        if (fx != null && fx.getRate() != null && fx.getRate().signum() > 0
                && !nativeCurrency.equalsIgnoreCase(displayCurrency)) {
            BigDecimal expected = currencies.round(
                    nativeAmount.multiply(fx.getRate()), displayCurrency);
            BigDecimal tolerance = currencies.smallestUnit(displayCurrency)
                    .multiply(BigDecimal.valueOf(2));
            if (expected.subtract(displayAmount).abs().compareTo(tolerance) > 0) {
                throw new BadRequestException(String.format(
                        "Those two amounts do not agree. %s %s at this order's own rate of %s is "
                                + "%s %s, not %s. The rate is the one frozen when the order was "
                                + "placed and it is not recomputed here.",
                        nativeAmount, nativeCurrency, fx.getRate(), expected, displayCurrency,
                        displayAmount));
            }
        }
        return new Amounts(nativeAmount, displayAmount);
    }

    /** The commission that came off this much of the sub-order, handed back. */
    private BigDecimal commissionShareOf(VendorOrder slice, BigDecimal refundedNative,
                                         String currency) {
        BigDecimal total = orZero(slice.getTotalNative());
        BigDecimal commission = orZero(slice.getCommissionNative()).abs();
        if (total.signum() == 0 || commission.signum() == 0) {
            return BigDecimal.ZERO;
        }
        // Proportional to what is going back, which is the one place a proportion
        // is right: it is a proportion of ONE seller's own figures, not a
        // proportion of a payment shared between several (C3).
        return currencies.round(
                commission.multiply(refundedNative).divide(total, 8, RoundingMode.HALF_UP),
                currency);
    }

    private BigDecimal refundedNativeFor(VendorOrder slice) {
        if (slice.getOrder() == null) return BigDecimal.ZERO;
        return refunds.findByOrderIdOrderByCreatedAtDesc(slice.getOrder().getId()).stream()
                .filter(r -> r.getVendorOrder() != null
                        && r.getVendorOrder().getId().equals(slice.getId()))
                .filter(r -> r.getStatus() == RefundRequestStatus.APPROVED
                        || r.getStatus() == RefundRequestStatus.COMPLETED)
                .map(r -> orZero(r.getAmountNative()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void notifyRefund(VendorOrder slice, Amounts amounts, String displayCurrency,
                              String nativeCurrency, String reason) {
        // The seller, in their own currency. Telling a Gambian seller their
        // balance moved by 42 EUR is telling them nothing they can check.
        if (slice.getVendor() != null && slice.getVendor().getUser() != null) {
            notifications.send(slice.getVendor().getUser().getId(),
                    "A refund was issued on one of your orders",
                    amounts.nativeAmount() + " " + nativeCurrency + " has come off your balance"
                            + (reason == null || reason.isBlank() ? "." : ": " + reason)
                            + " The commission on it has been returned to you as a separate line.",
                    NotificationEvent.PAYOUT_SENT, String.valueOf(slice.getId()));
        }
        // The buyer, in the currency they were charged in.
        if (slice.getOrder() != null && slice.getOrder().getCustomer() != null) {
            notifications.send(slice.getOrder().getCustomer().getId(),
                    "A refund is on its way",
                    amounts.displayAmount() + " " + displayCurrency + " is being returned"
                            + (reason == null || reason.isBlank() ? "." : ": " + reason)
                            + " It can take a few days to appear on your statement.",
                    NotificationEvent.ORDER_UPDATE,
                    String.valueOf(slice.getOrder().getId()));
        }
    }

    // ── Ledger ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AdminMoneyResponses.LedgerPage exploreLedger(
            Long vendorId, String currency, LedgerEntryType type, Long orderId,
            String reference, LocalDate from, LocalDate to, Pageable pageable) {

        Page<com.sujula.model.money.VendorLedgerEntry> page = ledger.explore(
                vendorId, blankToNull(currency), type, orderId, blankToNull(reference),
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay().minusNanos(1),
                pageable);

        List<AdminMoneyResponses.LedgerRow> rows = new ArrayList<>();
        // One total per currency on this page, never one across them. A journal
        // spanning GMD and XOF has two totals, and a reader who wanted one is
        // asking for a number that does not exist (C2).
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();

        for (com.sujula.model.money.VendorLedgerEntry entry : page.getContent()) {
            rows.add(new AdminMoneyResponses.LedgerRow(
                    entry.getId(), entry.getOccurredAt(),
                    entry.getVendor() == null ? null : entry.getVendor().getId(),
                    entry.getVendor() == null ? null : entry.getVendor().getStoreName(),
                    entry.getType(), entry.getAmount(), entry.getCurrency(),
                    entry.getAvailableFrom(),
                    entry.getVendorOrder() == null ? null : entry.getVendorOrder().getId(),
                    entry.getVendorOrder() == null || entry.getVendorOrder().getOrder() == null
                            ? null : entry.getVendorOrder().getOrder().getId(),
                    entry.getPayout() == null ? null : entry.getPayout().getId(),
                    entry.getReference(), entry.getDescription()));

            String code = entry.getCurrency();
            sums.merge(code, orZero(entry.getAmount()), BigDecimal::add);
            counts.merge(code, 1L, Long::sum);
        }

        List<AdminMoneyResponses.CurrencyTotal> totals = new ArrayList<>();
        sums.forEach((code, sum) -> totals.add(new AdminMoneyResponses.CurrencyTotal(
                code, currencies.round(sum, code), counts.getOrDefault(code, 0L))));

        return new AdminMoneyResponses.LedgerPage(rows, totals, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminMoneyResponses.Reconciliation reconcile(LocalDate asOf) {
        LocalDate day = asOf == null ? LocalDate.now() : asOf;

        Map<String, BigDecimal> escrow = toMap(ledger.escrowByCurrency());
        Map<String, BigDecimal> available = toMap(ledger.availableByCurrency());
        Map<String, BigDecimal> paidOut = new LinkedHashMap<>();
        Map<String, BigDecimal> inFlight = new LinkedHashMap<>();
        for (Payout payout : payouts.findAll()) {
            String code = payout.getCurrency();
            if (code == null) continue;
            if (payout.getStatus() == PayoutStatus.COMPLETED) {
                paidOut.merge(code, orZero(payout.getAmount()), BigDecimal::add);
            } else if (payout.getStatus().isOpen()) {
                inFlight.merge(code, orZero(payout.getAmount()), BigDecimal::add);
            }
        }

        // What buyers paid, by the currency they were charged in. That is a
        // DIFFERENT denomination from the ledger side for every cross-border
        // order, which is most of them here — so the two are shown side by side
        // and the difference is only computed where the currencies genuinely
        // match. Subtracting euros from dalasi would produce an alarming number
        // that is pure arithmetic (C2).
        Map<String, BigDecimal> taken = new LinkedHashMap<>();
        for (Object[] row : payments.takenByCurrency()) {
            String code = (String) row[0];
            BigDecimal gross = (BigDecimal) row[1];
            BigDecimal returned = (BigDecimal) row[2];
            taken.put(code, orZero(gross).subtract(orZero(returned)));
        }

        java.util.SortedSet<String> allCurrencies = new java.util.TreeSet<>();
        allCurrencies.addAll(escrow.keySet());
        allCurrencies.addAll(available.keySet());
        allCurrencies.addAll(paidOut.keySet());
        allCurrencies.addAll(inFlight.keySet());
        allCurrencies.addAll(taken.keySet());

        List<AdminMoneyResponses.ReconciliationLine> lines = new ArrayList<>();
        List<String> findings = new ArrayList<>();

        for (String code : allCurrencies) {
            BigDecimal held = currencies.round(escrow.getOrDefault(code, BigDecimal.ZERO), code);
            BigDecimal payable = currencies.round(
                    available.getOrDefault(code, BigDecimal.ZERO), code);
            BigDecimal out = currencies.round(paidOut.getOrDefault(code, BigDecimal.ZERO), code);
            BigDecimal moving = currencies.round(
                    inFlight.getOrDefault(code, BigDecimal.ZERO), code);
            BigDecimal fromBuyers = taken.containsKey(code)
                    ? currencies.round(taken.get(code), code) : null;

            BigDecimal difference = null;
            String caveat = null;
            boolean balanced = true;

            if (fromBuyers == null) {
                caveat = "No buyer was charged in " + code + " — this is a settlement currency "
                        + "only, so there is no payment side to compare against.";
            } else {
                // Only comparable where the platform both charged and settles in
                // this currency. The difference is what should still be sitting
                // with the platform less what the ledger says it holds.
                difference = fromBuyers.subtract(held).subtract(payable).subtract(out)
                        .subtract(moving);
                balanced = difference.abs().compareTo(currencies.smallestUnit(code)) <= 0;
                caveat = "Cross-border orders are charged in the buyer's currency and settled in "
                        + "the seller's, so a difference here is expected wherever those differ — "
                        + "compare against the payment provider before treating it as a "
                        + "discrepancy.";
                if (!balanced) {
                    findings.add(code + ": " + difference + " unaccounted for.");
                }
            }

            if (payable.signum() < 0) {
                findings.add(code + ": vendors are collectively in debt by " + payable.abs()
                        + " — somebody was paid out before a refund came in.");
            }

            lines.add(new AdminMoneyResponses.ReconciliationLine(
                    code, held, payable, fromBuyers, out, moving, difference, balanced, caveat));
        }

        return new AdminMoneyResponses.Reconciliation(day, lines, findings,
                findings.isEmpty()
                        ? "Nothing is unaccounted for."
                        : findings.size() + " thing(s) worth looking at.");
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminMoneyResponses.VendorBalance> listBalances(
            Long vendorId, String currency, boolean payableOnly, Pageable pageable) {

        List<Object[]> rows = ledger.allBalances(vendorId, blankToNull(currency));
        Map<Long, Vendor> loaded = new LinkedHashMap<>();
        Map<Long, Map<String, BigDecimal>> committed = new LinkedHashMap<>();

        List<AdminMoneyResponses.VendorBalance> balances = new ArrayList<>();
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            String code = (String) row[1];
            BigDecimal available = currencies.round(orZero((BigDecimal) row[2]), code);
            BigDecimal held = currencies.round(orZero((BigDecimal) row[3]), code);

            if (payableOnly && available.signum() <= 0) {
                continue;
            }

            Vendor vendor = loaded.computeIfAbsent(id,
                    key -> vendors.findById(key).orElse(null));
            if (vendor == null) continue;

            BigDecimal inFlight = committed
                    .computeIfAbsent(id, this::openPayoutsByCurrency)
                    .getOrDefault(code, BigDecimal.ZERO);

            balances.add(new AdminMoneyResponses.VendorBalance(
                    id, vendor.getStoreName(), vendor.getSettlementCurrency(), code,
                    available, held, currencies.round(available.add(held), code),
                    currencies.round(inFlight, code),
                    vendor.arePayoutsHeld(), vendor.getPayoutsHeldReason(),
                    vendor.getSettlementCurrency() != null
                            && !vendor.getSettlementCurrency().equalsIgnoreCase(code)));
        }

        // Paged in memory: this is one GROUP BY over the ledger returning one row
        // per vendor per currency, which is hundreds rather than millions, and
        // the in-flight figure beside each needs the payout table anyway.
        int start = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(),
                balances.size());
        int end = Math.min(start + pageable.getPageSize(), balances.size());
        List<AdminMoneyResponses.VendorBalance> pageRows = balances.subList(start, end);

        return PagedResponse.<AdminMoneyResponses.VendorBalance>builder()
                .content(pageRows)
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(balances.size())
                .totalPages((int) Math.ceil(balances.size() / (double) pageable.getPageSize()))
                .last(end >= balances.size())
                .build();
    }

    private Map<String, BigDecimal> openPayoutsByCurrency(Long vendorId) {
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        for (Payout payout : payouts.findUnsettledForVendor(vendorId)) {
            if (payout.getStatus() != null && payout.getStatus().isOpen()
                    && payout.getCurrency() != null) {
                byCurrency.merge(payout.getCurrency(), orZero(payout.getAmount()), BigDecimal::add);
            }
        }
        return byCurrency;
    }

    private static Map<String, BigDecimal> toMap(List<Object[]> rows) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.put((String) row[0], orZero((BigDecimal) row[1]));
        }
        return map;
    }

    // ── Payout batches ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminMoneyResponses.BatchRow> listBatches(
            PayoutBatchStatus status, String currency, Pageable pageable) {
        return PagedResponse.of(batches.search(status, blankToNull(currency), pageable)
                .map(this::toBatchRow));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminMoneyResponses.BatchRow readBatch(Long batchId) {
        return toBatchRow(requireBatch(batchId));
    }

    private AdminMoneyResponses.BatchRow toBatchRow(PayoutBatch batch) {
        List<AdminMoneyResponses.BatchItem> items = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;

        for (Payout payout : payouts.findByBatchIdOrderByIdAsc(batch.getId())) {
            Vendor vendor = payout.getVendor();
            items.add(new AdminMoneyResponses.BatchItem(
                    payout.getId(),
                    vendor == null ? null : vendor.getId(),
                    vendor == null ? null : vendor.getStoreName(),
                    payout.getAmount(), payout.getCurrency(), payout.getStatus(),
                    payout.getAttempts(), payout.getFailureReason(),
                    vendor == null ? null : destinationSummary(vendor)));
            sum = sum.add(orZero(payout.getAmount()));
        }

        List<String> warnings = new ArrayList<>();
        BigDecimal stored = orZero(batch.getTotal());
        if (stored.compareTo(sum) != 0) {
            // The reason the total is stored rather than summed. A batch whose
            // recorded total and item sum disagree has been edited underneath,
            // and releasing it would move an amount nobody approved — which is
            // exactly what the second approver believes they are preventing.
            warnings.add("The recorded total is " + stored + " but the items add up to " + sum
                    + ". This batch has changed since it was assembled and must not be released.");
        }
        for (AdminMoneyResponses.BatchItem item : items) {
            if (item.bankAccountSummary() == null) {
                warnings.add(item.storeName() + " has no payout destination on file.");
            }
        }

        return new AdminMoneyResponses.BatchRow(
                batch.getId(), batch.getReference(), batch.getStatus(), batch.getCurrency(),
                stored, batch.getItemCount(),
                batch.getPreparedByUserId(), emailOf(batch.getPreparedByUserId()),
                batch.getPreparedAt(),
                batch.getApprovedByUserId(), emailOf(batch.getApprovedByUserId()),
                batch.getApprovedAt(),
                batch.getNote(), batch.getExclusions(), items, warnings);
    }

    @Override
    @Transactional
    public AdminMoneyResponses.BatchSaved prepareBatch(
            User staff, AdminMoneyRequests.PrepareBatch request) {

        String currency = currencies.require(request.currency());

        // Money leaving cannot be undone. Assembling a run is not itself a
        // transfer, but it is the moment the amounts are decided, and the person
        // approving it afterwards is checking a list somebody else made.
        stepUp.verify(staff, request.password(), request.totpCode(),
                "preparing a payout run in " + currency);

        if (batches.hasOpenBatchFor(currency)) {
            throw new BadRequestException(
                    "There is already an open " + currency + " run. Two open batches in one "
                            + "currency is how the same balance is committed twice — finish or "
                            + "cancel that one first.");
        }

        BigDecimal floor = request.minimumAmount() != null
                ? request.minimumAmount() : payoutFloor();

        List<Object[]> rows = ledger.allBalances(null, currency);
        List<Payout> items = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        LocalDateTime now = LocalDateTime.now();

        PayoutBatch batch = PayoutBatch.builder()
                .reference(reference("PB"))
                .status(PayoutBatchStatus.DRAFT)
                .currency(currency)
                .preparedByUserId(staff.getId())
                .preparedAt(now)
                .note(request.note())
                .build();
        batches.save(batch);

        for (Object[] row : rows) {
            Long vendorId = ((Number) row[0]).longValue();
            BigDecimal available = currencies.round(orZero((BigDecimal) row[2]), currency);

            if (request.vendorIds() != null && !request.vendorIds().isEmpty()
                    && !request.vendorIds().contains(vendorId)) {
                continue;
            }

            Vendor vendor = vendors.findById(vendorId).orElse(null);
            if (vendor == null) continue;

            // A suspended store's money is HELD, not refused. The seller earned
            // it and the platform is keeping it still — so it is named in the
            // exclusions rather than quietly missing, because an approver who
            // notices a vendor absent will assume a bug.
            if (vendor.arePayoutsHeld()) {
                excluded.add(vendor.getStoreName() + ": payouts on hold ("
                        + (vendor.getPayoutsHeldReason() == null ? "no reason recorded"
                                                                : vendor.getPayoutsHeldReason())
                        + ") — " + available + " " + currency + " is being kept, not lost.");
                continue;
            }
            if (available.signum() <= 0) {
                continue;   // nothing owed; not worth naming
            }

            BigDecimal committed = openPayoutsByCurrency(vendorId)
                    .getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal payable = available.subtract(committed);
            if (payable.signum() <= 0) {
                excluded.add(vendor.getStoreName() + ": already has " + committed + " " + currency
                        + " in flight.");
                continue;
            }
            if (payable.compareTo(floor) < 0) {
                excluded.add(vendor.getStoreName() + ": " + payable + " " + currency
                        + " is below the " + floor + " floor — a transfer would cost more than it "
                        + "moves, so it rolls into the next run.");
                continue;
            }
            if (destinationSummary(vendor) == null) {
                excluded.add(vendor.getStoreName() + ": no payout destination on file.");
                continue;
            }

            Payout payout = Payout.builder()
                    .user(vendor.getUser())
                    .vendor(vendor)
                    .requestedBy(staff)
                    .requestedAt(now)
                    .amount(payable)
                    .currency(currency)
                    .status(PayoutStatus.REQUESTED)
                    .reference(reference("PO"))
                    .batch(batch)
                    .notes("Run " + batch.getReference())
                    .build();
            payouts.save(payout);
            items.add(payout);
            total = total.add(payable);
        }

        if (items.isEmpty()) {
            batch.setStatus(PayoutBatchStatus.CANCELLED);
            batch.setCancelledAt(now);
            batch.setCancelledReason("Nothing to pay out.");
            batch.setExclusions(String.join("\n", excluded));
            batch.applyTotals(BigDecimal.ZERO, 0);
            batches.save(batch);
            return new AdminMoneyResponses.BatchSaved(batch.getId(), batch.getReference(),
                    batch.getStatus(), currency, BigDecimal.ZERO, 0,
                    excluded.isEmpty()
                            ? "Nobody is owed anything in " + currency + " right now."
                            : "Nothing to pay out. " + excluded.size() + " balance(s) were "
                              + "excluded — see the exclusions for why.");
        }

        batch.applyTotals(currencies.round(total, currency), items.size());
        batch.setStatus(PayoutBatchStatus.AWAITING_APPROVAL);
        batch.setExclusions(excluded.isEmpty() ? null : String.join("\n", excluded));
        batches.save(batch);

        audit.record(AuditAction.PAYOUT_BATCH_CREATED, "PAYOUT_BATCH", batch.getId(),
                batch.getReference(),
                staff.getEmail() + " assembled " + items.size() + " transfer(s) totalling "
                        + batch.getTotal() + " " + currency,
                request.note());

        return new AdminMoneyResponses.BatchSaved(batch.getId(), batch.getReference(),
                batch.getStatus(), currency, batch.getTotal(), items.size(),
                "Waiting for a second person. You cannot approve this one yourself — money "
                        + "leaving is the one thing no later call can undo."
                        + (excluded.isEmpty() ? ""
                                : " " + excluded.size() + " balance(s) were excluded; the reasons "
                                  + "are on the batch."));
    }

    @Override
    @Transactional
    public AdminMoneyResponses.BatchSaved approveBatch(
            User staff, Long batchId, AdminMoneyRequests.ApproveBatch request) {

        PayoutBatch batch = requireBatch(batchId);

        if (batch.getStatus() != PayoutBatchStatus.AWAITING_APPROVAL) {
            throw new BadRequestException(
                    "That run is " + batch.getStatus() + " and cannot be approved.");
        }
        if (!batch.canBeApprovedBy(staff.getId())) {
            throw new BadRequestException(
                    "You prepared this run, so you cannot release it. Four eyes on money leaving "
                            + "is the only control between a compromised session and every vendor "
                            + "balance on the platform.");
        }

        stepUp.verify(staff, request.password(), request.totpCode(),
                "releasing " + batch.getTotal() + " " + batch.getCurrency() + " of transfers");

        List<Payout> items = payouts.findByBatchIdOrderByIdAsc(batch.getId());
        BigDecimal sum = items.stream().map(p -> orZero(p.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (currencies.round(sum, batch.getCurrency()).compareTo(orZero(batch.getTotal())) != 0) {
            // The stored total is what the approver is being asked about. If the
            // items no longer add to it, this is not the run they were shown.
            throw new BadRequestException(String.format(
                    "This run was assembled as %s %s and its transfers now add up to %s. It has "
                            + "changed since it was put in front of you — cancel it and assemble "
                            + "a fresh one rather than releasing an amount nobody approved.",
                    batch.getTotal(), batch.getCurrency(), sum));
        }

        LocalDateTime now = LocalDateTime.now();
        for (Payout payout : items) {
            // The ledger entry is what commits the money. Posting it here rather
            // than at assembly means a batch that is never approved has moved
            // nothing at all.
            payout.setStatus(PayoutStatus.PENDING);
            payout.setProcessedBy(staff);
            payout.setAttempts(payout.getAttempts() + 1);
            payout.setLastAttemptAt(now);
            payouts.save(payout);
            money.postPayout(payout.getVendor(), payout);

            if (payout.getVendor() != null && payout.getVendor().getUser() != null) {
                notifications.send(payout.getVendor().getUser().getId(),
                        "A payout is on its way",
                        payout.getAmount() + " " + payout.getCurrency()
                                + " has been sent to your account on file. It usually arrives "
                                + "within a few working days.",
                        NotificationEvent.PAYOUT_SENT, String.valueOf(payout.getId()));
            }
        }

        batch.setStatus(PayoutBatchStatus.APPROVED);
        batch.setApprovedByUserId(staff.getId());
        batch.setApprovedAt(now);
        if (request.note() != null && !request.note().isBlank()) {
            batch.setNote((batch.getNote() == null ? "" : batch.getNote() + "\n")
                    + "Approved: " + request.note());
        }
        batches.save(batch);

        audit.record(AuditAction.PAYOUT_BATCH_APPROVED, "PAYOUT_BATCH", batch.getId(),
                batch.getReference(),
                staff.getEmail() + " released " + items.size() + " transfer(s) totalling "
                        + batch.getTotal() + " " + batch.getCurrency()
                        + ", prepared by " + emailOf(batch.getPreparedByUserId()),
                request.note());

        return new AdminMoneyResponses.BatchSaved(batch.getId(), batch.getReference(),
                batch.getStatus(), batch.getCurrency(), batch.getTotal(), items.size(),
                "Released. Each transfer now succeeds or fails on its own — a batch is how they "
                        + "were authorised, not one thing that either worked or did not.");
    }

    @Override
    @Transactional
    public AdminMoneyResponses.BatchSaved cancelBatch(
            User staff, Long batchId, AdminMoneyRequests.CancelBatch request) {

        PayoutBatch batch = requireBatch(batchId);
        if (!batch.getStatus().isOpen()) {
            throw new BadRequestException(
                    "That run is " + batch.getStatus() + ". Once transfers are released they are "
                            + "with a bank, and cancelling the batch here would not recall them.");
        }

        List<Payout> items = payouts.findByBatchIdOrderByIdAsc(batch.getId());
        for (Payout payout : items) {
            payout.setStatus(PayoutStatus.CANCELLED);
            payout.setNotes("Run " + batch.getReference() + " cancelled: " + request.reason());
            payouts.save(payout);
        }

        batch.setStatus(PayoutBatchStatus.CANCELLED);
        batch.setCancelledAt(LocalDateTime.now());
        batch.setCancelledReason(request.reason());
        batches.save(batch);

        audit.record(AuditAction.PAYOUT_BATCH_CANCELLED, "PAYOUT_BATCH", batch.getId(),
                batch.getReference(),
                staff.getEmail() + " cancelled the run before release", request.reason());

        return new AdminMoneyResponses.BatchSaved(batch.getId(), batch.getReference(),
                batch.getStatus(), batch.getCurrency(), batch.getTotal(), items.size(),
                "Cancelled before anything moved. No ledger entry was written, so no seller's "
                        + "balance changed — they are still owed exactly what they were.");
    }

    @Override
    @Transactional
    public AdminMoneyResponses.PayoutRetried retryPayout(
            User staff, Long payoutId, AdminMoneyRequests.RetryPayoutItem request) {

        Payout payout = payouts.findById(payoutId).orElseThrow(
                () -> new ResourceNotFoundException("No such payout."));

        if (payout.getStatus() != PayoutStatus.FAILED) {
            throw new BadRequestException(
                    "That payout is " + payout.getStatus() + ". Only a failed transfer is retried "
                            + "— retrying one that is with a bank would send the money twice.");
        }
        if (payout.getAttempts() >= MAX_PAYOUT_ATTEMPTS) {
            throw new BadRequestException(
                    "This has been attempted " + payout.getAttempts() + " times and the bank has "
                            + "refused it every time. The answer now is a different destination "
                            + "account, not another attempt — the reason on the last failure was: "
                            + (payout.getFailureReason() == null ? "not recorded"
                                                                 : payout.getFailureReason()));
        }

        // A retry is a new attempt on the SAME payout, never a new row. The
        // seller is owed one amount, and a second row would look like two.
        // Reversing first is what puts the money back before it is committed
        // again, so the balance is right in between the two attempts.
        money.reversePayout(payout, "Retry: " + request.reason());

        payout.setStatus(PayoutStatus.PENDING);
        payout.setAttempts(payout.getAttempts() + 1);
        payout.setLastAttemptAt(LocalDateTime.now());
        payout.setFailureReason(null);
        payout.setProcessedBy(staff);
        payouts.save(payout);
        money.postPayout(payout.getVendor(), payout);

        audit.record(AuditAction.PAYOUT_ITEM_RETRIED, "PAYOUT", payout.getId(),
                payout.getReference(),
                staff.getEmail() + " retried the transfer (attempt " + payout.getAttempts() + ")",
                request.reason());

        return new AdminMoneyResponses.PayoutRetried(payout.getId(), payout.getStatus(),
                payout.getAttempts(),
                "Attempt " + payout.getAttempts() + " of " + MAX_PAYOUT_ATTEMPTS
                        + ". The failed attempt is still in the ledger as a reversal — the seller "
                        + "can see what happened rather than an unexplained gap.");
    }

    private PayoutBatch requireBatch(Long id) {
        return batches.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("No such payout run."));
    }

    private BigDecimal payoutFloor() {
        if (configuredFloor == null || configuredFloor.isBlank()) {
            return DEFAULT_PAYOUT_FLOOR;
        }
        try {
            return new BigDecimal(configuredFloor.trim());
        } catch (NumberFormatException e) {
            log.warn("[Money] sujula.money.payout-floor is not a number: {}", configuredFloor);
            return DEFAULT_PAYOUT_FLOOR;
        }
    }

    /**
     * Where a seller's money goes, in the form it is safe to show.
     *
     * <p>Last four digits only. An administrator checking a run needs to see
     * that a destination exists and roughly which one it is; the full number is
     * encrypted and there is no reason for it to appear on a screen somebody is
     * scrolling through twenty of.
     */
    private String destinationSummary(Vendor vendor) {
        Optional<BankAccount> account = bankAccounts.findDefaultForVendor(vendor.getId());
        if (account.isEmpty()) {
            List<BankAccount> any = bankAccounts.findForVendor(vendor.getId());
            if (any.isEmpty()) return null;
            account = Optional.of(any.get(0));
        }
        BankAccount destination = account.get();
        String tail = destination.getAccountNumberLast4() != null
                ? destination.getAccountNumberLast4()
                : destination.getIbanLast4() != null
                        ? destination.getIbanLast4()
                        : destination.getMobileMoneyLast4();
        if (tail == null) return null;
        String where = destination.getBankName() != null
                ? destination.getBankName()
                : destination.getMobileMoneyProvider() != null
                        ? destination.getMobileMoneyProvider() : "account";
        return where + " ****" + tail;
    }

    // ── FX ───────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminMoneyResponses.RateRow> rateHistory(
            String currency, LocalDate from, LocalDate to, Pageable pageable) {

        return PagedResponse.of(rates.history(blankToNull(currency), from, to, pageable)
                .map(rate -> {
                    // The spread as it stood on the day of the rate, not today's.
                    // Reading the two together is the only way a figure on an
                    // old order can be reconstructed, and pairing an old rate
                    // with a current spread would reconstruct it wrong.
                    FxSpreadRegistry.Applied applied = spreadRegistry.resolve(
                            rate.getFromCurrency(), rate.getCurrency(),
                            rate.getRateDate() == null ? null : rate.getRateDate().atStartOfDay());
                    return new AdminMoneyResponses.RateRow(
                            rate.getId(), rate.getFromCurrency(), rate.getCurrency(),
                            rate.getRate(), rate.getRateDate(), rate.getCreatedAt(),
                            applied.basisPoints(),
                            rate.getRate() == null ? null
                                    : rate.getRate().multiply(applied.multiplier())
                                            .setScale(8, RoundingMode.HALF_UP));
                }));
    }

    @Override
    @Transactional
    public AdminMoneyResponses.RatesRefreshed refreshRates(
            User staff, AdminMoneyRequests.RefreshRates request) {

        // Which pairs to pull. Given none, every pair the platform has ever
        // recorded — because the set in use is what the data says, not what
        // somebody remembers to list.
        java.util.SortedSet<String> targets = new java.util.TreeSet<>();
        if (request.currencies() != null && !request.currencies().isEmpty()) {
            request.currencies().forEach(code -> targets.add(currencies.require(code)));
        } else {
            for (Object[] pair : rates.knownPairs()) {
                if (pair[1] != null) targets.add(((String) pair[1]).toUpperCase(java.util.Locale.ROOT));
            }
        }

        java.util.SortedSet<String> sources = new java.util.TreeSet<>();
        for (Object[] pair : rates.knownPairs()) {
            if (pair[0] != null) sources.add(((String) pair[0]).toUpperCase(java.util.Locale.ROOT));
        }
        if (sources.isEmpty()) {
            sources.add(currencies.baseCurrency());
        }

        int refreshed = 0;
        int unchanged = 0;
        List<String> failed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (String target : targets) {
            java.util.Set<String> from = new java.util.LinkedHashSet<>(sources);
            from.remove(target);
            if (from.isEmpty()) continue;
            try {
                Map<String, BigDecimal> pulled = exchangeRates.getLatestRates(target, from);
                if (pulled == null || pulled.isEmpty()) {
                    failed.add(target + ": the provider returned nothing");
                    continue;
                }
                for (Map.Entry<String, BigDecimal> entry : pulled.entrySet()) {
                    Optional<com.sujula.model.ExchangeRate> existing =
                            rates.findByFromCurrencyAndCurrencyAndRateDate(
                                    entry.getKey(), target, LocalDate.now());
                    if (existing.isPresent()) {
                        if (existing.get().getRate() != null
                                && existing.get().getRate().compareTo(entry.getValue()) == 0) {
                            unchanged++;
                            continue;
                        }
                        // Today's row is updated rather than duplicated; a
                        // previous day's is never touched, because an order
                        // converted yesterday cites yesterday's row.
                        existing.get().setRate(entry.getValue());
                        rates.save(existing.get());
                    } else {
                        rates.save(com.sujula.model.ExchangeRate.builder()
                                .fromCurrency(entry.getKey())
                                .currency(target)
                                .rate(entry.getValue())
                                .rateDate(LocalDate.now())
                                .build());
                    }
                    refreshed++;
                }
            } catch (RuntimeException e) {
                // One provider failure must not stop the rest: a platform with
                // three currencies and one flaky feed still needs the other two.
                log.warn("[Money] Could not refresh rates into {}: {}", target, e.getMessage());
                failed.add(target + ": " + e.getMessage());
            }
        }

        audit.record(AuditAction.FX_REFRESHED, "FX", null, "rates",
                staff.getEmail() + " pulled rates: " + refreshed + " changed, " + unchanged
                        + " unchanged, " + failed.size() + " failed",
                request.note());

        return new AdminMoneyResponses.RatesRefreshed(refreshed, unchanged, failed, now,
                failed.isEmpty()
                        ? refreshed + " rate(s) updated. Orders already placed keep the rate they "
                          + "were converted at — this changes what happens next, not what happened."
                        : refreshed + " updated, " + failed.size() + " pair(s) could not be "
                          + "pulled. The old rates are still in force for those.");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminMoneyResponses.SpreadRow> spreads() {
        LocalDateTime now = LocalDateTime.now();
        List<FxSpread> all = spreads.findAll().stream()
                .sorted(Comparator.comparing(FxSpread::getEffectiveFrom).reversed())
                .toList();

        List<AdminMoneyResponses.SpreadRow> rows = new ArrayList<>();
        for (FxSpread spread : all) {
            // "In force now" is resolved through the registry rather than guessed
            // from the dates, so the flag agrees with what a conversion would
            // actually use — including the most-specific-wins rule.
            FxSpreadRegistry.Applied applied = spreadRegistry.resolve(
                    spread.getFromCurrency() == null ? "GMD" : spread.getFromCurrency(),
                    spread.getToCurrency() == null ? "EUR" : spread.getToCurrency(), now);
            rows.add(toSpreadRow(spread,
                    applied.spreadId() != null && applied.spreadId().equals(spread.getId())));
        }
        return rows;
    }

    @Override
    @Transactional
    public AdminMoneyResponses.SpreadSet setSpread(User staff, AdminMoneyRequests.SetSpread request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = request.effectiveFrom() == null ? now : request.effectiveFrom();

        if (from.isBefore(now.minusMinutes(1))) {
            // Every converted figure anybody was charged carries the rate and
            // the moment it was taken. A backdated spread would claim they were
            // charged something they were not.
            throw new BadRequestException(
                    "A spread cannot start in the past. Conversions already made carry the rate "
                            + "and the spread that applied at the time, and backdating this would "
                            + "say buyers were charged something they were not.");
        }

        String fromCurrency = request.fromCurrency() == null
                ? null : currencies.require(request.fromCurrency());
        String toCurrency = request.toCurrency() == null
                ? null : currencies.require(request.toCurrency());
        if (fromCurrency != null && fromCurrency.equalsIgnoreCase(toCurrency)) {
            throw new BadRequestException(
                    "There is no conversion from " + fromCurrency + " to itself to add a spread to.");
        }

        // What this is replacing, read before the new row exists.
        FxSpreadRegistry.Applied before = spreadRegistry.resolve(
                fromCurrency == null ? "GMD" : fromCurrency,
                toCurrency == null ? "EUR" : toCurrency, now);
        FxSpread superseded = before.spreadId() == null
                ? null : spreads.findById(before.spreadId()).orElse(null);

        FxSpread spread = FxSpread.builder()
                .fromCurrency(fromCurrency)
                .toCurrency(toCurrency)
                .basisPoints(request.basisPoints())
                .effectiveFrom(from)
                .setByUserId(staff.getId())
                .reason(request.reason())
                .build();
        spreads.save(spread);

        audit.record(AuditAction.FX_SPREAD_CHANGED, "FX_SPREAD", spread.getId(),
                (fromCurrency == null ? "*" : fromCurrency) + "→"
                        + (toCurrency == null ? "*" : toCurrency),
                staff.getEmail() + " set the spread to " + spread.asPercentage() + " from " + from
                        + (superseded == null ? "" : ", replacing " + superseded.asPercentage()),
                request.reason());

        return new AdminMoneyResponses.SpreadSet(
                toSpreadRow(spread, !from.isAfter(now)),
                superseded == null ? null : toSpreadRow(superseded, false),
                (from.isAfter(now) ? "Takes effect " + from + ". " : "In force now. ")
                        + "Rows are appended rather than edited, so the spread that applied to any "
                        + "past order is still answerable."
                        + (superseded == null ? "" : " It replaces " + superseded.asPercentage()
                                + ", which stays on record."));
    }

    private static AdminMoneyResponses.SpreadRow toSpreadRow(FxSpread spread, boolean inForce) {
        return new AdminMoneyResponses.SpreadRow(
                spread.getId(), spread.getFromCurrency(), spread.getToCurrency(),
                spread.getBasisPoints(), spread.asPercentage(), spread.getEffectiveFrom(),
                spread.getSetByUserId(), spread.getReason(), inForce);
    }

    // ── Reports ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AdminMoneyResponses.RevenueReport revenue(LocalDate from, LocalDate to, Long vendorId) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start)) {
            throw new BadRequestException("The window ends before it starts.");
        }

        Map<String, BigDecimal[]> byCurrency = new LinkedHashMap<>();
        Map<String, Long> orders = new LinkedHashMap<>();

        for (Object[] row : ledger.platformTotalsByType(
                start.atStartOfDay(), end.plusDays(1).atStartOfDay(), vendorId)) {
            LedgerEntryType type = (LedgerEntryType) row[0];
            String code = (String) row[1];
            BigDecimal amount = orZero((BigDecimal) row[2]);
            long count = ((Number) row[3]).longValue();

            // [gross, commission, commissionReversed, refunds]
            BigDecimal[] totals = byCurrency.computeIfAbsent(code,
                    key -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO,
                                              BigDecimal.ZERO, BigDecimal.ZERO });
            switch (type) {
                case SALE -> { totals[0] = totals[0].add(amount); orders.merge(code, count, Long::sum); }
                case COMMISSION -> totals[1] = totals[1].add(amount.abs());
                case COMMISSION_REVERSAL -> totals[2] = totals[2].add(amount);
                case REFUND -> totals[3] = totals[3].add(amount.abs());
                default -> { /* payouts and holds move money, they do not earn it */ }
            }
        }

        List<AdminMoneyResponses.RevenueLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> entry : byCurrency.entrySet()) {
            String code = entry.getKey();
            BigDecimal[] totals = entry.getValue();
            BigDecimal netCommission = totals[1].subtract(totals[2]);

            lines.add(new AdminMoneyResponses.RevenueLine(
                    code,
                    currencies.round(totals[0], code),
                    currencies.round(totals[1], code),
                    currencies.round(totals[2], code),
                    currencies.round(totals[3], code),
                    currencies.round(netCommission, code),
                    // FX margin belongs to the buyer's currency, not the
                    // seller's, and this report is denominated per settlement
                    // currency — so it is reported separately rather than added
                    // to a commission figure it is not comparable with (C2).
                    fxMarginFor(start, end, vendorId, code),
                    "per order's own display currency",
                    orders.getOrDefault(code, 0L)));
        }

        return new AdminMoneyResponses.RevenueReport(start, end, lines,
                lines.isEmpty()
                        ? "Nothing was sold in that window."
                        : lines.size() + " settlement currency(ies). They are not added together, "
                          + "because a figure that summed dalasi and CFA would be a number nobody "
                          + "could reconcile against anything.");
    }

    /**
     * What conversion earned in a window, per settlement currency.
     *
     * <p>Computed from each order's OWN snapshotted rate and the spread that was
     * in force when it was placed. Reading today's spread would rewrite what
     * March appears to have earned every time somebody changes it.
     */
    private BigDecimal fxMarginFor(LocalDate start, LocalDate end, Long vendorId, String currency) {
        BigDecimal margin = BigDecimal.ZERO;
        for (VendorOrder slice : vendorOrders.findPlacedBetween(
                start.atStartOfDay(), end.plusDays(1).atStartOfDay(), vendorId)) {
            if (slice.getNativeCurrency() == null
                    || !slice.getNativeCurrency().equalsIgnoreCase(currency)) {
                continue;
            }
            FxSnapshot fx = slice.getFx();
            if (fx == null || fx.getDisplayCurrency() == null
                    || fx.getDisplayCurrency().equalsIgnoreCase(slice.getNativeCurrency())) {
                continue;   // nothing was converted, so nothing was earned on it
            }
            FxSpreadRegistry.Applied applied = spreadRegistry.resolve(
                    fx.getNativeCurrency(), fx.getDisplayCurrency(), fx.getRateAt());
            margin = margin.add(FxSpreadRegistry.marginOn(orZero(slice.getTotal()),
                    applied.basisPoints(), currencies.minorUnits(fx.getDisplayCurrency())));
        }
        return margin.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional
    public AdminMoneyResponses.ExportQueued requestExport(
            User staff, ReportType type, AdminMoneyRequests.RequestExport request) {

        LocalDateTime since = LocalDateTime.now().minus(EXPORT_WINDOW);
        long already = exports.countSince(staff.getId(), since);
        if (already >= EXPORT_LIMIT) {
            // Per person rather than per platform. This file is every vendor's
            // earnings; ten of them in an hour from one account is the shape of
            // an account somebody else is using.
            throw new BadRequestException(
                    "You have asked for " + already + " exports in the last hour, which is the "
                            + "limit. These files contain every seller's earnings, so the limit is "
                            + "per person rather than per platform. Try again shortly.");
        }
        if (request.fromDate() != null && request.toDate() != null
                && request.toDate().isBefore(request.fromDate())) {
            throw new BadRequestException("The window ends before it starts.");
        }

        ReportExport export = ReportExport.builder()
                .reference(reference("EXP"))
                .type(type)
                .status(ReportExportStatus.QUEUED)
                .requestedByUserId(staff.getId())
                .requestedByEmail(staff.getEmail())
                .fromDate(request.fromDate())
                .toDate(request.toDate())
                .currency(request.currency() == null ? null : request.currency().toUpperCase())
                .vendorId(request.vendorId())
                .format(request.format() == null ? "CSV" : request.format().toUpperCase())
                .resultExpiresAt(LocalDateTime.now().plus(EXPORT_TTL))
                .build();
        exports.save(export);

        audit.record(AuditAction.REPORT_EXPORTED, "REPORT_EXPORT", export.getId(),
                export.getReference(),
                staff.getEmail() + " asked for a " + type + " export"
                        + (request.vendorId() == null ? " across every seller"
                                                      : " for vendor " + request.vendorId())
                        + (request.fromDate() == null ? "" : " from " + request.fromDate())
                        + (request.toDate() == null ? "" : " to " + request.toDate()),
                null);

        return new AdminMoneyResponses.ExportQueued(export.getId(), export.getReference(), type,
                export.getStatus(), export.getFromDate(), export.getToDate(),
                (int) (EXPORT_LIMIT - already - 1),
                "Queued. The link will work for " + EXPORT_TTL.toHours() + " hours and then stop — "
                        + "a file of every seller's earnings with no expiry is one the platform has "
                        + "lost track of. This request is in the audit log with your name on it.");
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminMoneyResponses.ExportRow> listExports(
            Long requestedByUserId, ReportExportStatus status, Pageable pageable) {
        return PagedResponse.of(exports.search(requestedByUserId, status, pageable)
                .map(export -> new AdminMoneyResponses.ExportRow(
                        export.getId(), export.getReference(), export.getType(),
                        export.getStatus(), export.getRequestedByUserId(),
                        export.getRequestedByEmail(), export.getFromDate(), export.getToDate(),
                        export.getCurrency(), export.getVendorId(), export.getFormat(),
                        export.getRowCount(),
                        // Withheld once it has lapsed: the row survives so the
                        // question "who exported this" stays answerable, but the
                        // link is not offered again.
                        export.isDownloadable() ? export.getResultUrl() : null,
                        export.getResultExpiresAt(), export.getFailureReason(),
                        export.getCreatedAt(), export.getFinishedAt())));
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private Payment requirePayment(Long id) {
        return payments.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("No such payment."));
    }

    /**
     * Who an id belongs to, for a response that names the two people on a batch.
     *
     * <p>Looked up rather than stored on the batch: an administrator who changes
     * their email address should not leave a payout run naming an address that no
     * longer reaches them.
     */
    private String emailOf(Long userId) {
        if (userId == null) return null;
        return users.findById(userId).map(User::getEmail).orElse(null);
    }

    private static String buyerEmail(Order order) {
        if (order.getCustomer() != null) return order.getCustomer().getEmail();
        return order.getGuestEmail();
    }

    private static String reference(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
