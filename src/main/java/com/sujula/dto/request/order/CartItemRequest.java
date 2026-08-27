package com.sujula.dto.request.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemRequest {

    public static final int MAX_QUANTITY_PER_LINE = 99;

    @NotNull
    private Long productId;

    /** Null for products that have no variants. */
    private Long variantId;

    /**
     * Upper bound is a hard abuse guard, not a business rule — the service
     * additionally clamps to the live stock level. Without a ceiling a single
     * request can drive the subtotal arithmetic into absurd territory.
     */
    @NotNull
    @Min(1)
    @Max(MAX_QUANTITY_PER_LINE)
    private Integer quantity;
}
