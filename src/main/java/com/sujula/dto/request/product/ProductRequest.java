package com.sujula.dto.request.product;

import com.sujula.model.constant.DeliveryScope;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.math.BigDecimal;


@Data
public class ProductRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 500)
    private String shortDescription;

    @Size(max = 10000)
    private String description;

    @NotNull
    @DecimalMin(value = "0.01")
    @DecimalMax(value = "9999999.99")
    private BigDecimal price;

    @Size(min = 3, max = 3, message = "priceCurrency must be a 3-letter ISO 4217 currency code")
    private String priceCurrency = "GMD";


    @DecimalMin(value = "0.01")
    private BigDecimal compareAtPrice;

    @NotNull
    @Min(0)
    @Max(999999)
    private Integer stockQuantity;

    @Size(max = 100)
    private String sku;

    private Long categoryId;
    private Long brandId;
    private Long taxClassId;

    @DecimalMin(value = "0.0") @DecimalMax(value = "9999.0")
    private Double weightKg;

    @Size(max = 100)
    private String dimensions;

    @Size(min = 2, max = 2)
    private String countryCode;

    @DecimalMin(value = "-90.0")  @DecimalMax(value = "90.0")
    private Double latitude;
    @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
    private Double longitude;


    private DeliveryScope deliveryScope;


    @AssertTrue(message = "compareAtPrice must be greater than price when provided")
    public boolean isComparePriceValid() {
        if (price == null || compareAtPrice == null) return true;
        return compareAtPrice.compareTo(price) > 0;
    }
}
