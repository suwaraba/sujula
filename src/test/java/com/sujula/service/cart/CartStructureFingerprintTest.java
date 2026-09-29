package com.sujula.service.cart;

import com.sujula.dto.response.order.CartResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CartStructureFingerprintTest {

    @Test
    void equivalentItemsHaveTheSameFingerprintRegardlessOfJpaGroupOrItemOrder() {
        CartResponse.CartItemResponse first = item(11L, null, 2);
        CartResponse.CartItemResponse second = item(42L, 7L, 1);

        String firstOrder = CartStructureFingerprint.of(cart("GMD",
                List.of(first), List.of(second)));
        String reversedOrder = CartStructureFingerprint.of(cart("GMD",
                List.of(second), List.of(first)));

        assertEquals(firstOrder, reversedOrder);
    }

    @Test
    void everyApprovedStructuralFieldChangesTheFingerprint() {
        String original = CartStructureFingerprint.of(cart("GMD", List.of(item(11L, null, 2))));

        assertNotEquals(original,
                CartStructureFingerprint.of(cart("GMD", List.of(item(12L, null, 2)))),
                "product id is structural");
        assertNotEquals(original,
                CartStructureFingerprint.of(cart("GMD", List.of(item(11L, 7L, 2)))),
                "variant id is structural");
        assertNotEquals(original,
                CartStructureFingerprint.of(cart("GMD", List.of(item(11L, null, 3)))),
                "quantity is structural");
        assertNotEquals(original,
                CartStructureFingerprint.of(cart("USD", List.of(item(11L, null, 2)))),
                "display currency is structural");
    }

    @Test
    void nullVariantHasItsOwnUnambiguousEncoding() {
        assertNotEquals(
                CartStructureFingerprint.of(cart("GMD", List.of(item(11L, null, 2)))),
                CartStructureFingerprint.of(cart("GMD", List.of(item(11L, 0L, 2)))));
    }

    @SafeVarargs
    private static CartResponse cart(String currency, List<CartResponse.CartItemResponse>... groups) {
        return CartResponse.builder()
                .displayCurrency(currency)
                .vendors(java.util.Arrays.stream(groups)
                        .map(items -> CartResponse.VendorGroup.builder().items(items).build())
                        .toList())
                .build();
    }

    private static CartResponse.CartItemResponse item(Long productId, Long variantId, int quantity) {
        return CartResponse.CartItemResponse.builder()
                .productId(productId)
                .variantId(variantId)
                .quantity(quantity)
                .build();
    }
}
