package com.sujula.service.catalogue.impl;

import com.sujula.dto.request.catalogue.ProductFilter;
import com.sujula.dto.response.catalogue.CatalogueResponses;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Review;
import com.sujula.model.constant.ProductCondition;
import com.sujula.model.products.*;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.*;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.cart.RateTable;
import com.sujula.service.catalogue.BrowsingContext;
import com.sujula.service.catalogue.CatalogueService;
import com.sujula.service.delivery.Distances;
import com.sujula.service.reference.CurrencyCatalogue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The public catalogue.
 *
 * <p>Two things run through every method here.
 *
 * <p><strong>Prices are converted once, in bulk.</strong> A page of twenty
 * products can span several listing currencies, and looking a rate up per card
 * would be twenty queries for three distinct answers. The rates are fetched once
 * per page and applied from a {@link RateTable}, rounded to the buyer's currency
 * — which for CFA means a whole franc, since there is no centime to show.
 *
 * <p><strong>Ranking is against the delivery point.</strong> Never the payer's.
 * The context makes that a matter of which field is read rather than a
 * convention to remember.
 */
@Slf4j
@Service
public class CatalogueServiceImpl implements CatalogueService {

    /** Beyond this, "near the recipient" stops meaning anything useful. */
    @Value("${sujula.catalogue.proximity-radius-km:75}")
    private double proximityRadiusKm;

    /** A suggestion list nobody scrolls past. */
    private static final int MAX_SUGGESTIONS = 10;

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final VendorRepository vendors;
    private final ReviewRepository reviews;
    private final ExchangeRateService exchangeRates;
    private final CurrencyCatalogue currencies;

    public CatalogueServiceImpl(ProductRepository products, CategoryRepository categories,
                                BrandRepository brands, VendorRepository vendors,
                                ReviewRepository reviews, ExchangeRateService exchangeRates,
                                CurrencyCatalogue currencies) {
        this.products = products;
        this.categories = categories;
        this.brands = brands;
        this.vendors = vendors;
        this.reviews = reviews;
        this.exchangeRates = exchangeRates;
        this.currencies = currencies;
    }

