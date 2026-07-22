package com.sujula.service;


import com.sujula.dto.request.VendorApplicationRequest;
import com.sujula.dto.request.VendorUpdateProfileRequest;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.dto.response.VendorResponse;
import com.sujula.model.user.Vendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VendorService {

    VendorResponse findById(Long id);

    VendorResponse findByUserId(Long userId);

    VendorResponse findByStoreSlug(String slug);

    Page<VendorResponse> findAll(Pageable pageable);

    Page<VendorResponse> findByStatus(PartnerStatus status, Pageable pageable);

    VendorResponse apply(VendorApplicationRequest request, String language);

    VendorResponse updateProfile(Long userId, VendorUpdateProfileRequest request, String language);

    VendorResponse updateLogo(Long userId, String logoUrl);

    VendorResponse updateBanner(Long userId, String bannerUrl);

    VendorResponse updateStatus(Long vendorId, PartnerStatus status, String reason);

    Vendor requireApproved(Long userId);

}
