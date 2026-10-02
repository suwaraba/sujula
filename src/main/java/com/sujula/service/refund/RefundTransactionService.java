package com.sujula.service.refund;

import static com.sujula.model.constant.RefundRequestStatus.APPROVED;
import static com.sujula.model.constant.RefundRequestStatus.COMPLETED;
import static com.sujula.model.constant.RefundRequestStatus.REQUESTED;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.model.order.RefundRequest;
import com.sujula.model.order.VendorOrder;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.RefundRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.service.AuditService;
import com.sujula.service.NotificationService;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.refund.RefundCoordinator.PreparedRefund;
import com.sujula.service.refund.RefundCoordinator.RefundCommand;
import com.sujula.service.refund.RefundCoordinator.RefundResult;
import com.sujula.service.security.StepUpVerifier;

/** The two database halves of a refund. Provider I/O never runs in either transaction. */
@Service
public class RefundTransactionService {

    private static final BigDecimal REFUND_STEP_UP_ABOVE = new BigDecimal("5000.00");
    private static final List<RefundRequestStatus> RESERVED = List.of(APPROVED, COMPLETED);
    private static final List<RefundRequestStatus> OPEN = List.of(REQUESTED, APPROVED);

    private final OrderRepository orders;
    private final VendorOrderRepository vendorOrders;
    private final PaymentRepository payments;
    private final RefundRequestRepository refunds;
    private final VendorLedgerEntryRepository ledger;
    private final MoneyLedger money;
    private final CurrencyCatalogue currencies;
    private final StepUpVerifier stepUp;
    private final AuditService audit;
    private final NotificationService notifications;

