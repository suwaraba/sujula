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
import com.sujula.model.order.CartQuoteLine;
import com.sujula.model.order.CartQuoteVendorSnapshot;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checkout: validate, reserve, create, intend.
 *
 * <p><strong>The persisted quote is the monetary contract.</strong> Checkout
 * validates that the cart still contains the same products, variants and
 * quantities, then constructs the order from the quote's frozen lines, vendor
 * discount snapshots, delivery shares and FX evidence. Live catalogue prices,
 * exchange rates, delivery pricing and coupon state are not monetary inputs.
 *
 * <p>The resulting order must match the quote exactly. There is no one-minor-unit
 * reconciliation allowance: any difference means the transaction rolls back
 * before payment is initiated and the buyer must request a new quote.
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

        if (request.addressId() == null) {
            throw new BadRequestException(
                    "An address is needed: the delivery context says where the parcel goes, and "
                            + "this says who to hand it to and on what street.");
        }
        requireBoundDelivery(quote, cart, userId, request.addressId());

        // Reserve and create. The existing path locks each product row before
        // decrementing, so two shoppers racing for the last unit cannot both
        // succeed.
        Order order = orderService.createFromQuote(userId, request.addressId(),
                request.notes(), quote);

        requireExactOrderContract(order, quote);

        quote.setConsumedAt(LocalDateTime.now());
        quote.setConsumedOrderId(order.getId());
        quotes.save(quote);

        PaymentResponse payment = payments.initiate(order.getId(), userId,
                InitiatePaymentRequest.builder().method(request.paymentMethod()).build(), paymentOperation);

        log.info("[Checkout] Order {} placed by user {} from quote {} — {} {}",
                order.getOrderNumber(), userId, quote.getId(), order.getTotal(), order.getCurrency());

        return toPlaced(order, payment);
    }

    /** The quote and resulting order must match exactly, with no repricing tolerance. */
    private void requireExactOrderContract(Order order, CartQuote quote) {
        String currency = currencies.require(quote.getDisplayCurrency());
        BigDecimal quotedTotal = currencies.round(quote.getTotal(), currency);

        if (quote.getTotal() == null || quotedTotal.compareTo(quote.getTotal()) != 0) {
            throw new BadRequestException(
                    "This quote uses invalid currency precision. Refresh the cart and request a new quote.");
        }
        if (order == null || order.getTotal() == null
                || order.getTotal().compareTo(quotedTotal) != 0
                || !currency.equalsIgnoreCase(order.getCurrency())) {
            log.warn("[Checkout] Quote {} priced {} {} but the order came to {} — refusing",
                    quote.getId(), quote.getTotal(), quote.getDisplayCurrency(),
                    order == null ? null : order.getTotal());
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
        requireCompleteSnapshotCoverage(quote);
        return quote;
    }

    /** Initializes the lazy contract collections while the quote row is locked. */
    private static void requireCompleteSnapshotCoverage(CartQuote quote) {
        List<CartQuoteLine> lines = quote.getLines();
        List<CartQuoteVendorSnapshot> snapshots = quote.getVendorSnapshots();
        if (lines == null || lines.isEmpty() || snapshots == null || snapshots.isEmpty()) {
            throw requoteForIncompleteContract();
        }

        Set<Long> lineVendors = new HashSet<>();
        for (CartQuoteLine line : lines) {
            if (line == null || line.getVendorId() == null) {
                throw requoteForIncompleteContract();
            }
            lineVendors.add(line.getVendorId());
        }

        Set<Long> snapshotVendors = new HashSet<>();
        for (CartQuoteVendorSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.getVendorId() == null
                    || !snapshotVendors.add(snapshot.getVendorId())) {
                throw requoteForIncompleteContract();
            }
        }
        if (!snapshotVendors.equals(lineVendors)) {
            throw requoteForIncompleteContract();
        }
    }

    private static BadRequestException requoteForIncompleteContract() {
        return new BadRequestException(
                "This quote predates the complete checkout contract. Refresh the cart and request a new quote.");
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
    private void requireBoundDelivery(CartQuote quote, CartResponse cart, Long userId,
                                      Long shippingAddressId) {
        if (quote.getDeliveryContextId() == null || quote.getDeliveryContextId().isBlank()
                || !Objects.equals(quote.getDeliveryContextId(), cart.getDeliveryContextId())) {
            throw new BadRequestException(
                    "The delivery destination changed after this quote was prepared. Request a new quote.");
        }

        DeliveryContext context = deliveryContexts.require(quote.getDeliveryContextId(), userId);
        if (context.getAddressId() != null
                && !Objects.equals(context.getAddressId(), shippingAddressId)) {
            throw new BadRequestException(
                    "The selected address is not the address this quote was priced for. Request a new quote.");
        }
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
