package com.sujula.dto.response.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartItem;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CartResponse {

    private Long cartId;
    private String sessionId;
    private Boolean guest;
    private List<CartItemResponse> items;
    private String couponCode;

    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private int itemCount;

    private String currency;

    public static CartResponse from(
            Cart cart,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal total,
            String currency
    ) {

        List<CartItemResponse> itemResponses = cart.getItems()
                .stream()
                .map(CartItemResponse::from)
                .toList();

        int itemCount = cart.getItems()
                .stream()
                .mapToInt(CartItem::getQuantity)
                .sum();

        return CartResponse.builder()
                .cartId(cart.getId())
                .sessionId(cart.getSessionId())
                .guest(cart.getUser() == null)
                .items(itemResponses)
                .couponCode(cart.getCoupon() != null ? cart.getCoupon().getCode() : null)
                .subtotal(subtotal)
                .discount(discount)
                .total(total)
                .itemCount(itemCount)
                .currency(currency)
                .build();
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CartItemResponse {

        private Long itemId;
        private Long productId;
        private String productName;
        private String productSlug;
        private String imageUrl;
        private Long variantId;
        private String variantSku;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
        private Integer availableStock;
        private String priceCurrency;

        public static CartItemResponse from(CartItem item) {

            return CartItemResponse.builder()
                    .itemId(item.getId())
                    .productId(item.getProduct().getId())
                    .productName(item.getProduct().getName())
                    .productSlug(item.getProduct().getSlug())


                    .variantId(item.getVariant() != null ? item.getVariant().getId() : null)
                    .variantSku(item.getVariant() != null ? item.getVariant().getSku() : null)

                    .quantity(item.getQuantity())
                    .unitPrice(item.getUnitPrice())
                    .totalPrice(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))

                    // change according to your Product/ProductVariant
                    .availableStock(
                            item.getVariant() != null
                                    ? item.getVariant().getStockQuantity()
                                    : item.getProduct().getStockQuantity()
                    )


                    .build();
        }
    }
}