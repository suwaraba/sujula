package com.sujula.service.checkout.impl;

import com.sujula.dto.request.checkout.CheckoutRequests;
import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.dto.response.checkout.CheckoutResponses;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.delivery.DeliveryContext;
import com.sujula.model.order.CartQuote;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.repository.order.CartQuoteRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.service.CartService;
import com.sujula.service.OrderService;
import com.sujula.service.PaymentService;
import com.sujula.service.cart.CartStructureFingerprint;
import com.sujula.service.checkout.CheckoutService;
import com.sujula.service.delivery.DeliveryContextService;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.payment.PaymentOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Checkout: validate, reserve, create, intend.
 *
 * <p><strong>What the quote guarantees, and what it does not.</strong> The quote
 * is the figure the buyer agreed to. Order creation re-prices the cart through
 * the same path every other order uses — there is one order assembly in this
 * application and a second one here would eventually disagree with it — and the
 * result is then reconciled against the quote before anything is charged.
 *
 * <p>If the two differ, the order is rejected and the transaction rolls back.
 * That means a buyer is never charged a figure they did not agree to, which is
 * the property that matters. It does <em>not</em> yet mean they are charged the
 * quoted figure when the market moves underneath them: today they are asked to
 * re-quote. Honouring the frozen rate through assembly is the next step, and the
 * frozen lines are already on the quote for it — but a reconciliation that
 * refuses is safe, whereas a freeze that is half-applied is not.
 */
@Slf4j
@Service
public class CheckoutServiceImpl implements CheckoutService {

    private final CartQuoteRepository quotes;
    private final OrderRepository orders;
    private final CartService carts;
    private final OrderService orderService;
    private final PaymentService payments;
    private final DeliveryContextService deliveryContexts;
    private final CurrencyCatalogue currencies;

    public CheckoutServiceImpl(CartQuoteRepository quotes, OrderRepository orders, CartService carts,
                               OrderService orderService, PaymentService payments,
                               DeliveryContextService deliveryContexts, CurrencyCatalogue currencies) {
        this.quotes = quotes;
        this.orders = orders;
        this.carts = carts;
        this.orderService = orderService;
        this.payments = payments;
        this.deliveryContexts = deliveryContexts;
        this.currencies = currencies;
    }

    @Override
    @Transactional
    public CheckoutResponses.Placed checkout(Long userId, CheckoutRequests.Checkout request,
                                             PaymentOperation paymentOperation) {
        CartQuote quote = requireUsableQuote(request.quoteId(), userId);
        CartResponse cart = carts.getCartForCheckout(userId, sourceCartId(quote));
        requireSameCartStructure(quote, cart);
        requireBoundDelivery(quote, cart, userId);

        if (request.addressId() == null) {
            throw new BadRequestException(
                    "An address is needed: the delivery context says where the parcel goes, and "
                            + "this says who to hand it to and on what street.");
        }

        // Reserve and create. The existing path locks each product row before
        // decrementing, so two shoppers racing for the last unit cannot both
        // succeed.
        Order order = orderService.createFromValidatedCart(userId, request.addressId(),
                request.notes(), cart);

        reconcile(order, quote);

        quote.setConsumedAt(LocalDateTime.now());
        quote.setConsumedOrderId(order.getId());
        quotes.save(quote);

        PaymentResponse payment = payments.initiate(order.getId(), userId,
                InitiatePaymentRequest.builder().method(request.paymentMethod()).build(), paymentOperation);

        log.info("[Checkout] Order {} placed by user {} from quote {} — {} {}",
                order.getOrderNumber(), userId, quote.getId(), order.getTotal(), order.getCurrency());

        return toPlaced(order, payment);
    }

