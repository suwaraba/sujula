package com.sujula.service.impl;

import com.sujula.dto.request.product.ProductRequest;
import com.sujula.dto.response.product.ProductCardResponse;
import com.sujula.dto.response.product.ProductImageResponse;
import com.sujula.dto.response.product.ProductResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.products.*;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.*;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.ProductService;
import com.sujula.service.StorageService;
import com.sujula.service.VendorService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int MAX_IMAGES_PER_PRODUCT = 3;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final VendorService vendorService;
    private final StorageService storageService;
    private final VendorRepository vendorRepository;
    private final ProductVariantRepository variantRepository;




    // ── Browse / search ──────────────────────────────────────────────────────
    // Every method below ranks results within DEFAULT_RADIUS_KM of (userLat,
    // userLng) ahead of everything else, then by promotion status and score
    // (see ProductRepository.RANK_ORDER) — coordinates are optional; passing
    // null lat/lng just falls back to promotion + score ordering.

    private static final double DEFAULT_RADIUS_KM = 50.0;

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        return productRepository
                .findFeaturedProducts(featured, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findNewArrivalsProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        return productRepository
                .findNewArrivalsProducts(deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findBestSellersProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        return productRepository
                .findBestSellersProducts(deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        return productRepository
                .findByCategoryProducts(categoryId, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
    }

    /**
     * Products similar to {@code productId} — same category, excluding the product itself.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findSimilarProducts(Long productId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        Category category = product.getCategory();
        if (category == null) {
            return Page.empty(pageable);
        }
        return productRepository
                .findSimilarProducts(category.getId(), productId, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
                .map(p -> ProductCardResponse.from(p, distanceKm(p, userLat, userLng)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> searchNearUser(String query, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        return productRepository
                .searchNearUser(query, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
    }

    /** Great-circle distance (km) between the user and the product, or null if either location is unknown. */
    private static Double distanceKm(Product product, Double userLat, Double userLng) {
        if (userLat == null || userLng == null || product.getLatitude() == null || product.getLongitude() == null) {
            return null;
        }
        double lat1 = Math.toRadians(userLat);
        double lat2 = Math.toRadians(product.getLatitude());
        double deltaLng = Math.toRadians(product.getLongitude() - userLng);
        double cosCentralAngle = Math.sin(lat1) * Math.sin(lat2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.cos(deltaLng);
        return 6371 * Math.acos(Math.min(1, Math.max(-1, cosCentralAngle)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertOwnership(Product product, Vendor vendor) {
        if (!product.getVendor().getId().equals(vendor.getId())) {
            throw new AccessDeniedException("You do not own this product");
        }
    }

    private Category resolveCategory(Long id) {
        if (id == null) return null;
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
    }

    private Brand resolveBrand(Long id) {
        if (id == null) return null;
        return brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand", id));
    }

    private Product findProductEntityById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        initAssociations(product);
        return product;
    }

    /** Force-initialise all lazy associations needed by the DTO mapper, while the session is still open. */
    private void initAssociations(Product p) {
        Hibernate.initialize(p.getImages());
        Hibernate.initialize(p.getVariants());
        Hibernate.initialize(p.getAttributes());
        if (p.getVendor() != null) Hibernate.initialize(p.getVendor());
        if (p.getCategory() != null) Hibernate.initialize(p.getCategory());
        if (p.getBrand() != null) Hibernate.initialize(p.getBrand());
    }

    @Override
    public ProductResponse create(Long vendorUserId, ProductRequest request) {
        return null;
    }

    @Override
    public ProductResponse update(Long productId, Long vendorUserId, ProductRequest request) {
        return null;
    }
    @Override
    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return ProductResponse.from(findProductEntityById(id));
    }


    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public void delete(Long productId, Long vendorUserId) {

    }

    @Override
    public ProductImage addImage(Long productId, Long vendorUserId, MultipartFile file, String altText, boolean makeDefault) {
        return null;
    }



    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductImageResponse addImage(Long productId, Long vendorUserId, String imageUrl,
                                         String altText, boolean makeDefault) {
        return null;
    }

    /**
     * Delete a product image.
     * DB record is removed first; the R2 object is then deleted (outside the transaction boundary).
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public void deleteImage(Long imageId, Long vendorUserId) {

    }

    @Override
    public ProductImage setDefaultImage(Long imageId, Long vendorUserId) {
        return null;
    }
}