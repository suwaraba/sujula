package com.sujula.dto.response.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.products.ProductOptionValue;
import com.sujula.model.products.ProductVariant;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * One buyable combination of a product's options — "Large, Red".
 *
 * <p>Mirrors what {@code ProductVariant} actually holds today: a SKU, its own
 * stock, an optional price override and the option values that define it.
 * Anything a variant does not carry (a compare-at price, its own image, its own
 * sort order, its own timestamps) belongs to the product or to the option
 * values, and is served from there rather than invented here.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductVariantResponse {

    private Long id;
    private String sku;

    /**
     * What this variant costs, in the product's listing currency: the override
     * when the vendor set one, otherwise the product's base price plus the
     * surcharges of its option values.
     */
    private BigDecimal price;

    /**
     * The vendor's explicit price for this variant, or null when {@link #price}
     * was derived. Kept separate so a vendor's edit form can tell "priced at
     * 500" from "priced like the others, which happens to be 500".
     */
    private BigDecimal priceOverride;

    private Integer stock;

    /** The vendor's own switch — a variant can be discontinued while still in stock. */
    private boolean active;

    /**
     * True when there are units on hand. Whether the variant can actually be
     * bought is {@code active && (inStock || product.allowBackorder)}, and the
     * product response carries that last flag.
     */
    private boolean inStock;

    /** "Large, Red" — the same summary the cart and the order line show. */
    private String label;

    /** The option values that define this variant, in option-value order. */
    private List<ProductVariantValueResponse> values;

    public static ProductVariantResponse from(ProductVariant variant) {
        Integer stock = variant.getStock();

        return ProductVariantResponse.builder()
                .id(variant.getId())
                .sku(variant.getSku())
                .price(price(variant))
                .priceOverride(variant.getPriceOverride())
                .stock(stock)
                .active(variant.isActive())
                .inStock(stock != null && stock > 0)
                .label(variant.getVariantLabel())
                .values(values(variant))
                .build();
    }

    /**
     * The derived price needs the parent product's base price. A variant read
     * without its product — which the mapper cannot rule out — reports no price
     * rather than failing the whole response.
     */
    private static BigDecimal price(ProductVariant variant) {
        if (variant.getPriceOverride() != null) {
            return variant.getPriceOverride();
        }
        return variant.getProduct() != null ? variant.getEffectivePrice() : null;
    }

    private static List<ProductVariantValueResponse> values(ProductVariant variant) {
        if (variant.getSelectedValues() == null) {
            return List.of();
        }
        return variant.getSelectedValues().stream()
                .sorted(Comparator.comparing(ProductOptionValue::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ProductVariantValueResponse::from)
                .toList();
    }
}