    /**
     * The buyer must never be charged a figure they did not agree to.
     *
     * <p>Compared to the currency's own smallest unit rather than to zero: a
     * conversion and a re-conversion can legitimately differ by one butut, and
     * failing a checkout over rounding residue would be its own bug. In CFA,
     * which has no minor unit, the tolerance is a whole franc — which is
     * correct, because a franc is the smallest amount that exists there.
     */
    private void reconcile(Order order, CartQuote quote) {
        BigDecimal tolerance = currencies.smallestUnit(quote.getDisplayCurrency());
        BigDecimal difference = order.getTotal().subtract(quote.getTotal()).abs();

        if (difference.compareTo(tolerance) > 0) {
            log.warn("[Checkout] Quote {} priced {} {} but the order came to {} — refusing",
                    quote.getId(), quote.getTotal(), quote.getDisplayCurrency(), order.getTotal());
            throw new BadRequestException(
                    "The price changed while you were checking out. Nothing has been charged — "
                            + "please review the basket and try again.");
        }
    }

    @Override
    @Transactional
    public CheckoutResponses.PaymentIntent retryPayment(Long userId, Long orderId,
                                                        CheckoutRequests.RetryPayment request,
                                                        PaymentOperation paymentOperation) {
        Order order = requireOwnOrder(orderId, userId);

        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            throw new BadRequestException("This order is already paid.");
        }
        if (order.getStatus() != null && order.getStatus().name().equals("CANCELLED")) {
            // A cancelled order has released its stock. A new intent against it
            // would take money for goods nobody is holding.
            throw new BadRequestException(
                    "This order was cancelled and its stock released. Start a new basket.");
        }

        PaymentResponse payment = payments.initiate(orderId, userId,
                InitiatePaymentRequest.builder().method(request.paymentMethod()).build(), paymentOperation);

