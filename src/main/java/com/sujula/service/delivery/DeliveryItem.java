package com.sujula.service.delivery;

import com.sujula.model.products.Product;

import java.math.BigDecimal;

/**
 * One product to be priced for delivery.
 *
 * @param lineValue what the buyer pays for these units, in the quote's display
 *                  currency. Only used to decide whether a vendor's free-delivery
 *                  threshold is met; null simply opts this line out of that.
 */
public record DeliveryItem(Product product, int quantity, BigDecimal lineValue) {

    public static DeliveryItem of(Product product, int quantity) {
        return new DeliveryItem(product, quantity, null);
    }
}
