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




    // ── Read ──────────────────────────────────────────────────────────────────







    @Override
    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return ProductResponse.from(findProductEntityById(id));
    }

    @Override
    public ProductResponse create(Long vendorUserId, ProductRequest request) {
        return null;
    }

    @Override
    public ProductResponse update(Long productId, Long vendorUserId, ProductRequest request) {
        return null;
    }


    /**
     * Soft-delete: marks the product inactive.
     * Verifies ownership and APPROVED status.
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public void delete(Long productId, Long vendorUserId) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);
        Product product = findProductEntityById(productId);
        assertOwnership(product, vendor);

        product.setActive(false);
        productRepository.save(product);
        // Removing the product's images from object storage is intentionally not
        // handled here — there is no storage service implementation wired up in
        // this codebase yet.
    }

    @Override
    public ProductImage addImage(Long productId, Long vendorUserId, MultipartFile file, String altText, boolean makeDefault) {
        return null;
    }

    // ── Image management ────────────────────────────────────────────────────
    // The frontend uploads the file straight to object storage via a
    // presigned URL (see StorageService.presignUpload) and calls addImage
    // with the resulting public URL — this server never handles the file
    // bytes, so there's no MultipartFile here.

    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductImageResponse addImage(Long productId, Long vendorUserId, String imageUrl,
                                         String altText, boolean makeDefault) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);
        Product product = findProductEntityById(productId);
        assertOwnership(product, vendor);

        if (!storageService.isManagedUrl(imageUrl, "products")) {
            throw new BadRequestException("imageUrl must point to an object already uploaded to the products folder");
        }

        long existingCount = productImageRepository.countByProductId(productId);
        if (existingCount >= MAX_IMAGES_PER_PRODUCT) {
            throw new BadRequestException(
                    "Maximum of " + MAX_IMAGES_PER_PRODUCT + " images allowed per product");
        }

        boolean isFirst = existingCount == 0;
        boolean setAsDefault = makeDefault || isFirst;
        if (setAsDefault) {
            productImageRepository.clearDefaultsByProductId(productId);
        }

        ProductImage image = ProductImage.builder()
                .product(product)
                .imageUrl(imageUrl)
                .altText(altText)
                .sortOrder((int) existingCount)
                .isDefault(setAsDefault)
                .build();

        return ProductImageResponse.from(productImageRepository.save(image));
    }

    /**
     * Delete a product image.
     * DB record is removed first; the R2 object is then deleted (outside the transaction boundary).
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public void deleteImage(Long imageId, Long vendorUserId) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);
        ProductImage image = productImageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", imageId));
        assertOwnership(image.getProduct(), vendor);

        boolean wasDefault = image.isDefault();
        Long productId = image.getProduct().getId();
        String url = image.getImageUrl();

        productImageRepository.delete(image);

        if (wasDefault) {
            List<ProductImage> remaining =
                    productImageRepository.findByProductIdOrderBySortOrderAsc(productId);
            if (!remaining.isEmpty()) {
                remaining.get(0).setDefault(true);
                productImageRepository.save(remaining.get(0));
            }
        }

        storageService.delete(url);
    }

    @Override
    public ProductImage setDefaultImage(Long imageId, Long vendorUserId) {
        return null;
    }

    // ── Browse / search ──────────────────────────────────────────────────────

    @Override
    public Page<ProductCardResponse> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
    }

    @Override
    public Page<ProductCardResponse> findNewArrivalsProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
    }

    @Override
    public Page<ProductCardResponse> findBestSellersProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
    }

    @Override
    public Page<ProductCardResponse> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
    }

    @Override
    public Page<ProductCardResponse> searchNearUser(String query, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
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
}