        log.info("[Checkout] New payment intent on order {} for user {}",
                order.getOrderNumber(), userId);
        return toIntent(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutResponses.Status status(Long userId, Long orderId) {
        Order order = requireOwnOrder(orderId, userId);

        PaymentStatus paymentStatus = order.getPaymentStatus();
        boolean settled = paymentStatus == PaymentStatus.PAID;

        return new CheckoutResponses.Status(
                order.getId(), order.getOrderNumber(), order.getStatus(), paymentStatus,
                settled,
                // Retryable only while nothing has settled and the order still
                // holds its reservation.
                !settled && paymentStatus != PaymentStatus.CANCELLED,
                order.getTotal(), order.getCurrency(), order.getPaidAt(), null);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A quote this caller may spend.
     *
     * <p>Expiry is in the repository query, so an expired quote is
     * indistinguishable from one that never existed. Ownership is checked here
     * because a quote bound to an account is spendable only by that account —
     * and an anonymous quote cannot be spent at all, since an order needs an
     * owner for the refund, the status poll and the retry to resolve through.
     */
    private CartQuote requireUsableQuote(String quoteId, Long userId) {
        CartQuote quote = quotes.findByIdForUpdate(quoteId)
                .orElseThrow(() -> new ResourceNotFoundException("Quote", quoteId));

        if (quote.getExpiresAt() == null || !quote.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ResourceNotFoundException("Quote", quoteId);
        }
        if (!quote.isComplete()) {
            throw new BadRequestException(
                    "That quote could not be fully priced, so it cannot be paid. Re-quote the "
                            + "basket.");
        }
        if (quote.isAnonymous()) {
            throw new BadRequestException(
                    "This quote belongs to an anonymous cart and cannot be checked out after signing in. "
                            + "Merge the cart, then request a new quote.");
        }
        if (!quote.belongsTo(userId)) {
            throw new ResourceNotFoundException("Quote", quoteId);
        }
        if (quote.isConsumed()) {
            throw new BadRequestException(
                    "That quote has already been used for order " + quote.getConsumedOrderId()
                            + ". Ask for a new one.");
        }
        return quote;
    }

    /** The exact cart that was quoted, never an account's later replacement cart. */
    private static Long sourceCartId(CartQuote quote) {
        if (quote.getCart() == null || quote.getCart().getId() == null) {
            throw new BadRequestException("This quote is not bound to a cart. Request a new quote.");
        }
        return quote.getCart().getId();
    }

    private static void requireSameCartStructure(CartQuote quote, CartResponse cart) {
        if (cart == null || !Objects.equals(sourceCartId(quote), cart.getCartId())) {
            throw new BadRequestException(
                    "The cart behind this quote changed. Request a new quote before checking out.");
        }
        if (!quote.getCartFingerprint().equals(CartStructureFingerprint.of(cart))) {
            throw new BadRequestException(
                    "The cart changed after this quote was prepared. Request a new quote before checking out.");
        }
    }

    /** Refuse a price that was calculated for another delivery destination or mode. */
    private void requireBoundDelivery(CartQuote quote, CartResponse cart, Long userId) {
        if (quote.getDeliveryContextId() == null || quote.getDeliveryContextId().isBlank()
                || !Objects.equals(quote.getDeliveryContextId(), cart.getDeliveryContextId())) {
            throw new BadRequestException(
                    "The delivery destination changed after this quote was prepared. Request a new quote.");
        }

        DeliveryContext context = deliveryContexts.require(quote.getDeliveryContextId(), userId);
        if (quote.getDeliveryMode() != context.getMode()
                || !Objects.equals(quote.getPickupPointId(), context.getPickupPointId())) {
            throw new BadRequestException(
                    "The delivery arrangement changed after this quote was prepared. Request a new quote.");
        }
        if (quote.getDeliveryMode() != DeliveryMode.HOME_DELIVERY) {
            throw new BadRequestException(
                    "Checkout currently supports home-delivery quotes only. Request a home-delivery quote.");
        }
    }

    /**
     * An order belonging to this caller.
     *
     * <p>Not-found rather than forbidden for somebody else's, because confirming
     * order 4102 exists tells whoever guessed it that somebody bought something.
     */
    private Order requireOwnOrder(Long orderId, Long userId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        return order;
    }

    private static CheckoutResponses.Placed toPlaced(Order order, PaymentResponse payment) {
        List<CheckoutResponses.VendorSlice> slices = order.getVendorOrders().stream()
                .map(CheckoutServiceImpl::toSlice)
                .toList();

        return new CheckoutResponses.Placed(
                order.getId(), order.getOrderNumber(), order.getStatus(), order.getCurrency(),
                order.getSubtotal(), order.getDiscount(), order.getShippingCost(), order.getTotal(),
                slices, toIntent(payment), order.getCreatedAt());
    }

    /**
     * One seller's slice, with what they are owed and the rate it was struck at.
     *
     * <p>Returned at checkout rather than left for later because this is the
     * shape the order actually has: one payment, several sub-orders that ship,
     * cancel, refund and pay out independently. A client that renders one order
     * with one status will eventually be wrong about half of it.
     */
    private static CheckoutResponses.VendorSlice toSlice(VendorOrder slice) {
        return new CheckoutResponses.VendorSlice(
                slice.getId(),
                slice.getVendor() == null ? null : slice.getVendor().getId(),
                slice.getVendor() == null ? null : slice.getVendor().getStoreName(),
                slice.getStatus() == null ? null : slice.getStatus().name(),
                slice.getTotal(), slice.getNativeCurrency(), slice.getTotalNative(),
                slice.getPayoutNative(),
                slice.getFx() == null ? null : slice.getFx().getRate());
    }

    private static CheckoutResponses.PaymentIntent toIntent(PaymentResponse payment) {
        if (payment == null) {
            return null;
        }
        return new CheckoutResponses.PaymentIntent(
                payment.getPaymentId(), payment.getReference(), payment.getMethod(),
                payment.getStatus(), payment.getAmount(), payment.getCurrency(),
                payment.getCheckoutUrl(), payment.getClientSecret(), payment.getInstructions());
    }
}
