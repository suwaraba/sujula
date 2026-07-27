package com.sujula.service.impl;

import com.sujula.dto.request.product.ProductRequest;
import com.sujula.dto.response.product.ProductResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.products.*;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.*;
import com.sujula.repository.user.VendorRepository;
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

import java.util.*;
import java.util.stream.Collectors;

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
    private final VendorRepository vendorRepository;
    private final ProductVariantRepository variantRepository;


    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return ProductResponse.from(findProductEntityById(id));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    @Transactional
    public ProductResponse create(Long vendorUserId, ProductRequest  request) {

        // ---------- 1. Vendor must exist and be active ----------
        Vendor vendor = vendorRepository.findByUserId(vendorUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vendor not found for user id " + vendorUserId));

        if (!vendor.getAcive()) {
            throw new BadRequestException("Vendor account is not active; cannot create products");
        }

        // ---------- 2. Location must match vendor's registered location ----------
        if (!request.getLatitude().equals(vendor.getLatitude()) && !request.getLongitude().equals(vendor.getLongitude())) {
            throw new BadRequestException(
                    "Location '" + request.getLatitude()+", "+request.getLongitude()
                            + "' does not match vendor's registered location '"
                            + vendor.getLatitude() +", "+vendor.getLatitude()+ "'");
        }

        // ---------- 3. No duplicate product name for the same vendor ----------
        if (productRepository.existsByVendorIdAndNameIgnoreCase(
                vendor.getId(), request.getName().trim())) {
            throw new BadRequestException(
                    "You already have a product named '" + request.getName().trim() + "'");
        }

        // ---------- 4. Brand must exist and be active ----------
        Brand brand = brandRepository.findById(request.getBrandId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Brand not found with id " + request.getBrandId()));
        if (!brand.isActive()) {
            throw new BadRequestException("Brand '" + brand.getName() + "' is disabled");
        }

        // ---------- 5. Build the product ----------
        Product product = new Product();
        product.setVendor(vendor);
        product.setBrand(brand);
        product.setName(request.getName().trim());
        product.setDescription(request.getDescription() != null
                ? request.getDescription().trim() : null);
        product.setPrice(request.getPrice());
        product.setStockQuantity(request.getStock());

        // Store the vendor's canonical location, not the raw user input
        product.setLongitude(vendor.getLongitude());
        product.setLatitude(vendor.getLatitude());

        // ---------- 6. Attributes: set them, reject duplicates ----------
        if (request.getAttributes() != null && !request.getAttributes().isEmpty()) {
            Set<String> seenAttrNames = new HashSet<>();
            List<ProductAttribute> attributes = new ArrayList<>();

            for (ProductRequest.AttributeRequest attrReq : request.getAttributes()) {
                String attrName = attrReq.getName().trim();
                if (!seenAttrNames.add(attrName.toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException(
                            "Duplicate attribute name: '" + attrName + "'");
                }
                ProductAttribute attr = new ProductAttribute();
                attr.setName(attrName);
                attr.setValue(attrReq.getValue().trim());
                attr.setProduct(product); // back-reference: required for the FK
                attributes.add(attr);
            }
            product.setAttributes(attributes);
        }


        // ---------- 7. Options: each must have values; no duplicates anywhere ----------
        Map<String, ProductOption> optionsByName = new LinkedHashMap<>();
        if (request.getOptions() != null && !request.getOptions().isEmpty()) {
            List<ProductOption> options = new ArrayList<>();

            for (ProductRequest.OptionRequest optReq : request.getOptions()) {
                String optName = optReq.getName().trim();
                if (optionsByName.containsKey(optName.toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException(
                            "Duplicate option name: '" + optName + "'");
                }

                // Defensive re-check (Bean Validation already enforces @NotEmpty,
                // but the service must never trust the caller)
                if (optReq.getValues() == null || optReq.getValues().isEmpty()) {
                    throw new BadRequestException(
                            "Option '" + optName + "' must have at least one value");
                }

                ProductOption option = new ProductOption();
                option.setName(optName);
                option.setProduct(product);

                Set<String> seenValues = new HashSet<>();
                List<ProductOptionValue> values = new ArrayList<>();
                for (ProductRequest.OptionValueRequest valReq : optReq.getValues()) {
                    String val = valReq.getValue().trim();
                    if (val.isEmpty()) {
                        throw new BadRequestException(
                                "Option '" + optName + "' contains an empty value");
                    }
                    if (!seenValues.add(val.toLowerCase(Locale.ROOT))) {
                        throw new BadRequestException(
                                "Option '" + optName + "' has duplicate value: '" + val + "'");
                    }
                    ProductOptionValue optionValue = new ProductOptionValue();
                    optionValue.setValue(val);
                    optionValue.setExtraPrice(valReq.getExtraPrice()); // may be null → treated as 0
                    optionValue.setOption(option); // back-reference
                    values.add(optionValue);
                }
                option.setValues(values);
                optionsByName.put(optName.toLowerCase(Locale.ROOT), option);
                options.add(option);
            }
            product.setOptions(options);
        }

        // ---------- 8. Variants: exactly one value per option, unique combos, unique SKUs ----------
        buildVariants(product, request, optionsByName);

        // ---------- 9. Persist (cascades save attributes, options, values, variants) ----------
        Product saved = productRepository.save(product);
        return ProductResponse.from(saved);
    }

    /**
     * Update a product. Verifies ownership and APPROVED status.
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductResponse update(Long vendorUserId, Long productId, ProductRequest request) {
        Vendor vendor = requireActiveVendor(vendorUserId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found with id " + productId));

        // Ownership: a vendor can only update their own products
        if (!product.getVendor().getId().equals(vendor.getId())) {
            throw new BadRequestException("You do not own this product");
        }
// location
        if (!request.getLatitude().equals(vendor.getLatitude()) && !request.getLongitude().equals(vendor.getLongitude())) {
            throw new BadRequestException(
                    "Location '" + request.getLatitude()+", "+request.getLongitude()
                            + "' does not match vendor's registered location '"
                            + vendor.getLatitude() +", "+vendor.getLatitude()+ "'");
        }

        // Duplicate-name check must exclude the product itself,
        // otherwise updating without renaming always fails
        if (productRepository.existsByVendorIdAndNameIgnoreCaseAndIdNot(
                vendor.getId(), request.getName().trim(), productId)) {
            throw new BadRequestException(
                    "You already have another product named '" + request.getName().trim() + "'");
        }

        Brand brand = requireActiveBrand(request.getBrandId());

        applyBasics(product, request, vendor, brand);
        applyAttributes(product, request);

        // Variants reference option values through the join table, so they must be
        // cleared BEFORE the options they point at, and the deletes must hit the DB
        // (flush) before re-inserting rows that may reuse the same SKUs
        product.getVariants().clear();
        product.getOptions().clear();
        entityManager.flush();

        Map<String, ProductOption> optionsByName = applyOptions(product, request);
        applyVariants(product, request, optionsByName, productId);

        Product saved = productRepository.save(product);
        return ProductResponse.fromEntity(saved);
    }
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

    private void buildVariants(Product product,
                               ProductRequest request,
                               Map<String, ProductOption> optionsByName) {

        boolean hasVariants = request.getVariants() != null && !request.getVariants().isEmpty();

        if (hasVariants && optionsByName.isEmpty()) {
            throw new BadRequestException(
                    "Variants were provided but the product declares no options");
        }
        if (!hasVariants) {
            if (!optionsByName.isEmpty()) {
                // No explicit variants → auto-generate the full cartesian product,
                // splitting the product stock is not guessable, so each starts at 0.
                product.setVariants(generateCartesian(product, optionsByName));
            }
            return;
        }

        Set<String> seenSkus = new HashSet<>();
        Set<String> seenCombos = new HashSet<>();
        List<ProductVariant> variants = new ArrayList<>();

        for (ProductRequest.VariantRequest varReq : request.getVariants()) {
            String sku = varReq.getSku().trim().toUpperCase(Locale.ROOT);

            if (!seenSkus.add(sku)) {
                throw new BadRequestException("Duplicate SKU in request: '" + sku + "'");
            }
            if (variantRepository.existsBySku(sku)) {
                throw new BadRequestException("SKU already exists: '" + sku + "'");
            }

            // Resolve selections against the options built above (they have no ids yet,
            // so matching is by normalized name)
            Map<String, ProductOptionValue> chosenPerOption = new LinkedHashMap<>();
            for (ProductRequest.SelectionRequest sel : varReq.getSelections()) {
                String optKey = sel.getOption().trim().toLowerCase(Locale.ROOT);
                ProductOption option = optionsByName.get(optKey);
                if (option == null) {
                    throw new BadRequestException("Variant '" + sku
                            + "' references unknown option '" + sel.getOption() + "'");
                }
                if (chosenPerOption.containsKey(optKey)) {
                    throw new BadRequestException("Variant '" + sku
                            + "' selects option '" + option.getName() + "' more than once");
                }
                ProductOptionValue match = option.getValues().stream()
                        .filter(v -> v.getValue().equalsIgnoreCase(sel.getValue().trim()))
                        .findFirst()
                        .orElseThrow(() -> new BadRequestException("Variant '" + sku
                                + "': value '" + sel.getValue()
                                + "' does not exist in option '" + option.getName() + "'"));
                chosenPerOption.put(optKey, match);
            }

            // Every declared option must be covered — a variant with Storage but
            // no Color is not a sellable combination
            for (Map.Entry<String, ProductOption> e : optionsByName.entrySet()) {
                if (!chosenPerOption.containsKey(e.getKey())) {
                    throw new BadRequestException("Variant '" + sku
                            + "' is missing a value for option '" + e.getValue().getName() + "'");
                }
            }

            // Combination must be unique across the product (order-independent key)
            String comboKey = chosenPerOption.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue().getValue().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining("|"));
            if (!seenCombos.add(comboKey)) {
                throw new BadRequestException(
                        "Duplicate variant combination: " + comboKey.replace("|", ", "));
            }

            ProductVariant variant = new ProductVariant();
            variant.setSku(sku);
            variant.setStock(varReq.getStock());
            variant.setPriceOverride(varReq.getPriceOverride());
            variant.setProduct(product);
            variant.setSelectedValues(new ArrayList<>(chosenPerOption.values()));
            variants.add(variant);
        }
        product.setVariants(variants);
    }

    private List<ProductVariant> generateCartesian(Product product,
                                                   Map<String, ProductOption> optionsByName) {
        List<List<ProductOptionValue>> combos = new ArrayList<>();
        combos.add(new ArrayList<>());
        for (ProductOption option : optionsByName.values()) {
            List<List<ProductOptionValue>> next = new ArrayList<>();
            for (List<ProductOptionValue> partial : combos) {
                for (ProductOptionValue value : option.getValues()) {
                    List<ProductOptionValue> extended = new ArrayList<>(partial);
                    extended.add(value);
                    next.add(extended);
                }
            }
            combos = next;
        }
        if (combos.size() > 200) {
            throw new BadRequestException("Too many auto-generated variants ("
                    + combos.size() + "); send explicit variants instead");
        }
        List<ProductVariant> variants = new ArrayList<>();
        for (List<ProductOptionValue> combo : combos) {
            ProductVariant v = new ProductVariant();
            v.setSku(buildSku(product.getName(), combo));
            v.setStock(0);
            v.setProduct(product);
            v.setSelectedValues(combo);
            variants.add(v);
        }
        return variants;
    }

    private String buildSku(String productName, List<ProductOptionValue> combo) {
        StringBuilder sb = new StringBuilder(Utils.toSlug(productName));
        for (ProductOptionValue v : combo) {
            sb.append('-').append(Utils.toSlug(v.getValue()));
        }
        String sku = sb.toString().toUpperCase(Locale.ROOT);
        return sku.length() <= 60 ? sku : sku.substring(0, 60);
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