package com.sujula.dto.request.order;

import com.sujula.model.constant.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateOrderStatusRequest {

    @NotNull
    private OrderStatus status;

    @Size(max = 500)
    private String notes;
}
