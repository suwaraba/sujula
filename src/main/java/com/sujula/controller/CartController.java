package com.sujula.controller;

import com.sujula.dto.request.order.ApplyCouponRequest;
import com.sujula.dto.request.order.CartItemRequest;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.model.user.User;
import com.sujula.service.CartService;
import com.sujula.service.cart.CartOwner;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * One cart API for shoppers, signed in or not.
 *
 * <p>Who the cart belongs to is decided here and nowhere else: an authenticated
 * caller gets their own cart, and anyone else gets the cart named by a
 * server-issued cookie. <strong>The session id is never read from a request
 * parameter or header</strong> — a client-supplied id is a bearer token for
 * whichever cart it names, and the whole point of {@code CartOwner} is that this
 * decision happens once.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    /** Server-issued, HttpOnly: the browser carries it, scripts cannot read it. */
    static final String GUEST_CART_COOKIE = "sujula_cart";

    private final CartService cartService;

    @Value("${sujula.cart.guest-ttl-days:7}")
    private int guestTtlDays;

    @Value("${sujula.cart.cookie-secure:true}")
    private boolean secureCookie;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<CartResponse> cart(Authentication authentication,
                                             HttpServletRequest request,
                                             HttpServletResponse response,
                                             @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(cartService.getCart(owner(authentication, request, response), currency));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(Authentication authentication,
                                                HttpServletRequest request,
                                                HttpServletResponse response,
                                                @Valid @RequestBody CartItemRequest itemRequest,
                                                @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(
                cartService.addItem(owner(authentication, request, response), itemRequest, currency));
    }

    @PutMapping("/items/{cartItemId}")
    public ResponseEntity<CartResponse> updateItem(Authentication authentication,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response,
                                                   @PathVariable Long cartItemId,
                                                   @Valid @RequestBody CartItemRequest itemRequest,
                                                   @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(cartService.updateItem(
                owner(authentication, request, response), cartItemId, itemRequest, currency));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<CartResponse> removeItem(Authentication authentication,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response,
                                                   @PathVariable Long cartItemId,
                                                   @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(
                cartService.removeItem(owner(authentication, request, response), cartItemId, currency));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(Authentication authentication,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        cartService.clearCart(owner(authentication, request, response));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/currency")
    public ResponseEntity<CartResponse> setCurrency(Authentication authentication,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response,
                                                    @RequestParam String currency) {
        return ResponseEntity.ok(
                cartService.setDisplayCurrency(owner(authentication, request, response), currency));
    }

    // ── Coupons ──────────────────────────────────────────────────────────────

    @PostMapping("/coupons")
    public ResponseEntity<CartResponse> applyCoupon(Authentication authentication,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response,
                                                    @Valid @RequestBody ApplyCouponRequest couponRequest,
                                                    @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(
                cartService.applyCoupon(owner(authentication, request, response), couponRequest, currency));
    }

    @DeleteMapping("/coupons")
    public ResponseEntity<CartResponse> removeCoupon(Authentication authentication,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response,
                                                     @RequestParam(required = false) Long vendorId,
                                                     @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(
                cartService.removeCoupon(owner(authentication, request, response), vendorId, currency));
    }

    // ── Signing in ───────────────────────────────────────────────────────────

    /**
     * Folds whatever was in the guest cart into the signed-in buyer's own, then
     * drops the guest cookie. Called once, straight after sign-in.
     *
     * <p>The session id comes from the cookie, never from the caller — otherwise
     * anyone could name someone else's cart and have its contents merged into
     * their own.
     */
    @PostMapping("/merge")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> mergeGuestCart(Authentication authentication,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response,
                                                       @RequestParam(required = false) String currency) {
        Long userId = authenticatedUserId(authentication)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                        "Authentication is required"));

        Optional<String> guestSession = readGuestCookie(request);
        if (guestSession.isEmpty()) {
            return ResponseEntity.ok(cartService.getCart(CartOwner.user(userId), currency));
        }

        CartResponse merged = cartService.mergeGuestCart(userId, guestSession.get(), currency);
        expireGuestCookie(response);
        return ResponseEntity.ok(merged);
    }

    // ── Ownership ────────────────────────────────────────────────────────────

    private CartOwner owner(Authentication authentication,
                            HttpServletRequest request,
                            HttpServletResponse response) {
        return authenticatedUserId(authentication)
                .map(CartOwner::user)
                .orElseGet(() -> CartOwner.guest(guestSessionId(request, response)));
    }

    private Optional<Long> authenticatedUserId(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return Optional.of(user.getId());
        }
        return Optional.empty();
    }

    /** Returns the browser's cart id, issuing one on first contact. */
    private String guestSessionId(HttpServletRequest request, HttpServletResponse response) {
        return readGuestCookie(request).orElseGet(() -> issueGuestCookie(response));
    }

    private Optional<String> readGuestCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> GUEST_CART_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                // Only ever a UUID we minted; anything else is someone probing.
                .filter(CartController::isUuid)
                .findFirst();
    }

    private String issueGuestCookie(HttpServletResponse response) {
        String sessionId = UUID.randomUUID().toString();
        Cookie cookie = new Cookie(GUEST_CART_COOKIE, sessionId);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath("/");
        cookie.setMaxAge((int) java.time.Duration.ofDays(guestTtlDays).toSeconds());
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return sessionId;
    }

    private void expireGuestCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(GUEST_CART_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
