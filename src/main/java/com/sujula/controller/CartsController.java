package com.sujula.controller;

import com.sujula.dto.request.cart.CartRequests;
import com.sujula.dto.response.cart.CartQuoteResponse;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.service.cart.CartSessionService;
import com.sujula.service.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * The basket, addressed by a token rather than reached through a cookie.
 *
 * <p>Open to guests, because on this marketplace most baskets are filled before
 * anybody signs in — a shopper arrives from a link, compares two phones, and
 * only creates an account when they are ready to pay. A cart that required a
 * login first is a cart most people never fill.
 *
 * <p>The token is the credential. It is 256 bits of secure random for that
 * reason: a cart holds a destination, a list of what somebody is buying and for
 * whom, and what they are about to spend.
 *
 * <p>Two endpoints here are deliberately separate and must stay so.
 * {@code PUT /delivery-context} says where the goods go and re-prices shipping;
 * {@code PUT /currency} says what the prices read as. A buyer in Madrid sending
 * to Serrekunda sets both, to different values, and neither may be derived from
 * the other.
 */
@RestController
@RequestMapping("/carts")
@Tag(name = "carts", description = "The basket: items, destination, currency, coupons and quotes")
public class CartsController {

    private static final String ADD_ITEM = "carts.items.add";

    private final CartSessionService carts;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public CartsController(CartSessionService carts, AuthenticatedCaller caller,
                           IdempotencyService idempotency) {
        this.carts = carts;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    @PostMapping
    @Operation(summary = "Open a cart",
               description = "Returns the cart with its token. Everything is optional — a shopper "
                       + "can start a basket before deciding where it goes or what currency to see.")
    public ResponseEntity<CartResponse> create(Authentication authentication,
                                               @Valid @RequestBody(required = false)
                                               CartRequests.Create request) {
        CartRequests.Create body = request == null
                ? new CartRequests.Create(null, null) : request;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(carts.create(body, caller.userIdOrNull(authentication)));
    }

    @GetMapping("/{cartToken}")
    @Operation(summary = "Read a cart",
               description = "Grouped by store, with each group's shipping and each line's own "
                       + "delivery leg and serviceability. A cart bound to an account is reported "
                       + "as not found to anybody else.")
    public ResponseEntity<CartResponse> get(Authentication authentication,
                                            @PathVariable String cartToken) {
        return ResponseEntity.ok(carts.get(cartToken, caller.userIdOrNull(authentication)));
    }

    /**
     * {@code Idempotency-Key} matters here more than it looks.
     *
     * <p>A shopper on a patchy connection taps "add to basket", the request
     * arrives, the response does not, and their client retries. Without a key
     * they now have two.
     */
    @PostMapping("/{cartToken}/items")
    @Operation(summary = "Add an item",
               description = "Validates stock and the per-order maximum. Send an Idempotency-Key "
                       + "so a retried tap does not add the item twice.")
    public ResponseEntity<CartResponse> addItem(
            Authentication authentication,
            @PathVariable String cartToken,
            @Parameter(description = "A unique value per add, so a retry is answered rather than repeated")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CartRequests.AddItem request) {

        Long userId = caller.userIdOrNull(authentication);
        // Scoped to this cart, not to the caller: a guest has no account to scope
        // to, and two guests sharing a key must not share an answer.
        String scope = "cart:" + cartToken + ":" + ADD_ITEM;

        return ResponseEntity.ok(idempotency.execute(scope, idempotencyKey, request,
                HttpStatus.OK.value(), CartResponse.class,
                () -> carts.addItem(cartToken, userId, request)));
    }

    @PatchMapping("/{cartToken}/items/{itemId}")
    @Operation(summary = "Change a quantity",
               description = "Zero removes the line — which is what a stepper stepped to zero means.")
    public ResponseEntity<CartResponse> updateQuantity(
            Authentication authentication, @PathVariable String cartToken,
            @PathVariable Long itemId, @Valid @RequestBody CartRequests.UpdateQuantity request) {
        return ResponseEntity.ok(carts.updateQuantity(cartToken,
                caller.userIdOrNull(authentication), itemId, request));
    }

    @DeleteMapping("/{cartToken}/items/{itemId}")
    @Operation(summary = "Remove a line")
    public ResponseEntity<CartResponse> removeItem(Authentication authentication,
                                                   @PathVariable String cartToken,
                                                   @PathVariable Long itemId) {
        return ResponseEntity.ok(carts.removeItem(cartToken,
                caller.userIdOrNull(authentication), itemId));
    }

    @PostMapping("/{cartToken}/merge")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Take over an anonymous cart on sign-in",
               description = "Folds the anonymous basket into the account's. Both were filled by "
                       + "the same person, so neither is discarded.")
    public ResponseEntity<CartResponse> merge(Authentication authentication,
                                              @PathVariable String cartToken,
                                              @Valid @RequestBody CartRequests.Merge request) {
        return ResponseEntity.ok(carts.merge(cartToken, caller.userId(authentication), request));
    }

