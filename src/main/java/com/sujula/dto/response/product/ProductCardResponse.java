package com.sujula.dto.response.product;

import com.sujula.model.products.Product;
import com.sujula.model.products.ProductImage;
import com.sujula.model.user.Vendor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Comparator;

@Data
@Builder
public class ProductCardResponse {
    private Long id;
    private String name;
    private String slug;
    private String imageUrl;
    private BigDecimal price;
    private BigDecimal compareAtPrice;
    private String priceCurrency;
    private BigDecimal rating;
    private Integer totalReviews;
    private boolean inStock;
    private String vendorStoreName;
    private boolean featured;

    public static ProductCardResponse from(Product product) {
        Vendor vendor = product.getVendor();
        Integer stockQuantity = product.getStock();

        return ProductCardResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .slug(product.getSlug())
                .imageUrl(primaryImageUrl(product))
                .price(product.getPrice())
                .compareAtPrice(product.getCompareAtPrice())
                .priceCurrency(product.getPriceCurrency())
                .rating(product.getRating())
                .totalReviews(product.getTotalReviews())
                .inStock((stockQuantity != null && stockQuantity > 0) || product.isAllowBackorder())
                .vendorStoreName(vendor != null ? vendor.getStoreName() : null)
                .featured(product.isFeatured())
                .build();
    }

    private static String primaryImageUrl(Product product) {
        return product.getImages().stream()
                .filter(ProductImage::isDefault)
                .findFirst()
                .or(() -> product.getImages().stream()
                        .min(Comparator.comparing(ProductImage::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))))
                .map(ProductImage::getImageUrl)
                .orElse(null);
    }
}