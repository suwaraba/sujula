package com.sujula.dto.response.product;

import com.sujula.model.products.ProductVariant;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ProductVariantResponse {
    private Long id;
    private String sku;
    private BigDecimal price;
    private BigDecimal compareAtPrice;
    private Integer stockQuantity;
    private boolean available;
    private String imageUrl;
    private Integer sortOrder;
    private List<ProductVariantValueResponse> values;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProductVariantResponse from(ProductVariant variant) {
        return ProductVariantResponse.builder()
                .id(variant.getId())
                .sku(variant.getSku())
                .price(variant.getPrice())
                .compareAtPrice(variant.getCompareAtPrice())
                .stockQuantity(variant.getStockQuantity())
                .available(variant.isAvailable())
                .imageUrl(variant.getImageUrl())
                .sortOrder(variant.getSortOrder())
                .values(variant.getValues().stream().map(ProductVariantValueResponse::from).toList())
                .createdAt(variant.getCreatedAt())
                .updatedAt(variant.getUpdatedAt())
                .build();
    }
}