    public RefundTransactionService(OrderRepository orders,
                                    VendorOrderRepository vendorOrders,
                                    PaymentRepository payments,
                                    RefundRequestRepository refunds,
                                    VendorLedgerEntryRepository ledger,
                                    MoneyLedger money,
                                    CurrencyCatalogue currencies,
                                    StepUpVerifier stepUp,
                                    AuditService audit,
                                    NotificationService notifications) {
        this.orders = orders;
        this.vendorOrders = vendorOrders;
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.money = money;
        this.currencies = currencies;
        this.stepUp = stepUp;
        this.audit = audit;
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedRefund prepare(RefundCommand command) {
        Order order = orders.findByPaymentIdForUpdate(command.paymentId()).orElseThrow(
                () -> new ResourceNotFoundException("No such payment."));
        VendorOrder slice = vendorOrders.findByIdAndOrderIdForUpdate(
                        command.vendorOrderId(), order.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No such sub-order on this payment."));
        Payment payment = payments.findByOrderIdForUpdate(order.getId()).orElseThrow(
                () -> new ResourceNotFoundException("No such payment."));

        validateRefundablePayment(payment);
        RefundRequest open = refunds
                .findFirstByVendorOrderIdAndStatusInOrderByCreatedAtAsc(slice.getId(), OPEN)
                .orElse(null);
        if (open != null && open.getStatus() == APPROVED) {
            verifyRetryMatches(open, command, payment, slice);
            return prepared(open, payment, null);
        }

        Amounts amounts = open == null
                ? resolveNewAmounts(command, payment, slice)
                : amountsFromRequested(open, command, payment, slice);
        BigDecimal reservedPayment = reservedPaymentAmount(order.getId(), payment);
        validateCaps(order, payment, slice, amounts, reservedPayment,
                sumSliceDisplay(slice.getId(), RESERVED), sumNative(slice.getId(), RESERVED));

        boolean stepUpRequired = amounts.display().compareTo(REFUND_STEP_UP_ABOVE) > 0;
        if (stepUpRequired) {
            stepUp.verify(command.staff(), command.password(), command.totpCode(),
                    "refunding " + amounts.display() + " " + payment.getCurrency());
        }

        LocalDateTime now = LocalDateTime.now();
        RefundRequest operation = open != null ? open : RefundRequest.builder()
                .reference(reference())
                .order(order)
                .vendorOrder(slice)
                .requestedBy(command.staff())
                .createdAt(now)
                .build();
        operation.setStatus(APPROVED);
        operation.setAmount(amounts.display());
        operation.setCurrency(normalise(payment.getCurrency()));
        operation.setAmountNative(amounts.nativeAmount());
        operation.setFx(copyFx(slice.getFx()));
        operation.setReason(command.reason());
        operation.setDecidedBy(command.staff());
        operation.setDecidedAt(now);
        operation.setDecisionNote("Approved for provider refund by " + command.staff().getEmail());
        operation.setPaymentId(payment.getId());
        operation = refunds.saveAndFlush(operation);

        return prepared(operation, payment, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedRefund prepareExisting(long refundRequestId) {
        Long orderId = refunds.findOrderIdById(refundRequestId).orElseThrow(
                () -> new ResourceNotFoundException("No such refund operation."));
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(
                () -> new ResourceNotFoundException("No such order."));
        RefundRequest initial = refunds.findById(refundRequestId).orElseThrow(
                () -> new ResourceNotFoundException("No such refund operation."));
        VendorOrder slice = vendorOrders.findByIdAndOrderIdForUpdate(
                        initial.getVendorOrder().getId(), order.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No such sub-order."));
        Payment payment = payments.findByOrderIdForUpdate(order.getId()).orElseThrow(
                () -> new ResourceNotFoundException("No such payment."));
        RefundRequest operation = refunds.findByIdForUpdate(refundRequestId).orElseThrow(
                () -> new ResourceNotFoundException("No such refund operation."));
        if (!operation.getVendorOrder().getId().equals(slice.getId())) {
            throw new IllegalStateException("Refund operation changed sub-order while being locked");
        }
        if (operation.getStatus() != APPROVED && operation.getStatus() != COMPLETED) {
            throw new BadRequestException("Refund operation " + operation.getReference()
                    + " is " + operation.getStatus() + ", not approved for execution.");
        }
        return prepared(operation, payment,
                operation.getStatus() == COMPLETED ? resultOf(operation, slice, payment) : null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundResult finalizeRefund(long refundRequestId, String providerResponse) {
        Long orderId = refunds.findOrderIdById(refundRequestId).orElseThrow(
                () -> new ResourceNotFoundException("No such refund operation."));
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(
                () -> new ResourceNotFoundException("No such order."));
        RefundRequest initial = refunds.findById(refundRequestId).orElseThrow(
                () -> new ResourceNotFoundException("No such refund operation."));
        VendorOrder slice = vendorOrders.findByIdAndOrderIdForUpdate(
                        initial.getVendorOrder().getId(), order.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No such sub-order."));
        Payment payment = payments.findByOrderIdForUpdate(order.getId()).orElseThrow(
                () -> new ResourceNotFoundException("No such payment."));
        RefundRequest operation = refunds.findByIdForUpdate(refundRequestId).orElseThrow(
                () -> new ResourceNotFoundException("No such refund operation."));

        if (operation.getStatus() == COMPLETED) {
            return resultOf(operation, slice, payment);
        }
        if (operation.getStatus() != APPROVED) {
            throw new BadRequestException("Refund operation " + operation.getReference()
                    + " is " + operation.getStatus() + ", not approved for execution.");
        }

        Amounts amounts = new Amounts(
                currencies.round(operation.getAmount(), payment.getCurrency()),
                currencies.round(operation.getAmountNative(), slice.getNativeCurrency()));
        boolean refundAlreadyPosted = ledger.existsByVendorOrderIdAndTypeAndReference(
                slice.getId(), LedgerEntryType.REFUND, operation.getReference());

        if (!refundAlreadyPosted) {
            BigDecimal completedDisplay = sumDisplay(order.getId(), List.of(COMPLETED));
            BigDecimal completedSliceDisplay = sumSliceDisplay(slice.getId(), List.of(COMPLETED));
            BigDecimal completedNative = sumNative(slice.getId(), List.of(COMPLETED));
            validateCaps(order, payment, slice, amounts, completedDisplay,
                    completedSliceDisplay, completedNative);

            BigDecimal commissionBack = commissionReversal(
                    slice, amounts.nativeAmount(), completedNative);
            money.postRefund(slice, amounts.nativeAmount(), commissionBack,
                    operation.getReference(), operation.getReason(), LocalDateTime.now());

            BigDecimal currentRefunded = currencies.round(orZero(payment.getAmountRefunded()),
                    payment.getCurrency());
            BigDecimal nextRefunded = currencies.round(currentRefunded.add(amounts.display()),
                    payment.getCurrency());
            BigDecimal paymentTotal = currencies.round(payment.getAmount(), payment.getCurrency());
            if (nextRefunded.compareTo(paymentTotal) > 0) {
                throw new BadRequestException("This refund would return more than the payment received.");
            }
            payment.setAmountRefunded(nextRefunded);
        }

        BigDecimal paymentTotal = currencies.round(payment.getAmount(), payment.getCurrency());
        BigDecimal cumulative = currencies.round(orZero(payment.getAmountRefunded()), payment.getCurrency());
        PaymentStatus status = cumulative.compareTo(paymentTotal) == 0
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        payment.setStatus(status);
        order.setPaymentStatus(status);
        if (payment.getRefundedAt() == null) {
            payment.setRefundedAt(LocalDateTime.now());
        }
        if (providerResponse != null) {
            payment.setGatewayResponse(providerResponse);
        }

        operation.setStatus(COMPLETED);
        operation.setCompletedAt(LocalDateTime.now());
        operation.setPaymentId(payment.getId());
        payments.save(payment);
        orders.save(order);
        refunds.save(operation);

        boolean stepUpRequired = operation.getAmount().compareTo(REFUND_STEP_UP_ABOVE) > 0;
        String actor = operation.getDecidedBy() == null
                ? "system" : operation.getDecidedBy().getEmail();
        audit.record(AuditAction.PAYMENT_REFUNDED, "PAYMENT", payment.getId(),
                payment.getReference(),
                actor + " refunded " + operation.getAmount() + " " + operation.getCurrency()
                        + " (" + operation.getAmountNative() + " " + slice.getNativeCurrency()
                        + ") on sub-order " + slice.getId()
                        + (stepUpRequired ? ", with step-up" : ""),
                operation.getReason());
        notifyRefund(slice, operation);

        return resultOf(operation, slice, payment);
    }

    private void validateRefundablePayment(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PAID
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BadRequestException("That payment is " + payment.getStatus()
                    + ". There is nothing to give back until money has actually been taken.");
        }
    }

    private Amounts resolveNewAmounts(RefundCommand command, Payment payment, VendorOrder slice) {
        String displayCurrency = normalise(payment.getCurrency());
        String nativeCurrency = requireNativeSlice(slice);
        BigDecimal reservedPayment = reservedPaymentAmount(payment.getOrder().getId(), payment);
        BigDecimal reservedDisplay = sumSliceDisplay(slice.getId(), RESERVED);
        BigDecimal reservedNative = sumNative(slice.getId(), RESERVED);

        BigDecimal paymentRemaining = currencies.round(payment.getAmount(), displayCurrency)
                .subtract(reservedPayment);
        BigDecimal displayRemaining = currencies.round(slice.getTotal(), displayCurrency)
                .subtract(reservedDisplay);
        BigDecimal nativeRemaining = currencies.round(slice.getTotalNative(), nativeCurrency)
                .subtract(reservedNative);

        Amounts amounts;
        if (command.fullRefund()) {
            if (displayRemaining.compareTo(paymentRemaining) > 0) {
                throw new BadRequestException("The remaining sub-order refund exceeds the payment balance.");
            }
            amounts = new Amounts(displayRemaining, nativeRemaining);
        } else {
            if (command.displayAmount() == null || command.nativeAmount() == null) {
                throw new BadRequestException("Send both refund amounts or neither for the remaining sub-order.");
            }
            amounts = new Amounts(
                    currencies.round(command.displayAmount(), displayCurrency),
                    currencies.round(command.nativeAmount(), nativeCurrency));
        }
        validatePositive(amounts);
        validateCurrencyPair(amounts, displayCurrency, nativeCurrency, slice.getFx(),
                command.fullRefund());
        validateCaps(payment.getOrder(), payment, slice, amounts, reservedPayment,
                reservedDisplay, reservedNative);
        return amounts;
    }

    private Amounts amountsFromRequested(RefundRequest requested, RefundCommand command,
                                         Payment payment, VendorOrder slice) {
        String nativeCurrency = requireNativeSlice(slice);
        Amounts frozen = new Amounts(
                currencies.round(requested.getAmount(), payment.getCurrency()),
                currencies.round(requested.getAmountNative(), nativeCurrency));
        validatePositive(frozen);
        validateStoredCurrencies(requested, payment, slice);
        validateCurrencyPair(frozen, payment.getCurrency(), nativeCurrency, slice.getFx(),
                command.fullRefund());
        if (!command.fullRefund()) {
            Amounts supplied = new Amounts(
                    currencies.round(command.displayAmount(), payment.getCurrency()),
                    currencies.round(command.nativeAmount(), nativeCurrency));
            if (frozen.display().compareTo(supplied.display()) != 0
                    || frozen.nativeAmount().compareTo(supplied.nativeAmount()) != 0) {
                throw new BadRequestException("An existing refund request already froze different amounts.");
            }
        }
        return frozen;
    }

    private void verifyRetryMatches(RefundRequest operation, RefundCommand command,
                                    Payment payment, VendorOrder slice) {
        validateStoredCurrencies(operation, payment, slice);
        if (!command.fullRefund()) {
            BigDecimal display = currencies.round(command.displayAmount(), payment.getCurrency());
            BigDecimal nativeAmount = currencies.round(command.nativeAmount(), slice.getNativeCurrency());
            if (operation.getAmount().compareTo(display) != 0
                    || operation.getAmountNative().compareTo(nativeAmount) != 0) {
                throw new BadRequestException("This sub-order already has a different approved refund in progress.");
            }
        }
    }

    private void validateCaps(Order order, Payment payment, VendorOrder slice, Amounts amounts,
                              BigDecimal alreadyPayment, BigDecimal alreadyDisplay,
                              BigDecimal alreadyNative) {
        String displayCurrency = payment.getCurrency();
        String nativeCurrency = requireNativeSlice(slice);
        BigDecimal paymentTotal = currencies.round(payment.getAmount(), displayCurrency);
        BigDecimal sliceDisplay = currencies.round(slice.getTotal(), displayCurrency);
        BigDecimal sliceNative = currencies.round(slice.getTotalNative(), nativeCurrency);
        if (currencies.round(alreadyPayment.add(amounts.display()), displayCurrency)
                .compareTo(paymentTotal) > 0) {
            throw new BadRequestException("This refund would return more than the payment received.");
        }
        if (currencies.round(alreadyDisplay.add(amounts.display()), displayCurrency)
                .compareTo(sliceDisplay) > 0) {
            throw new BadRequestException("This refund exceeds the selected sub-order's display amount.");
        }
        if (currencies.round(alreadyNative.add(amounts.nativeAmount()), nativeCurrency)
                .compareTo(sliceNative) > 0) {
            throw new BadRequestException("This refund exceeds the selected sub-order's native amount.");
        }
    }

    private void validateCurrencyPair(Amounts amounts, String displayCurrency, String nativeCurrency,
                                      FxSnapshot fx, boolean finalRemainder) {
        if (displayCurrency.equalsIgnoreCase(nativeCurrency)) {
            if (amounts.display().compareTo(amounts.nativeAmount()) != 0) {
                throw new BadRequestException("Display and native refund amounts must match in the same currency.");
            }
            return;
        }
        if (fx == null || fx.getRate() == null || fx.getRate().signum() <= 0
                || !nativeCurrency.equalsIgnoreCase(fx.getNativeCurrency())
                || !displayCurrency.equalsIgnoreCase(fx.getDisplayCurrency())) {
            throw new BadRequestException("This sub-order has no valid frozen FX snapshot for a refund.");
        }
        BigDecimal expected = currencies.round(
                amounts.nativeAmount().multiply(fx.getRate()), displayCurrency);
        BigDecimal difference = expected.subtract(amounts.display()).abs();
        BigDecimal tolerance = finalRemainder
                ? currencies.smallestUnit(displayCurrency) : BigDecimal.ZERO;
        if (difference.compareTo(tolerance) > 0) {
            throw new BadRequestException("Those refund amounts do not agree at the order's frozen FX rate."
                    + " Expected " + expected + " " + displayCurrency + ".");
        }
    }

    private BigDecimal commissionReversal(VendorOrder slice, BigDecimal currentNative,
                                          BigDecimal completedNative) {
        String currency = slice.getNativeCurrency();
        BigDecimal total = currencies.round(slice.getTotalNative(), currency);
        BigDecimal frozenCommission = currencies.round(orZero(slice.getCommissionNative()).abs(), currency);
        if (total.signum() == 0 || frozenCommission.signum() == 0) {
            return currencies.round(BigDecimal.ZERO, currency);
        }
        BigDecimal committed = ledger.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId()).stream()
                .filter(entry -> entry.getType() == LedgerEntryType.COMMISSION_REVERSAL)
                .map(VendorLedgerEntry::getAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean finalRefund = currencies.round(completedNative.add(currentNative), currency)
                .compareTo(total) == 0;
        if (finalRefund) {
            return currencies.round(frozenCommission.subtract(committed).max(BigDecimal.ZERO), currency);
        }
        return currencies.round(frozenCommission.multiply(currentNative)
                .divide(total, 12, RoundingMode.HALF_UP), currency);
    }

    private PreparedRefund prepared(RefundRequest operation, Payment payment,
                                    RefundResult completedResult) {
        VendorOrder slice = operation.getVendorOrder();
        return new PreparedRefund(operation.getId(), payment,
                currencies.round(operation.getAmount(), operation.getCurrency()),
                normalise(operation.getCurrency()),
                currencies.round(operation.getAmountNative(), slice.getNativeCurrency()),
                normalise(slice.getNativeCurrency()), operation.getReason(),
                "refund-request-" + operation.getId(), completedResult);
    }

    private RefundResult resultOf(RefundRequest operation, VendorOrder slice, Payment payment) {
        FxSnapshot fx = operation.getFx();
        BigDecimal completedNative = sumNative(slice.getId(), List.of(COMPLETED));
        boolean full = currencies.round(completedNative, slice.getNativeCurrency())
                .compareTo(currencies.round(slice.getTotalNative(), slice.getNativeCurrency())) == 0;
        return new RefundResult(operation.getId(), operation.getReference(), slice.getId(),
                slice.getVendor() == null ? null : slice.getVendor().getStoreName(),
                currencies.round(operation.getAmount(), payment.getCurrency()), payment.getCurrency(),
                currencies.round(operation.getAmountNative(), slice.getNativeCurrency()),
                slice.getNativeCurrency(), fx == null ? null : fx.getRate(),
                fx == null ? null : fx.getRateAt(),
                operation.getAmount().compareTo(REFUND_STEP_UP_ABOVE) > 0, full);
    }

    private void validateStoredCurrencies(RefundRequest operation, Payment payment,
                                          VendorOrder slice) {
        if (!normalise(payment.getCurrency()).equals(normalise(operation.getCurrency()))) {
            throw new BadRequestException("The refund currency does not match the payment currency.");
        }
        FxSnapshot stored = operation.getFx();
        FxSnapshot frozen = slice.getFx();
        if (stored != null && frozen != null
                && (stored.getRate() == null || frozen.getRate() == null
                    || stored.getRate().compareTo(frozen.getRate()) != 0)) {
            throw new BadRequestException("The refund FX snapshot does not match the sub-order snapshot.");
        }
    }

    private String requireNativeSlice(VendorOrder slice) {
        if (slice.getNativeCurrency() == null || slice.getTotalNative() == null) {
            throw new BadRequestException("This sub-order has no single frozen native amount to refund.");
        }
        return normalise(slice.getNativeCurrency());
    }

    private void validatePositive(Amounts amounts) {
        if (amounts.display() == null || amounts.nativeAmount() == null
                || amounts.display().signum() <= 0 || amounts.nativeAmount().signum() <= 0) {
            throw new BadRequestException("Refund amounts must be positive.");
        }
    }

    private BigDecimal sumDisplay(Long orderId, List<RefundRequestStatus> statuses) {
        return orZero(refunds.sumDisplayByOrderAndStatusIn(orderId, statuses));
    }

    private BigDecimal sumSliceDisplay(Long sliceId, List<RefundRequestStatus> statuses) {
        return orZero(refunds.sumDisplayByVendorOrderAndStatusIn(sliceId, statuses));
    }

    private BigDecimal sumNative(Long sliceId, List<RefundRequestStatus> statuses) {
        return orZero(refunds.sumNativeByVendorOrderAndStatusIn(sliceId, statuses));
    }

    private BigDecimal reservedPaymentAmount(Long orderId, Payment payment) {
        BigDecimal fromOperations = sumDisplay(orderId, RESERVED);
        BigDecimal recorded = currencies.round(orZero(payment.getAmountRefunded()), payment.getCurrency());
        BigDecimal pending = sumDisplay(orderId, List.of(APPROVED));
        BigDecimal fromPaymentAndPending = currencies.round(recorded.add(pending), payment.getCurrency());
        return fromOperations.max(fromPaymentAndPending);
    }

    private void notifyRefund(VendorOrder slice, RefundRequest operation) {
        if (slice.getVendor() != null && slice.getVendor().getUser() != null) {
            notifications.send(slice.getVendor().getUser().getId(),
                    "A refund was issued on one of your orders",
                    operation.getAmountNative() + " " + slice.getNativeCurrency()
                            + " has come off your balance. The commission on it has been returned separately.",
                    NotificationEvent.PAYOUT_SENT, String.valueOf(slice.getId()));
        }
        if (slice.getOrder() != null && slice.getOrder().getCustomer() != null) {
            notifications.send(slice.getOrder().getCustomer().getId(),
                    "Refund processed",
                    operation.getAmount() + " " + operation.getCurrency() + " is being returned.",
                    NotificationEvent.ORDER_UPDATE, String.valueOf(slice.getOrder().getId()));
        }
    }

    private static FxSnapshot copyFx(FxSnapshot fx) {
        return fx == null ? null : new FxSnapshot(fx.getNativeCurrency(), fx.getDisplayCurrency(),
                fx.getRate(), fx.getRateAt(), fx.getSource(), fx.getQuoteId());
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String normalise(String currency) {
        return currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    }

    private static String reference() {
        return "RFD-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private record Amounts(BigDecimal display, BigDecimal nativeAmount) {}
}
