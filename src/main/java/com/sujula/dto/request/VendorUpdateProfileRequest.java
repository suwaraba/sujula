package com.sujula.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class VendorUpdateProfileRequest {

    @Size(max = 100)
    private String storeName;

    @Size(max = 5000)
    private String description;

    @Email
    @Size(max = 150)
    private String storeEmail;

    @Size(max = 25)
    private String storePhone;

    @URL
    @Size(max = 255)
    private String website;

    @Size(max = 255)
    private String addressStreet;

    @Size(max = 100)
    private String addressCity;

    @Size(max = 100)
    private String addressState;

    @Size(max = 20)
    private String addressPostalCode;

    @Pattern(regexp = "^[A-Z]{2}$", message = "addressCountryCode must be an ISO 3166-1 alpha-2 code")
    private String addressCountryCode;

    @DecimalMin(value = "-90.0", message = "latitude must be at least -90")
    @DecimalMax(value = "90.0", message = "latitude must be at most 90")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "longitude must be at least -180")
    @DecimalMax(value = "180.0", message = "longitude must be at most 180")
    private Double longitude;

    @Size(max = 255)
    private String businessRegistrationNumber;

    @Size(max = 255)
    private String taxNumber;
}
