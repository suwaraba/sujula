package com.sujula.dto.request.order;

import com.sujula.model.order.CartItem;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemRequest {

    @NotNull
    private Long productId;

    private Long variantId;   // null for non-variant products

    @NotNull
    @Min(1)
    private Integer quantity;

    /**
     * Entity -> DTO
     */
    public static CartItemRequest from(CartItem cartItem) {
        return CartItemRequest.builder()
                .productId(cartItem.getProduct().getId())
                .variantId(cartItem.getVariant() != null
                        ? cartItem.getVariant().getId()
                        : null)
                .quantity(cartItem.getQuantity())
                .build();
    }

}