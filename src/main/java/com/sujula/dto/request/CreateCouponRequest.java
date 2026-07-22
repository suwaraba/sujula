package com.sujula.dto.request;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sujula.model.constant.CouponType;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCouponRequest {

    @NotBlank
    @Size(min = 3, max = 50, message = "Coupon code must be between 3 and 50 characters")
    @Pattern(regexp = "^[A-Z0-9_\\-]+$", message = "Coupon code must contain only uppercase letters, digits, underscores, or hyphens")
    private String code;

    @Size(max = 500)
    private String description;

    @NotNull
    private CouponType type;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal value;

    private BigDecimal minimumOrderAmount;
    private BigDecimal maximumDiscountAmount;

    @Min(1)
    private Integer usageLimit;

    @Min(1)
    private Integer perUserLimit;

    private boolean active = true;
    private LocalDateTime startsAt;
    private LocalDateTime expiresAt;

    @AssertTrue(message = "expiresAt must be after startsAt when both are provided")
    public boolean isExpiryAfterStart() {
        if (startsAt == null || expiresAt == null) return true;
        return expiresAt.isAfter(startsAt);
    }
}
