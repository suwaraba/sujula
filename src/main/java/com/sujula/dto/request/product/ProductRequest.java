package com.sujula.dto.request.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;




public class ProductRequest {

    @NotBlank(message = "Product name is required")
    @Size(min = 2, max = 120, message = "Product name must be between 2 and 120 characters")
    private String name;

    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Price format is invalid (max 2 decimals)")
    private BigDecimal price;

    @NotNull(message = "Stock is required")
    @Min(value = 0, message = "Stock cannot be negative")
    private Integer stock;

    @DecimalMin(value = "-90.0")  @DecimalMax(value = "90.0")
    private Double latitude;
    @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
    private Double longitude;

    @NotNull(message = "Brand is required")
    @Positive(message = "Brand id must be a positive number")
    private Long brandId;

    @Valid
    private List<AttributeRequest> attributes;

    @Valid
    private List<OptionRequest> options;

    /**
     * Sellable combinations. Each variant picks exactly one value per declared option.
     * Optional: if omitted while options exist, the service auto-generates the cartesian product.
     */
    @Valid
    private List<VariantRequest> variants;

    // ---------- nested DTOs ----------

    public static class AttributeRequest {

        @NotBlank(message = "Attribute name is required")
        @Size(max = 60, message = "Attribute name cannot exceed 60 characters")
        private String name;

        @NotBlank(message = "Attribute value is required")
        @Size(max = 255, message = "Attribute value cannot exceed 255 characters")
        private String value;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static class OptionRequest {

        @NotBlank(message = "Option name is required")
        @Size(max = 60, message = "Option name cannot exceed 60 characters")
        private String name;

        /** An option (e.g. "Storage", "Color") MUST have at least one value. */
        @NotEmpty(message = "Option must have at least one value")
        @Valid
        private List<OptionValueRequest> values;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<OptionValueRequest> getValues() { return values; }
        public void setValues(List<OptionValueRequest> values) { this.values = values; }
    }

    public static class OptionValueRequest {

        @NotBlank(message = "Option value is required")
        @Size(max = 120, message = "Option value cannot exceed 120 characters")
        private String value;

        /** Optional surcharge for this value (e.g. +50 for 256GB). Never negative. */
        @DecimalMin(value = "0.00", message = "Extra price cannot be negative")
        @Digits(integer = 10, fraction = 2, message = "Extra price format is invalid")
        private BigDecimal extraPrice;

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public double getExtraPrice() { return extraPrice; }
        public void setExtraPrice(BigDecimal extraPrice) { this.extraPrice = extraPrice; }
    }

    public static class VariantRequest {

        @NotBlank(message = "Variant SKU is required")
        @Size(max = 60, message = "SKU cannot exceed 60 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "SKU may only contain letters, digits, dot, underscore and hyphen")
        private String sku;

        @NotNull(message = "Variant stock is required")
        @Min(value = 0, message = "Variant stock cannot be negative")
        private Integer stock;

        /** Optional absolute price for this combination; null = base + surcharges. */
        @DecimalMin(value = "0.01", message = "Price override must be greater than 0")
        @Digits(integer = 10, fraction = 2, message = "Price override format is invalid")
        private BigDecimal priceOverride;

        /** One selection per option, e.g. [{option:"Storage", value:"256GB"}, {option:"Color", value:"Blue"}]. */
        @NotEmpty(message = "Variant must select at least one option value")
        @Valid
        private List<SelectionRequest> selections;

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }
        public BigDecimal getPriceOverride() { return priceOverride; }
        public void setPriceOverride(BigDecimal priceOverride) { this.priceOverride = priceOverride; }
        public List<SelectionRequest> getSelections() { return selections; }
        public void setSelections(List<SelectionRequest> selections) { this.selections = selections; }
    }

    public static class SelectionRequest {

        @NotBlank(message = "Selection option name is required")
        private String option;

        @NotBlank(message = "Selection value is required")
        private String value;

        public String getOption() { return option; }
        public void setOption(String option) { this.option = option; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    // ---------- getters / setters ----------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Long getBrandId() { return brandId; }
    public void setBrandId(Long brandId) { this.brandId = brandId; }
    public List<AttributeRequest> getAttributes() { return attributes; }
    public void setAttributes(List<AttributeRequest> attributes) { this.attributes = attributes; }
    public List<OptionRequest> getOptions() { return options; }
    public void setOptions(List<OptionRequest> options) { this.options = options; }
    public List<VariantRequest> getVariants() { return variants; }
    public void setVariants(List<VariantRequest> variants) { this.variants = variants; }

}