    // ── The two independent settings ─────────────────────────────────────────

    @PutMapping("/{cartToken}/delivery-context")
    @Operation(summary = "Say where the goods are going",
               description = "Re-prices delivery per line and re-checks serviceability. Does not "
                       + "touch the display currency: a buyer in Madrid who switches the recipient "
                       + "from Serrekunda to Dakar is still paying with a European card.")
    public ResponseEntity<CartResponse> setDeliveryContext(
            Authentication authentication, @PathVariable String cartToken,
            @Valid @RequestBody CartRequests.SetDeliveryContext request) {
        return ResponseEntity.ok(carts.setDeliveryContext(cartToken,
                caller.userIdOrNull(authentication), request));
    }

    @PutMapping("/{cartToken}/currency")
    @Operation(summary = "Say what the prices should read as",
               description = "Overrides what the payer's browser and IP suggested. Changes nothing "
                       + "about delivery — the parcel goes where it was always going.")
    public ResponseEntity<CartResponse> setCurrency(
            Authentication authentication, @PathVariable String cartToken,
            @Valid @RequestBody CartRequests.SetCurrency request) {
        return ResponseEntity.ok(carts.setCurrency(cartToken,
                caller.userIdOrNull(authentication), request));
    }

    // ── Coupons ──────────────────────────────────────────────────────────────

    @PostMapping("/{cartToken}/coupons")
    @Operation(summary = "Apply a coupon",
               description = "Eligibility is checked per vendor group: a vendor-scoped coupon "
                       + "applies to that seller's goods and nobody else's.")
    public ResponseEntity<CartResponse> applyCoupon(
            Authentication authentication, @PathVariable String cartToken,
            @Valid @RequestBody CartRequests.ApplyCoupon request) {
        return ResponseEntity.ok(carts.applyCoupon(cartToken,
                caller.userIdOrNull(authentication), request));
    }

    @DeleteMapping("/{cartToken}/coupons/{code}")
    @Operation(summary = "Remove a coupon")
    public ResponseEntity<CartResponse> removeCoupon(Authentication authentication,
                                                     @PathVariable String cartToken,
                                                     @PathVariable String code) {
        return ResponseEntity.ok(carts.removeCoupon(cartToken,
                caller.userIdOrNull(authentication), code));
    }

    // ── The quote ────────────────────────────────────────────────────────────

    @PostMapping("/{cartToken}/quote")
    @Operation(summary = "Price the cart and hold the figures",
               description = "Returns the full breakdown with an id and an expiry, and freezes "
                       + "every rate it used. Checkout prices from this rather than from the "
                       + "catalogue — otherwise the total a buyer agreed to and the total they are "
                       + "charged are two different numbers.")
    public ResponseEntity<CartQuoteResponse> quote(
            Authentication authentication, @PathVariable String cartToken,
            @Valid @RequestBody(required = false) CartRequests.Quote request) {

        CartRequests.Quote body = request == null ? new CartRequests.Quote(null, null) : request;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(carts.quote(cartToken, caller.userIdOrNull(authentication), body));
    }
}
