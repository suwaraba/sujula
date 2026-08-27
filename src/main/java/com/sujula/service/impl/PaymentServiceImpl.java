package com.sujula.service.impl;

import com.sujula.dto.request.payment.ConfirmPaymentRequest;
import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.dto.request.payment.PaymentCallbackRequest;
import com.sujula.dto.request.payment.RefundPaymentRequest;
import com.sujula.dto.response.payment.PaymentMethodOption;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderStatusHistory;
import com.sujula.model.order.Payment;
import com.sujula.model.user.User;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.OrderStatusHistoryRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.EmailService;
import com.sujula.service.NotificationService;
import com.sujula.service.PaymentService;
import com.sujula.service.payment.PaymentGateway;
import com.sujula.service.payment.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Payment lifecycle for every method the platform accepts.
 *
 * <p>All of them share one record per order and one settlement path
 * ({@link #settle}), so "paid" means exactly the same thing whether a provider
 * confirmed it, an admin matched a bank transfer, or a driver took cash at the
 * door. What varies is only the leg before that: an online method needs a
 * gateway checkout, a transfer needs instructions and a reference to quote, and
 * an in-person method needs nothing until someone hands the goods over.
 *
 * <p>No provider code lives here. Card and PayPal go through whichever
 * {@link PaymentGateway} beans are registered; when none is, those methods are
 * reported unavailable at checkout rather than failing halfway through one.
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    /** An order in one of these states can never take a new payment. */
    private static final Set<OrderStatus> UNPAYABLE_ORDER_STATUSES =
            EnumSet.of(OrderStatus.CANCELLED, OrderStatus.REFUNDED);

    /** Payment states that still accept money. */
    private static final Set<PaymentStatus> OPEN_STATUSES =
            EnumSet.of(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED, PaymentStatus.FAILED);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final PaymentProperties properties;
    private final ObjectProvider<PaymentGateway> gateways;

    public PaymentServiceImpl(PaymentRepository paymentRepository,
                              OrderRepository orderRepository,
                              OrderStatusHistoryRepository statusHistoryRepository,
                              UserRepository userRepository,
                              EmailService emailService,
                              NotificationService notificationService,
                              PaymentProperties properties,
                              ObjectProvider<PaymentGateway> gateways) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.notificationService = notificationService;
        this.properties = properties;
        this.gateways = gateways;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Choosing a method
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethodOption> availableMethods(Long orderId, Long requestingUserId) {
        Order order = requireOrder(orderId);
        requireOwnership(order, requestingUserId);
        return methodsFor(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethodOption> availableMethodsForGuest(String orderNumber, String guestEmail) {
        return methodsFor(requireGuestOrder(orderNumber, guestEmail));
    }

    private List<PaymentMethodOption> methodsFor(Order order) {
        List<PaymentMethodOption> options = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            String reason = unavailableReason(order, method);
            options.add(PaymentMethodOption.builder()
                    .method(method)
                    .label(method.getDisplayName())
                    .channel(method.getChannel())
                    .available(reason == null)
                    .unavailableReason(reason)
                    .requiresGateway(method.requiresGateway())
                    .payLater(method.isPayLater())
                    .description(describe(method, order))
                    .build());
        }
        return options;
    }

    /** Why this method cannot be used for this order, or null when it can. */
    private String unavailableReason(Order order, PaymentMethod method) {
        if (UNPAYABLE_ORDER_STATUSES.contains(order.getStatus())) {
            return "This order is " + order.getStatus().name().toLowerCase() + " and can no longer be paid";
        }
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            return "This order has already been paid";
        }
        if (method.requiresGateway() && gatewayFor(method).isEmpty()) {
            return method.getDisplayName() + " payments are not available right now";
        }
        if (method == PaymentMethod.BANK_TRANSFER && !properties.getBankTransfer().isConfigured()) {
            return "Bank transfer is not available right now";
        }
        DeliveryMode required = method.requiredDeliveryMode();
        if (required != null && order.getDeliveryMode() != required) {
            return switch (method) {
                case PAY_ON_DELIVERY -> "Paying on delivery is only possible for orders delivered to an address";
                case PAY_AT_PICKUP   -> "Paying at a pickup point is only possible for pickup-point orders";
                case CASH_IN_STORE   -> "Paying in store is only possible when you collect from the store";
                default              -> "This method does not match how the order is being fulfilled";
            };
        }
        return null;
    }

    private String describe(PaymentMethod method, Order order) {
        return switch (method) {
            case CARD           -> "Pay now with a debit or credit card.";
            case PAYPAL         -> "Pay now with your PayPal account.";
            case BANK_TRANSFER  -> "Transfer the total to our bank account quoting your payment reference. "
                                   + "Your order is released once the transfer is confirmed.";
            case CASH_IN_STORE  -> "Pay the seller when you collect your order from the store.";
            case PAY_AT_PICKUP  -> "Pay the operator when you collect your order from the pickup point.";
            case PAY_ON_DELIVERY -> "Pay the driver in cash when your order arrives"
                                   + (order.getShippingCity() != null ? " in " + order.getShippingCity() : "") + ".";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Starting a payment
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse initiate(Long orderId, Long requestingUserId, InitiatePaymentRequest request) {
        Order order = requireOrder(orderId);
        requireOwnership(order, requestingUserId);
        return PaymentResponse.from(open(order, request));
    }

    @Override
    @Transactional
    public PaymentResponse initiateForGuest(String orderNumber, String guestEmail,
                                            InitiatePaymentRequest request) {
        return PaymentResponse.from(open(requireGuestOrder(orderNumber, guestEmail), request));
    }

    /**
     * Creates the order's payment, or moves an unpaid one onto another method.
     *
     * <p>Re-asking for the method already chosen returns the payment as it
     * stands: a buyer who reloads the checkout page must land back on the
     * checkout the gateway already opened, not on a second one.
     */
    private Payment open(Order order, InitiatePaymentRequest request) {
        PaymentMethod method = request.getMethod();
        if (method == null) {
            throw new BadRequestException("A payment method must be chosen");
        }
        String blocked = unavailableReason(order, method);
        if (blocked != null) {
            throw new BadRequestException(blocked);
        }

        Payment payment = paymentRepository.findByOrderIdForUpdate(order.getId()).orElse(null);

        if (payment == null) {
            payment = Payment.builder()
                    .order(order)
                    .reference(newReference())
                    .status(PaymentStatus.PENDING)
                    .method(method)
                    .amount(order.getTotal())
                    .amountRefunded(BigDecimal.ZERO)
                    .currency(order.getCurrency())
                    .note(request.getNote())
                    .build();
        } else {
            if (payment.getStatus() == PaymentStatus.PAID) {
                throw new BadRequestException("This order has already been paid");
            }
            if (payment.getStatus().isClosed() || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
                throw new BadRequestException(
                        "This payment is " + payment.getStatus().name().toLowerCase() + " and cannot be reopened");
            }

            boolean sameMethod = payment.getMethod() == method;
            if (sameMethod && payment.getStatus() == PaymentStatus.PENDING && legIsUsable(payment)) {
                // Idempotent re-entry: keep the open checkout, only re-pin the amount.
                repriceFromOrder(payment, order);
                return paymentRepository.save(payment);
            }

            // Switching method (or retrying after a failure) — drop the previous
            // provider leg so a stale checkout URL or client secret can never be
            // presented for the new one.
            payment.setMethod(method);
            payment.setStatus(PaymentStatus.PENDING);
            payment.setTransactionId(null);
            payment.setCheckoutUrl(null);
            payment.setClientSecret(null);
            payment.setGatewayResponse(null);
            payment.setFailureReason(null);
            payment.setInstructions(null);
            if (request.getNote() != null) {
                payment.setNote(request.getNote());
            }
        }

        repriceFromOrder(payment, order);
        payment = paymentRepository.save(payment);   // persist before the gateway sees it
        attachMethodLeg(payment, order, request.getReturnUrl());
        payment = paymentRepository.save(payment);

        order.setPaymentMethod(method);
        order.setPaymentStatus(payment.getStatus());
        orderRepository.save(order);

        log.info("[Payment] {} opened for order {} via {}",
                payment.getReference(), order.getOrderNumber(), method);
        return payment;
    }

    /**
     * Re-pins the amount due to the order total.
     *
     * <p>The order is the authority on both figure and currency — it is what the
     * buyer agreed to — so an unpaid payment always follows it rather than
     * holding a total that an admin edit may since have changed.
     */
    private void repriceFromOrder(Payment payment, Order order) {
        payment.setAmount(order.getTotal());
        payment.setCurrency(order.getCurrency());
    }

    /** True when an already-opened leg can still be handed back to the buyer as-is. */
    private boolean legIsUsable(Payment payment) {
        if (!payment.getMethod().requiresGateway()) {
            return payment.getInstructions() != null;
        }
        if (payment.getCheckoutUrl() == null && payment.getClientSecret() == null) {
            return false;
        }
        LocalDateTime openedAt = payment.getUpdatedAt() != null ? payment.getUpdatedAt() : payment.getCreatedAt();
        return openedAt == null
                || openedAt.plusMinutes(properties.getCheckoutTtlMinutes()).isAfter(LocalDateTime.now());
    }

    /** Gives the payment whatever its method needs: a gateway checkout, or instructions. */
    private void attachMethodLeg(Payment payment, Order order, String returnUrl) {
        PaymentMethod method = payment.getMethod();
        switch (method.getChannel()) {
            case ONLINE -> {
                PaymentGateway gateway = gatewayFor(method).orElseThrow(() -> new BadRequestException(
                        method.getDisplayName() + " payments are not available right now"));
                PaymentGateway.GatewayCheckout checkout = gateway.createCheckout(payment, returnUrl);
                payment.setTransactionId(checkout.transactionId());
                payment.setCheckoutUrl(checkout.checkoutUrl());
                payment.setClientSecret(checkout.clientSecret());
                payment.setGatewayResponse(checkout.rawResponse());
            }
            case OFFLINE_TRANSFER -> payment.setInstructions(bankTransferInstructions(payment));
            case IN_PERSON        -> payment.setInstructions(inPersonInstructions(payment, order));
        }
    }

    private String bankTransferInstructions(Payment payment) {
        PaymentProperties.BankTransfer bank = properties.getBankTransfer();
        StringBuilder text = new StringBuilder()
                .append("Transfer ").append(payment.getAmount()).append(' ').append(payment.getCurrency())
                .append(" to:\n")
                .append("Bank: ").append(bank.getBankName()).append('\n')
                .append("Account name: ").append(bank.getAccountName()).append('\n')
                .append("Account number: ").append(bank.getAccountNumber()).append('\n');
        if (!bank.getBranch().isBlank()) {
            text.append("Branch: ").append(bank.getBranch()).append('\n');
        }
        if (!bank.getSwift().isBlank()) {
            text.append("SWIFT/BIC: ").append(bank.getSwift()).append('\n');
        }
        // The reference is what lets finance match the transfer back to this order.
        return text.append("Reference: ").append(payment.getReference())
                .append("\n\nYour order is released once we confirm the transfer.")
                .toString();
    }

    private String inPersonInstructions(Payment payment, Order order) {
        String amount = payment.getAmount() + " " + payment.getCurrency();
        return switch (payment.getMethod()) {
            case CASH_IN_STORE -> "Pay " + amount + " to the seller when you collect your order. "
                    + "Quote reference " + payment.getReference() + ".";
            case PAY_AT_PICKUP -> "Pay " + amount + " to the pickup-point operator when you collect your order. "
                    + "Quote reference " + payment.getReference() + ".";
            case PAY_ON_DELIVERY -> "Have " + amount + " ready for the driver"
                    + (order.getShippingCity() != null ? " in " + order.getShippingCity() : "")
                    + ". Quote reference " + payment.getReference() + ".";
            default -> "Pay " + amount + " at handover. Quote reference " + payment.getReference() + ".";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confirming money
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse handleCallback(PaymentCallbackRequest callback) {
        if (callback.getTransactionId() == null || callback.getTransactionId().isBlank()) {
            throw new BadRequestException("transactionId must not be blank");
        }
        if (callback.getStatus() == null) {
            throw new BadRequestException("status must not be null");
        }

        Payment payment = paymentRepository.findByTransactionIdForUpdate(callback.getTransactionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment", "no payment for transaction " + callback.getTransactionId()));

        if (callback.getRawPayload() != null) {
            // Audit trail only — clientSecret and checkoutUrl live in their own
            // columns precisely so a callback can never destroy them.
            payment.setGatewayResponse(callback.getRawPayload());
        }

        if (payment.getStatus() == callback.getStatus()) {
            log.info("[Payment] Duplicate callback ignored: {} already {}",
                    payment.getReference(), callback.getStatus());
            return PaymentResponse.from(paymentRepository.save(payment));
        }
        if (payment.getStatus().isClosed() || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            log.warn("[Payment] Late callback ({}) for {} which is already {} — ignored",
                    callback.getStatus(), payment.getReference(), payment.getStatus());
            return PaymentResponse.from(paymentRepository.save(payment));
        }

        switch (callback.getStatus()) {
            case PAID -> {
                requireMatchingAmount(payment, callback.getAmount());
                settle(payment, null, callback.getTransactionId(), null);
            }
            case AUTHORIZED -> {
                payment.setStatus(PaymentStatus.AUTHORIZED);
                syncOrderFlag(payment);
            }
            case FAILED -> fail(payment, callback.getFailureReason());
            case CANCELLED -> abandon(payment, callback.getFailureReason());
            case REFUNDED, PARTIALLY_REFUNDED -> applyRefund(payment,
                    callback.getAmount() != null ? callback.getAmount() : payment.getRefundableAmount(),
                    callback.getFailureReason(), null);
            default -> throw new BadRequestException("Unsupported callback status: " + callback.getStatus());
        }

        Payment saved = paymentRepository.save(payment);
        log.info("[Payment] Callback applied: {} → {}", saved.getReference(), saved.getStatus());
        return PaymentResponse.from(saved);
    }

    @Override
    @Transactional
    public PaymentResponse confirmTransfer(Long orderId, ConfirmPaymentRequest request, Long adminUserId) {
        Payment payment = requireOpenPayment(orderId);
        if (payment.getMethod() != PaymentMethod.BANK_TRANSFER) {
            throw new BadRequestException(
                    "This order is being paid by " + payment.getMethod().getDisplayName()
                            + ", not by bank transfer");
        }
        requireMatchingAmount(payment, request != null ? request.getAmountReceived() : null);
        settle(payment, adminUserId,
                request != null ? request.getCollectionReference() : null,
                request != null ? request.getNote() : null);
        return PaymentResponse.from(paymentRepository.save(payment));
    }

    @Override
    @Transactional
    public PaymentResponse collectInPerson(Long orderId, ConfirmPaymentRequest request, Long collectorUserId) {
        Payment payment = requireOpenPayment(orderId);
        if (!payment.getMethod().isCollectedInPerson()) {
            throw new BadRequestException(
                    "This order is being paid by " + payment.getMethod().getDisplayName()
                            + ", so there is nothing to collect at handover");
        }
        requireMatchingAmount(payment, request != null ? request.getAmountReceived() : null);
        settle(payment, collectorUserId,
                request != null ? request.getCollectionReference() : null,
                request != null ? request.getNote() : null);
        return PaymentResponse.from(paymentRepository.save(payment));
    }

    @Override
    @Transactional
    public PaymentResponse markFailed(Long orderId, String reason) {
        Payment payment = requireOpenPayment(orderId);
        fail(payment, reason);
        return PaymentResponse.from(paymentRepository.save(payment));
    }

    @Override
    @Transactional
    public PaymentResponse cancel(Long orderId, String reason) {
        Payment payment = requirePayment(orderId);
        if (payment.getStatus() == PaymentStatus.PAID) {
            throw new BadRequestException("A paid payment cannot be cancelled — refund it instead");
        }
        if (payment.getStatus().isClosed()) {
            return PaymentResponse.from(payment);   // idempotent
        }
        abandon(payment, reason);
        return PaymentResponse.from(paymentRepository.save(payment));
    }

    @Override
    @Transactional
    public PaymentResponse refund(Long orderId, RefundPaymentRequest request, Long adminUserId) {
        Payment payment = requirePayment(orderId);
        if (payment.getStatus() != PaymentStatus.PAID && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BadRequestException(
                    "Only a settled payment can be refunded. This one is " + payment.getStatus());
        }

        BigDecimal refundable = payment.getRefundableAmount();
        BigDecimal amount = (request != null && request.getAmount() != null)
                ? request.getAmount().setScale(2, RoundingMode.HALF_UP)
                : refundable;
        if (amount.signum() <= 0) {
            throw new BadRequestException("Refund amount must be positive");
        }
        if (amount.compareTo(refundable) > 0) {
            throw new BadRequestException("Refund of " + amount + " " + payment.getCurrency()
                    + " exceeds the refundable balance of " + refundable + " " + payment.getCurrency());
        }

        // Online methods can return the money through the provider; everything
        // else is settled outside the platform, so we only record what happened.
        if (payment.getMethod().requiresGateway()) {
            Optional<PaymentGateway> gateway = gatewayFor(payment.getMethod());
            if (gateway.isPresent()) {
                try {
                    PaymentGateway.GatewayRefund result = gateway.get().refund(
                            payment, amount, request != null ? request.getReason() : null);
                    payment.setGatewayResponse(result.rawResponse());
                } catch (UnsupportedOperationException ex) {
                    log.warn("[Payment] {} cannot refund {} programmatically — recording the refund only: {}",
                            gateway.get().name(), payment.getReference(), ex.getMessage());
                }
            }
        }

        applyRefund(payment, amount, request != null ? request.getReason() : null, adminUserId);
        Payment saved = paymentRepository.save(payment);
        notifyBuyer(saved.getOrder(), "Refund Processed",
                "A refund of " + amount + " " + saved.getCurrency() + " for order "
                        + saved.getOrder().getOrderNumber() + " has been processed.");
        return PaymentResponse.from(saved);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reads
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse findForOrder(Long orderId, Long requestingUserId) {
        Order order = requireOrder(orderId);
        requireOwnership(order, requestingUserId);
        return PaymentResponse.from(requirePayment(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse findForGuest(String orderNumber, String guestEmail) {
        Order order = requireGuestOrder(orderNumber, guestEmail);
        return PaymentResponse.from(requirePayment(order.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOrderPaid(Long orderId) {
        return requireOrder(orderId).getPaymentStatus() == PaymentStatus.PAID;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentResponse> findAll(Pageable pageable) {
        return paymentRepository.findAll(pageable).map(PaymentResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentResponse> findByStatus(PaymentStatus status, Pageable pageable) {
        return paymentRepository.findByStatus(status, pageable).map(PaymentResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentResponse> findByVendor(Long vendorId, PaymentStatus status, Pageable pageable) {
        Page<Payment> page = (status == null)
                ? paymentRepository.findByVendorId(vendorId, pageable)
                : paymentRepository.findByVendorIdAndStatus(vendorId, status, pageable);
        return page.map(PaymentResponse::from);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transitions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The one place a payment becomes PAID, whatever brought the money in.
     *
     * @param collectorUserId whoever confirmed it; null for a gateway callback
     */
    private void settle(Payment payment, Long collectorUserId, String collectionReference, String note) {
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(LocalDateTime.now());
        payment.setFailureReason(null);
        if (collectionReference != null) {
            payment.setCollectionReference(collectionReference);
        }
        if (note != null) {
            payment.setNote(note);
        }
        if (collectorUserId != null) {
            payment.setConfirmedBy(userRepository.findById(collectorUserId).orElse(null));
        }

        Order order = payment.getOrder();
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(payment.getMethod());
        order.setPaidAt(payment.getPaidAt());
        orderRepository.save(order);

        advanceOrderOnPayment(order, payment);
        announceSettlement(order, payment);
    }

    private void fail(Payment payment, String reason) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        syncOrderFlag(payment);
        log.info("[Payment] {} failed: {}", payment.getReference(), reason);
    }

    private void abandon(Payment payment, String reason) {
        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setCancelledAt(LocalDateTime.now());
        payment.setFailureReason(reason);
        payment.setCheckoutUrl(null);
        payment.setClientSecret(null);
        syncOrderFlag(payment);
    }

    private void applyRefund(Payment payment, BigDecimal amount, String reason, Long adminUserId) {
        BigDecimal already = payment.getAmountRefunded() != null ? payment.getAmountRefunded() : BigDecimal.ZERO;
        BigDecimal refunded = already.add(amount).min(payment.getAmount());
        payment.setAmountRefunded(refunded);
        payment.setStatus(refunded.compareTo(payment.getAmount()) >= 0
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        payment.setRefundedAt(LocalDateTime.now());
        if (reason != null) {
            payment.setNote(reason);
        }
        if (adminUserId != null) {
            payment.setConfirmedBy(userRepository.findById(adminUserId).orElse(null));
        }
        syncOrderFlag(payment);
        log.info("[Payment] {} refunded {} {} → {}",
                payment.getReference(), amount, payment.getCurrency(), payment.getStatus());
    }

    /** Keeps the order's payment flag in step with the payment record. */
    private void syncOrderFlag(Payment payment) {
        Order order = payment.getOrder();
        if (order == null) {
            return;
        }
        order.setPaymentStatus(payment.getStatus());
        order.setPaymentMethod(payment.getMethod());
        if (payment.getStatus() != PaymentStatus.PAID) {
            order.setPaidAt(null);
        }
        orderRepository.save(order);
    }

    /**
     * Moves a still-pending order to CONFIRMED now that its money is in.
     *
     * <p>Only from PENDING: an order paid in person is already SHIPPED or
     * DELIVERED by the time the driver hands the cash in, and nothing about the
     * payment should drag its fulfilment status backwards.
     */
    private void advanceOrderOnPayment(Order order, Payment payment) {
        if (order.getStatus() != OrderStatus.PENDING) {
            return;
        }
        OrderStatus from = order.getStatus();
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        statusHistoryRepository.save(OrderStatusHistory.builder()
                .order(order)
                .fromStatus(from)
                .toStatus(OrderStatus.CONFIRMED)
                .notes("Payment received via " + payment.getMethod().getDisplayName()
                        + " (" + payment.getReference() + ")")
                .build());
    }

    /** Best-effort buyer confirmation — never let a mail or notification failure undo a payment. */
    private void announceSettlement(Order order, Payment payment) {
        try {
            String email = order.getContactEmail();
            if (email != null && !email.isBlank()) {
                emailService.sendOrderConfirmationEmail(email, order.getDisplayName(), order.getOrderNumber());
            }
        } catch (Exception ex) {
            log.warn("[Payment] Could not email the payment confirmation for {}: {}",
                    order.getOrderNumber(), ex.getMessage());
        }
        notifyBuyer(order, "Payment Received",
                "We have received your " + payment.getMethod().getDisplayName().toLowerCase()
                        + " payment for order " + order.getOrderNumber() + ".");
    }

    private void notifyBuyer(Order order, String title, String message) {
        if (order == null || order.getCustomer() == null) {
            return;   // guests have no in-app inbox
        }
        try {
            notificationService.send(order.getCustomer().getId(), title, message, "ORDER", order.getOrderNumber());
        } catch (Exception ex) {
            log.warn("[Payment] Could not notify the buyer of order {}: {}",
                    order.getOrderNumber(), ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Optional<PaymentGateway> gatewayFor(PaymentMethod method) {
        return gateways.stream().filter(g -> g.supports(method)).findFirst();
    }

    /**
     * Rejects a confirmation that does not cover the amount due.
     *
     * <p>A short payment is a reconciliation problem, not something to record as
     * settled: whoever counted the money has to resolve it before the order is
     * released. An overpayment is accepted, since refusing it would leave the
     * buyer out of pocket with nothing to show for it.
     */
    private void requireMatchingAmount(Payment payment, BigDecimal received) {
        if (received == null) {
            return;   // "the full amount due"
        }
        int comparison = received.compareTo(payment.getAmount());
        if (comparison < 0) {
            throw new BadRequestException("Received " + received + " " + payment.getCurrency()
                    + " but " + payment.getAmount() + " " + payment.getCurrency() + " is due");
        }
        if (comparison > 0) {
            log.warn("[Payment] {} overpaid: received {} against {} due",
                    payment.getReference(), received, payment.getAmount());
        }
    }

    private Payment requirePayment(Long orderId) {
        return paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment", "no payment has been started for order " + orderId));
    }

    private Payment requireOpenPayment(Long orderId) {
        Payment payment = requirePayment(orderId);
        if (payment.getStatus() == PaymentStatus.PAID) {
            throw new BadRequestException("This order has already been paid");
        }
        if (!OPEN_STATUSES.contains(payment.getStatus())) {
            throw new BadRequestException(
                    "This payment is " + payment.getStatus().name().toLowerCase() + " and takes no further money");
        }
        return payment;
    }

    private Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    private Order requireGuestOrder(String orderNumber, String guestEmail) {
        if (orderNumber == null || guestEmail == null || guestEmail.isBlank()) {
            throw new BadRequestException("Both the order number and the email used at checkout are required");
        }
        return orderRepository.findByOrderNumberAndGuestEmailIgnoreCase(orderNumber, guestEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "no matching guest order"));
    }

    /** {@code requestingUserId} null means an admin or system caller — no ownership to check. */
    private void requireOwnership(Order order, Long requestingUserId) {
        if (requestingUserId == null) {
            return;
        }
        User customer = order.getCustomer();
        if (customer == null || !customer.getId().equals(requestingUserId)) {
            throw new BadRequestException("Order does not belong to this user");
        }
    }

    /** Short, unambiguous reference a buyer can quote on a transfer or at a counter. */
    private String newReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "PAY-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 10).toUpperCase();
            if (!paymentRepository.existsByReference(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique payment reference");
    }
}
