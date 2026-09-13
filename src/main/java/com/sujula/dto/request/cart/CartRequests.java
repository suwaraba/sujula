package com.sujula.dto.request.cart;

import com.sujula.model.constant.DeliveryMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What the /carts surface accepts. */
public final class CartRequests {

    private CartRequests() {}

    /**
     * A new cart.
     *
     * <p>Everything optional: a shopper can start a basket before deciding where
     * it goes or what currency to see. Both can be set later and neither blocks
     * adding the first item.
     */
    public record Create(
            @Size(min = 3, max = 3) String currency,
            @Size(max = 64) String deliveryContextId) {}

    public record AddItem(
            @NotNull Long productId,
            Long variantId,
            @NotNull @Min(1) @Max(99) Integer quantity) {}

    public record UpdateQuantity(
            @NotNull @Min(0) @Max(99) Integer quantity) {}

    /**
     * Where the goods are going.
     *
     * <p>Changing this re-prices delivery and re-checks every line's
     * serviceability. It does not touch the display currency — a buyer in Madrid
     * who switches the recipient from Serrekunda to Dakar still pays in euro.
     */
    public record SetDeliveryContext(
            @NotBlank @Size(max = 64) String deliveryContextId) {}

    /**
     * What the prices say.
     *
     * <p>A manual override of what the payer's browser and IP suggested. It
     * changes nothing about delivery — the parcel goes where it was always going.
     */
    public record SetCurrency(
            @NotBlank @Size(min = 3, max = 3) String currency) {}

    public record ApplyCoupon(
            @NotBlank @Size(max = 50) String code) {}

    /**
     * Asks for the cart to be priced and held.
     *
     * @param deliveryMode how the goods are received; defaults to home delivery
     * @param pickupPointId required when collecting from a hub
     */
    public record Quote(
            DeliveryMode deliveryMode,
            Long pickupPointId) {}

    /** Takes over an anonymous cart on sign-in. */
    public record Merge(
            @NotBlank @Size(max = 64) String sourceCartToken) {}
}
