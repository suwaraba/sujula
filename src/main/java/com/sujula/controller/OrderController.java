package com.sujula.controller;

import com.sujula.dto.request.order.CreateOrderRequest;
import com.sujula.dto.request.order.GuestCheckoutRequest;
import com.sujula.dto.request.order.OrderScheduleRequest;
import com.sujula.dto.request.order.UpdateOrderStatusRequest;
import com.sujula.dto.request.order.UserCheckoutRequest;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.dto.response.order.OrderAdminDto;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderStatusHistory;
import com.sujula.model.user.User;
import com.sujula.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // ── Authenticated checkout ──────────────────────────────────────────────

    @PostMapping("/api/user/orders")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Order> checkout(Authentication authentication,
                                          @Valid @RequestBody UserCheckoutRequest request) {
        Order order = orderService.createUserOrder(currentUserId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @PostMapping("/api/user/orders/checkout-cart")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Order> checkoutCart(Authentication authentication,
                                              @RequestParam Long shippingAddressId,
                                              @RequestParam(required = false) String notes,
                                              @RequestParam(required = false) String currency) {
        Order order = orderService.createFromCart(currentUserId(authentication), shippingAddressId, notes, currency);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    // ── Guest checkout ───────────────────────────────────────────────────────

    @PostMapping("/api/guest/orders")
    public ResponseEntity<Order> guestCheckout(@Valid @RequestBody GuestCheckoutRequest request) {
        Order order = orderService.createGuestOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/api/guest/orders/lookup")
    public ResponseEntity<Order> lookupGuestOrder(@RequestParam String orderNumber, @RequestParam String email) {
        return ResponseEntity.ok(orderService.findGuestOrder(orderNumber, email));
    }

    @PostMapping("/api/guest/orders/{orderNumber}/cancel")
    public ResponseEntity<Order> cancelGuestOrder(@PathVariable String orderNumber, @RequestParam String email) {
        return ResponseEntity.ok(orderService.cancelGuestOrder(orderNumber, email));
    }

    // ── Authenticated self-service ──────────────────────────────────────────

    @GetMapping("/api/user/orders")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedResponse<Order>> myOrders(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(
                orderService.findByCustomer(currentUserId(authentication), PageRequest.of(page, size))));
    }

    @GetMapping("/api/user/orders/{orderNumber}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Order> myOrder(Authentication authentication, @PathVariable String orderNumber) {
        Order order = orderService.findByOrderNumber(orderNumber);
        requireOwnedByOrThrow(order, currentUserId(authentication));
        return ResponseEntity.ok(order);
    }

    @GetMapping("/api/user/orders/{orderNumber}/history")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<OrderStatusHistory>> myOrderHistory(Authentication authentication, @PathVariable String orderNumber) {
        Order order = orderService.findByOrderNumber(orderNumber);
        requireOwnedByOrThrow(order, currentUserId(authentication));
        return ResponseEntity.ok(orderService.getStatusHistory(order.getId()));
    }

    @PostMapping("/api/user/orders/{orderId}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Order> cancelMyOrder(Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.cancelByCustomer(orderId, currentUserId(authentication)));
    }

    @PutMapping("/api/user/orders/{orderNumber}/schedule")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Order> updateSchedule(Authentication authentication, @PathVariable String orderNumber,
                                                @Valid @RequestBody OrderScheduleRequest request) {
        return ResponseEntity.ok(orderService.updateSchedule(orderNumber, currentUserId(authentication), request));
    }

    @PostMapping("/api/user/orders/{orderNumber}/reorder")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> reorder(Authentication authentication, @PathVariable String orderNumber) {
        return ResponseEntity.ok(orderService.reorder(orderNumber, currentUserId(authentication)));
    }

    // ── Admin ────────────────────────────────────────────────────────────────

    @PostMapping("/api/admin/orders")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Order> createForCustomer(@RequestParam Long customerId,
                                                   @Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(customerId, request));
    }

    @GetMapping("/api/admin/orders")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<Order>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(orderService.findAll(PageRequest.of(page, size))));
    }

    @GetMapping("/api/admin/orders/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<Order>> findByStatus(
            @RequestParam OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(orderService.findByStatus(status, PageRequest.of(page, size))));
    }

    @GetMapping("/api/admin/orders/vendor/{vendorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<Order>> findByVendor(
            @PathVariable Long vendorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(orderService.findByVendor(vendorId, PageRequest.of(page, size))));
    }

    @GetMapping("/api/admin/orders/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderAdminDto> findById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @GetMapping("/api/admin/orders/{id}/history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<OrderStatusHistory>> history(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getStatusHistory(id));
    }

    @PatchMapping("/api/admin/orders/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Order> updateStatus(Authentication authentication, @PathVariable Long id,
                                              @Valid @RequestBody UpdateOrderStatusRequest request) {
        return ResponseEntity.ok(orderService.updateStatus(id, currentUserId(authentication), request));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void requireOwnedByOrThrow(Order order, Long userId) {
        if (order.isGuestOrder() || !order.getCustomer().getId().equals(userId)) {
            throw new BadRequestException("Order does not belong to this user");
        }
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        throw new AccessDeniedException("Authentication is required");
    }
}
