package com.sujula.dto.response.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.products.ProductOption;
import com.sujula.model.products.ProductOptionValue;
import lombok.Builder;
import lombok.Data;

/**
 * One option value a variant is defined by — "Size: Large".
 *
 * <p>Built straight from the {@code ProductOptionValue} the variant points at:
 * the variant's link to its values is a join table, not an entity, so there is
 * nothing between the two worth mapping.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductVariantValueResponse {
    private Long optionId;
    private String optionName;
    private String optionCode;
    private Long optionValueId;
    private String value;
    private String displayValue;
    private String colorHex;
    private String imageUrl;

    public static ProductVariantValueResponse from(ProductOptionValue optionValue) {
        ProductOption option = optionValue.getOption();

        return ProductVariantValueResponse.builder()
                .optionId(option != null ? option.getId() : null)
                .optionName(option != null ? option.getName() : null)
                .optionCode(option != null ? option.getCode() : null)
                .optionValueId(optionValue.getId())
                .value(optionValue.getValue())
                .displayValue(optionValue.getDisplayValue())
                .colorHex(optionValue.getColorHex())
                .imageUrl(optionValue.getImageUrl())
                .build();
    }
}