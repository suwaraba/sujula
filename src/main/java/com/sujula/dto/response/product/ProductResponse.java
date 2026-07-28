package com.sujula.dto.response.product;

import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.products.Brand;
import com.sujula.model.products.Category;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


@Data
@Builder
public class ProductResponse {
    private Long id;
    private String name;
    private String slug;
    private String shortDescription;
    private String description;
    private BigDecimal price;
    private BigDecimal compareAtPrice;
    private String priceCurrency;
    private String sku;
    private Integer stockQuantity;

    private Long vendorId;
    private String vendorStoreName;
    private String vendorStoreSlug;

    private Long categoryId;
    private String categoryName;
    private String categorySlug;

    private Long brandId;
    private String brandName;
    private String brandSlug;
    private String brandLogoUrl;

    private boolean active;
    private boolean featured;

    private Double weightKg;
    private String dimensions;
    private String country;

    private DeliveryScope deliveryScope;

    private BigDecimal rating;
    private Integer totalReviews;
    private Integer totalSold;

    private Integer lowStockThreshold;
    private boolean allowBackorder;
    private LocalDateTime lastRestockedAt;

    private List<ProductImageResponse> images;
    private List<ProductVariantResponse> variants;
    private List<ProductAttributeResponse> attributes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProductResponse from(Product product) {
        Vendor vendor = product.getVendor();
        Category category = product.getCategory();
        Brand brand = product.getBrand();

        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .slug(product.getSlug())
                .shortDescription(product.getShortDescription())
                .description(product.getDescription())
                .price(product.getPrice())
                .compareAtPrice(product.getCompareAtPrice())
                .priceCurrency(product.getPriceCurrency())
                .sku(product.getSku())
                .stockQuantity(product.getStock())
                .vendorId(vendor != null ? vendor.getId() : null)
                .vendorStoreName(vendor != null ? vendor.getStoreName() : null)
                .vendorStoreSlug(vendor != null ? vendor.getStoreSlug() : null)
                .categoryId(category != null ? category.getId() : null)
                .categoryName(category != null ? category.getName() : null)
                .categorySlug(category != null ? category.getSlug() : null)
                .brandId(brand != null ? brand.getId() : null)
                .brandName(brand != null ? brand.getName() : null)
                .brandSlug(brand != null ? brand.getSlug() : null)
                .brandLogoUrl(brand != null ? brand.getLogoUrl() : null)
                .active(product.isActive())
                .featured(product.isFeatured())
                .weightKg(product.getWeightKg())
                .dimensions(product.getDimensions())
                .country(product.getCountry())
                .deliveryScope(product.getDeliveryScope())
                .rating(product.getRating())
                .totalReviews(product.getTotalReviews())
                .totalSold(product.getTotalSold())
                .lowStockThreshold(product.getLowStockThreshold())
                .allowBackorder(product.isAllowBackorder())
                .lastRestockedAt(product.getLastRestockedAt())
                .images(product.getImages().stream().map(ProductImageResponse::from).toList())
                .variants(product.getVariants().stream().map(ProductVariantResponse::from).toList())
                .attributes(product.getAttributes().stream().map(ProductAttributeResponse::from).toList())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
