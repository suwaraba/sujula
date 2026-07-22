package com.sujula.service;


import com.sujula.dto.request.order.CartItemRequest;
import com.sujula.dto.response.order.CartResponse;

public interface CartService {

    // ── Authenticated-user cart ───────────────────────────────────────────────

    CartResponse getCart(Long userId);
    CartResponse addItem(Long userId, CartItemRequest request);
    CartResponse updateItem(Long userId, Long cartItemId, CartItemRequest request);
    CartResponse removeItem(Long userId, Long cartItemId);
    CartResponse applyCoupon(Long userId, ApplyCouponRequest request);
    CartResponse removeCoupon(Long userId);
    void         clearCart(Long userId);

    // ── Guest cart (identified by sessionId) ──────────────────────────────────

    CartResponse getGuestCart(String sessionId);
    CartResponse addItemGuest(String sessionId, CartItemRequest request);
    CartResponse updateItemGuest(String sessionId, Long cartItemId, CartItemRequest request);
    CartResponse removeItemGuest(String sessionId, Long cartItemId);
    CartResponse applyCouponGuest(String sessionId, ApplyCouponRequest request);
    CartResponse removeCouponGuest(String sessionId);


    CartResponse mergeGuestCart(Long userId, String sessionId);
}
