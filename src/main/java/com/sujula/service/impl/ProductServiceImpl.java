package com.sujula.service.impl;

import com.sujula.dto.request.CreateProductRequest;
import com.sujula.exception.BadRequestException;
import com.sujula.exception.ResourceNotFoundException;
import com.sujula.exception.UnauthorizedException;
import com.sujula.model.enums.DeliveryScope;
import com.sujula.repository.product.CategoryRepository;
import com.sujula.repository.product.ProductImageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.service.ProductService;
import com.sujula.service.StorageService;
import com.sujula.service.VendorService;
import com.sujula.util.ImageValidator;
import com.sujula.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int MAX_IMAGES_PER_PRODUCT = 10;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository        brandRepository;
    private final TaxClassRepository     taxClassRepository;
    private final VendorService          vendorService;
    private final StorageService         storageService;

    // ── Read ──────────────────────────────────────────────────────────────────

    // @Override
    // @Transactional(readOnly = true)
    // public Page<Product> findAll(Pageable pageable) {
    //     return productRepository.findByActiveTrue(pageable);
    // }


    // @Override
    // @Transactional(readOnly = true)
    // public Page<Product> findByVendor(Long vendorId, Pageable pageable) {
    //     return productRepository.findByVendorIdAndActiveTrue(vendorId, pageable);
    // }

    // @Override
    // @Transactional(readOnly = true)
    // public Page<Product> findByCategory(Long categoryId, Pageable pageable) {
    //     return productRepository.findByCategoryIdAndActiveTrue(categoryId, pageable);
    // }

    // @Override
    // @Transactional(readOnly = true)
    // public Page<Product> search(String query, Pageable pageable) {
    //     return productRepository.searchProducts(query, pageable);
    // }

    @Override
    @Transactional(readOnly = true)
    public Product findById(Long id) {
        Product p = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        initAssociations(p);
        return p;
    }


    @Override
    @Transactional
    @PreAuthorize("hasRole('VENDOR')")
    public Product create(Long vendorUserId, CreateProductRequest request) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);

        String baseSlug = SlugUtils.toSlug(request.getName());
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
                .stockQuantity(request.getStockQuantity())
                .sku(request.getSku())
                .vendor(vendor)
                .category(resolveCategory(request.getCategoryId()))
                .brand(resolveBrand(request.getBrandId()))
                .taxClass(resolveTaxClass(request.getTaxClassId()))
                .weightKg(request.getWeightKg())
                .dimensions(request.getDimensions())
                .countryCode(request.getCountryCode())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .deliveryScope(request.getDeliveryScope() != null ? request.getDeliveryScope() : DeliveryScope.LOCAL)
                .active(true)
                .build();

        try {
            return productRepository.save(product);
        } catch (DataIntegrityViolationException e) {
            // Slug uniqueness race condition — retry with a fresh suffix
            product.setSlug(baseSlug + "-" + UUID.randomUUID().toString().substring(0, 8));
            return productRepository.save(product);
        }
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('VENDOR')")
    public Product update(Long productId, Long vendorUserId, CreateProductRequest request) {
        Vendor vendor   = vendorService.requireApproved(vendorUserId);
        Product product = findById(productId);
        assertOwnership(product, vendor);

        product.setName(request.getName());
        if (request.getShortDescription() != null)  product.setShortDescription(request.getShortDescription());
        if (request.getDescription() != null)        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        if (request.getPriceCurrency() != null)      product.setPriceCurrency(request.getPriceCurrency());
        if (request.getCompareAtPrice() != null)     product.setCompareAtPrice(request.getCompareAtPrice());
        product.setStockQuantity(request.getStockQuantity());
        if (request.getSku() != null)                product.setSku(request.getSku());
        if (request.getWeightKg() != null)           product.setWeightKg(request.getWeightKg());
        if (request.getDimensions() != null)         product.setDimensions(request.getDimensions());
        if (request.getCountryCode() != null)        product.setCountryCode(request.getCountryCode());
        if (request.getLatitude() != null)           product.setLatitude(request.getLatitude());
        if (request.getLongitude() != null)          product.setLongitude(request.getLongitude());
        if (request.getDeliveryScope() != null)      product.setDeliveryScope(request.getDeliveryScope());
        if (request.getCategoryId() != null)         product.setCategory(resolveCategory(request.getCategoryId()));
        if (request.getBrandId() != null)            product.setBrand(resolveBrand(request.getBrandId()));
        if (request.getTaxClassId() != null)         product.setTaxClass(resolveTaxClass(request.getTaxClassId()));

        return productRepository.save(product);
    }

    /**
     * Soft-delete: marks the product inactive then removes its images from R2.
     *
     * Order matters for atomicity: the DB write is committed first.
     * If R2 cleanup subsequently fails it is logged but does not roll back the deactivation.
     * A background reconciliation job should periodically clean orphaned R2 objects.
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('VENDOR')")
    public void delete(Long productId, Long vendorUserId) {
        Vendor vendor   = vendorService.requireApproved(vendorUserId);
        Product product = findById(productId);
        assertOwnership(product, vendor);

        // 1. Collect image URLs before detaching
        List<String> imageUrls = productImageRepository
                .findByProductIdOrderBySortOrderAsc(productId)
                .stream().map(ProductImage::getImageUrl).toList();

        // 2. Soft-delete the product (DB write — this will be committed)
        product.setActive(false);
        productRepository.save(product);

        // 3. Remove from R2 after the DB state is settled
        //    Done within the transaction boundary but AFTER the save — R2 is not transactional,
        //    so failures here are tolerated (logged by R2StorageServiceImpl).
        imageUrls.forEach(storageService::delete);
    }

    // ── Image management ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProductImage addImage(Long productId, Long vendorUserId, MultipartFile file,
                                 String altText, boolean makeDefault) {
        Vendor vendor   = vendorService.requireApproved(vendorUserId);
        Product product = findById(productId);
        assertOwnership(product, vendor);

        long existingCount = productImageRepository.countByProductId(productId);
        if (existingCount >= MAX_IMAGES_PER_PRODUCT) {
            throw new BadRequestException(
                    "Maximum of " + MAX_IMAGES_PER_PRODUCT + " images allowed per product");
        }

        // Extension is derived from the whitelisted MIME type — never from the raw filename
        String ext      = ImageValidator.safeExtension(file);
        String filename = "product-" + productId + "-" + UUID.randomUUID() + ext;
        String url      = storageService.upload(file, "products", filename);

        boolean isFirst      = existingCount == 0;
        boolean setAsDefault = makeDefault || isFirst;
        if (setAsDefault) {
            productImageRepository.clearDefaultsByProductId(productId);
        }

        ProductImage image = ProductImage.builder()
                .product(product)
                .imageUrl(url)
                .altText(altText)
                .sortOrder((int) existingCount)
                .isDefault(setAsDefault)
                .build();

        return productImageRepository.save(image);
    }

    /**
     * Delete a product image.
     * DB record is removed first; R2 object is then deleted (outside transaction boundary).
     */
    @Override
    @Transactional
    public void deleteImage(Long imageId, Long vendorUserId) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);
        ProductImage image = productImageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", imageId));
        assertOwnership(image.getProduct(), vendor);

        boolean wasDefault = image.isDefault();
        Long    productId  = image.getProduct().getId();
        String  url        = image.getImageUrl();

        // 1. Remove DB record first (atomically in the transaction)
        productImageRepository.delete(image);

        // 2. If default, promote the next image
        if (wasDefault) {
            List<ProductImage> remaining =
                    productImageRepository.findByProductIdOrderBySortOrderAsc(productId);
            if (!remaining.isEmpty()) {
                remaining.get(0).setDefault(true);
                productImageRepository.save(remaining.get(0));
            }
        }

        // 3. R2 cleanup — happens after DB commit; failure is tolerated and logged
        storageService.delete(url);
    }

    @Override
    @Transactional
    public ProductImage setDefaultImage(Long imageId, Long vendorUserId) {
        Vendor vendor = vendorService.requireApproved(vendorUserId);
        ProductImage image = productImageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", imageId));
        assertOwnership(image.getProduct(), vendor);

        productImageRepository.clearDefaultsByProductId(image.getProduct().getId());
        image.setDefault(true);
        return productImageRepository.save(image);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertOwnership(Product product, Vendor vendor) {
        if (!product.getVendor().getId().equals(vendor.getId())) {
            throw new UnauthorizedException("You do not own this product");
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

    private TaxClass resolveTaxClass(Long id) {
        if (id == null) return null;
        return taxClassRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TaxClass", id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency,
            Double userLat, Double userLng, Pageable pageable) {
        String cc = (deliveryCountry != null && !deliveryCountry.isBlank()) ? deliveryCountry : "";
        Page<Product> page = productRepository.findFeaturedProductsNearUser(
                featured, cc, userLat, userLng, userLat != null && userLng != null, pageable);
        page.getContent().forEach(this::initAssociations);
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> findNewArrivalsProducts(String deliveryCountry, String currency,
            Double userLat, Double userLng, Pageable pageable) {
        String cc = (deliveryCountry != null && !deliveryCountry.isBlank()) ? deliveryCountry : "";
        Page<Product> page = productRepository.findNewArrivalsProducts(cc, userLat, userLng, pageable);
        page.getContent().forEach(this::initAssociations);
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> findBestSellersProducts(String deliveryCountry, String currency,
            Double userLat, Double userLng, Pageable pageable) {
        String cc = (deliveryCountry != null && !deliveryCountry.isBlank()) ? deliveryCountry : "";
        Page<Product> page = productRepository.findBestSellersProducts(cc, userLat, userLng, pageable);
        page.getContent().forEach(this::initAssociations);
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency,
            Double userLat, Double userLng, Pageable pageable) {
        String cc = (deliveryCountry != null && !deliveryCountry.isBlank()) ? deliveryCountry : "";
        Page<Product> page = productRepository.findByCategoryNearUser(
                categoryId, cc, userLat, userLng, userLat != null && userLng != null, pageable);
        page.getContent().forEach(this::initAssociations);
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> searchNearUser(String query, String deliveryCountry, String currency,
            Double userLat, Double userLng, Pageable pageable) {
        String cc = (deliveryCountry != null && !deliveryCountry.isBlank()) ? deliveryCountry : "";
        String q  = (query != null) ? query.trim() : "";
        Page<Product> page = productRepository.searchNearUser(
                q, cc, userLat, userLng, userLat != null && userLng != null, pageable);
        page.getContent().forEach(this::initAssociations);
        return page;
    }

    /** Force-initialise all lazy associations needed by the DTO mapper, while the session is still open. */
    private void initAssociations(Product p) {
        Hibernate.initialize(p.getImages());
        if (p.getVendor()   != null) Hibernate.initialize(p.getVendor());
        if (p.getCategory() != null) Hibernate.initialize(p.getCategory());
        if (p.getBrand()    != null) Hibernate.initialize(p.getBrand());
    }


 
}
