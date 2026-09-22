package com.sujula.service;

import com.sujula.dto.request.payment.ConfirmPaymentRequest;
import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.dto.request.payment.PaymentCallbackRequest;
import com.sujula.dto.request.payment.RefundPaymentRequest;
import com.sujula.dto.response.payment.PaymentMethodOption;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.model.constant.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Everything that happens to the money for an order.
 *
 * <p>An order is placed unpaid and carries a payment flag from that moment on
 * ({@code Order.paymentStatus}); this service is the only thing that moves that
 * flag. Every method the platform accepts settles through the same record — the
 * difference is who is allowed to confirm it:
 *
 * <ul>
 *   <li><b>Card / PayPal</b> — a {@code PaymentGateway} adapter opens a checkout
 *       and the provider's callback confirms it. With no adapter registered for
 *       a method, that method is reported unavailable at checkout instead of
 *       failing mid-flow.</li>
 *   <li><b>Bank transfer</b> — the buyer gets the platform's bank details and a
 *       payment reference to quote; an admin confirms it once the transfer is
 *       matched.</li>
 *   <li><b>In person</b> — cash in store, at a pickup point, or on delivery. The
 *       order is fulfilled first and the person handing the goods over records
 *       the collection.</li>
 * </ul>
 *
 * <p>Confirming a payment advances a still-pending order to {@code CONFIRMED}
 * and notifies the buyer; it never overrides a fulfilment status an admin,
 * vendor or driver has already moved on, which is what makes the in-person
 * methods (confirmed after delivery) safe to run through the same path.
 *
 * <p>Customer-facing methods take the requesting user's id and refuse orders
 * that do not belong to them; pass {@code null} only from an admin or system
 * context. Guest orders are addressed by order number plus the email used at
 * checkout — both, so order numbers cannot be enumerated.
 */
public interface PaymentService {

    // ── Choosing a method ────────────────────────────────────────────────────

    /**
     * Payment methods for this order, each flagged available or not with the
     * reason — a pickup-point order cannot be paid on delivery, and card is
     * unavailable when no gateway adapter is registered.
     */
    List<PaymentMethodOption> availableMethods(Long orderId, Long requestingUserId);

    List<PaymentMethodOption> availableMethodsForGuest(String orderNumber, String guestEmail);

    // ── Starting a payment ───────────────────────────────────────────────────

    /**
     * Creates the order's payment, or switches an unpaid one to another method.
     *
     * <p>Idempotent for the method already chosen: asking again for a card
     * payment returns the open checkout rather than opening a second one.
     *
     * @throws com.sujula.exceptions.BadRequestException if the order is already
     *         paid, cancelled or refunded, or the method cannot be used for it
     */
    PaymentResponse initiate(Long orderId, Long requestingUserId, InitiatePaymentRequest request);

    PaymentResponse initiateForGuest(String orderNumber, String guestEmail, InitiatePaymentRequest request);

    // ── Confirming money ─────────────────────────────────────────────────────

    /**
     * Applies a provider callback to the payment it names.
     *
     * <p>The caller must have verified the provider's signature first. Replays
     * are ignored rather than rejected, since providers retry callbacks they
     * think were not acknowledged.
     */
    PaymentResponse handleCallback(PaymentCallbackRequest callback);

    /**
     * Records a bank transfer that finance has matched to this order.
     *
     * @param adminUserId the admin confirming it, kept as the audit trail
     */
    PaymentResponse confirmTransfer(Long orderId, ConfirmPaymentRequest request, Long adminUserId);

    /**
     * Records money taken in person at handover — by the driver on delivery, the
     * pickup-point operator on collection, or the vendor in store.
     *
     * @param collectorUserId whoever took the money, kept as the audit trail
     * @throws com.sujula.exceptions.BadRequestException if the order's method is
     *         not an in-person one
     */
    PaymentResponse collectInPerson(Long orderId, ConfirmPaymentRequest request, Long collectorUserId);

    /** Marks the current attempt failed so the buyer can retry or switch method. */
    PaymentResponse markFailed(Long orderId, String reason);

    /** Abandons an unpaid payment. Paid payments must be refunded instead. */
    PaymentResponse cancel(Long orderId, String reason);

    /**
     * Returns money to the buyer, in full or in part.
     *
     * <p>Leaves the fulfilment status alone — refunding is a money decision, and
     * whether the order is then cancelled or kept is the admin's to make through
     * {@code OrderService}.
     */
    PaymentResponse refund(Long orderId, RefundPaymentRequest request, Long adminUserId);

    // ── Reads ────────────────────────────────────────────────────────────────

    PaymentResponse findForOrder(Long orderId, Long requestingUserId);

    PaymentResponse findForGuest(String orderNumber, String guestEmail);

    /** True when the order's money has been received in full. */
    boolean isOrderPaid(Long orderId);

    Page<PaymentResponse> findAll(Pageable pageable);

    Page<PaymentResponse> findByStatus(PaymentStatus status, Pageable pageable);

    /** Payments covering at least one item from this vendor; {@code status} may be null. */
    Page<PaymentResponse> findByVendor(Long vendorId, PaymentStatus status, Pageable pageable);
}
