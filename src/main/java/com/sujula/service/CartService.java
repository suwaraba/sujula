package com.sujula.service;

import com.sujula.dto.request.order.ApplyCouponRequest;
import com.sujula.dto.request.order.CartItemRequest;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.service.cart.CartOwner;

/**
 * Cart operations for a multivendor, multicurrency storefront.
 *
 * <p>Every method takes a {@link CartOwner} rather than a raw id, so the user
 * and guest flows share one implementation instead of two that drift apart.
 *
 * <p>Reads revalidate the cart against live catalogue state before responding:
 * prices are re-read, quantities are clamped to stock, and unavailable vendors
 * or products are flagged. Callers get the reasons back in
 * {@code CartResponse.issues} rather than a silently mutated cart.
 */
public interface CartService {

    /**
     * Returns the owner's cart, revalidated. Does not create one — an owner with
     * no cart yet gets an empty response.
     */
    CartResponse getCart(CartOwner owner, String displayCurrency);

    /** Adds a line, or increases an existing line for the same product/variant. */
    CartResponse addItem(CartOwner owner, CartItemRequest request, String displayCurrency);

    /** Sets an existing line to an absolute quantity. */
    CartResponse updateItem(CartOwner owner, Long cartItemId, CartItemRequest request, String displayCurrency);

    CartResponse removeItem(CartOwner owner, Long cartItemId, String displayCurrency);

    void clearCart(CartOwner owner);

    /** Changes the currency totals are presented in and persists the preference. */
    CartResponse setDisplayCurrency(CartOwner owner, String displayCurrency);

    // ── Coupons ───────────────────────────────────────────────────────────────

    /**
     * Applies a platform- or vendor-scoped coupon. A vendor coupon is rejected
     * unless the cart contains items from that vendor. At most one platform
     * coupon and one coupon per vendor may be active at a time.
     */
    CartResponse applyCoupon(CartOwner owner, ApplyCouponRequest request, String displayCurrency);

    /** Removes the platform coupon, or a single vendor's coupon when {@code vendorId} is given. */
    CartResponse removeCoupon(CartOwner owner, Long vendorId, String displayCurrency);

    // ── Session transition ────────────────────────────────────────────────────

    /**
     * Folds a guest cart into the signed-in user's cart and deletes the guest
     * cart. Quantities are summed and clamped to stock; unavailable lines are
     * dropped and reported.
     *
     * <p>The caller must have verified that {@code sessionId} belongs to the
     * requester — it comes from the server-issued guest cookie, never from a
     * caller-supplied parameter.
     */
    CartResponse mergeGuestCart(Long userId, String sessionId, String displayCurrency);

    // ── Maintenance ───────────────────────────────────────────────────────────

    /** Deletes guest carts past their TTL. Returns the number removed. */
    int purgeExpiredGuestCarts();
}
