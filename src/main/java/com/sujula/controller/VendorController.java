package com.sujula.controller;


import com.sujula.dto.request.VendorApplicationRequest;
import com.sujula.dto.request.VendorUpdateProfileRequest;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.VendorResponse;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.user.User;
import com.sujula.service.VendorService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorService vendorService;

    public VendorController(VendorService vendorService) {
        this.vendorService = vendorService;
    }

    @PostMapping("/apply")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VendorResponse> apply(
            @Valid @RequestBody VendorApplicationRequest request,
            @RequestParam(defaultValue = "en") String language) {
        return ResponseEntity.ok(vendorService.apply(request, language));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VendorResponse> getMyVendorProfile(Authentication authentication) {
        return ResponseEntity.ok(vendorService.findByUserId(currentUserId(authentication)));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #userId)")
    public ResponseEntity<VendorResponse> getByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(vendorService.findByUserId(userId));
    }

    @PutMapping("/user/{userId}/profile")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #userId)")
    public ResponseEntity<VendorResponse> updateProfile(
            @PathVariable Long userId,
            @Valid @RequestBody VendorUpdateProfileRequest request,
            @RequestParam(defaultValue = "en") String language) {
        return ResponseEntity.ok(vendorService.updateProfile(userId, request, language));
    }

    @PutMapping("/user/{userId}/logo")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #userId)")
                                                     public ResponseEntity<VendorResponse> updateLogo(@PathVariable Long userId, @RequestParam String logoUrl) {
        return ResponseEntity.ok(vendorService.updateLogo(userId, logoUrl));
    }

    @PutMapping("/user/{userId}/banner")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #userId)")
    public ResponseEntity<VendorResponse> updateBanner(@PathVariable Long userId, @RequestParam String bannerUrl) {
        return ResponseEntity.ok(vendorService.updateBanner(userId, bannerUrl));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VendorResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(vendorService.findById(id));
    }

    @GetMapping("/by-slug/{slug}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VendorResponse> getByStoreSlug(@PathVariable String slug) {
        return ResponseEntity.ok(vendorService.findByStoreSlug(slug));
    }

    // --- Admin only --------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<VendorResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(vendorService.findAll(PageRequest.of(page, size))));
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<VendorResponse>> findByStatus(
            @RequestParam PartnerStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(vendorService.findByStatus(status, PageRequest.of(page, size))));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VendorResponse> updateStatus(@PathVariable Long id, @RequestParam String status, @RequestParam String reason) {
        return ResponseEntity.ok(vendorService.updateStatus(id, PartnerStatus.valueOf(status), reason));
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        throw new AccessDeniedException("Authentication is required");
    }
}