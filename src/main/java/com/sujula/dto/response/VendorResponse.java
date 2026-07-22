package com.sujula.dto.response;

import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.user.Vendor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class VendorResponse {
    private Long id;
    private Long userId;
    private String ownerEmail;
    private String ownerName;
    private String storeName;
    private String storeSlug;
    private String description;
    private String storeEmail;
    private String storePhone;
    private String website;
    private String logoUrl;
    private String bannerUrl;
    private String addressStreet;
    private String addressCity;
    private String addressState;
    private String addressPostalCode;
    private String addressCountryCode;
    private Double latitude;
    private Double longitude;
    private PartnerStatus status;
    private BigDecimal balance;
    private BigDecimal defaultCommissionRate;
    private BigDecimal rating;
    private Integer totalReviews;
    private Integer totalSold;
    private String businessRegistrationNumber;
    private String taxNumber;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static VendorResponse from(Vendor vendor) {
        return VendorResponse.builder()
                .id(vendor.getId())
                .userId(vendor.getUser() != null ? vendor.getUser().getId() : null)
                .ownerEmail(vendor.getUser() != null ? vendor.getUser().getEmail() : null)
                .ownerName(vendor.getUser() != null ? vendor.getUser().getFullName() : null)
                .storeName(vendor.getStoreName())
                .storeSlug(vendor.getStoreSlug())
                .description(vendor.getDescription())
                .storeEmail(vendor.getStoreEmail())
                .storePhone(vendor.getStorePhone())
                .website(vendor.getWebsite())
                .logoUrl(vendor.getLogoUrl())
                .bannerUrl(vendor.getBannerUrl())
                .addressStreet(vendor.getAddressStreet())
                .addressCity(vendor.getAddressCity())
                .addressState(vendor.getAddressState())
                .addressPostalCode(vendor.getAddressPostalCode())
                .addressCountryCode(vendor.getAddressCountryCode())
                .latitude(vendor.getLatitude())
                .longitude(vendor.getLongitude())
                .status(vendor.getStatus())
                .balance(vendor.getBalance())
                .rating(vendor.getRating())
                .totalReviews(vendor.getTotalReviews())
                .totalSold(vendor.getTotalSold())
                .businessRegistrationNumber(vendor.getBusinessRegistrationNumber())
                .taxNumber(vendor.getTaxNumber())
                .createdAt(vendor.getCreatedAt())
                .updatedAt(vendor.getUpdatedAt())
                .build();
    }
}
