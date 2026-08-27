package com.sujula.dto.request.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/**
 * Places an order from an explicit item list against a stored address —
 * the admin/API path, as opposed to {@code createFromCart} (checkout of the
 * shopper's live cart) or {@code UserCheckoutRequest} (inline frontend payload).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {

    @NotNull
    private Long shippingAddressId;

    @NotEmpty
    @Valid
    private List<OrderItemRequest> items;

    @Size(max = 50)
    private String couponCode;

    @Size(max = 1000)
    private String notes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemRequest {

        @NotNull
        private Long productId;

        /** Null for products that have no variants. */
        private Long variantId;

        @NotNull
        @Min(1)
        @Max(99)
        private Integer quantity;
    }
}
