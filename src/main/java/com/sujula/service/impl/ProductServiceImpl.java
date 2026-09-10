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
import com.sujula.service.ExchangeRateService;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final ExchangeRateService exchangeRateService;
    /** Holds the catalogue write rules; this service reads and delegates. */
    private final ProductCreateUpdateServiceImpl productWriteService;





    // ── Browse / search ──────────────────────────────────────────────────────
    // Every method below ranks results within DEFAULT_RADIUS_KM of (userLat,
    // userLng) ahead of everything else, then by promotion status and score
    // (see ProductRepository.RANK_ORDER) — coordinates are optional; passing
    // null lat/lng just falls back to promotion + score ordering.

    private static final double DEFAULT_RADIUS_KM = 50.0;

//    @Override
//    @Transactional(readOnly = true)
//    public Page<ProductCardResponse> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
//        return productRepository
//                .findFeaturedProducts(featured, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
//                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
//    }
//
//    @Override
//    @Transactional(readOnly = true)
//    public Page<ProductCardResponse> findNewArrivalsProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
//        return productRepository
//                .findNewArrivalsProducts(deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
//                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
//    }
//
//    @Override
//    @Transactional(readOnly = true)
//    public Page<ProductCardResponse> findBestSellersProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
//        return productRepository
//                .findBestSellersProducts(deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
//                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
//    }
//
//    @Override
//    @Transactional(readOnly = true)
//    public Page<ProductCardResponse> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
//        return productRepository
//                .findByCategoryProducts(categoryId, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
//                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
//    }
//
//    /**
//     * Products similar to {@code productId} — same category, excluding the product itself.
//     */
//    @Override
//    @Transactional(readOnly = true)
//    public Page<ProductCardResponse> findSimilarProducts(Long productId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
//        Product product = productRepository.findById(productId)
//                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
//        Category category = product.getCategory();
//        if (category == null) {
//            return Page.empty(pageable);
//        }
//        return productRepository
//                .findSimilarProducts(category.getId(), productId, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
//                .map(p -> ProductCardResponse.from(p, distanceKm(p, userLat, userLng)));
//    }
//
//    @Override
//    @Transactional(readOnly = true)
//    public Page<ProductCardResponse> searchNearUser(String query, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
//        return productRepository
//                .searchNearUser(query, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable)
//                .map(product -> ProductCardResponse.from(product, distanceKm(product, userLat, userLng)));
//    }

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
        // A variant is described by its option values, and each value by the
        // option it belongs to ("Size"), so both levels have to be resolved here
        // rather than lazily inside the response mapper.
        for (ProductVariant variant : p.getVariants()) {
            Hibernate.initialize(variant.getSelectedValues());
            for (ProductOptionValue value : variant.getSelectedValues()) {
                Hibernate.initialize(value.getOption());
            }
        }
        if (p.getVendor() != null) Hibernate.initialize(p.getVendor());
        if (p.getCategory() != null) Hibernate.initialize(p.getCategory());
        if (p.getBrand() != null) Hibernate.initialize(p.getBrand());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return ProductResponse.from(findProductEntityById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse findPublishedById(Long id) {
        Product product = findProductEntityById(id);
        if (!product.isActive()) {
            // Unpublishing is how a listing is withdrawn, and order lines still
            // point at the row, so it is never deleted. To a shopper it has to
            // look exactly like a product that was never there.
            throw new ResourceNotFoundException("Product", id);
        }
        return ProductResponse.from(product);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> findMyProducts(Long vendorUserId, Pageable pageable) {
        Vendor vendor = vendorRepository.findByUserId(vendorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor for user", vendorUserId));
        return productRepository.findByVendorIdOrderByCreatedAtDesc(vendor.getId(), pageable)
                .map(product -> {
                    initAssociations(product);
                    return ProductResponse.from(product);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse findMineById(Long vendorUserId, Long productId) {
        Vendor vendor = vendorRepository.findByUserId(vendorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor for user", vendorUserId));
        Product product = findProductEntityById(productId);
        if (product.getVendor() == null || !vendor.getId().equals(product.getVendor().getId())) {
            throw new ResourceNotFoundException("Product", productId);
        }
        return ProductResponse.from(product);
    }

    // ── Writes ───────────────────────────────────────────────────────────────
    // Delegated rather than reimplemented. The catalogue write rules — ownership,
    // duplicate names, option/variant rebuilding in the right order — live in one
    // place; these existed as stubs returning null beside them, and a caller had
    // no way to tell which of the two it had reached. Authorisation stays on the
    // delegate, which is where the vendor id is checked against the principal.
    //
    // Note the argument order differs between the two: two Longs, silently
    // swappable, so the mapping is spelled out here once.

    @Override
    public ProductResponse create(Long vendorUserId, ProductRequest request) {
        return productWriteService.create(vendorUserId, request);
    }

    @Override
    public ProductResponse update(Long productId, Long vendorUserId, ProductRequest request) {
        return productWriteService.update(vendorUserId, productId, request);
    }

    @Override
    public void delete(Long productId, Long vendorUserId) {
        productWriteService.delete(vendorUserId, productId);
    }

    @Override
    public ProductImageResponse addImage(Long productId, Long vendorUserId, String imageUrl,
                                         String altText, boolean makeDefault) {
        return productWriteService.addImage(vendorUserId, productId, imageUrl, altText, makeDefault);
    }

    @Override
    public List<ProductImageResponse> reorderImages(Long productId, Long vendorUserId,
                                                    List<Long> imageIdsInOrder) {
        return productWriteService.reorderImages(vendorUserId, productId, imageIdsInOrder);
    }

    @Override
    public void deleteImage(Long imageId, Long vendorUserId) {
        productWriteService.removeImage(vendorUserId, imageId);
    }

    @Override
    public ProductImageResponse setDefaultImage(Long imageId, Long vendorUserId) {
        return productWriteService.setDefaultImage(vendorUserId, imageId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        Page<Product> products = productRepository
                .findFeaturedProducts(featured, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable);
        Map<String, BigDecimal> rates = ratesFor(products, currency);
        return products.map(product -> toCardResponse(product, distanceKm(product, userLat, userLng), currency, rates));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findNewArrivalsProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        Page<Product> products = productRepository
                .findNewArrivalsProducts(deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable);
        Map<String, BigDecimal> rates = ratesFor(products, currency);
        return products.map(product -> toCardResponse(product, distanceKm(product, userLat, userLng), currency, rates));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findBestSellersProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        Page<Product> products = productRepository
                .findBestSellersProducts(deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable);
        Map<String, BigDecimal> rates = ratesFor(products, currency);
        return products.map(product -> toCardResponse(product, distanceKm(product, userLat, userLng), currency, rates));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        Page<Product> products = productRepository
                .findByCategoryProducts(categoryId, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable);
        Map<String, BigDecimal> rates = ratesFor(products, currency);
        return products.map(product -> toCardResponse(product, distanceKm(product, userLat, userLng), currency, rates));
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
        Page<Product> products = productRepository
                .findSimilarProducts(category.getId(), productId, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable);
        Map<String, BigDecimal> rates = ratesFor(products, currency);
        return products.map(p -> toCardResponse(p, distanceKm(p, userLat, userLng), currency, rates));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardResponse> searchNearUser(String query, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable) {
        Page<Product> products = productRepository
                .searchNearUser(query, deliveryCountry, userLat, userLng, DEFAULT_RADIUS_KM, pageable);
        Map<String, BigDecimal> rates = ratesFor(products, currency);
        return products.map(product -> toCardResponse(product, distanceKm(product, userLat, userLng), currency, rates));
    }

    /**
     * Batch-fetches the exchange rates needed to convert every product on this page into
     * {@code targetCurrency} — one query per page regardless of page size, instead of one
     * query per product.
     */
    private Map<String, BigDecimal> ratesFor(Page<Product> products, String targetCurrency) {
        if (targetCurrency == null || targetCurrency.isBlank()) {
            return Map.of();
        }
        Set<String> fromCurrencies = products.getContent().stream()
                .map(Product::getPriceCurrency)
                .filter(Objects::nonNull)
                .filter(c -> !c.equalsIgnoreCase(targetCurrency))
                .collect(Collectors.toSet());
        return exchangeRateService.getLatestRates(targetCurrency, fromCurrencies);
    }

    /**
     * Builds the card response for a product, converting its price/compareAtPrice into
     * {@code targetCurrency} via a pre-fetched rate map (see {@link #ratesFor}) — an O(1) lookup
     * per product rather than a per-product exchange-rate query. Falls back to the product's own
     * currency when no target is requested or no rate is available.
     */
    private ProductCardResponse toCardResponse(Product product, Double distanceKm, String targetCurrency, Map<String, BigDecimal> rates) {
        ProductCardResponse card = ProductCardResponse.from(product, distanceKm);
        if (targetCurrency == null || targetCurrency.isBlank()) {
            return card;
        }
        String target = targetCurrency.toUpperCase();
        String from = product.getPriceCurrency();
        if (target.equalsIgnoreCase(from)) {
            return card;
        }
        BigDecimal rate = rates.get(from);
        if (rate == null) {
            return card;
        }
        card.setPrice(product.getPrice().multiply(rate).setScale(2, RoundingMode.HALF_UP));
        if (product.getCompareAtPrice() != null) {
            card.setCompareAtPrice(product.getCompareAtPrice().multiply(rate).setScale(2, RoundingMode.HALF_UP));
        }
        card.setPriceCurrency(target);
        return card;
    }

    /** Great-circle distance (km) between the user and the product, or null if either location is unknown. */

}