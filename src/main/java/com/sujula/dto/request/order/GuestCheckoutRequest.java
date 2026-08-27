package com.sujula.dto.request.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/**
 * Places an order without an account. Items come either from the guest's
 * cart ({@link #sessionId} set — the browser cookie the server itself
 * issued) or from an explicit list ({@link #items} set); exactly one of the
 * two must be provided.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestCheckoutRequest {

    /** Guest cart identifier. When set, {@link #items} is ignored. */
    @Size(max = 36)
    private String sessionId;

    @Valid
    private List<CreateOrderRequest.OrderItemRequest> items;

    @NotBlank
    @Size(max = 200)
    private String guestName;

    @NotBlank
    @Email
    @Size(max = 200)
    private String guestEmail;

    @Size(max = 30)
    private String guestPhone;

    @Size(min = 3, max = 3)
    private String currency;

    @Size(max = 50)
    private String couponCode;

    @Size(max = 1000)
    private String notes;

    @NotBlank
    private String shippingFullName;

    @NotBlank
    private String shippingPhone;

    @NotBlank
    private String shippingStreet;

    private String shippingApartment;

    @NotBlank
    private String shippingCity;

    private String shippingState;
    private String shippingPostalCode;

    @NotBlank
    @Size(min = 2, max = 2)
    private String shippingCountry;
}
