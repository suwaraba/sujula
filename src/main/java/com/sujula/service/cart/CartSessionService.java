package com.sujula.service.cart;

import com.sujula.dto.request.cart.CartRequests;
import com.sujula.dto.response.cart.CartQuoteResponse;
import com.sujula.dto.response.order.CartResponse;

/**
 * The addressable cart: {@code /carts/{token}}.
 *
 * <p>Carts here are named by an unguessable token rather than reached through a
 * cookie, which is what lets a shopper start a basket on a phone and finish it
 * on a laptop — and what lets a guest have a cart at all without an account.
 *
 * <p>Two operations on this surface are separate for reasons that go to the
 * heart of the design, and must stay separate: setting the delivery context says
 * where the goods go and re-prices shipping; setting the currency says what the
 * prices read as. A buyer in Madrid sending to Serrekunda uses both, and neither
 * may be inferred from the other.
 */
public interface CartSessionService {

    /** Opens an anonymous cart and returns it with its token. */
    CartResponse create(CartRequests.Create request, Long userId);

    /** Reads a cart by token. Priced, grouped by store, with per-line delivery. */
    CartResponse get(String token, Long userId);

    CartResponse addItem(String token, Long userId, CartRequests.AddItem request);

    /** Quantity zero removes the line, which is what a stepper stepped to zero means. */
    CartResponse updateQuantity(String token, Long userId, Long itemId,
                                CartRequests.UpdateQuantity request);

    CartResponse removeItem(String token, Long userId, Long itemId);

    /**
     * Takes over an anonymous cart on sign-in.
     *
     * <p>The shopper filled a basket before they had an account, which is the
     * normal order of events. Merging rather than replacing: whatever is already
     * in their account cart was also put there by them.
     */
    CartResponse merge(String targetToken, Long userId, CartRequests.Merge request);

    /** Where the goods go. Re-prices delivery and re-checks every line. */
    CartResponse setDeliveryContext(String token, Long userId,
                                    CartRequests.SetDeliveryContext request);

    /** What the prices say. Touches nothing about delivery. */
    CartResponse setCurrency(String token, Long userId, CartRequests.SetCurrency request);

    CartResponse applyCoupon(String token, Long userId, CartRequests.ApplyCoupon request);

    CartResponse removeCoupon(String token, Long userId, String code);

    /**
     * Prices the cart and holds the figures.
     *
     * <p>The contract checkout is held to. Every rate used is frozen onto the
     * quote, so what the buyer agreed to is what they are charged even though
     * the rate table, the listed prices and the stock all move independently in
     * the minute it takes to fill in a card.
     */
    CartQuoteResponse quote(String token, Long userId, CartRequests.Quote request);
}
