package com.sujula.dto.request.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyCouponRequest {

    @NotBlank
    @Size(max = 50)
    private String couponCode;
}
