package com.sujula.dto.response.product;

import com.sujula.model.products.ProductOption;
import com.sujula.model.products.ProductOptionValue;
import com.sujula.model.products.ProductVariantValue;
import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class ProductVariantValueResponse {
    private Long optionId;
    private String optionName;
    private String optionCode;
    private Long optionValueId;
    private String value;
    private String displayValue;
    private String colorHex;
    private String imageUrl;

    public static ProductVariantValueResponse from(ProductVariantValue variantValue) {
        ProductOptionValue optionValue = variantValue.getOptionValue();
        ProductOption option = optionValue != null ? optionValue.getOption() : null;

        return ProductVariantValueResponse.builder()
                .optionId(option != null ? option.getId() : null)
                .optionName(option != null ? option.getName() : null)
                .optionCode(option != null ? option.getCode() : null)
                .optionValueId(optionValue != null ? optionValue.getId() : null)
                .value(optionValue != null ? optionValue.getValue() : null)
                .displayValue(optionValue != null ? optionValue.getDisplayValue() : null)
                .colorHex(optionValue != null ? optionValue.getColorHex() : null)
                .imageUrl(optionValue != null ? optionValue.getImageUrl() : null)
                .build();
    }
}