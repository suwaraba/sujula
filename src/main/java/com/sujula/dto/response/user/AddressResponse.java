package com.sujula.dto.response.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.Address;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** A saved delivery address, as its owner sees it. */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddressResponse {

    private Long id;
    private String label;
    private String fullName;
    private String phone;
    private String street;
    private String apartmentSuite;
    private String city;
    private String state;
    private String postalCode;
    private String countryCode;

    private Double latitude;
    private Double longitude;

    /**
     * False when the address could not be placed on a map. Delivery for it is
     * priced from a fallback distance until the buyer corrects it, so the client
     * has a reason to prompt.
     */
    private boolean located;

    private boolean isDefault;

    private LocalDateTime createdAt;

    public static AddressResponse from(Address address) {
        return AddressResponse.builder()
                .id(address.getId())
                .label(address.getLabel())
                .fullName(address.getFullName())
                .phone(address.getPhone())
                .street(address.getStreet())
                .apartmentSuite(address.getApartmentSuite())
                .city(address.getCity())
                .state(address.getState())
                .postalCode(address.getPostalCode())
                .countryCode(address.getCountryCode())
                .latitude(address.getLatitude())
                .longitude(address.getLongitude())
                .located(address.getLatitude() != null && address.getLongitude() != null)
                .isDefault(address.isDefault())
                .createdAt(address.getCreatedAt())
                .build();
    }
}
