package com.sujula.service.impl;


import com.sujula.dto.request.product.ProductRequest;
import com.sujula.dto.response.product.ProductImageResponse;
import com.sujula.dto.response.product.ProductResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.products.*;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.BrandRepository;
import com.sujula.repository.product.ProductImageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.StorageService;
import com.sujula.service.VendorService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ProductCreateUpdateServiceImpl {

    private static final int MAX_IMAGES_PER_PRODUCT = 3;

    private final ProductRepository productRepository;
    private final VendorRepository vendorRepository;
    private final BrandRepository brandRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository productImageRepository;
    private final StorageService storageService;




    @PersistenceContext
    private EntityManager entityManager;
    // =====================================================================
    //  CREATE
    // =====================================================================

    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    @Transactional
    public ProductResponse create(Long vendorUserId, ProductRequest request) {
        Vendor vendor = requireActiveVendor(vendorUserId);
        validateLocation(request, vendor);
        if (productRepository.existsByVendorIdAndNameIgnoreCase(
                vendor.getId(), request.getName().trim())) {
            throw new BadRequestException(
                    "You already have a product named '" + request.getName().trim() + "'");
        }

        Brand brand = requireActiveBrand(request.getBrandId());

        Product product = new Product();
        product.setVendor(vendor);
        applyBasics(product, request, vendor, brand);
        applyAttributes(product, request);
        Map<String, ProductOption> optionsByName = applyOptions(product, request);
        applyVariants(product, request, optionsByName, null);

        Product saved = productRepository.save(product);
        return ProductResponse.from(saved);
    }

    // =====================================================================
    //  UPDATE — same rules as create, applied to an existing product
    // =====================================================================

    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductResponse update(Long vendorUserId, Long productId, ProductRequest request) {
        Vendor vendor = requireActiveVendor(vendorUserId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found with id " + productId));

        assertOwnership(product, vendor);
        validateLocation(request, vendor);

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
        return ProductResponse.from(saved);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductImageResponse addImage(Long vendorUserId, Long productId, String imageUrl,
                                         String altText, boolean makeDefault) {
        Vendor vendor = requireActiveVendor(vendorUserId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found with id " + productId));
        assertOwnership(product, vendor);

        if (!storageService.isManagedUrl(imageUrl, "products")) {
            throw new BadRequestException(
                    "imageUrl must point to an object already uploaded to the products folder");
        }

        long existingCount = productImageRepository.countByProductId(productId);
        if (existingCount >= MAX_IMAGES_PER_PRODUCT) {
            throw new BadRequestException(
                    "Maximum of " + MAX_IMAGES_PER_PRODUCT + " images allowed per product");
        }

        boolean setAsDefault = makeDefault || existingCount == 0;
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

    /** Reassigns display order: orderedImageIds must list every one of the product's images exactly once. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public List<ProductImageResponse> reorderImages(Long vendorUserId, Long productId,
                                                    List<Long> orderedImageIds) {
        Vendor vendor = requireActiveVendor(vendorUserId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found with id " + productId));
        assertOwnership(product, vendor);

        List<ProductImage> images = productImageRepository.findByProductIdOrderBySortOrderAsc(productId);
        Map<Long, ProductImage> byId = images.stream()
                .collect(Collectors.toMap(ProductImage::getId, i -> i));

        if (orderedImageIds == null
                || orderedImageIds.size() != images.size()
                || !byId.keySet().equals(new HashSet<>(orderedImageIds))) {
            throw new BadRequestException(
                    "orderedImageIds must list each of the product's images exactly once");
        }

        for (int i = 0; i < orderedImageIds.size(); i++) {
            byId.get(orderedImageIds.get(i)).setSortOrder(i);
        }

        return productImageRepository.saveAll(images).stream()
                .sorted(Comparator.comparing(ProductImage::getSortOrder))
                .map(ProductImageResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public ProductImageResponse setDefaultImage(Long vendorUserId, Long imageId) {
        Vendor vendor = requireActiveVendor(vendorUserId);
        ProductImage image = productImageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", imageId));
        assertOwnership(image.getProduct(), vendor);

        productImageRepository.clearDefaultsByProductId(image.getProduct().getId());
        image.setDefault(true);
        return ProductImageResponse.from(productImageRepository.save(image));
    }

    /** DB record is removed first; the stored object is then deleted outside the transaction boundary. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public void removeImage(Long vendorUserId, Long imageId) {
        Vendor vendor = requireActiveVendor(vendorUserId);
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

    // =====================================================================
    //  Shared building blocks
    // =====================================================================

    private Vendor requireActiveVendor(Long vendorUserId) {
        Vendor vendor = vendorRepository.findByUserId(vendorUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vendor not found for user id " + vendorUserId));
        if (!vendor.getAcive()) {
            throw new BadRequestException("Vendor account is not active");
        }
        return vendor;
    }

    private void assertOwnership(Product product, Vendor vendor) {
        if (!product.getVendor().getId().equals(vendor.getId())) {
            throw new BadRequestException("You do not own this product");
        }
    }

    private Brand requireActiveBrand(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Brand not found with id " + brandId));
        if (!brand.isActive()) {
            throw new BadRequestException("Brand '" + brand.getName() + "' is disabled");
        }
        return brand;
    }

    private void applyBasics(Product product, ProductRequest request,
                             Vendor vendor, Brand brand) {
        product.setBrand(brand);
        product.setName(request.getName().trim());
        product.setDescription(request.getDescription() != null
                ? request.getDescription().trim() : null);
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setLatitude(vendor.getLatitude());   // canonical, never the raw input
        product.setLongitude(vendor.getLongitude());
    }

    /** Request location must match the vendor's registered location exactly. */
    private void validateLocation(ProductRequest request, Vendor vendor) {
        if (!Objects.equals(request.getLatitude(), vendor.getLatitude())
                || !Objects.equals(request.getLongitude(), vendor.getLongitude())) {
            throw new BadRequestException(
                    "Location '" + request.getLatitude() + ", " + request.getLongitude()
                            + "' does not match vendor's registered location '"
                            + vendor.getLatitude() + ", " + vendor.getLongitude() + "'");
        }
    }

    /** Full-replace semantics: the request is the complete new attribute list. */
    private void applyAttributes(Product product, ProductRequest request) {
        product.getAttributes().clear(); // orphanRemoval deletes the old rows

        if (request.getAttributes() == null || request.getAttributes().isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ProductRequest.AttributeRequest attrReq : request.getAttributes()) {
            String attrName = attrReq.getName().trim();
            if (!seen.add(attrName.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Duplicate attribute name: '" + attrName + "'");
            }
            ProductAttribute attr = new ProductAttribute();
            attr.setName(attrName);
            attr.setValue(attrReq.getValue().trim());
            attr.setProduct(product);
            product.getAttributes().add(attr);
        }
    }

    /** Full-replace semantics for options + values. Returns lookup map for variants. */
    private Map<String, ProductOption> applyOptions(Product product, ProductRequest request) {
        Map<String, ProductOption> optionsByName = new LinkedHashMap<>();
        product.getOptions().clear();

        if (request.getOptions() == null || request.getOptions().isEmpty()) {
            return optionsByName;
        }
        for (ProductRequest.OptionRequest optReq : request.getOptions()) {
            String optName = optReq.getName().trim();
            if (optionsByName.containsKey(optName.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Duplicate option name: '" + optName + "'");
            }
            if (optReq.getValues() == null || optReq.getValues().isEmpty()) {
                throw new BadRequestException(
                        "Option '" + optName + "' must have at least one value");
            }

            ProductOption option = new ProductOption();
            option.setName(optName);
            option.setProduct(product);

            Set<String> seenValues = new HashSet<>();
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
                optionValue.setExtraPrice(valReq.getExtraPrice());
                optionValue.setOption(option);
                option.getValues().add(optionValue);
            }
            optionsByName.put(optName.toLowerCase(Locale.ROOT), option);
            product.getOptions().add(option);
        }
        return optionsByName;
    }

    /**
     * Full-replace semantics for variants.
     * @param existingProductId null on create; on update, used to exclude the product's
     *                          own (already deleted) variants from the global SKU check.
     */
    private void applyVariants(Product product, ProductRequest request,
                               Map<String, ProductOption> optionsByName,
                               Long existingProductId) {

        boolean hasVariants = request.getVariants() != null && !request.getVariants().isEmpty();

        if (hasVariants && optionsByName.isEmpty()) {
            throw new BadRequestException(
                    "Variants were provided but the product declares no options");
        }
        if (!hasVariants) {
            if (!optionsByName.isEmpty()) {
                product.getVariants().addAll(generateCartesian(product, optionsByName));
            }
            return;
        }

        Set<String> requestedSkus = request.getVariants().stream()
                .map(v -> v.getSku().trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> existingSkus = (existingProductId == null)
                ? variantRepository.findExistingSkus(requestedSkus)
                : variantRepository.findExistingSkusExcludingProduct(requestedSkus, existingProductId);

        Set<String> seenSkus = new HashSet<>();
        Set<String> seenCombos = new HashSet<>();

        for (ProductRequest.VariantRequest varReq : request.getVariants()) {
            String sku = varReq.getSku().trim().toUpperCase(Locale.ROOT);

            if (!seenSkus.add(sku)) {
                throw new BadRequestException("Duplicate SKU in request: '" + sku + "'");
            }
            if (existingSkus.contains(sku)) {
                throw new BadRequestException("SKU already exists: '" + sku + "'");
            }

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

            for (Map.Entry<String, ProductOption> e : optionsByName.entrySet()) {
                if (!chosenPerOption.containsKey(e.getKey())) {
                    throw new BadRequestException("Variant '" + sku
                            + "' is missing a value for option '" + e.getValue().getName() + "'");
                }
            }

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
            product.getVariants().add(variant);
        }
    }
    private List<ProductVariant> generateCartesian(Product product,
                                                   Map<String, ProductOption> optionsByName) {
        // Check the combination count before materializing it, so a product with many
        // options/values fails fast instead of building a huge list first.
        long total = 1;
        for (ProductOption option : optionsByName.values()) {
            total *= option.getValues().size();
            if (total > 200) {
                throw new BadRequestException("Too many auto-generated variants ("
                        + total + "+); send explicit variants instead");
            }
        }

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
        StringBuilder sb = new StringBuilder(slug(productName));
        for (ProductOptionValue v : combo) {
            sb.append('-').append(slug(v.getValue()));
        }
        String sku = sb.toString().toUpperCase(Locale.ROOT);
        return sku.length() <= 60 ? sku : sku.substring(0, 60);
    }

    private String slug(String s) {
        return normalize(s).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private boolean sameLocation(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
