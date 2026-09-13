package com.sujula.service.vendorcatalogue.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.vendorcatalogue.VendorProductRequests;
import com.sujula.dto.response.vendorcatalogue.VendorProductResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.MediaStatus;
import com.sujula.model.constant.ProductCondition;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.products.Brand;
import com.sujula.model.products.Category;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductImage;
import com.sujula.model.products.ProductOption;
import com.sujula.model.products.ProductOptionValue;
import com.sujula.model.products.ProductTranslation;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.BrandRepository;
import com.sujula.repository.product.CategoryRepository;
import com.sujula.repository.product.ProductImageRepository;
import com.sujula.repository.product.ProductOptionRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductTranslationRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.reference.ReferenceDataService;
import com.sujula.service.vendorcatalogue.ProductLifecycle;
import com.sujula.service.vendorcatalogue.VendorCatalogueService;
import com.sujula.util.Utils;

import lombok.extern.slf4j.Slf4j;

/**
 * {@inheritDoc}
 *
 * <p>Four rules run through this class.
 *
 * <p><strong>Ownership is the query.</strong> {@link #requireOwn} resolves by id
 * and vendor together, so another seller's listing is not found rather than
 * found and refused.
 *
 * <p><strong>Nothing publishes itself.</strong> Status changes go through
 * {@link ProductLifecycle}, which owns the transition table and the derived
 * {@code active} flag. No method here sets either directly.
 *
 * <p><strong>The listing price is the payout price.</strong> Products are priced
 * in the vendor's settlement currency, enforced on every write, so the figure a
 * buyer is converted from and the figure the seller is owed are one number.
 *
 * <p><strong>Nothing a buyer bought is deleted.</strong> Archive is the default;
 * a hard delete happens only for a listing nobody ever ordered.
 */
@Slf4j
@Service
public class VendorCatalogueServiceImpl implements VendorCatalogueService {

    private final ProductRepository products;
    private final VendorRepository vendors;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final ProductOptionRepository options;
    private final ProductTranslationRepository translations;
    private final ProductLifecycle lifecycle;
    private final ReferenceDataService reference;

    @Value("${sujula.catalogue.max-images-per-product:12}")
    private int maxImages;

    public VendorCatalogueServiceImpl(ProductRepository products, VendorRepository vendors,
                                      CategoryRepository categories, BrandRepository brands,
                                      ProductVariantRepository variants, ProductImageRepository images,
                                      ProductOptionRepository options,
                                      ProductTranslationRepository translations,
                                      ProductLifecycle lifecycle, ReferenceDataService reference) {
        this.products = products;
        this.vendors = vendors;
        this.categories = categories;
        this.brands = brands;
        this.variants = variants;
        this.images = images;
        this.options = options;
        this.translations = translations;
        this.lifecycle = lifecycle;
        this.reference = reference;
    }

