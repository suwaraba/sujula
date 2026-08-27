package com.sujula.dto.request.order;

import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderScheduleRequest {

    private LocalDate scheduledDate;

    @Size(max = 20)
    private String scheduledTimeSlot;

    @Size(max = 1000)
    private String deliveryInstructions;

    private boolean contactlessDelivery;
}
