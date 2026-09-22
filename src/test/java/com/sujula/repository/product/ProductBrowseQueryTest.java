package com.sujula.repository.product;

import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ProductCondition;
import com.sujula.model.constant.UserRole;
import com.sujula.model.products.Brand;
import com.sujula.model.products.Category;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The browse and facet queries, executed rather than merely parsed.
 *
 * <p>These are native SQL, which the context-load test cannot check. Spring Data
 * validates JPQL at startup and hands native strings straight to the driver, so
 * a typo, a reserved word or a column that does not exist surfaces on the first
 * request instead — which in practice means in production.
 *
 * <p>{@code condition} is why this file exists. It is reserved in MySQL and not
 * in H2, so the column is mapped as {@code product_condition}. Had it been left
 * as the obvious name, every test here would still pass and the real database
 * would reject the schema.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductBrowseQueryTest {

    // Serrekunda — where the goods are going.
    private static final double DEST_LAT = 13.4383, DEST_LNG = -16.6781;
    private static final double RADIUS_KM = 75;

    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private CategoryRepository categories;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;

    private Long phonesId;
    private Long samsungId;

    @BeforeEach
    void seed() {
        User owner = users.save(User.builder()
                .email("lamin-" + System.nanoTime() + "@example.gm")
                .password("x").firstName("Lamin").lastName("Touray")
                .role(UserRole.VENDOR).enabled(true).emailVerified(true).phoneVerified(true)
                .build());

        Vendor kombo = vendors.save(Vendor.builder()
                .user(owner).storeName("Kombo Electronics")
                .storeSlug("kombo-" + System.nanoTime())
                .settlementCurrency("GMD").status(PartnerStatus.APPROVED)
                .latitude(DEST_LAT).longitude(DEST_LNG).addressCountryCode("GM")
                .build());

        Category phones = categories.save(Category.builder()
                .name("Phones").slug("phones-" + System.nanoTime()).active(true).sortOrder(1).build());
        phonesId = phones.getId();

        Brand samsung = brands.save(Brand.builder()
                .name("Samsung").slug("samsung-" + System.nanoTime()).active(true).sortOrder(1).build());
        samsungId = samsung.getId();

        products.save(product("Galaxy A16", "galaxy-a16", kombo, phones, samsung,
                new BigDecimal("9700"), ProductCondition.NEW, 5, DEST_LAT, DEST_LNG,
                new BigDecimal("4.5"), "GM"));
        products.save(product("Galaxy A05 refurbished", "galaxy-a05-refurb", kombo, phones, samsung,
                new BigDecimal("2900"), ProductCondition.REFURBISHED, 2, DEST_LAT, DEST_LNG,
                new BigDecimal("3.8"), "GM"));
        products.save(product("Sold out handset", "sold-out", kombo, phones, samsung,
                new BigDecimal("5000"), ProductCondition.NEW, 0, DEST_LAT, DEST_LNG,
                new BigDecimal("4.0"), "GM"));
        products.save(product("London handset", "london-handset", kombo, phones, null,
                new BigDecimal("50000"), ProductCondition.USED, 3, 51.5237, -0.1585,
                new BigDecimal("2.0"), "GB"));

        Product hidden = product("Unpublished", "unpublished", kombo, phones, samsung,
                new BigDecimal("1000"), ProductCondition.NEW, 9, DEST_LAT, DEST_LNG,
                new BigDecimal("5.0"), "GM");
        hidden.setActive(false);
        products.save(hidden);
    }

    private static Product product(String name, String slug, Vendor vendor, Category category,
                                   Brand brand, BigDecimal price, ProductCondition condition,
                                   int stock, double lat, double lng, BigDecimal rating,
                                   String country) {
        return Product.builder()
                .name(name).slug(slug).vendor(vendor).category(category).brand(brand)
                .price(price).priceCurrency("GMD").stock(stock).condition(condition)
                .active(true).latitude(lat).longitude(lng).country(country)
                .deliveryScope(DeliveryScope.REGIIONAL).rating(rating).score(10)
                .build();
    }

    private Page<Product> browse(String query, Long categoryId, Long brandId,
                                 ProductCondition condition, BigDecimal minPrice,
                                 BigDecimal maxPrice, BigDecimal minRating, boolean inStockOnly,
                                 String deliveryCountry) {
        return products.browse(query, categoryId, brandId, null,
                condition == null ? null : condition.name(),
                minPrice, maxPrice, minRating, inStockOnly,
                deliveryCountry, DEST_LAT, DEST_LNG, RADIUS_KM, PageRequest.of(0, 20));
    }

    // ── The query runs at all ────────────────────────────────────────────────

    @Test
    void theBrowseQueryExecutes() {
        Page<Product> page = browse(null, null, null, null, null, null, null, false, null);

        assertTrue(page.getTotalElements() >= 4);
        assertTrue(page.getContent().stream().noneMatch(p -> p.getSlug().equals("unpublished")),
                "an unpublished listing must never reach a public browse");
    }

    // ── Each filter ──────────────────────────────────────────────────────────

    @Test
    void filtersByCondition() {
        Page<Product> refurbished =
                browse(null, null, null, ProductCondition.REFURBISHED, null, null, null, false, null);

        assertEquals(1, refurbished.getTotalElements());
        assertEquals("galaxy-a05-refurb", refurbished.getContent().get(0).getSlug());
    }

    @Test
    void filtersByPriceRange() {
        Page<Product> cheap = browse(null, null, null, null,
                new BigDecimal("1000"), new BigDecimal("6000"), null, false, null);

        assertTrue(cheap.getContent().stream().allMatch(
                p -> p.getPrice().compareTo(new BigDecimal("6000")) <= 0));
        assertTrue(cheap.getTotalElements() >= 2);
    }

    @Test
    void filtersByRating() {
        Page<Product> good = browse(null, null, null, null, null, null,
                new BigDecimal("4.0"), false, null);

        assertTrue(good.getContent().stream().allMatch(
                p -> p.getRating().compareTo(new BigDecimal("4.0")) >= 0));
    }

    /** A vendor who accepts backorders has said the goods are buyable. */
    @Test
    void inStockOnlyExcludesSoldOut() {
        Page<Product> available = browse(null, null, null, null, null, null, null, true, null);

        assertTrue(available.getContent().stream().noneMatch(p -> p.getSlug().equals("sold-out")));
        assertTrue(available.getTotalElements() >= 3);
    }

    @Test
    void filtersByBrandAndCategory() {
        assertTrue(browse(null, null, samsungId, null, null, null, null, false, null)
                .getContent().stream().allMatch(p -> p.getBrand() != null));
        assertTrue(browse(null, phonesId, null, null, null, null, null, false, null)
                .getTotalElements() >= 4);
    }

    @Test
    void searchesNameAndSku() {
        assertEquals(1, browse("A16", null, null, null, null, null, null, false, null)
                .getTotalElements());
        assertEquals(0, browse("refrigerator", null, null, null, null, null, null, false, null)
                .getTotalElements());
    }

    /** A product that cannot reach the destination should not be offered for it. */
    @Test
    void filtersByDeliveryCountry() {
        Page<Product> toGambia = browse(null, null, null, null, null, null, null, false, "GM");

        assertTrue(toGambia.getContent().stream().noneMatch(p -> p.getSlug().equals("london-handset")),
                "a regional listing in the UK cannot be delivered to Gambia");
    }

    // ── Ranking is against the delivery point ────────────────────────────────

    /**
     * C1 at the query level. Stock near Serrekunda outranks stock in London
     * because Serrekunda is where the parcel is going — not because of anything
     * about the person paying.
     */
    @Test
    void stockNearTheDestinationOutranksStockFarFromIt() {
        List<Product> ranked = browse(null, null, null, null, null, null, null, false, null)
                .getContent();

        assertTrue(indexOfSlug(ranked, "galaxy-a16") < indexOfSlug(ranked, "london-handset"),
                "stock two miles from the recipient must outrank stock on another continent");
    }

    @Test
    void withoutADestinationEverythingStillRanks() {
        Page<Product> page = products.browse(null, null, null, null, null, null, null, null,
                false, null, null, null, RADIUS_KM, PageRequest.of(0, 20));

        assertTrue(page.getTotalElements() >= 4, "a shopper who has not said where still browses");
    }

    // ── Facets ───────────────────────────────────────────────────────────────

    @Test
    void theFacetQueriesExecuteAndCount() {
        List<Object[]> byBrand = products.facetByBrand(null, null, null, null, null,
                null, null, null, false, null);
        List<Object[]> byCategory = products.facetByCategory(null, null, null, null, null,
                null, null, null, false, null);
        List<Object[]> byCondition = products.facetByCondition(null, null, null, null, null,
                null, null, null, false, null);

        assertFalse(byBrand.isEmpty());
        assertFalse(byCategory.isEmpty());
        assertFalse(byCondition.isEmpty(), "grouping on the reserved-word column must work");

        long conditions = byCondition.stream().map(row -> String.valueOf(row[0])).distinct().count();
        assertTrue(conditions >= 3, "NEW, REFURBISHED and USED are all present");
    }

    @Test
    void aFacetCountMatchesWhatTheFilterWouldReturn() {
        long refurbishedFacet = products.facetByCondition(null, null, null, null, null,
                        null, null, null, false, null).stream()
                .filter(row -> "REFURBISHED".equals(String.valueOf(row[0])))
                .mapToLong(row -> ((Number) row[1]).longValue())
                .findFirst().orElse(0);

        assertEquals(
                browse(null, null, null, ProductCondition.REFURBISHED, null, null, null, false, null)
                        .getTotalElements(),
                refurbishedFacet,
                "a facet count that disagrees with its own filter sends shoppers to empty pages");
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    @Test
    void aPublishedProductIsFoundBySlug() {
        assertTrue(products.findPublishedBySlug("galaxy-a16").isPresent());
        assertTrue(products.findPublishedBySlug("GALAXY-A16").isPresent(), "slugs are case-tolerant");
        assertTrue(products.findPublishedBySlug("unpublished").isEmpty(),
                "an unpublished listing has no public URL");
    }

    @Test
    void suggestionsArePrefixWeightedAndBounded() {
        List<String> suggestions = products.suggestNames("gal", null, 5);

        assertFalse(suggestions.isEmpty());
        assertTrue(suggestions.size() <= 5);
        assertTrue(suggestions.get(0).toLowerCase().startsWith("gal"), "a prefix match should lead");
        assertTrue(suggestions.stream().noneMatch(name -> name.equals("Unpublished")));
    }

    private static int indexOfSlug(List<Product> ranked, String slug) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getSlug().equals(slug)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
