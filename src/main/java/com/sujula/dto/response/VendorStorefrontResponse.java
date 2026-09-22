package com.sujula.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.user.Vendor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A store as a shopper sees it.
 *
 * <p>Deliberately narrower than {@link VendorResponse}: a balance, a tax number,
 * a business registration number and the owner's email address are the seller's
 * business and nobody else's. The full record stays behind the owner-or-admin
 * endpoints; this is what the storefront gets, and it needs no login.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VendorStorefrontResponse {

    private Long id;
    private String storeName;
    private String storeSlug;
    private String description;
    private String logoUrl;
    private String bannerUrl;

    /** Contact details the seller publishes on purpose — not the owner's own. */
    private String storeEmail;
    private String storePhone;
    private String website;

    /** Where the store trades from. Street and postcode are left out. */
    private String addressCity;
    private String addressState;
    private String addressCountryCode;
    private Double latitude;
    private Double longitude;

    /** The currency this store's prices are quoted in before conversion. */
    private String settlementCurrency;

    private BigDecimal rating;
    private Integer totalReviews;
    private Integer totalSold;

    private LocalDateTime memberSince;

    public static VendorStorefrontResponse from(Vendor vendor) {
        return VendorStorefrontResponse.builder()
                .id(vendor.getId())
                .storeName(vendor.getStoreName())
                .storeSlug(vendor.getStoreSlug())
                .description(vendor.getDescription())
                .logoUrl(vendor.getLogoUrl())
                .bannerUrl(vendor.getBannerUrl())
                .storeEmail(vendor.getStoreEmail())
                .storePhone(vendor.getStorePhone())
                .website(vendor.getWebsite())
                .addressCity(vendor.getAddressCity())
                .addressState(vendor.getAddressState())
                .addressCountryCode(vendor.getAddressCountryCode())
                .latitude(vendor.getLatitude())
                .longitude(vendor.getLongitude())
                .settlementCurrency(vendor.getSettlementCurrency())
                .rating(vendor.getRating())
                .totalReviews(vendor.getTotalReviews())
                .totalSold(vendor.getTotalSold())
                .memberSince(vendor.getCreatedAt())
                .build();
    }
}
