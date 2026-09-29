package com.sujula.service.cart;

import com.sujula.dto.response.order.CartResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Canonical identity of the item structure a buyer approved in a held cart quote.
 *
 * <p>Commercial figures deliberately stay out of this representation. Checkout
 * separately reconciles the current aggregate against the held total; Phase 4
 * owns the fuller frozen-price and FX model. Sorting makes an equivalent cart
 * stable even when JPA returns item rows in a different order.
 */
public final class CartStructureFingerprint {

    private CartStructureFingerprint() {
    }

    public static String of(CartResponse cart) {
        if (cart == null) {
            throw new IllegalArgumentException("A cart is required to calculate its structure fingerprint.");
        }

        List<String> items = new ArrayList<>();
        if (cart.getVendors() != null) {
            for (CartResponse.VendorGroup group : cart.getVendors()) {
                if (group == null || group.getItems() == null) {
                    continue;
                }
                for (CartResponse.CartItemResponse item : group.getItems()) {
                    if (item != null) {
                        items.add(itemRepresentation(item));
                    }
                }
            }
        }
        items.sort(String::compareTo);

        String currency = cart.getDisplayCurrency() == null
                ? "<null>" : cart.getDisplayCurrency().trim().toUpperCase(Locale.ROOT);
        String representation = "currency=" + currency + "\n"
                + String.join("\n", items);

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(representation.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    private static String itemRepresentation(CartResponse.CartItemResponse item) {
        return "product=" + requiredLong(item.getProductId())
                + "|variant=" + nullableLong(item.getVariantId())
                + "|quantity=" + requiredInteger(item.getQuantity());
    }

    private static String requiredLong(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("A cart item without a product cannot be quoted.");
        }
        return Long.toString(value);
    }

    private static String nullableLong(Long value) {
        return value == null ? "<null>" : "<id:" + value + ">";
    }

    private static String requiredInteger(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("A cart item without a quantity cannot be quoted.");
        }
        return Integer.toString(value);
    }
}
