package com.sujula.dto.request.catalogue;

import com.sujula.model.constant.ProductCondition;

import java.math.BigDecimal;

/**
 * Everything GET /products can be narrowed by.
 *
 * <p>Price bounds arrive in the buyer's display currency, because that is what
 * the slider showed them. They are converted into each listing currency before
 * they reach the query — comparing a euro bound against a dalasi column would
 * silently return the whole catalogue, which looks like a working filter that
 * simply found a lot.
 */
public record ProductFilter(
        String query,
        Long categoryId,
        String categorySlug,
        Long brandId,
        String brandSlug,
        String storeSlug,
        ProductCondition condition,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal minRating,
        boolean inStockOnly) {

    public static ProductFilter empty() {
        return new ProductFilter(null, null, null, null, null, null, null, null, null, null, false);
    }

    public ProductFilter withQuery(String newQuery) {
        return new ProductFilter(newQuery, categoryId, categorySlug, brandId, brandSlug,
                storeSlug, condition, minPrice, maxPrice, minRating, inStockOnly);
    }

    public ProductFilter withCategoryId(Long id) {
        return new ProductFilter(query, id, categorySlug, brandId, brandSlug,
                storeSlug, condition, minPrice, maxPrice, minRating, inStockOnly);
    }
}
