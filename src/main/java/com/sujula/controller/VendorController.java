package com.sujula.controller;


import com.sujula.dto.request.VendorApplicationRequest;
import com.sujula.dto.request.VendorUpdateProfileRequest;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.PresignedUploadResponse;
import com.sujula.dto.response.VendorResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.user.User;
import com.sujula.service.StorageService;
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

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorService vendorService;

    private final StorageService storageService;


    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);


    public VendorController(VendorService vendorService, StorageService storageService) {
        this.vendorService = vendorService;
        this.storageService = storageService;
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

    @PostMapping("/user/{userId}/logo/presign")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #userId)")
    public ResponseEntity<PresignedUploadResponse> presignLogoUpload(@PathVariable Long userId, @RequestParam String contentType) {
        return ResponseEntity.ok(presignImageUpload("logo-" + userId, contentType));
    }

    @PostMapping("/user/{userId}/banner/presign")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #userId)")
    public ResponseEntity<PresignedUploadResponse> presignBannerUpload(@PathVariable Long userId, @RequestParam String contentType) {
        return ResponseEntity.ok(presignImageUpload("banner-" + userId, contentType));
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

    private PresignedUploadResponse presignImageUpload(String namePrefix, String contentType) {
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BadRequestException("Unsupported content type: " + contentType + ". Allowed: " + ALLOWED_IMAGE_TYPES);
        }

        String filename = namePrefix + "-" + UUID.randomUUID() + extensionFor(contentType);
        String uploadUrl = storageService.presignUpload("vendors", filename, contentType, PRESIGN_TTL);
        String publicUrl = storageService.publicUrl("vendors", filename);

        return PresignedUploadResponse.builder()
                .uploadUrl(uploadUrl)
                .publicUrl(publicUrl)
                .build();
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}