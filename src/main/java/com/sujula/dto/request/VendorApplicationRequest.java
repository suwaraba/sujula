package com.sujula.dto.request;

import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class VendorApplicationRequest {

    @Positive
    private Long userId;

    @NotBlank
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

    @URL
    @Size(max = 500)
    private String logoUrl;

    @URL
    @Size(max = 500)
    private String bannerUrl;

    @NotBlank(message = "Business or intended business street address is required")
    @Size(max = 255)
    private String addressStreet;

    @NotBlank(message = "Business or intended business city is required")
    @Size(max = 100)
    private String addressCity;

    @Size(max = 100)
    private String addressState;

    @Size(max = 20)
    private String addressPostalCode;

    @NotBlank(message = "Business or intended business country code is required")
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

    public Vendor toEntity(User user, String storeSlug) {
        return Vendor.builder()
                .user(user)
                .storeName(storeName)
                .storeSlug(storeSlug)
                .description(description)
                .storeEmail(storeEmail)
                .storePhone(storePhone)
                .website(website)
                .logoUrl(logoUrl)
                .bannerUrl(bannerUrl)
                .addressStreet(addressStreet)
                .addressCity(addressCity)
                .addressState(addressState)
                .addressPostalCode(addressPostalCode)
                .addressCountryCode(addressCountryCode)
                .latitude(latitude)
                .longitude(longitude)
                .businessRegistrationNumber(businessRegistrationNumber)
                .taxNumber(taxNumber)
                .status(PartnerStatus.PENDING)
                .build();
    }
}
