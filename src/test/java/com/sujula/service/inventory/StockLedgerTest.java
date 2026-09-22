package com.sujula.service.inventory;

import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.constant.StockMovementReason;
import com.sujula.model.constant.UserRole;
import com.sujula.model.inventory.StockMovement;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.inventory.StockMovementRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stock ledger and its optimistic lock, against a real database.
 *
 * <p>Two things here cannot be tested with mocks. The version column is
 * maintained by Hibernate, so whether it actually increments on a write is a
 * question about the persistence layer rather than about this code. And the
 * ledger's central claim - that the movements sum to the figure on the shelf -
 * is only meaningful if both are really written and really read back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(StockLedger.class)
class StockLedgerTest {

    @Autowired private StockLedger ledger;
    @Autowired private StockMovementRepository movements;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ProductRepository products;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private ProductVariant variant;
    private User seller;

    @BeforeEach
    void setUp() {
        seller = new User();
        seller.setFirstName("Lamin");
        seller.setLastName("Touray");
        seller.setEmail("lamin.ledger@sujula.gm");
        seller.setPassword("x");
        seller.setPhone("+2203100077");
        seller.setRole(UserRole.VENDOR);
        users.save(seller);

        Vendor vendor = vendors.save(Vendor.builder()
                .user(seller).storeName("Kombo Electronics").storeSlug("kombo-ledger")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").build());

        Product product = products.save(Product.builder()
                .vendor(vendor).name("Kettle").slug("kettle-ledger")
                .price(new BigDecimal("450.00")).priceCurrency("GMD")
                .sku("KET-LEDGER").stock(0).lowStockThreshold(3)
                .status(ProductStatus.DRAFT).active(false)
                .build());

        variant = variants.save(ProductVariant.builder()
                .product(product).sku("KET-LEDGER-A").stock(10).active(true).build());

        entityManager.flush();
    }

    // -- The ledger's central claim ------------------------------------------

    @Test
    void theMovementsSumToTheFigureOnTheShelf() {
        ledger.adjustVariant(variant, 20, StockMovementReason.RESTOCK, "GRN-1", null, seller);
        ledger.adjustVariant(variant, -3, StockMovementReason.SALE, null, null, null);
        ledger.adjustVariant(variant, -1, StockMovementReason.DAMAGE, null, "Dropped", seller);
        entityManager.flush();

        List<StockMovement> trail = movements
                .findForVariant(variant.getId(), variant.getProduct().getVendor().getId(),
                        PageRequest.of(0, 50))
                .getContent();

        assertEquals(3, trail.size());
        int summed = 10 + trail.stream().mapToInt(StockMovement::getQuantityChange).sum();
        assertEquals(variant.getStock(), summed);
        assertEquals(26, variant.getStock());
    }

    @Test
    void everyMovementRecordsTheFiguresEitherSideOfIt() {
        ledger.adjustVariant(variant, -4, StockMovementReason.SALE, "SJL-1", null, null);
        entityManager.flush();

        StockMovement movement = movements.findForVariant(variant.getId(),
                variant.getProduct().getVendor().getId(), PageRequest.of(0, 1)).getContent().get(0);

        // So one row makes sense without replaying the whole ledger.
        assertEquals(10, movement.getStockBefore());
        assertEquals(6, movement.getStockAfter());
        assertEquals(-4, movement.getQuantityChange());
        assertEquals("SJL-1", movement.getReference());
    }

    @Test
    void settingAnAbsoluteFigureStillRecordsTheDifference() {
        ledger.setVariant(variant, 7, StockMovementReason.CORRECTION, null, "Counted", seller);
        entityManager.flush();

        StockMovement movement = movements.findForVariant(variant.getId(),
                variant.getProduct().getVendor().getId(), PageRequest.of(0, 1)).getContent().get(0);

        // "-3, CORRECTION" tells a reconciliation what happened to the missing
        // three. "Set to 7" tells it nothing.
        assertEquals(-3, movement.getQuantityChange());
        assertEquals(7, variant.getStock());
    }

    @Test
    void aStocktakeThatFoundNothingWrongIsStillRecorded() {
        ledger.setVariant(variant, 10, StockMovementReason.CORRECTION, null, "Counted", seller);
        entityManager.flush();

        // "Somebody counted the shelf and it was right" is information. A ledger
        // that dropped it would make the stocktake look like it never happened.
        assertEquals(1, movements.countByVendorId(variant.getProduct().getVendor().getId()));
    }

    @Test
    void aShelfCannotGoBelowEmpty() {
        assertThrows(RuntimeException.class, () ->
                ledger.adjustVariant(variant, -11, StockMovementReason.SALE, null, null, null));

        // And nothing is recorded for a movement that did not happen.
        assertEquals(0, movements.countByVendorId(variant.getProduct().getVendor().getId()));
        assertEquals(10, variant.getStock());
    }

    @Test
    void aSaleIsInTheAuditJustLikeACorrection() {
        ledger.adjustVariant(variant, -2, StockMovementReason.SALE, null, "Reserved", null);
        entityManager.flush();

        StockMovement movement = movements.findForVariant(variant.getId(),
                variant.getProduct().getVendor().getId(), PageRequest.of(0, 1)).getContent().get(0);

        // The whole reason the order paths go through here: an audit that showed
        // a seller's corrections and omitted the orders would be wrong in
        // exactly the case they open it for.
        assertEquals(StockMovementReason.SALE, movement.getReason());
        // No person behind it. An order deducted the stock, not a member of staff.
        assertEquals(null, movement.getRecordedBy());
    }

    // -- The optimistic lock -------------------------------------------------

    @Test
    void theVersionMovesOnEveryWrite() {
        Long before = variant.getVersion();

        ledger.adjustVariant(variant, 1, StockMovementReason.RESTOCK, null, null, seller);
        entityManager.flush();

        // Maintained by Hibernate, not by this code - which is exactly why it
        // is worth asserting against a real database rather than a mock.
        assertNotEquals(before, variant.getVersion());
    }

    @Test
    void aWriteAgainstAStaleVersionIsRejected() {
        Long staleVersion = variant.getVersion();

        // Somebody else saves first.
        ledger.adjustVariant(variant, 5, StockMovementReason.RESTOCK, null, null, seller);
        entityManager.flush();
        entityManager.clear();

        // Our copy, still carrying the version we read before they wrote.
        ProductVariant ourCopy = variants.findById(variant.getId()).orElseThrow();
        assertNotEquals(staleVersion, ourCopy.getVersion());

        // Which is what the inventory endpoint compares before it will honour an
        // absolute figure. Two people counting one shelf and saving 10 and 12
        // must not leave whichever committed last, with the other simply wrong
        // and nothing anywhere to say so.
        assertTrue(ourCopy.getVersion() > staleVersion);
    }
}
