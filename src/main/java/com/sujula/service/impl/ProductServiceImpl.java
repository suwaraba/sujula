package com.sujula.service.impl;

import com.sujula.dto.request.CreateProductRequest;
import com.sujula.dto.response.products.ProductImageResponse;
import com.sujula.dto.response.products.ProductResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.products.Brand;
import com.sujula.model.products.Category;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductImage;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.BrandRepository;
import com.sujula.repository.product.CategoryRepository;
import com.sujula.repository.product.ProductImageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.service.ProductService;
import com.sujula.service.StorageService;
import com.sujula.service.VendorService;
import com.sujula.util.Utils;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int MAX_IMAGES_PER_PRODUCT = 10;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final VendorService vendorService;
    private final StorageService storageService;

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return ProductResponse.from(findProductEntityById(id));
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductResponse create(Long vendorUserId, CreateProductRequest request) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);

        String baseSlug = Utils.toSlug(request.getName());
        String slug = productRepository.existsBySlug(baseSlug)
                ? baseSlug + "-" + UUID.randomUUID().toString().substring(0, 8)
                : baseSlug;

        Product product = Product.builder()
                .name(request.getName())
                .slug(slug)
                .shortDescription(request.getShortDescription())
                .description(request.getDescription())
                .price(request.getPrice())
                .priceCurrency(request.getPriceCurrency() != null ? request.getPriceCurrency() : "GMD")
                .compareAtPrice(request.getCompareAtPrice())
                .stockQuantity(request.getStockQuantity() != null ? request.getStockQuantity() : 0)
                .sku(request.getSku())
                .vendor(vendor)
                .category(resolveCategory(request.getCategoryId()))
                .brand(resolveBrand(request.getBrandId()))
                .weightKg(request.getWeightKg())
                .dimensions(request.getDimensions())
                .country(request.getCountry())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .deliveryScope(request.getDeliveryScope() != null ? request.getDeliveryScope() : DeliveryScope.REGIIONAL)
                .active(true)
                .build();

        try {
            return ProductResponse.from(productRepository.save(product));
        } catch (DataIntegrityViolationException e) {
            // Slug uniqueness race condition — retry with a fresh suffix
            product.setSlug(baseSlug + "-" + UUID.randomUUID().toString().substring(0, 8));
            return ProductResponse.from(productRepository.save(product));
        }
    }

    /**
     * Update a product. Verifies ownership and APPROVED status.
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductResponse update(Long productId, Long vendorUserId, CreateProductRequest request) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);
        Product product = findProductEntityById(productId);
        assertOwnership(product, vendor);

        product.setName(request.getName());
        if (request.getShortDescription() != null) product.setShortDescription(request.getShortDescription());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        if (request.getPriceCurrency() != null) product.setPriceCurrency(request.getPriceCurrency());
        if (request.getCompareAtPrice() != null) product.setCompareAtPrice(request.getCompareAtPrice());
        if (request.getStockQuantity() != null) product.setStockQuantity(request.getStockQuantity());
        if (request.getSku() != null) product.setSku(request.getSku());
        if (request.getWeightKg() != null) product.setWeightKg(request.getWeightKg());
        if (request.getDimensions() != null) product.setDimensions(request.getDimensions());
        if (request.getCountry() != null) product.setCountry(request.getCountry());
        if (request.getLatitude() != null) product.setLatitude(request.getLatitude());
        if (request.getLongitude() != null) product.setLongitude(request.getLongitude());
        if (request.getDeliveryScope() != null) product.setDeliveryScope(request.getDeliveryScope());
        if (request.getCategoryId() != null) product.setCategory(resolveCategory(request.getCategoryId()));
        if (request.getBrandId() != null) product.setBrand(resolveBrand(request.getBrandId()));

        return ProductResponse.from(productRepository.save(product));
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

    // ── Image management ────────────────────────────────────────────────────
    // The frontend uploads the file straight to object storage via a
    // presigned URL (see StorageService.presignUpload) and calls addImage
    // with the resulting public URL — this server never handles the file
    // bytes, so there's no MultipartFile here.

    @Override
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
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductImageResponse setDefaultImage(Long imageId, Long vendorUserId) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);
        ProductImage image = productImageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", imageId));
        assertOwnership(image.getProduct(), vendor);

        productImageRepository.clearDefaultsByProductId(image.getProduct().getId());
        image.setDefault(true);
        return ProductImageResponse.from(productImageRepository.save(image));
    }

    // ── Browse / search ──────────────────────────────────────────────────────
    // Not implemented: ProductRepository's supporting queries are commented out
    // pending review — several reference column names (e.g. countryCode) that
    // don't match Product's actual fields. Stubbed so the class compiles; a
    // separate task from create/update.

    @Override
    public Page<Product> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
    }

    @Override
    public Page<Product> findNewArrivalsProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
    }

    @Override
    public Page<Product> findBestSellersProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
    }

    @Override
    public Page<Product> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        throw new UnsupportedOperationException("Product browse/search is not implemented yet");
    }

    @Override
    public Page<Product> searchNearUser(String query, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
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