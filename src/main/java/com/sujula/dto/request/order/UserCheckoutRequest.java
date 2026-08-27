package com.sujula.dto.request.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/**
 * Checkout payload sent directly from the frontend for an authenticated
 * buyer — inline delivery details rather than a stored address id, so the
 * shopper can check out without having saved an address first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCheckoutRequest {

    @NotEmpty
    @Valid
    private List<ItemRequest> items;

    /** Currency totals should be priced and charged in. Defaults to the vendor's own currency if blank. */
    @Size(min = 3, max = 3)
    private String currency;

    @Size(max = 50)
    private String couponCode;

    @Size(max = 1000)
    private String notes;

    @NotNull
    @Valid
    private RecipientInfo recipient;

    /** Set for home delivery; null when {@link #pickupPointId} is used instead. */
    @Valid
    private DeliveryAddress deliveryAddress;

    /** Set for pickup-point fulfilment; null when {@link #deliveryAddress} is used instead. */
    private Long pickupPointId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemRequest {

        @NotNull
        private Long productId;

        private Long variantId;

        @NotNull
        @Min(1)
        @Max(99)
        private Integer quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipientInfo {

        @NotBlank
        private String name;

        @NotBlank
        private String phone;

        private String city;
        private String region;
        private String country;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryAddress {

        @NotBlank
        private String address;
    }
}
