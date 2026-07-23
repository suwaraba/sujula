package com.sujula.dto.response.product;

import com.sujula.model.products.ProductAttribute;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductAttributeResponse {
    private Long id;
    private String name;
    private String value;
    private Integer sortOrder;

    public static ProductAttributeResponse from(ProductAttribute attribute) {
        return ProductAttributeResponse.builder()
                .id(attribute.getId())
                .name(attribute.getName())
                .value(attribute.getValue())
                .sortOrder(attribute.getSortOrder())
                .build();
    }
}