    // -- Reads ---------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public VendorProductResponses.Page list(Long userId, ProductStatus status, String search,
                                            boolean includeArchived, Pageable pageable) {
        Vendor vendor = requireVendor(userId);

        Page<Product> page = products.findForVendor(
                vendor.getId(), status, includeArchived, blankToNull(search), pageable);

        // The tab counts come back with the page rather than as a second round
        // trip, because a back office draws them on every render.
        Map<ProductStatus, Long> counts = new EnumMap<>(ProductStatus.class);
        for (Object[] row : products.countByStatusForVendor(vendor.getId())) {
            counts.put((ProductStatus) row[0], ((Number) row[1]).longValue());
        }

        // Two queries for the whole page rather than two lazy loads per row.
        // Twenty rows each wanting a thumbnail and a variant count is forty
        // round trips otherwise, and it stays invisible until a seller's
        // catalogue is big enough for it to hurt.
        List<Long> ids = page.getContent().stream().map(Product::getId).toList();
        Map<Long, String> leadImages = new java.util.HashMap<>();
        Map<Long, Integer> imageCounts = new java.util.HashMap<>();
        Map<Long, Integer> variantCounts = new java.util.HashMap<>();

        if (!ids.isEmpty()) {
            // Ordered default-first, so the first seen per product is its card.
            for (ProductImage image : images.findReadyForProducts(ids)) {
                leadImages.putIfAbsent(image.getProduct().getId(), image.getImageUrl());
            }
            for (Object[] row : images.countByProductIds(ids)) {
                imageCounts.put((Long) row[0], ((Number) row[1]).intValue());
            }
            for (Object[] row : variants.countByProductIds(ids)) {
                variantCounts.put((Long) row[0], ((Number) row[1]).intValue());
            }
        }

        return new VendorProductResponses.Page(
                page.getContent().stream()
                        .map(product -> toSummary(product,
                                leadImages.get(product.getId()),
                                imageCounts.getOrDefault(product.getId(), 0),
                                variantCounts.getOrDefault(product.getId(), 0)))
                        .toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                counts);
    }

    @Override
    @Transactional(readOnly = true)
    public VendorProductResponses.Detail get(Long userId, Long productId) {
        return toDetail(requireOwn(userId, productId));
    }

    // -- Writes --------------------------------------------------------------

    @Override
    @Transactional
    public VendorProductResponses.Detail create(Long userId,
                                                VendorProductRequests.CreateProduct request) {
        Vendor vendor = requireVendor(userId);
        requireCanList(vendor);

        Product product = Product.builder()
                .vendor(vendor)
                .name(request.name().trim())
                .slug(uniqueSlug(request.name()))
                .shortDescription(trimToNull(request.shortDescription()))
                .description(trimToNull(request.description()))
                .price(request.price())
                .compareAtPrice(request.compareAtPrice())
                // Not taken from the request. The listing currency and the
                // payout currency are the same thing by construction: letting a
                // seller list in EUR while banking in GMD would put an
                // unsnapshotted conversion between what a buyer is charged and
                // what the seller is owed.
                .priceCurrency(vendor.getSettlementCurrency())
                .sku(resolveSku(vendor, request.sku(), null))
                .stock(request.stock() == null ? 0 : request.stock())
                .lowStockThreshold(request.lowStockThreshold() == null ? 3 : request.lowStockThreshold())
                .allowBackorder(Boolean.TRUE.equals(request.allowBackorder()))
                .category(resolveCategory(request.categoryId()))
                .brand(resolveBrand(request.brandId()))
                .condition(request.condition() == null ? ProductCondition.NEW : request.condition())
                .weightKg(request.weightKg())
                .dimensions(trimToNull(request.dimensions()))
                .country(upper(request.country()) == null
                        ? vendor.getAddressCountryCode() : upper(request.country()))
                // Where the goods are, which is a delivery-side fact and the
                // origin every leg is priced from. Copied from the store rather
                // than asked for: a seller does not retype their own address per
                // product, and a listing whose pin disagrees with its shop is a
                // collection sent to the wrong place.
                .latitude(vendor.getPickupLatitude() != null ? vendor.getPickupLatitude() : vendor.getLatitude())
                .longitude(vendor.getPickupLongitude() != null ? vendor.getPickupLongitude() : vendor.getLongitude())
                // A DRAFT, whatever the request said. There is no field for this
                // on the way in, because a seller who could name the status
                // could name PUBLISHED.
                .status(ProductStatus.DRAFT)
                .active(false)
                .build();

        if (request.deliveryScope() != null) {
            product.setDeliveryScope(request.deliveryScope());
        }
        lifecycle.syncVisibility(product);

        Product saved = products.save(product);
        log.info("[Catalogue] Vendor {} created draft listing {} ({})",
                vendor.getId(), saved.getId(), saved.getSku());

        return toDetail(saved);
    }

    @Override
    @Transactional
    public VendorProductResponses.Saved update(Long userId, Long productId,
                                               VendorProductRequests.UpdateProduct request) {
        Product product = requireOwn(userId, productId);

        if (!product.getStatus().isEditable()) {
            throw new BadRequestException(notEditable(product.getStatus()));
        }

        // Absent means "leave it". A PATCH that blanked what it was not sent
        // would let a client changing the stock wipe the description.
        if (request.name() != null && !request.name().isBlank()) {
            // The slug stays put. It is in links buyers saved and in search
            // engines' indexes, and a rename that 404s a product page costs the
            // seller their traffic.
            product.setName(request.name().trim());
        }
        if (request.shortDescription() != null) {
            product.setShortDescription(trimToNull(request.shortDescription()));
        }
        if (request.description() != null) {
            product.setDescription(trimToNull(request.description()));
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        if (request.compareAtPrice() != null) {
            product.setCompareAtPrice(request.compareAtPrice());
        }
        if (request.sku() != null) {
            product.setSku(resolveSku(product.getVendor(), request.sku(), product.getId()));
        }
        if (request.stock() != null) {
            product.setStock(request.stock());
            product.setLastRestockedAt(LocalDateTime.now());
        }
        if (request.lowStockThreshold() != null) {
            product.setLowStockThreshold(request.lowStockThreshold());
        }
        if (request.allowBackorder() != null) {
            product.setAllowBackorder(request.allowBackorder());
        }
        if (request.categoryId() != null) {
            product.setCategory(resolveCategory(request.categoryId()));
        }
        if (request.brandId() != null) {
            product.setBrand(resolveBrand(request.brandId()));
        }
        if (request.condition() != null) {
            product.setCondition(request.condition());
        }
        if (request.deliveryScope() != null) {
            product.setDeliveryScope(request.deliveryScope());
        }
        if (request.weightKg() != null) {
            product.setWeightKg(request.weightKg());
        }
        if (request.dimensions() != null) {
            product.setDimensions(trimToNull(request.dimensions()));
        }
        if (request.country() != null) {
            product.setCountry(upper(request.country()));
        }

        // The listing currency follows the store, always. A vendor who changed
        // their settlement currency must not be left with listings priced in
        // the old one, quietly paying out at a rate nobody agreed.
        product.setPriceCurrency(product.getVendor().getSettlementCurrency());

        boolean sentBack = false;
        String message = "Saved.";

        if (product.getStatus().isApproved() && lifecycle.contentChanged(product)) {
            // What was approved is not what is there now. Back to the queue and
            // off sale: the alternative is approval at one price followed by a
            // quiet rise, which is the commonest listing fraud there is.
            lifecycle.transition(product, ProductStatus.IN_REVIEW);
            sentBack = true;
            message = "Saved. Because the price or the description changed, this listing has gone "
                    + "back for review and is off sale until we have looked at it.";
        } else if (product.getStatus() == ProductStatus.REJECTED) {
            // Editing a refused listing is the seller acting on the reason, so
            // it stops being a refusal and becomes a draft again.
            lifecycle.transition(product, ProductStatus.DRAFT);
            message = "Saved. Send it for review again when you are ready.";
        }

        lifecycle.syncVisibility(product);
        Product saved = products.save(product);

        log.info("[Catalogue] Vendor {} edited listing {}{}",
                saved.getVendor().getId(), productId, sentBack ? " - sent back for review" : "");

        return new VendorProductResponses.Saved(
                saved.getId(), saved.getStatus(), saved.isActive(), sentBack, message);
    }

    @Override
    @Transactional
    public VendorProductResponses.Removed remove(Long userId, Long productId) {
        Product product = requireOwn(userId, productId);

        if (product.getStatus() == ProductStatus.ARCHIVED) {
            return new VendorProductResponses.Removed(productId, false, ProductStatus.ARCHIVED,
                    "This listing was already archived.");
        }

        // The question that decides archive from delete, asked of the order
        // lines rather than of the product's own counters: totalSold is a
        // denormalised figure and a cancelled order can leave it at zero while
        // an order line still points here.
        if (products.hasBeenOrdered(productId)) {
            lifecycle.transition(product, ProductStatus.ARCHIVED);
            products.save(product);
            log.info("[Catalogue] Listing {} archived - it has been ordered", productId);
            return new VendorProductResponses.Removed(productId, false, ProductStatus.ARCHIVED,
                    "Archived. It cannot be deleted outright because customers have ordered it, "
                    + "and their receipts have to keep working.");
        }

        products.delete(product);
        log.info("[Catalogue] Listing {} deleted - never ordered", productId);
        return new VendorProductResponses.Removed(productId, true, null, "Deleted.");
    }

    // -- Lifecycle -----------------------------------------------------------

    @Override
    @Transactional
    public VendorProductResponses.Saved submitForReview(Long userId, Long productId,
                                                        VendorProductRequests.SubmitForReview request) {
        Product product = requireOwn(userId, productId);
        requireCanList(product.getVendor());
        requireListable(product);

        lifecycle.transition(product, ProductStatus.IN_REVIEW);
        products.save(product);

        log.info("[Catalogue] Listing {} sent for review", productId);
        return new VendorProductResponses.Saved(productId, product.getStatus(), product.isActive(),
                true, "Sent for review. We will let you know as soon as we have looked at it.");
    }

    @Override
    @Transactional
    public VendorProductResponses.Saved publish(Long userId, Long productId) {
        Product product = requireOwn(userId, productId);
        requireCanList(product.getVendor());
        requireListable(product);

        // The guard that makes moderation real. Without it, "publish" on a draft
        // is a way past the queue.
        if (!product.getStatus().isApproved()) {
            throw new BadRequestException(product.getStatus() == ProductStatus.IN_REVIEW
                    ? "We are still looking at this listing. It can go on sale once it is approved."
                    : "This listing has not been approved yet. Send it for review first.");
        }

        lifecycle.transition(product, ProductStatus.PUBLISHED);
        products.save(product);

        log.info("[Catalogue] Listing {} published", productId);
        return new VendorProductResponses.Saved(productId, product.getStatus(), product.isActive(),
                false, "On sale.");
    }

    @Override
    @Transactional
    public VendorProductResponses.Saved unpublish(Long userId, Long productId) {
        Product product = requireOwn(userId, productId);

        if (product.getStatus() != ProductStatus.PUBLISHED) {
            return new VendorProductResponses.Saved(productId, product.getStatus(),
                    product.isActive(), false, "This listing is not on sale.");
        }

        lifecycle.transition(product, ProductStatus.UNPUBLISHED);
        products.save(product);

        log.info("[Catalogue] Listing {} unpublished", productId);
        return new VendorProductResponses.Saved(productId, product.getStatus(), product.isActive(),
                false, "Taken off sale. It keeps its approval, so you can put it back up.");
    }

    // -- Variants ------------------------------------------------------------

    @Override
    @Transactional
    public VendorProductResponses.Variant addVariant(Long userId, Long productId,
                                                     VendorProductRequests.CreateVariant request) {
        Product product = requireOwn(userId, productId);
        requireEditable(product);

        List<ProductOptionValue> values = resolveValues(product, request.optionValueIds());
        requireCombinationFree(product, values, null);

        ProductVariant variant = ProductVariant.builder()
                .product(product)
                .sku(resolveVariantSku(request.sku(), null))
                .stock(request.stock() == null ? 0 : request.stock())
                .priceOverride(request.priceOverride())
                .active(true)
                .selectedValues(new ArrayList<>(values))
                .build();

        ProductVariant saved = variants.save(variant);
        log.info("[Catalogue] Variant {} added to listing {}", saved.getId(), productId);
        return toVariant(product, saved);
    }

    @Override
    @Transactional
    public VendorProductResponses.Variant updateVariant(Long userId, Long productId, Long variantId,
                                                        VendorProductRequests.UpdateVariant request) {
        Product product = requireOwn(userId, productId);
        requireEditable(product);
        ProductVariant variant = requireVariant(product, variantId);

        if (request.sku() != null) {
            variant.setSku(resolveVariantSku(request.sku(), variant.getId()));
        }
        if (request.stock() != null) {
            variant.setStock(request.stock());
        }
        if (request.priceOverride() != null) {
            variant.setPriceOverride(request.priceOverride());
        }
        if (request.active() != null) {
            variant.setActive(request.active());
        }
        if (request.optionValueIds() != null && !request.optionValueIds().isEmpty()) {
            List<ProductOptionValue> values = resolveValues(product, request.optionValueIds());
            requireCombinationFree(product, values, variant.getId());
            variant.getSelectedValues().clear();
            variant.getSelectedValues().addAll(values);
        }

        ProductVariant saved = variants.save(variant);
        log.info("[Catalogue] Variant {} of listing {} updated", variantId, productId);
        return toVariant(product, saved);
    }

    @Override
    @Transactional
    public VendorProductResponses.Removed removeVariant(Long userId, Long productId, Long variantId) {
        Product product = requireOwn(userId, productId);
        ProductVariant variant = requireVariant(product, variantId);

        // Same rule as the listing itself, and asked the same way: of the order
        // lines, not of the product's sold counter. A listing can have sold
        // plenty without this variant ever moving, and deactivating a variant
        // nobody bought leaves dead rows in the seller's option list for ever.
        if (variants.hasBeenOrdered(variantId)) {
            variant.setActive(false);
            variants.save(variant);
            return new VendorProductResponses.Removed(variantId, false, product.getStatus(),
                    "Turned off. It cannot be deleted outright because this listing has sold.");
        }

        variants.delete(variant);
        log.info("[Catalogue] Variant {} of listing {} deleted", variantId, productId);
        return new VendorProductResponses.Removed(variantId, true, product.getStatus(), "Deleted.");
    }

    /**
     * Resolves option-value ids, refusing anything that is not this product's.
     *
     * <p>The check that matters is the last one: exactly one value per option.
     * A variant naming two colours is not a variant of anything, and one naming
     * no size cannot be told apart from another that does - which is how a
     * basket ends up holding a thing nobody can pick from a shelf.
     */
    private List<ProductOptionValue> resolveValues(Product product, List<Long> valueIds) {
        List<ProductOption> productOptions = options.findByProductIdOrderBySortOrderAsc(product.getId());
        Map<Long, ProductOptionValue> byId = productOptions.stream()
                .flatMap(option -> option.getValues().stream())
                .collect(Collectors.toMap(ProductOptionValue::getId, value -> value));

        List<ProductOptionValue> resolved = new ArrayList<>();
        for (Long valueId : valueIds) {
            ProductOptionValue value = byId.get(valueId);
            if (value == null) {
                throw new BadRequestException(
                        "Option value " + valueId + " does not belong to this listing.");
            }
            resolved.add(value);
        }

        Set<Long> optionsCovered = resolved.stream()
                .map(value -> value.getOption().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (optionsCovered.size() != resolved.size()) {
            throw new BadRequestException(
                    "A variant can only pick one value per option.");
        }
        if (optionsCovered.size() != productOptions.size()) {
            String missing = productOptions.stream()
                    .filter(option -> !optionsCovered.contains(option.getId()))
                    .map(ProductOption::getName)
                    .collect(Collectors.joining(", "));
            throw new BadRequestException(
                    "This variant has to say which " + missing + " it is.");
        }
        return resolved;
    }

    /**
     * Refuses a combination the product already has.
     *
     * <p>Two variants that are both "Large, Red" cannot be told apart by anyone:
     * not the buyer choosing one, not the picker in the shop, and not the stock
     * count, which then belongs to neither.
     */
    private void requireCombinationFree(Product product, List<ProductOptionValue> values,
                                        Long exceptVariantId) {
        Set<Long> wanted = values.stream().map(ProductOptionValue::getId)
                .collect(Collectors.toCollection(HashSet::new));

        boolean clash = variants.findByProductId(product.getId()).stream()
                .filter(existing -> !existing.getId().equals(exceptVariantId))
                .anyMatch(existing -> existing.getSelectedValues().stream()
                        .map(ProductOptionValue::getId)
                        .collect(Collectors.toSet())
                        .equals(wanted));

        if (clash) {
            throw new BadRequestException(
                    "You already have a variant with that combination.");
        }
    }

    private String resolveVariantSku(String requested, Long exceptVariantId) {
        String sku = trimToNull(requested);
        if (sku == null) {
            return "VAR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        }
        boolean taken = variants.findBySku(sku)
                .filter(existing -> !existing.getId().equals(exceptVariantId))
                .isPresent();
        if (taken) {
            throw new BadRequestException("The variant code " + sku + " is already in use.");
        }
        return sku;
    }

    private ProductVariant requireVariant(Product product, Long variantId) {
        return variants.findByProductId(product.getId()).stream()
                .filter(variant -> variant.getId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Variant", variantId));
    }

    // -- Media ---------------------------------------------------------------

    @Override
    @Transactional
    public VendorProductResponses.Media confirmMedia(Long userId, Long productId,
                                                     VendorProductRequests.ConfirmMedia request) {
        Product product = requireOwn(userId, productId);
        requireEditable(product);

        List<ProductImage> existing = images.findByProductIdOrderBySortOrderAsc(productId);
        if (existing.size() >= maxImages) {
            throw new BadRequestException(
                    "This listing already has " + maxImages + " images, which is the limit.");
        }

        boolean first = existing.isEmpty();
        ProductImage image = ProductImage.builder()
                .product(product)
                .imageUrl(request.fileUrl().trim())
                .altText(trimToNull(request.altText()))
                .originalFilename(trimToNull(request.originalFilename()))
                .contentType(trimToNull(request.contentType()))
                .sizeBytes(request.sizeBytes())
                .sortOrder(existing.size())
                // The first image becomes the default whether or not anybody
                // asked: a listing whose card has no picture is a listing
                // nobody clicks.
                .isDefault(first || Boolean.TRUE.equals(request.makeDefault()))
                .status(MediaStatus.READY)
                .confirmedAt(LocalDateTime.now())
                .build();

        if (image.isDefault() && !first) {
            existing.forEach(other -> other.setDefault(false));
            images.saveAll(existing);
        }

        ProductImage saved = images.save(image);
        log.info("[Catalogue] Image {} confirmed on listing {}", saved.getId(), productId);
        return toMedia(saved);
    }

    @Override
    @Transactional
    public List<VendorProductResponses.Media> reorderMedia(
            Long userId, Long productId, VendorProductRequests.ReorderMedia request) {

        Product product = requireOwn(userId, productId);
        requireEditable(product);

        List<ProductImage> existing = images.findByProductIdOrderBySortOrderAsc(productId);
        Map<Long, ProductImage> byId = existing.stream()
                .collect(Collectors.toMap(ProductImage::getId, image -> image));

        // Every id, exactly once. A partial order would leave the images not
        // mentioned with positions that collide with the ones that were, and
        // the gallery would render in an order nobody chose.
        if (request.mediaIdsInOrder().size() != existing.size()
                || !byId.keySet().equals(new HashSet<>(request.mediaIdsInOrder()))) {
            throw new BadRequestException(
                    "List every image on this listing exactly once, in the order you want them.");
        }

        int position = 0;
        for (Long mediaId : request.mediaIdsInOrder()) {
            ProductImage image = byId.get(mediaId);
            image.setSortOrder(position);
            // The first image is the card image. Reordering is how a seller
            // changes it, so the two cannot be allowed to disagree.
            image.setDefault(position == 0);
            position++;
        }
        images.saveAll(byId.values());

        return byId.values().stream()
                .sorted(Comparator.comparing(ProductImage::getSortOrder))
                .map(VendorCatalogueServiceImpl::toMedia)
                .toList();
    }

    @Override
    @Transactional
    public void removeMedia(Long userId, Long productId, Long mediaId) {
        Product product = requireOwn(userId, productId);
        requireEditable(product);

        List<ProductImage> existing = images.findByProductIdOrderBySortOrderAsc(productId);
        ProductImage target = existing.stream()
                .filter(image -> image.getId().equals(mediaId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Image", mediaId));

        images.delete(target);
        existing.remove(target);

        // Close the gap, and hand the default to whatever is now first. A
        // listing whose default image was just deleted would otherwise have no
        // card image at all.
        int position = 0;
        for (ProductImage image : existing) {
            image.setSortOrder(position);
            image.setDefault(position == 0);
            position++;
        }
        images.saveAll(existing);

        log.info("[Catalogue] Image {} removed from listing {}", mediaId, productId);
    }

    // -- Translations --------------------------------------------------------

    @Override
    @Transactional
    public VendorProductResponses.Translation putTranslation(
            Long userId, Long productId, String locale,
            VendorProductRequests.PutTranslation request) {

        Product product = requireOwn(userId, productId);
        String tag = requireSupportedLocale(locale);

        ProductTranslation translation = translations
                .findByProductIdAndLocaleIgnoreCase(productId, tag)
                .orElseGet(() -> ProductTranslation.builder()
                        .product(product)
                        .locale(tag)
                        .build());

        // Blank fields fall back to the product's own. A partial translation is
        // useful - a translated name beside the original description beats
        // neither - so nothing here is required.
        translation.setName(trimToNull(request.name()));
        translation.setShortDescription(trimToNull(request.shortDescription()));
        translation.setDescription(trimToNull(request.description()));
        translation.setMachineTranslated(Boolean.TRUE.equals(request.machineTranslated()));

        ProductTranslation saved = translations.save(translation);
        log.info("[Catalogue] Listing {} translated into {}", productId, tag);

        return new VendorProductResponses.Translation(
                saved.getLocale(), saved.getName(), saved.getShortDescription(),
                saved.getDescription(), saved.isMachineTranslated(), saved.getUpdatedAt());
    }

    /**
     * Checks the locale against the ones this platform actually serves.
     *
     * <p>Free-form locales would let a seller write a translation nothing ever
     * reads: "fr_SN" and "french" both look reasonable and neither matches what
     * the catalogue asks for.
     */
    private String requireSupportedLocale(String locale) {
        String tag = trimToNull(locale);
        if (tag == null) {
            throw new BadRequestException("Name a locale, such as fr-SN.");
        }
        return reference.requireLocale(tag);
    }

    // -- Helpers -------------------------------------------------------------

    private Vendor requireVendor(Long userId) {
        return vendors.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Store", "you do not have a store yet"));
    }

    private Product requireOwn(Long userId, Long productId) {
        Vendor vendor = requireVendor(userId);
        return products.findByIdAndVendorId(productId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    /**
     * Whether this store may put anything on sale at all.
     *
     * <p>Read from the store's standing rather than the seller's role: a vendor
     * whose documents have not been accepted has the VENDOR role and cannot sell
     * a thing, and a back office that let them build a catalogue they can never
     * publish would be wasting their evening.
     */
    private static void requireCanList(Vendor vendor) {
        if (vendor.getStatus() == null || !vendor.getStatus().canTrade()) {
            throw new BadRequestException(
                    "Your store has not been approved yet, so listings cannot go on sale. "
                    + "Finish verification first.");
        }
    }

    /** What a listing needs before anybody should be asked to look at it. */
    private static void requireListable(Product product) {
        if (product.getPrice() == null || product.getPrice().signum() <= 0) {
            throw new BadRequestException("Give this listing a price before sending it for review.");
        }
        if (product.getCategory() == null) {
            throw new BadRequestException("Choose a category before sending it for review.");
        }
        if (blankToNull(product.getDescription()) == null) {
            // The buyer cannot pick the item up and look at it. The description
            // is the whole of what they get.
            throw new BadRequestException(
                    "Write a description before sending it for review. Buyers here are often "
                    + "thousands of miles from the goods, and this is all they have to go on.");
        }
    }

    private Category resolveCategory(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categories.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));
    }

    private Brand resolveBrand(Long brandId) {
        if (brandId == null) {
            return null;
        }
        return brands.findById(brandId)
                .orElseThrow(() -> new ResourceNotFoundException("Brand", brandId));
    }

    /**
     * A SKU that is this seller's own and nobody else's.
     *
     * <p>Unique per vendor rather than globally: two shops both selling
     * "IPH-14-128" is normal, and a global constraint would make the second
     * seller rename a code that is printed on their own shelves.
     */
    private String resolveSku(Vendor vendor, String requested, Long exceptProductId) {
        String sku = trimToNull(requested);
        if (sku == null) {
            return "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        }
        boolean taken = exceptProductId == null
                ? products.existsBySkuIgnoreCaseAndVendorId(sku, vendor.getId())
                : products.existsBySkuForVendorExcept(sku, vendor.getId(), exceptProductId);
        if (taken) {
            throw new BadRequestException(
                    "You already have a listing with the code " + sku + ".");
        }
        return sku;
    }

    private String uniqueSlug(String name) {
        String base = Utils.toSlug(name);
        if (base == null || base.isBlank()) {
            base = "listing";
        }
        // Suffixed unconditionally rather than only on a clash. Slugs are global
        // and checking then inserting is a race two sellers publishing at once
        // would lose; a suffix costs four characters and cannot collide.
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String notEditable(ProductStatus status) {
        return switch (status) {
            case IN_REVIEW -> "This listing is being reviewed. Wait for the decision, or withdraw "
                    + "it, before changing it - otherwise we would be deciding about text that no "
                    + "longer exists.";
            case SUSPENDED -> "This listing was taken down by us and cannot be edited. "
                    + "Contact support.";
            case ARCHIVED -> "This listing is archived.";
            default -> "This listing cannot be edited at the moment.";
        };
    }

    /** Refuses an edit to a listing a moderator is currently holding. */
    private static void requireEditable(Product product) {
        if (!product.getStatus().isEditable()) {
            throw new BadRequestException(notEditable(product.getStatus()));
        }
    }

    // ------------------------------------------------------------------------
    //  Assembly
    // ------------------------------------------------------------------------

    /**
     * One row of the seller's list.
     *
     * <p>The counts and the thumbnail are passed in rather than read off the
     * entity's collections, which are lazy: touching them here would turn one
     * page into forty queries.
     */
    private static VendorProductResponses.Summary toSummary(Product product, String leadImage,
                                                            int imageCount, int variantCount) {
        int stock = product.getStock() == null ? 0 : product.getStock();
        int threshold = product.getLowStockThreshold() == null ? 0 : product.getLowStockThreshold();

        return new VendorProductResponses.Summary(
                product.getId(), product.getName(), product.getSlug(), product.getSku(),
                product.getStatus(), product.isActive(),
                product.getPrice(), product.getPriceCurrency(),
                stock, stock <= threshold,
                leadImage, variantCount, imageCount,
                blockedReason(product),
                product.getUpdatedAt());
    }

    private VendorProductResponses.Detail toDetail(Product product) {
        List<ProductImage> productImages = images.findByProductIdOrderBySortOrderAsc(product.getId());
        List<ProductVariant> productVariants = variants.findByProductId(product.getId());
        List<ProductOption> productOptions = options.findByProductIdOrderBySortOrderAsc(product.getId());

        return new VendorProductResponses.Detail(
                product.getId(), product.getName(), product.getSlug(),
                product.getShortDescription(), product.getDescription(),
                product.getStatus(), product.isActive(), toModeration(product),
                product.getPrice(), product.getCompareAtPrice(), product.getPriceCurrency(),
                product.getSku(), product.getStock(), product.getLowStockThreshold(),
                product.isAllowBackorder(),
                product.getCategory() == null ? null : product.getCategory().getId(),
                product.getCategory() == null ? null : product.getCategory().getName(),
                product.getBrand() == null ? null : product.getBrand().getId(),
                product.getBrand() == null ? null : product.getBrand().getName(),
                product.getCondition(), product.getDeliveryScope(),
                product.getWeightKg(), product.getDimensions(), product.getCountry(),
                productImages.stream().map(VendorCatalogueServiceImpl::toMedia).toList(),
                productVariants.stream().map(variant -> toVariant(product, variant)).toList(),
                productOptions.stream()
                        .map(option -> new VendorProductResponses.OptionSummary(
                                option.getId(), option.getCode(), option.getName(),
                                option.getValues().stream()
                                        .map(value -> new VendorProductResponses.VariantValue(
                                                value.getId(), option.getName(),
                                                value.getDisplayValue() != null
                                                        ? value.getDisplayValue() : value.getValue()))
                                        .toList()))
                        .toList(),
                translations.findByProductIdOrderByLocaleAsc(product.getId()).stream()
                        .map(translation -> new VendorProductResponses.TranslationSummary(
                                translation.getLocale(), translation.getName(),
                                translation.isMachineTranslated(), translation.getUpdatedAt()))
                        .toList(),
                product.getTotalSold(), product.getRating(), product.getTotalReviews(),
                product.getCreatedAt(), product.getUpdatedAt());
    }

    /**
     * Where the listing stands, and what the seller can do about it next.
     *
     * <p>{@code editsNeedReview} is computed rather than remembered, so a seller
     * sees that their pending changes will take the listing off sale before they
     * publish rather than after.
     */
    private VendorProductResponses.Moderation toModeration(Product product) {
        ProductStatus status = product.getStatus();
        return new VendorProductResponses.Moderation(
                status.isEditable(),
                status.allowedNext().contains(ProductStatus.IN_REVIEW),
                status.allowedNext().contains(ProductStatus.PUBLISHED),
                status.isApproved() && lifecycle.contentChanged(product),
                product.getRejectionReason(),
                product.getSubmittedForReviewAt(), product.getReviewedAt(),
                product.getPublishedAt(), product.getUnpublishedAt(), product.getArchivedAt());
    }

    /** Why a buyer cannot see this listing today, in one line, or null when they can. */
    private static String blockedReason(Product product) {
        return switch (product.getStatus()) {
            case DRAFT -> "Not finished. Send it for review when it is ready.";
            case IN_REVIEW -> "With us for review.";
            case APPROVED -> "Approved and not yet on sale. Publish it when you are ready.";
            case UNPUBLISHED -> "You took this off sale.";
            case REJECTED -> product.getRejectionReason() == null
                    ? "Not accepted." : "Not accepted: " + product.getRejectionReason();
            case SUSPENDED -> "Taken down by us. Contact support.";
            case ARCHIVED -> "Archived.";
            case PUBLISHED -> null;
        };
    }

    private static VendorProductResponses.Media toMedia(ProductImage image) {
        return new VendorProductResponses.Media(
                image.getId(), image.getImageUrl(), image.getAltText(),
                image.getSortOrder() == null ? 0 : image.getSortOrder(),
                image.isDefault(), image.getStatus(),
                image.getOriginalFilename(), image.getSizeBytes());
    }

    private static VendorProductResponses.Variant toVariant(Product product, ProductVariant variant) {
        BigDecimal effective = variant.getPriceOverride() != null
                ? variant.getPriceOverride()
                : product.getPrice();

        return new VendorProductResponses.Variant(
                variant.getId(), variant.getSku(), variant.getStock(), variant.getPriceOverride(),
                effective, variant.isActive(),
                variant.getSelectedValues() == null ? List.of()
                        : variant.getSelectedValues().stream()
                                .map(value -> new VendorProductResponses.VariantValue(
                                        value.getId(),
                                        value.getOption() == null ? null : value.getOption().getName(),
                                        value.getDisplayValue() != null
                                                ? value.getDisplayValue() : value.getValue()))
                                .toList());
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String blankToNull(String value) {
        return trimToNull(value);
    }

    static String upper(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
