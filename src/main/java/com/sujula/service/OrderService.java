package com.sujula.service;

import com.sujula.dto.request.CreateOrderRequest;
import com.sujula.dto.request.GuestCheckoutRequest;
import com.sujula.dto.request.OrderScheduleRequest;
import com.sujula.dto.request.UpdateOrderStatusRequest;
import com.sujula.dto.request.UserCheckoutRequest;
import com.sujula.dto.response.CartResponse;
import com.sujula.dto.response.OrderAdminDto;
import com.sujula.model.Order;
import com.sujula.model.OrderStatusHistory;
import com.sujula.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface OrderService {

    // ── Authenticated checkout ────────────────────────────────────────────────

    /**
     * Create an order from an explicit item list (API / admin use).
     * Handles variant-level stock, coupon application, and stock deduction.
     */
    Order create(Long customerId, CreateOrderRequest request);

    /**
     * Create an order for an authenticated customer using the checkout payload
     * sent directly from the frontend (inline shipping address, item list,
     * optional coupon, delivery method, and display currency).
     *
     * This is the primary endpoint for POST /api/user/orders.
     */
    Order createUserOrder(Long userId, UserCheckoutRequest request);

    /**
     * Checkout the authenticated user's active cart.
     * Transfers all cart items (with variants + coupon) into a new Order,
     * records coupon usage, and clears the cart on success.
     *
     * @param userId            the authenticated buyer
     * @param shippingAddressId the chosen shipping address
     * @param notes             optional customer note
     */
    Order createFromCart(Long userId, Long shippingAddressId, String notes);

    // ── Guest checkout ────────────────────────────────────────────────────────

    /**
     * Place an order without an account.
     *
     * Items may come from:
     *   a) The guest's active cart (when {@code request.sessionId} is provided), or
     *   b) The explicit {@code request.items} list.
     *
     * A confirmation email is sent to {@code request.guestEmail}.
     * The guest can later look up the order via
     * {@link #findGuestOrder(String, String)}.
     */
    Order createGuestOrder(GuestCheckoutRequest request);

    /**
     * Retrieve a guest order by its public order number + the email used at
     * checkout.  Requiring both prevents order-number enumeration attacks.
     *
     * @throws com.sujula.exception.ResourceNotFoundException if not found
     */
    Order findGuestOrder(String orderNumber, String guestEmail);

    // ── Queries ───────────────────────────────────────────────────────────────

    OrderAdminDto       findById(Long id);
    Order       findByOrderNumber(String orderNumber);
    Page<Order> findByCustomer(Long customerId, Pageable pageable);
    Page<Order> findByVendor(Long vendorId, Pageable pageable);
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    Page<Order> findAll(Pageable pageable);

    // ── Status transitions ────────────────────────────────────────────────────

    /**
     * Transition an order to a new status.
     * On CANCELLED: restores stock and cancels all vendor sub-orders.
     */
    Order updateStatus(Long orderId, UpdateOrderStatusRequest request);
    Order updateStatus(Long orderId, Long adminUserId, UpdateOrderStatusRequest request);

    /**
     * Cancel an order on behalf of the authenticated customer.
     * Enforces that the order is still in a cancellable state (PENDING or CONFIRMED).
     */
    Order cancelByCustomer(Long orderId, Long customerId);

    /**
     * Cancel a guest order.
     * Identifies the order by {@code orderNumber} + {@code guestEmail} so
     * no account is needed.  Enforces the same PENDING/CONFIRMED guard as
     * {@link #cancelByCustomer}.
     */
    Order cancelGuestOrder(String orderNumber, String guestEmail);

    List<OrderStatusHistory> getStatusHistory(Long orderId);

    // ── Order Scheduling ──────────────────────────────────────────────────────

    /**
     * Update the scheduled delivery date, time slot, and delivery instructions.
     * Only allowed when order is in PENDING or CONFIRMED state.
     */
    Order updateSchedule(String orderNumber, Long userId, OrderScheduleRequest request);

    // ── Reorder ───────────────────────────────────────────────────────────────

    /**
     * Copy all items from a previous order into the user's active cart.
     * Items that are no longer available are skipped with a warning (non-fatal).
     */
    CartResponse reorder(String orderNumber, Long userId);
}
