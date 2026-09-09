package com.sujula.dto;

import com.sujula.dto.response.product.ProductVariantResponse;
import com.sujula.dto.response.product.ProductVariantValueResponse;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductOption;
import com.sujula.model.products.ProductOptionValue;
import com.sujula.model.products.ProductVariant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the catalogue reports for one buyable combination of a product's options. */
class ProductVariantResponseTest {

    @Test
    void derivesThePriceFromTheBaseProductPlusOptionSurcharges() {
        ProductVariant variant = variant(new BigDecimal("500.00"), null, 4, true,
                value("L", "Large", 0, 50.0), value("RED", "Red", 1, 25.0));

        ProductVariantResponse response = ProductVariantResponse.from(variant);

        assertEquals(new BigDecimal("575.00"), response.getPrice());
        assertNull(response.getPriceOverride(), "a derived price must not read as an explicit one");
    }

    @Test
    void anOverrideWinsOverTheDerivedPrice() {
        ProductVariant variant = variant(new BigDecimal("500.00"), new BigDecimal("420.00"), 4, true,
                value("L", "Large", 0, 50.0));

        ProductVariantResponse response = ProductVariantResponse.from(variant);

        assertEquals(new BigDecimal("420.00"), response.getPrice());
        assertEquals(new BigDecimal("420.00"), response.getPriceOverride());
    }

    @Test
    void reportsStockAndTheVendorsOwnSwitchSeparately() {
        ProductVariantResponse soldOut = ProductVariantResponse.from(
                variant(new BigDecimal("500.00"), null, 0, true, value("L", "Large", 0, 0)));
        ProductVariantResponse discontinued = ProductVariantResponse.from(
                variant(new BigDecimal("500.00"), null, 7, false, value("L", "Large", 0, 0)));

        assertFalse(soldOut.isInStock());
        assertTrue(soldOut.isActive(), "out of stock is not the same as withdrawn");
        assertTrue(discontinued.isInStock());
        assertFalse(discontinued.isActive());
    }

    @Test
    void labelsAndOrdersValuesByTheirOptionOrder() {
        // Colour is added first but sorts second.
        ProductVariant variant = variant(new BigDecimal("500.00"), null, 3, true,
                value("RED", "Red", 2, 0), value("L", "Large", 1, 0));

        ProductVariantResponse response = ProductVariantResponse.from(variant);

        assertEquals("Large, Red", response.getLabel());
        assertEquals(List.of("Large", "Red"),
                response.getValues().stream().map(ProductVariantValueResponse::getDisplayValue).toList());
    }

    @Test
    void carriesTheOptionEachValueBelongsTo() {
        ProductOptionValue large = value("L", "Large", 0, 0);
        large.setOption(option(9L, "Size", "size"));

        ProductVariantValueResponse response = ProductVariantValueResponse.from(large);

        assertEquals(9L, response.getOptionId());
        assertEquals("Size", response.getOptionName());
        assertEquals("size", response.getOptionCode());
        assertEquals("L", response.getValue());
        assertEquals("Large", response.getDisplayValue());
    }

    @Test
    void aVariantWithNoOptionValuesHasNoLabel() {
        ProductVariantResponse response = ProductVariantResponse.from(
                variant(new BigDecimal("500.00"), null, 2, true));

        assertNull(response.getLabel());
        assertTrue(response.getValues().isEmpty());
        assertEquals(new BigDecimal("500.00"), response.getPrice());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static ProductVariant variant(BigDecimal basePrice, BigDecimal override, int stock,
                                          boolean active, ProductOptionValue... values) {
        Product product = new Product();
        product.setId(1L);
        product.setPrice(basePrice);

        ProductVariant variant = new ProductVariant();
        variant.setId(2L);
        variant.setSku("SKU-2");
        variant.setProduct(product);
        variant.setPriceOverride(override);
        variant.setStock(stock);
        variant.setActive(active);
        variant.setSelectedValues(new java.util.ArrayList<>(List.of(values)));
        return variant;
    }

    private static ProductOptionValue value(String value, String displayValue, int sortOrder, double extraPrice) {
        ProductOptionValue optionValue = new ProductOptionValue();
        optionValue.setValue(value);
        optionValue.setDisplayValue(displayValue);
        optionValue.setSortOrder(sortOrder);
        optionValue.setExtraPrice(extraPrice);
        return optionValue;
    }

    private static ProductOption option(Long id, String name, String code) {
        ProductOption option = new ProductOption();
        option.setId(id);
        option.setName(name);
        option.setCode(code);
        return option;
    }
}
