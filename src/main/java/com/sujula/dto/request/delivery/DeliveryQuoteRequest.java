package com.sujula.dto.request.delivery;

import com.sujula.model.constant.DeliveryMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Asks what delivery would cost before an order exists — a cart or checkout
 * preview.
 *
 * <p>Coordinates price best; a written address is geocoded when they are
 * missing, and a quote is still returned (flagged as estimated per leg) when
 * neither is usable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryQuoteRequest {

    @NotEmpty
    @Valid
    private List<Line> items;

    /** Defaults to home delivery when omitted. */
    private DeliveryMode mode;

    /** Required when {@link #mode} is {@code PICKUP_POINT} — the hub prices the leg. */
    private Long pickupPointId;

    /** Currency to quote in; defaults to the rate card's own. */
    @Size(min = 3, max = 3)
    private String currency;

    @Valid
    private Destination destination;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {

        @NotNull
        private Long productId;

        @NotNull
        @Min(1)
        @Max(99)
        private Integer quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Destination {
        private Double latitude;
        private Double longitude;
        private String address;
        private String city;
        private String state;
        private String postalCode;
        @Size(min = 2, max = 2)
        private String countryCode;
    }
}
