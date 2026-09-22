package com.sujula.service.impl;


import com.sujula.dto.request.VendorApplicationRequest;
import com.sujula.dto.request.VendorUpdateProfileRequest;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.dto.GeoAddress;
import com.sujula.dto.response.VendorResponse;
import com.sujula.dto.response.VendorStorefrontResponse;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.EmailService;
import com.sujula.service.GoogleMapsService;
import com.sujula.service.VendorService;
import com.sujula.util.Utils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VendorServiceImpl implements VendorService {

    private final VendorRepository vendorRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final EmailService emailService;
    private final AuditService auditService;
    private final GoogleMapsService geoService;

    @Override
    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN') or returnObject.userId == authentication.principal.id")
    public VendorResponse findById(Long id) {
        return VendorResponse.from(findVendorById(id));
    }

    @Override
    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN') or returnObject.userId == authentication.principal.id")
    public VendorResponse findByUserId(Long userId) {
        return VendorResponse.from(findVendorByUserId(userId));
    }

    @Override
    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN') or returnObject.userId == authentication.principal.id")
    public VendorResponse findByStoreSlug(String slug) {
        return vendorRepository.findByStoreSlug(slug)
                .map(VendorResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found: " + slug));
    }

    @Override
    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN')")
    public Page<VendorResponse> findAll(Pageable pageable) {
        return vendorRepository.findAll(pageable).map(VendorResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN')")
    public Page<VendorResponse> findByStatus(PartnerStatus status, Pageable pageable) {
        return vendorRepository.findByStatus(status, pageable).map(VendorResponse::from);
    }

    @Override
    @Transactional
    public VendorResponse apply(VendorApplicationRequest request, String language) {
        if (request == null) {
            throw new BadRequestException("Vendor application request is required");
        }

        Long authenticatedUserId = requireAuthenticatedUserId();
        boolean admin = isAdmin();
        Long requestedUserId = request.getUserId();
        Long targetUserId = requestedUserId != null ? requestedUserId : authenticatedUserId;

        if (!admin && !authenticatedUserId.equals(targetUserId)) {
            throw new AccessDeniedException("Access is denied");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BadRequestException("Please register or sign in before applying to become a vendor"));

        if (user.getRole() == UserRole.VENDOR || vendorRepository.existsByUserId(targetUserId)) {
            throw new BadRequestException("You already have a vendor profile");
        }

        if (admin && user.getRole() != UserRole.CUSTOMER) {
            throw new BadRequestException("Admin-created vendors must be linked to a customer account");
        }

        // On application, the full address is mandatory and must match the coordinates.
        validateAddressMatchesLocation(
                request.getLatitude(), request.getLongitude(),
                request.getAddressCity(), request.getAddressCountryCode(),
                language);

        String baseSlug = Utils.toSlug(request.getStoreName());
        String slug = vendorRepository.existsByStoreSlug(baseSlug)
                ? baseSlug + "-" + UUID.randomUUID().toString().substring(0, 8)
                : baseSlug;

        Vendor vendor = request.toEntity(user, slug);
        vendor.setStatus(admin ? PartnerStatus.APPROVED : PartnerStatus.PENDING);

        try {
            Vendor saved = vendorRepository.save(vendor);
            if (admin) {
                user.setRole(UserRole.VENDOR);
                userRepository.save(user);
            }
            return VendorResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException(
                    "A vendor profile already exists for this account, email, or store name. Please try again.");
        }
    }

    @Override
    @Transactional
    @PostAuthorize("hasRole('ADMIN') or returnObject.userId == authentication.principal.id")
    public VendorResponse updateProfile(Long userId, VendorUpdateProfileRequest request, String language) {
        if (request == null) {
            throw new BadRequestException("Vendor profile update request is required");
        }

        Vendor vendor = requireApproved(userId);

        boolean updatingLocation = request.getLatitude() != null
                || request.getLongitude() != null
                || request.getAddressCity() != null
                || request.getAddressCountryCode() != null;

        if (updatingLocation) {
            if (request.getLatitude() == null || request.getLongitude() == null
                    || request.getAddressCity() == null || request.getAddressCountryCode() == null) {
                throw new BadRequestException(
                        "To update your address, please provide latitude, longitude, city and country code together.");
            }
            validateAddressMatchesLocation(
                    request.getLatitude(), request.getLongitude(),
                    request.getAddressCity(), request.getAddressCountryCode(),
                    language);
        }

        if (request.getStoreName() != null) vendor.setStoreName(request.getStoreName());
        if (request.getDescription() != null) vendor.setDescription(request.getDescription());
        if (request.getStoreEmail() != null) vendor.setStoreEmail(request.getStoreEmail());
        if (request.getStorePhone() != null) vendor.setStorePhone(request.getStorePhone());
        if (request.getWebsite() != null) vendor.setWebsite(request.getWebsite());
        if (request.getAddressStreet() != null) vendor.setAddressStreet(request.getAddressStreet());
        if (request.getAddressCity() != null) vendor.setAddressCity(request.getAddressCity());
        if (request.getAddressState() != null) vendor.setAddressState(request.getAddressState());
        if (request.getAddressPostalCode() != null) vendor.setAddressPostalCode(request.getAddressPostalCode());
        if (request.getAddressCountryCode() != null) vendor.setAddressCountryCode(request.getAddressCountryCode());
        if (request.getLatitude() != null) vendor.setLatitude(request.getLatitude());
        if (request.getLongitude() != null) vendor.setLongitude(request.getLongitude());
        if (request.getBusinessRegistrationNumber() != null) {
            vendor.setBusinessRegistrationNumber(request.getBusinessRegistrationNumber());
        }
        if (request.getTaxNumber() != null) vendor.setTaxNumber(request.getTaxNumber());

        return VendorResponse.from(vendorRepository.save(vendor));
    }

    @Override
    @Transactional
    public VendorResponse updateLogo(Long userId, String logoUrl) {
        Vendor vendor = requireApproved(userId);
        vendor.setLogoUrl(logoUrl);
        return VendorResponse.from(vendorRepository.save(vendor));
    }

    @Override
    @Transactional
    public VendorResponse updateBanner(Long userId, String bannerUrl) {
        Vendor vendor = requireApproved(userId);
        vendor.setBannerUrl(bannerUrl);
        return VendorResponse.from(vendorRepository.save(vendor));
    }

    @Override
    @Transactional
    @PostAuthorize("hasRole('ADMIN')")
    public VendorResponse updateStatus(Long vendorId, PartnerStatus newStatus, String reason) {
        Vendor vendor = findVendorById(vendorId);
        PartnerStatus oldStatus = vendor.getStatus();

        if (newStatus == oldStatus) {
            return VendorResponse.from(vendor); // nothing to change, no email
        }

        vendor.setStatus(newStatus);

        User user = userRepository.findById(vendor.getUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found for vendor " + vendorId));

        if (newStatus == PartnerStatus.APPROVED) {
            user.setRole(UserRole.VENDOR);
            userRepository.save(user);
        } else if (oldStatus == PartnerStatus.APPROVED && user.getRole() == UserRole.VENDOR) {
            // Only demote users who are actually VENDOR — never downgrade an ADMIN.
            user.setRole(UserRole.CUSTOMER);
            userRepository.save(user);
        }

        Vendor saved = vendorRepository.save(vendor);
        String effectiveReason = reason != null && !reason.isBlank() ? reason.trim() : "No reason provided";

        // Approving a seller lets them take money from buyers, and suspending one
        // cuts off their livelihood; both are decisions someone has to answer for.
        auditService.record(AuditAction.VENDOR_STATUS_CHANGED, "VENDOR", saved.getId(), saved.getStoreName(),
                "Vendor status changed from " + oldStatus + " to " + newStatus,
                "Reason: " + effectiveReason);

        emailService.sendVendorStatusChangeEmail(user.getEmail(), saved.getStoreName(), newStatus.name(), effectiveReason);

        return VendorResponse.from(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public VendorResponse updateSettlementCurrency(Long vendorId, String settlementCurrency) {
        String currency = requireCurrencyCode(settlementCurrency);
        Vendor vendor = findVendorById(vendorId);

        if (currency.equals(vendor.getSettlementCurrency())) {
            return VendorResponse.from(vendor);
        }

        // Checkout refuses a listing priced in anything but its vendor's
        // settlement currency, so switching it under live listings would take the
        // whole store off sale without saying so.
        long mismatched = productRepository.countByVendorIdAndPriceCurrencyNot(vendorId, currency);
        if (mismatched > 0) {
            throw new BadRequestException(
                    "This vendor has " + mismatched + " listing(s) priced in " + vendor.getSettlementCurrency()
                            + ". Re-price or deactivate them before switching to " + currency + ".");
        }

        String previous = vendor.getSettlementCurrency();
        vendor.setSettlementCurrency(currency);
        Vendor saved = vendorRepository.save(vendor);

        auditService.record(AuditAction.VENDOR_STATUS_CHANGED, "VENDOR", saved.getId(), saved.getStoreName(),
                "Settlement currency changed from " + previous + " to " + currency,
                "No listings were priced in another currency at the time of the change");

        return VendorResponse.from(saved);
    }

    // ── Storefront ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public VendorStorefrontResponse findStorefrontBySlug(String slug) {
        Vendor vendor = vendorRepository.findByStoreSlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", "no store with slug " + slug));
        if (!vendor.getStatus().canTrade()) {
            // A pending or suspended store is not a store yet, and saying so would
            // confirm the slug is taken.
            throw new ResourceNotFoundException("Vendor", "no store with slug " + slug);
        }
        return VendorStorefrontResponse.from(vendor);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VendorStorefrontResponse> searchStorefront(String query, Pageable pageable) {
        Page<Vendor> vendors = (query == null || query.isBlank())
                ? vendorRepository.findByStatus(PartnerStatus.APPROVED, pageable)
                : vendorRepository.searchByName(query.trim(), pageable);

        return vendors.map(VendorStorefrontResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Vendor requireApproved(Long userId) {
        Vendor vendor = findVendorByUserId(userId);
        if (!vendor.getStatus().canTrade()) {
            throw new BadRequestException("Your vendor account is not approved. Current status: " + vendor.getStatus());
        }
        return vendor;
    }


    private void validateAddressMatchesLocation(Double latitude, Double longitude,
                                                String declaredCity, String declaredCountryCode,
                                                String language) {
        if (latitude == null || longitude == null
                || declaredCity == null || declaredCity.isBlank()
                || declaredCountryCode == null || declaredCountryCode.isBlank()) {
            throw new BadRequestException("Location coordinates, city and country code are required.");
        }

        GeoAddress address = geoService.getAddress(latitude, longitude, language);
        if (address == null || address.getCity() == null || address.getCountry() == null) {
            throw new BadRequestException("We could not verify your location. Please try again.");
        }

        boolean cityMatches = address.getCity().trim().equalsIgnoreCase(declaredCity.trim());
        boolean countryMatches = address.getCountry().trim().equalsIgnoreCase(declaredCountryCode.trim());

        if (!cityMatches || !countryMatches) {
            throw new BadRequestException("Address provided is not the same as your location.");
        }
    }

    private String requireCurrencyCode(String code) {
        if (code == null || code.trim().length() != 3 || !code.trim().chars().allMatch(Character::isLetter)) {
            throw new BadRequestException("Settlement currency must be a 3-letter ISO 4217 code");
        }
        return code.trim().toUpperCase();
    }

    private Vendor findVendorById(Long id) {
        return vendorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", id));
    }

    private Vendor findVendorByUserId(Long userId) {
        return vendorRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor profile not found for user: " + userId));
    }

    private Long requireAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user.getId();
        }
        if (principal instanceof Long id) {
            return id;
        }
        if (principal instanceof String value && !"anonymousUser".equals(value)) {
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException ignored) {
                throw new AccessDeniedException("Authentication is required");
            }
        }

        throw new AccessDeniedException("Authentication is required");
    }

    private boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
