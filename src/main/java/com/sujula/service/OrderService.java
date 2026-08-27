package com.sujula.service;

import com.sujula.dto.request.order.CreateOrderRequest;
import com.sujula.dto.request.order.GuestCheckoutRequest;
import com.sujula.dto.request.order.OrderScheduleRequest;
import com.sujula.dto.request.order.UpdateOrderStatusRequest;
import com.sujula.dto.request.order.UserCheckoutRequest;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.dto.response.order.OrderAdminDto;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Order placement and lifecycle for a multivendor, multicurrency storefront.
 *
 * <p>An order is a frozen snapshot of a checkout: unlike the cart (which is a
 * live quote, re-priced on every read), placing an order pins prices, deducts
 * stock, and splits the purchase into one {@code VendorOrder} per vendor so
 * fulfilment, cancellation and payout can each proceed independently per
 * vendor. Every vendor slice keeps its own native settlement-currency amounts
 * alongside the buyer's display-currency amounts, mirroring how the cart
 * prices a still-open basket.
 */
public interface OrderService {

    // ── Placement ────────────────────────────────────────────────────────────

    /** Places an order from an explicit item list against a stored address (admin/API use). */
    Order create(Long customerId, CreateOrderRequest request);

    /** Checkout payload sent directly from the frontend for an authenticated buyer (inline address). */
    Order createUserOrder(Long userId, UserCheckoutRequest request);

    /**
     * Checks out the authenticated user's live cart: prices, stock and coupons
     * are taken from the cart's own (already-validated) quote, split per
     * vendor, and the cart is cleared on success.
     *
     * @param displayCurrency currency to charge in; null keeps the cart's own display currency
     */
    Order createFromCart(Long userId, Long shippingAddressId, String notes, String displayCurrency);

    /** Places an order without an account, from the guest's cart or an explicit item list. */
    Order createGuestOrder(GuestCheckoutRequest request);

    /**
     * Retrieves a guest order by its public order number + the email used at
     * checkout. Requiring both prevents order-number enumeration.
     */
    Order findGuestOrder(String orderNumber, String guestEmail);

    // ── Queries ──────────────────────────────────────────────────────────────

    OrderAdminDto findById(Long id);
    Order findByOrderNumber(String orderNumber);
    Page<Order> findByCustomer(Long customerId, Pageable pageable);
    Page<Order> findByVendor(Long vendorId, Pageable pageable);
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    Page<Order> findAll(Pageable pageable);

    // ── Status transitions ───────────────────────────────────────────────────

    /** System-driven transition (e.g. a payment webhook) — no admin attribution. */
    Order updateStatus(Long orderId, UpdateOrderStatusRequest request);

    Order updateStatus(Long orderId, Long adminUserId, UpdateOrderStatusRequest request);

    /** Cancels an order on behalf of its owner. Only PENDING or CONFIRMED orders may be cancelled this way. */
    Order cancelByCustomer(Long orderId, Long customerId);

    /** Cancels a guest order, identified by orderNumber + guestEmail — no account needed. */
    Order cancelGuestOrder(String orderNumber, String guestEmail);

    List<OrderStatusHistory> getStatusHistory(Long orderId);

    // ── Scheduling ───────────────────────────────────────────────────────────

    /** Updates delivery scheduling. Only allowed while the order is PENDING or CONFIRMED. */
    Order updateSchedule(String orderNumber, Long userId, OrderScheduleRequest request);

    // ── Reorder ──────────────────────────────────────────────────────────────

    /**
     * Copies every item from a previous order into the user's cart. Items that
     * are no longer available are skipped and reported as issues on the
     * returned cart rather than failing the whole reorder.
     */
    CartResponse reorder(String orderNumber, Long userId);
}
