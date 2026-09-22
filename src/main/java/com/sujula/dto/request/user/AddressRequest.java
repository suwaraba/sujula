package com.sujula.dto.request.user;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A delivery address a buyer saves to their account.
 *
 * <p>Coordinates are optional. When they are absent the address is geocoded on
 * save, because delivery is priced from the distance a parcel actually travels —
 * an address with no coordinates falls back to a flat scope distance and prices
 * the leg wrong.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressRequest {

    /** "Home", "Work", "Mum's place". */
    @NotBlank(message = "A label is required so you can tell your addresses apart")
    @Size(max = 60)
    private String label;

    @NotBlank(message = "Recipient name is required")
    @Size(max = 200)
    private String fullName;

    @NotBlank(message = "A phone number is required so the driver can call on arrival")
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$",
             message = "Phone must include the country prefix, for example +2201234567")
    private String phone;

    @NotBlank(message = "Street address is required")
    @Size(max = 255)
    private String street;

    @Size(max = 120)
    private String apartmentSuite;

    @NotBlank(message = "City is required")
    @Size(max = 120)
    private String city;

    @Size(max = 120)
    private String state;

    @Size(max = 20)
    private String postalCode;

    @NotBlank(message = "Country code is required")
    @Pattern(regexp = "^[A-Za-z]{2}$", message = "Country must be an ISO 3166-1 alpha-2 code")
    private String countryCode;

    /** Supply these when the client already has a map pin; otherwise the address is geocoded. */
    @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
    private Double latitude;

    @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
    private Double longitude;

    /** The first address a buyer saves becomes the default whatever this says. */
    private boolean makeDefault;
}