    // ── Categories ───────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Built from one read of every category rather than a query per level. A
     * tree assembled by recursive lookup costs one query per node, which for a
     * catalogue of any size is the slowest endpoint on the site and the one hit
     * on every page.
     */
    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.CategoryTree categoryTree() {
        List<Category> all = categories.findAll().stream()
                .filter(Category::isActive)
                .toList();

        Map<Long, List<Category>> byParent = all.stream()
                .collect(Collectors.groupingBy(
                        category -> category.getParent() == null ? 0L : category.getParent().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, Long> counts = productCountsByCategory();

        List<CatalogueResponses.CategoryNode> roots = byParent.getOrDefault(0L, List.of()).stream()
                .sorted(Comparator.comparing(c -> c.getSortOrder() == null ? 0 : c.getSortOrder()))
                .map(root -> toNode(root, byParent, counts))
                .toList();

        return new CatalogueResponses.CategoryTree(roots);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.CategoryDetail categoryBySlug(String slug, BrowsingContext context) {
        Category category = categories.findBySlug(slug)
                .filter(Category::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Category", slug));

        List<Product> inCategory = products.browse(
                null, category.getId(), null, null, null, null, null, null, false,
                context.deliveryCountry(), context.deliveryLatitude(), context.deliveryLongitude(),
                proximityRadiusKm, Pageable.ofSize(500)).getContent();

        Map<Long, Long> counts = productCountsByCategory();
        Map<Long, List<Category>> byParent = categories.findAll().stream()
                .filter(Category::isActive)
                .collect(Collectors.groupingBy(
                        c -> c.getParent() == null ? 0L : c.getParent().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        return new CatalogueResponses.CategoryDetail(
                toNode(category, byParent, counts),
                ancestorsOf(category, counts),
                attributeSchema(inCategory),
                brandFacet(inCategory),
                priceRange(inCategory, context));
    }

    /**
     * The filters a category can actually offer, derived from its contents.
     *
     * <p>Derived rather than configured, so a filter can never present a value
     * nothing has. A shopper who picks "128 GB" and gets an empty page blames the
     * site, not the catalogue.
     */
    private static List<CatalogueResponses.AttributeSchema> attributeSchema(List<Product> inCategory) {
        Map<String, Set<String>> byName = new LinkedHashMap<>();
        for (Product product : inCategory) {
            for (ProductAttribute attribute : product.getAttributes()) {
                if (attribute.getName() == null || attribute.getValue() == null) {
                    continue;
                }
                byName.computeIfAbsent(attribute.getName().trim(), k -> new LinkedHashSet<>())
                        .add(attribute.getValue().trim());
            }
        }
        return byName.entrySet().stream()
                .map(entry -> new CatalogueResponses.AttributeSchema(
                        entry.getKey(), entry.getValue().stream().sorted().toList()))
                .toList();
    }

    private static List<CatalogueResponses.FacetValue> brandFacet(List<Product> inCategory) {
        Map<Brand, Long> counted = inCategory.stream()
                .filter(product -> product.getBrand() != null)
                .collect(Collectors.groupingBy(Product::getBrand, LinkedHashMap::new, Collectors.counting()));

        return counted.entrySet().stream()
                .sorted(Map.Entry.<Brand, Long>comparingByValue().reversed())
                .map(entry -> new CatalogueResponses.FacetValue(
                        entry.getKey().getSlug(), entry.getKey().getName(), entry.getValue()))
                .toList();
    }

    /** What a price slider should span — in the buyer's currency, not the vendors'. */
    private CatalogueResponses.PriceRange priceRange(List<Product> inCategory, BrowsingContext context) {
        if (inCategory.isEmpty()) {
            return new CatalogueResponses.PriceRange(context.displayCurrency(), null, null);
        }
        RateTable rates = ratesFor(inCategory, context);

        List<BigDecimal> converted = inCategory.stream()
                .map(product -> rates.convert(product.getPrice(), listingCurrency(product)))
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        if (converted.isEmpty()) {
            return new CatalogueResponses.PriceRange(context.displayCurrency(), null, null);
        }
        return new CatalogueResponses.PriceRange(context.displayCurrency(),
                converted.get(0), converted.get(converted.size() - 1));
    }

    // ── Products ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.ProductPage browse(ProductFilter filter, BrowsingContext context,
                                                 Pageable pageable) {
        Resolved resolved = resolveFilter(filter);

        Page<Product> page = products.browse(
                blankToNull(filter.query()),
                resolved.categoryId, resolved.brandId, resolved.vendorId,
                resolved.condition,
                filter.minPrice(), filter.maxPrice(), filter.minRating(), filter.inStockOnly(),
                context.deliveryCountry(),
                context.deliveryLatitude(), context.deliveryLongitude(), proximityRadiusKm,
                pageable);

        return toProductPage(page, context, resolved, filter);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.ProductDetail productBySlug(String slug, BrowsingContext context) {
        return detail(products.findPublishedBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Product", slug)), context);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.ProductDetail productById(Long productId, BrowsingContext context) {
        Product product = products.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        return detail(product, context);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogueResponses.Variant> variants(Long productId, BrowsingContext context) {
        Product product = products.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        RateTable rates = ratesFor(List.of(product), context);
        return product.getVariants().stream()
                .filter(ProductVariant::isActive)
                .map(variant -> toVariant(product, variant, rates, context))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Same category, ranked against the same delivery point, with this product
     * itself removed. Proximity matters more here than it looks: "related" on a
     * marketplace like this one mostly means "something else that can actually
     * reach the recipient".
     */
    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.ProductPage related(Long productId, BrowsingContext context,
                                                  Pageable pageable) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        Long categoryId = product.getCategory() == null ? null : product.getCategory().getId();

        Page<Product> page = products.browse(
                null, categoryId, null, null, null, null, null, null, false,
                context.deliveryCountry(), context.deliveryLatitude(), context.deliveryLongitude(),
                proximityRadiusKm, pageable);

        RateTable rates = ratesFor(page.getContent(), context);
        List<CatalogueResponses.ProductCard> cards = page.getContent().stream()
                .filter(candidate -> !candidate.getId().equals(productId))
                .map(candidate -> toCard(candidate, rates, context))
                .toList();

        return new CatalogueResponses.ProductPage(cards, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), context.displayCurrency(),
                null, appliedDelivery(context));
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.ReviewPage reviews(Long productId, Pageable pageable) {
        if (!products.existsById(productId)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        Page<Review> page = reviews.findForProduct(productId, pageable);

        Map<Integer, Long> histogram = new LinkedHashMap<>();
        long total = 0;
        long weighted = 0;
        for (Object[] row : reviews.ratingHistogram(productId)) {
            int rating = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            histogram.put(rating, count);
            total += count;
            weighted += (long) rating * count;
        }
        BigDecimal average = total == 0 ? null
                : BigDecimal.valueOf(weighted).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        return new CatalogueResponses.ReviewPage(
                page.getContent().stream().map(CatalogueServiceImpl::toReview).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                average, histogram);
    }

    // ── Stores and brands ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.Store storeBySlug(String slug) {
        return toStore(requireTradingVendor(slug));
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.ProductPage storeProducts(String slug, ProductFilter filter,
                                                        BrowsingContext context, Pageable pageable) {
        Vendor vendor = requireTradingVendor(slug);
        Resolved resolved = resolveFilter(filter);

        Page<Product> page = products.browse(
                blankToNull(filter.query()), resolved.categoryId, resolved.brandId, vendor.getId(),
                resolved.condition, filter.minPrice(), filter.maxPrice(), filter.minRating(),
                filter.inStockOnly(), context.deliveryCountry(),
                context.deliveryLatitude(), context.deliveryLongitude(), proximityRadiusKm, pageable);

        return toProductPage(page, context, resolved, filter);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.Brands brands() {
        return new CatalogueResponses.Brands(
                brands.findActiveWithProducts().stream()
                        .map(brand -> new CatalogueResponses.Brand(
                                brand.getId(), brand.getName(), brand.getSlug(),
                                brand.getLogoUrl(), brand.getWebsite()))
                        .toList());
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.Suggestions suggest(String query, BrowsingContext context, int limit) {
        String prefix = blankToNull(query);
        if (prefix == null || prefix.length() < 2) {
            // One letter matches most of the catalogue, so the suggestion is
            // noise and the query is expensive. Two is where it starts to mean
            // something.
            return new CatalogueResponses.Suggestions(query, List.of());
        }
        int capped = Math.max(1, Math.min(limit, MAX_SUGGESTIONS));
        return new CatalogueResponses.Suggestions(prefix,
                products.suggestNames(prefix.trim(), context.deliveryCountry(), capped));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Assembly
    // ─────────────────────────────────────────────────────────────────────────

    private CatalogueResponses.ProductPage toProductPage(Page<Product> page, BrowsingContext context,
                                                         Resolved resolved, ProductFilter filter) {
        RateTable rates = ratesFor(page.getContent(), context);
        List<CatalogueResponses.ProductCard> cards = page.getContent().stream()
                .map(product -> toCard(product, rates, context))
                .toList();

        return new CatalogueResponses.ProductPage(
                cards, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                context.displayCurrency(), facets(filter, resolved, context), appliedDelivery(context));
    }

    /**
     * The counts a filter panel shows beside each option.
     *
     * <p>Counted against the same filter the results used. Note the facet query
     * still receives the selected brand: excluding it would make the counts
     * describe a search the shopper is not doing.
     */
    private CatalogueResponses.Facets facets(ProductFilter filter, Resolved resolved,
                                             BrowsingContext context) {
        String query = blankToNull(filter.query());

        List<Object[]> byBrand = products.facetByBrand(query, resolved.categoryId, null,
                resolved.vendorId, resolved.condition, filter.minPrice(), filter.maxPrice(),
                filter.minRating(), filter.inStockOnly(), context.deliveryCountry());

        List<Object[]> byCategory = products.facetByCategory(query, null, resolved.brandId,
                resolved.vendorId, resolved.condition, filter.minPrice(), filter.maxPrice(),
                filter.minRating(), filter.inStockOnly(), context.deliveryCountry());

        List<Object[]> byCondition = products.facetByCondition(query, resolved.categoryId,
                resolved.brandId, resolved.vendorId, null, filter.minPrice(), filter.maxPrice(),
                filter.minRating(), filter.inStockOnly(), context.deliveryCountry());

        return new CatalogueResponses.Facets(
                labelledCategories(byCategory), labelledBrands(byBrand), labelledConditions(byCondition));
    }

    private List<CatalogueResponses.FacetValue> labelledBrands(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Brand> byId = brands.findAllById(rows.stream()
                        .map(row -> ((Number) row[0]).longValue()).toList()).stream()
                .collect(Collectors.toMap(Brand::getId, brand -> brand));

        return rows.stream()
                .map(row -> {
                    Brand brand = byId.get(((Number) row[0]).longValue());
                    return brand == null ? null : new CatalogueResponses.FacetValue(
                            brand.getSlug(), brand.getName(), ((Number) row[1]).longValue());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private List<CatalogueResponses.FacetValue> labelledCategories(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Category> byId = categories.findAllById(rows.stream()
                        .map(row -> ((Number) row[0]).longValue()).toList()).stream()
                .collect(Collectors.toMap(Category::getId, category -> category));

        return rows.stream()
                .map(row -> {
                    Category category = byId.get(((Number) row[0]).longValue());
                    return category == null ? null : new CatalogueResponses.FacetValue(
                            category.getSlug(), category.getName(), ((Number) row[1]).longValue());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static List<CatalogueResponses.FacetValue> labelledConditions(List<Object[]> rows) {
        return rows.stream()
                .filter(row -> row[0] != null)
                .map(row -> {
                    String value = String.valueOf(row[0]);
                    return new CatalogueResponses.FacetValue(
                            value, humanise(value), ((Number) row[1]).longValue());
                })
                .toList();
    }

    private static String humanise(String enumName) {
        String lower = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Echoes back which location the ranking actually used.
     *
     * <p>So a client can say "delivering to Gambia" and a developer can see, in
     * the response itself, that the catalogue was ranked against the recipient
     * rather than the payer. C1 is easier to keep when it is visible.
     */
    private static CatalogueResponses.AppliedDelivery appliedDelivery(BrowsingContext context) {
        return new CatalogueResponses.AppliedDelivery(
                context.deliveryContextId(),
                context.deliveryCountry(),
                context.hasDeliveryPoint(),
                context.hasDeliveryPoint() && !context.hasPreciseDeliveryPoint());
    }

    private CatalogueResponses.ProductDetail detail(Product product, BrowsingContext context) {
        RateTable rates = ratesFor(List.of(product), context);
        String listing = listingCurrency(product);
        BigDecimal converted = rates.convert(product.getPrice(), listing);

        return new CatalogueResponses.ProductDetail(
                product.getId(), product.getSlug(), product.getName(),
                product.getShortDescription(), product.getDescription(), product.getSku(),
                product.getCondition(),
                converted, rates.convert(product.getCompareAtPrice(), listing),
                context.displayCurrency(),
                product.getPrice(), listing, !listing.equalsIgnoreCase(context.displayCurrency()),
                product.getRating(), product.getTotalReviews(), product.getTotalSold(),
                inStock(product), product.getStock(), product.isAllowBackorder(),
                product.getDimensions(), product.getWeightKg(),
                product.getVendor() == null ? null : toStore(product.getVendor()),
                product.getBrand() == null ? null : new CatalogueResponses.Brand(
                        product.getBrand().getId(), product.getBrand().getName(),
                        product.getBrand().getSlug(), product.getBrand().getLogoUrl(),
                        product.getBrand().getWebsite()),
                product.getCategory() == null ? null : new CatalogueResponses.CategoryNode(
                        product.getCategory().getId(), product.getCategory().getName(),
                        product.getCategory().getSlug(), null, null, 0, 0, List.of()),
                product.getImages().stream()
                        .sorted(Comparator.comparing(image ->
                                image.getSortOrder() == null ? 0 : image.getSortOrder()))
                        .map(image -> new CatalogueResponses.Image(image.getId(), image.getImageUrl(),
                                image.getAltText(), image.isDefault(),
                                image.getSortOrder() == null ? 0 : image.getSortOrder()))
                        .toList(),
                product.getAttributes().stream()
                        .map(attribute -> new CatalogueResponses.Attribute(
                                attribute.getName(), attribute.getValue()))
                        .toList(),
                product.getVariants().stream()
                        .filter(ProductVariant::isActive)
                        .map(variant -> toVariant(product, variant, rates, context))
                        .toList(),
                serviceability(product, context));
    }

    /**
     * Whether this product can reach the destination, answered on the product
     * page rather than at checkout.
     *
     * <p>A diaspora buyer choosing a gift needs to know before they choose.
     * Finding out at the last step that a seller cannot ship to Serrekunda is
     * how a cart gets abandoned.
     *
     * <p>Null when no destination was given, which is not the same as "cannot
     * be delivered" — the shopper simply has not said yet.
     */
    private CatalogueResponses.Serviceability serviceability(Product product, BrowsingContext context) {
        if (!context.hasDestination()) {
            return null;
        }

        boolean countryOk = product.getDeliveryScope() != null
                && (product.getDeliveryScope().name().equals("GLOBAL")
                    || context.deliveryCountry() == null
                    || context.deliveryCountry().equalsIgnoreCase(product.getCountry()));

        BigDecimal distance = null;
        boolean estimated = true;
        if (context.hasDeliveryPoint() && product.getLatitude() != null && product.getLongitude() != null) {
            distance = BigDecimal.valueOf(Distances.haversineKm(
                            product.getLatitude(), product.getLongitude(),
                            context.deliveryLatitude(), context.deliveryLongitude()))
                    .setScale(1, RoundingMode.HALF_UP);
            estimated = false;
        }

        return new CatalogueResponses.Serviceability(
                countryOk, distance, estimated, List.of(),
                countryOk ? null
                          : "This seller does not ship to " + context.deliveryCountry() + ".");
    }

    private CatalogueResponses.ProductCard toCard(Product product, RateTable rates,
                                                  BrowsingContext context) {
        String listing = listingCurrency(product);
        Vendor vendor = product.getVendor();

        BigDecimal distance = null;
        if (context.hasDeliveryPoint() && product.getLatitude() != null && product.getLongitude() != null) {
            distance = BigDecimal.valueOf(Distances.haversineKm(
                            product.getLatitude(), product.getLongitude(),
                            context.deliveryLatitude(), context.deliveryLongitude()))
                    .setScale(1, RoundingMode.HALF_UP);
        }

        return new CatalogueResponses.ProductCard(
                product.getId(), product.getSlug(), product.getName(), product.getShortDescription(),
                defaultImage(product),
                rates.convert(product.getPrice(), listing),
                rates.convert(product.getCompareAtPrice(), listing),
                context.displayCurrency(),
                product.getPrice(), listing, !listing.equalsIgnoreCase(context.displayCurrency()),
                product.getCondition(), product.getRating(), product.getTotalReviews(),
                inStock(product),
                vendor == null ? null : vendor.getId(),
                vendor == null ? null : vendor.getStoreName(),
                vendor == null ? null : vendor.getStoreSlug(),
                product.getBrand() == null ? null : product.getBrand().getId(),
                product.getBrand() == null ? null : product.getBrand().getName(),
                context.hasDestination() ? deliverableTo(product, context) : null,
                distance);
    }

    private static Boolean deliverableTo(Product product, BrowsingContext context) {
        if (product.getDeliveryScope() != null && product.getDeliveryScope().name().equals("GLOBAL")) {
            return true;
        }
        if (context.deliveryCountry() == null) {
            return true;
        }
        return context.deliveryCountry().equalsIgnoreCase(product.getCountry());
    }

    private CatalogueResponses.Variant toVariant(Product product, ProductVariant variant,
                                                 RateTable rates, BrowsingContext context) {
        String listing = listingCurrency(product);
        BigDecimal listPrice = variant.getPriceOverride() != null
                ? variant.getPriceOverride() : product.getPrice();

        // "Storage: 256 GB, Colour: Black" — what distinguishes this variant
        // from its siblings, as a shopper reads it rather than as ids.
        Map<String, String> options = new LinkedHashMap<>();
        for (ProductOptionValue optionValue : variant.getSelectedValues()) {
            if (optionValue != null && optionValue.getOption() != null) {
                options.put(optionValue.getOption().getName(), optionValue.getDisplayValue());
            }
        }

        return new CatalogueResponses.Variant(
                variant.getId(), variant.getSku(),
                rates.convert(listPrice, listing), context.displayCurrency(),
                listPrice, listing,
                variant.getStock(),
                variant.getStock() != null && variant.getStock() > 0,
                variant.isActive(), options);
    }

    private static CatalogueResponses.Review toReview(Review review) {
        String name = review.getUser() == null ? "A customer"
                : (review.getUser().getFirstName() == null ? "A customer"
                   : review.getUser().getFirstName());
        return new CatalogueResponses.Review(
                review.getId(), name, review.getRating() == null ? 0 : review.getRating(),
                review.getTitle(), review.getComment(), review.isVerified(),
                review.getVendorReply(), review.getVendorRepliedAt(), review.getCreatedAt());
    }

    private static CatalogueResponses.Store toStore(Vendor vendor) {
        return new CatalogueResponses.Store(
                vendor.getId(), vendor.getStoreName(), vendor.getStoreSlug(),
                vendor.getDescription(), vendor.getLogoUrl(), vendor.getBannerUrl(),
                vendor.getAddressCity(), vendor.getAddressCountryCode(),
                vendor.getRating(), vendor.getTotalReviews(), vendor.getTotalSold(),
                vendor.getStatus() != null && vendor.getStatus().canTrade());
    }

    // ── Rates ────────────────────────────────────────────────────────────────

    /**
     * One rate lookup for a whole page.
     *
     * <p>A page can span several listing currencies and will usually span two or
     * three. Looking a rate up per card would be twenty queries for three
     * answers, which is how a listing page gets slow without anyone noticing in
     * development, where the seed has one currency.
     */
    private RateTable ratesFor(List<Product> page, BrowsingContext context) {
        String target = context.displayCurrency();

        Set<String> needed = page.stream()
                .map(CatalogueServiceImpl::listingCurrency)
                .filter(currency -> !currency.equalsIgnoreCase(target))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, BigDecimal> rates = needed.isEmpty()
                ? Map.of() : exchangeRates.getLatestRates(target, needed);

        // The target's own scale, so a price shown in CFA lands on a whole franc.
        return new RateTable(target, rates, currencies.minorUnits(target), java.time.LocalDateTime.now());
    }

    private static String listingCurrency(Product product) {
        return product.getPriceCurrency() == null ? "GMD" : product.getPriceCurrency();
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    private Resolved resolveFilter(ProductFilter filter) {
        Long categoryId = filter.categoryId();
        if (categoryId == null && filter.categorySlug() != null && !filter.categorySlug().isBlank()) {
            categoryId = categories.findBySlug(filter.categorySlug().trim())
                    .map(Category::getId)
                    .orElseThrow(() -> new ResourceNotFoundException("Category", filter.categorySlug()));
        }

        Long brandId = filter.brandId();
        if (brandId == null && filter.brandSlug() != null && !filter.brandSlug().isBlank()) {
            brandId = brands.findBySlugIgnoreCase(filter.brandSlug().trim())
                    .map(Brand::getId)
                    .orElseThrow(() -> new ResourceNotFoundException("Brand", filter.brandSlug()));
        }

        Long vendorId = null;
        if (filter.storeSlug() != null && !filter.storeSlug().isBlank()) {
            vendorId = requireTradingVendor(filter.storeSlug().trim()).getId();
        }

        return new Resolved(categoryId, brandId, vendorId,
                filter.condition() == null ? null : filter.condition().name());
    }

    /**
     * A storefront the public may see.
     *
     * <p>A vendor who has not been approved, or who has been suspended, is
     * reported as not found rather than as forbidden — a suspended shop should
     * not be discoverable by its slug.
     */
    private Vendor requireTradingVendor(String slug) {
        return vendors.findByStoreSlug(slug)
                .filter(vendor -> vendor.getStatus() != null && vendor.getStatus().canTrade())
                .orElseThrow(() -> new ResourceNotFoundException("Store", slug));
    }

    private Map<Long, Long> productCountsByCategory() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : products.facetByCategory(null, null, null, null, null,
                null, null, null, false, null)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private CatalogueResponses.CategoryNode toNode(Category category,
                                                   Map<Long, List<Category>> byParent,
                                                   Map<Long, Long> counts) {
        List<CatalogueResponses.CategoryNode> children =
                byParent.getOrDefault(category.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(c -> c.getSortOrder() == null ? 0 : c.getSortOrder()))
                        .map(child -> toNode(child, byParent, counts))
                        .toList();

        // A parent's count includes its children's: a shopper clicking
        // "Electronics" expects everything underneath it, not the handful of
        // products nobody filed into a subcategory.
        long own = counts.getOrDefault(category.getId(), 0L);
        long total = own + children.stream()
                .mapToLong(CatalogueResponses.CategoryNode::productCount).sum();

        return new CatalogueResponses.CategoryNode(
                category.getId(), category.getName(), category.getSlug(),
                category.getDescription(), category.getImageUrl(),
                category.getSortOrder() == null ? 0 : category.getSortOrder(),
                total, children);
    }

    private static List<CatalogueResponses.CategoryNode> ancestorsOf(Category category,
                                                                     Map<Long, Long> counts) {
        List<CatalogueResponses.CategoryNode> trail = new ArrayList<>();
        Category parent = category.getParent();
        int guard = 0;
        while (parent != null && guard++ < 10) {
            trail.add(0, new CatalogueResponses.CategoryNode(
                    parent.getId(), parent.getName(), parent.getSlug(), null, null,
                    parent.getSortOrder() == null ? 0 : parent.getSortOrder(),
                    counts.getOrDefault(parent.getId(), 0L), List.of()));
            parent = parent.getParent();
        }
        return trail;
    }

    private static boolean inStock(Product product) {
        return (product.getStock() != null && product.getStock() > 0) || product.isAllowBackorder();
    }

    private static String defaultImage(Product product) {
        return product.getImages().stream()
                .filter(ProductImage::isDefault)
                .map(ProductImage::getImageUrl)
                .findFirst()
                .orElseGet(() -> product.getImages().stream()
                        .map(ProductImage::getImageUrl)
                        .findFirst()
                        .orElse(null));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Slugs turned into ids, once, before the query. */
    private record Resolved(Long categoryId, Long brandId, Long vendorId, String condition) {}
}
