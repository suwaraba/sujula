package com.sujula.service.vendorcatalogue;

import com.sujula.model.constant.ProductCondition;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.products.Category;
import com.sujula.model.products.Product;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The development seed's approved-content hashes are the real thing.
 *
 * <p>A seeded digest that nothing can reproduce is decoration: every listing in
 * the seed would read as approved-and-unedited whatever its text said, and the
 * one behaviour that depends on it - an edit sending a listing back for review -
 * could never be exercised against seeded data.
 *
 * <p>So this recomputes one from the row's own text and compares. It also
 * reaches into the seed file rather than trusting a copy, so that editing a
 * seeded description without re-deriving its hash fails here rather than
 * quietly making the seed dishonest.
 */
class SeededContentHashTest {

    private static final Path SEED = Path.of("src/main/resources/db/seed/dev-seed.sql");

    private final ProductLifecycle lifecycle = new ProductLifecycle();

    @Test
    void theSeededHashForTheGalaxyA16IsWhatTheApplicationWouldCompute() throws IOException {
        Product seeded = Product.builder()
                .name("Samsung Galaxy A16")
                .shortDescription("6.7-inch screen, 5000mAh battery")
                .description("Dual SIM, expandable storage, two-year local warranty.")
                .price(new BigDecimal("8500.00"))
                .priceCurrency("GMD")
                .category(category(1213L))
                .brand(brand(1201L))
                .condition(ProductCondition.NEW)
                .status(ProductStatus.PUBLISHED)
                .build();

        assertEquals(hashInSeedFor("KOM-SGA16"), lifecycle.contentHash(seeded));
    }

    @Test
    void andForACloth() throws IOException {
        Product seeded = Product.builder()
                .name("Wax Print — Six Yards, Indigo")
                .shortDescription("Hand-finished cotton, six-yard piece")
                .description("Printed in Ziguinchor. Colour holds through cold washing.")
                .price(new BigDecimal("14500.00"))
                .priceCurrency("XOF")
                .category(category(1216L))
                .condition(ProductCondition.NEW)
                .status(ProductStatus.PUBLISHED)
                .build();

        assertEquals(hashInSeedFor("TER-WAX-IND"), lifecycle.contentHash(seeded));
    }

    @Test
    void andChangingAnyOfThatWouldStopMatching() throws IOException {
        Product edited = Product.builder()
                .name("Samsung Galaxy A16")
                .shortDescription("6.7-inch screen, 5000mAh battery")
                .description("Dual SIM, expandable storage, two-year local warranty.")
                // The only change: a price rise, which is the case the digest
                // exists to catch.
                .price(new BigDecimal("11000.00"))
                .priceCurrency("GMD")
                .category(category(1213L))
                .brand(brand(1201L))
                .condition(ProductCondition.NEW)
                .approvedContentHash(hashInSeedFor("KOM-SGA16"))
                .status(ProductStatus.PUBLISHED)
                .build();

        assertTrue(lifecycle.contentChanged(edited));
    }

    /** Pulls the hash out of the seeded row carrying this SKU. */
    private static String hashInSeedFor(String sku) throws IOException {
        String seed = Files.readString(SEED);
        Matcher matcher = Pattern
                .compile("'" + Pattern.quote(sku) + "'.*?'([0-9a-f]{64})'", Pattern.DOTALL)
                .matcher(seed);
        assertTrue(matcher.find(), "no seeded hash found for " + sku);
        return matcher.group(1);
    }

    private static Category category(Long id) {
        Category category = new Category();
        category.setId(id);
        return category;
    }

    private static com.sujula.model.products.Brand brand(Long id) {
        com.sujula.model.products.Brand brand = new com.sujula.model.products.Brand();
        brand.setId(id);
        return brand;
    }
}
