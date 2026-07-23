package com.sujula.dto.response.product;

import com.sujula.model.products.ProductImage;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProductImageResponse {
    private Long id;
    private String imageUrl;
    private String altText;
    private Integer sortOrder;
    private boolean isDefault;
    private LocalDateTime createdAt;

    public static ProductImageResponse from(ProductImage image) {
        return ProductImageResponse.builder()
                .id(image.getId())
                .imageUrl(image.getImageUrl())
                .altText(image.getAltText())
                .sortOrder(image.getSortOrder())
                .isDefault(image.isDefault())
                .createdAt(image.getCreatedAt())
                .build();
    }
}