package com.sujula.service.inventory;

import com.sujula.dto.request.inventory.InventoryRequests;
import com.sujula.dto.response.inventory.InventoryResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.ImeiGrade;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.constant.StockMovementReason;
import com.sujula.model.inventory.ImeiUnit;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.Vendor;
import com.sujula.repository.inventory.ImeiUnitRepository;
import com.sujula.repository.inventory.StockMovementRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.inventory.impl.InventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A seller's stock, and the handsets in it.
 *
 * <p>The cases worth having are the ones where a plausible implementation lets a
 * shop sell something it does not have: an absolute count applied over somebody
 * else's, a hand-typed figure on a line whose units are counted individually, a
 * handset written off without the shelf following, a stolen-phone flag a seller
 * can clear.
 */
class InventoryServiceTest {

    private static final Long OWNER = 970L;
    private static final Long INTRUDER = 971L;
    private static final Long VENDOR = 6200L;
    private static final Long VARIANT = 7200L;
    private static final Long PRODUCT = 7100L;

    /** A real IMEI shape: fourteen digits and the digit that makes them check. */
    private static final String IMEI = "490154203237518";
    private static final String IMEI_TWO = "356938035643809";

    private VendorRepository vendors;
    private ProductRepository products;
    private ProductVariantRepository variants;
    private StockMovementRepository movements;
    private ImeiUnitRepository handsets;
    private UserRepository users;
    private InventoryServiceImpl service;

    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        vendors = mock(VendorRepository.class);
        products = mock(ProductRepository.class);
        variants = mock(ProductVariantRepository.class);
        movements = mock(StockMovementRepository.class);
        handsets = mock(ImeiUnitRepository.class);
        users = mock(UserRepository.class);

        // A real ledger over mocked repositories: mocking it would hide whether
        // the service still records why anything moved.
        service = new InventoryServiceImpl(vendors, products, variants, movements, handsets, users,
                new StockLedger(movements, variants, products));

        when(vendors.findByUserId(OWNER)).thenReturn(Optional.of(vendor()));
        when(vendors.findByUserId(INTRUDER)).thenReturn(Optional.of(
                Vendor.builder().id(9999L).status(PartnerStatus.APPROVED).build()));
        when(users.findById(anyLong())).thenReturn(Optional.empty());
        when(movements.save(any())).thenAnswer(call -> call.getArgument(0));
        when(variants.save(any(ProductVariant.class))).thenAnswer(call -> call.getArgument(0));
        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));
        when(handsets.save(any(ImeiUnit.class))).thenAnswer(call -> call.getArgument(0));
        when(handsets.existsByVariantId(anyLong())).thenReturn(false);
        when(handsets.findByImei(anyString())).thenReturn(Optional.empty());

        variant = variant(10, 0L);
    }

    private static Vendor vendor() {
        return Vendor.builder().id(VENDOR).status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD").build();
    }

    private ProductVariant variant(int stock, long version) {
        Product product = Product.builder()
                .id(PRODUCT).vendor(vendor()).name("Galaxy A16").sku("KOM-SGA16")
                .price(new BigDecimal("8500.00")).priceCurrency("GMD")
                .stock(stock).lowStockThreshold(3)
                .status(ProductStatus.PUBLISHED).active(true)
                .build();

        ProductVariant built = ProductVariant.builder()
                .id(VARIANT).product(product).sku("KOM-SGA16-128").stock(stock)
                .version(version).active(true).build();

        when(variants.findByIdAndVendorId(VARIANT, VENDOR)).thenReturn(Optional.of(built));
        when(variants.findByIdAndVendorId(VARIANT, 9999L)).thenReturn(Optional.empty());
        when(products.findByIdAndVendorId(PRODUCT, VENDOR)).thenReturn(Optional.of(product));
        return built;
    }

    private static InventoryRequests.AdjustStock setTo(int value, Long version) {
        return new InventoryRequests.AdjustStock(value, null, version,
                StockMovementReason.CORRECTION, null, "Counted the shelf");
    }

    private static InventoryRequests.AdjustStock delta(int change) {
        return new InventoryRequests.AdjustStock(null, change, null,
                StockMovementReason.RESTOCK, "GRN-1", null);
    }

    // -- Ownership -----------------------------------------------------------

    @Test
    void anotherSellersStockIsNotFoundRatherThanForbidden() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.adjust(INTRUDER, VARIANT, delta(5)));
        assertThrows(ResourceNotFoundException.class,
                () -> service.movements(INTRUDER, VARIANT, org.springframework.data.domain.PageRequest.of(0, 10)));
    }

    // -- Absolute versus delta -----------------------------------------------

    @Test
    void aDeltaNeedsNoVersionBecauseItIsSafeWhoeverElseIsWriting() {
        InventoryResponses.Adjusted result = service.adjust(OWNER, VARIANT, delta(5));

        assertEquals(10, result.stockBefore());
        assertEquals(15, result.stockAfter());
    }

    @Test
    void anAbsoluteFigureWithoutAVersionIsRefusedAndSaysWhy() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.adjust(OWNER, VARIANT, setTo(12, null)));

        // The refusal has to teach, because the seller's instinct is to type the
        // number they counted and the API just said no.
        assertTrue(refused.getMessage().contains("version"), refused.getMessage());
        assertTrue(refused.getMessage().contains("delta"), refused.getMessage());
        verify(movements, never()).save(any());
    }

    @Test
    void anAbsoluteFigureAgainstAStaleVersionIsRefused() {
        variant = variant(10, 4L);

        // Two people counted the same shelf. The one who read version 3 must not
        // quietly overwrite the one who already saved at 4.
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> service.adjust(OWNER, VARIANT, setTo(12, 3L)));

        assertEquals(10, variant.getStock());
        verify(movements, never()).save(any());
    }

    @Test
    void anAbsoluteFigureWithTheRightVersionGoesThroughAsADifference() {
        variant = variant(10, 4L);

        InventoryResponses.Adjusted result = service.adjust(OWNER, VARIANT, setTo(7, 4L));

        assertEquals(7, result.stockAfter());

        ArgumentCaptor<com.sujula.model.inventory.StockMovement> recorded =
                ArgumentCaptor.forClass(com.sujula.model.inventory.StockMovement.class);
        verify(movements).save(recorded.capture());
        // Signed, so the ledger still sums to the shelf.
        assertEquals(-3, recorded.getValue().getQuantityChange());
    }

    @Test
    void sendingBothFormsOrNeitherIsRefused() {
        assertThrows(BadRequestException.class, () -> service.adjust(OWNER, VARIANT,
                new InventoryRequests.AdjustStock(12, 5, 0L, StockMovementReason.CORRECTION, null, null)));
        assertThrows(BadRequestException.class, () -> service.adjust(OWNER, VARIANT,
                new InventoryRequests.AdjustStock(null, null, 0L, StockMovementReason.CORRECTION, null, null)));
    }

    // -- Bulk ----------------------------------------------------------------

    @Test
    void oneBadLineDoesNotTakeTheOthersWithIt() {
        when(variants.findByIdAndVendorId(99999L, VENDOR)).thenReturn(Optional.empty());

        InventoryResponses.BulkAdjusted result = service.bulkAdjust(OWNER,
                new InventoryRequests.BulkAdjust(List.of(
                        new InventoryRequests.Line(VARIANT, 5),
                        new InventoryRequests.Line(99999L, 3)),
                        StockMovementReason.RESTOCK, "GRN-2", null));

        assertEquals(2, result.requested());
        assertEquals(1, result.applied());
        assertEquals(1, result.errors().size());
        assertEquals(99999L, result.errors().get(0).variantId());
    }

    @Test
    void aLineThatWouldGoBelowZeroFailsAloneToo() {
        InventoryResponses.BulkAdjusted result = service.bulkAdjust(OWNER,
                new InventoryRequests.BulkAdjust(List.of(new InventoryRequests.Line(VARIANT, -50)),
                        StockMovementReason.DAMAGE, null, null));

        assertEquals(0, result.applied());
        assertTrue(result.errors().get(0).message().contains("below zero"),
                result.errors().get(0).message());
    }

    // -- Serialised lines ----------------------------------------------------

    @Test
    void aLineTrackedHandsetByHandsetRefusesATypedFigure() {
        when(handsets.existsByVariantId(VARIANT)).thenReturn(true);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.adjust(OWNER, VARIANT, delta(5)));

        // Typing 12 into a screen holding nine registered handsets creates three
        // phones that do not exist, and the first buyer to order one finds out.
        assertTrue(refused.getMessage().contains("handset by handset"), refused.getMessage());
    }

    // -- Handsets ------------------------------------------------------------

    @Test
    void registeringHandsetsSetsTheShelfToHowManyAreInStock() {
        when(handsets.countSellableForVariant(VARIANT)).thenReturn(2L);

        InventoryResponses.Registered result = service.registerHandsets(OWNER,
                new InventoryRequests.RegisterImeiUnits(PRODUCT, VARIANT, List.of(
                        unit(IMEI), unit(IMEI_TWO))));

        assertEquals(2, result.registered());
        // The units are the authority; the count follows them. A second opinion
        // about the same shelf disagrees the first time one is written off.
        assertEquals(2, result.stockAfter());
    }

    @Test
    void aMistypedCodeRejectsItsOwnLineAndRegistersTheRest() {
        when(handsets.countSellableForVariant(VARIANT)).thenReturn(1L);

        InventoryResponses.Registered result = service.registerHandsets(OWNER,
                new InventoryRequests.RegisterImeiUnits(PRODUCT, VARIANT, List.of(
                        unit(IMEI), unit("490154203237519"))));

        // A box of twenty with one bad label should put nineteen on the shelf.
        assertEquals(1, result.registered());
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).message().contains("check digit"),
                result.rejected().get(0).message());
    }

    @Test
    void aHandsetAlreadyRegisteredElsewhereIsRefusedWithSomewhereToGo() {
        ImeiUnit elsewhere = ImeiUnit.builder()
                .imei(IMEI).vendor(Vendor.builder().id(4242L).build()).build();
        when(handsets.findByImei(IMEI)).thenReturn(Optional.of(elsewhere));

        InventoryResponses.Registered result = service.registerHandsets(OWNER,
                new InventoryRequests.RegisterImeiUnits(PRODUCT, VARIANT, List.of(unit(IMEI))));

        assertEquals(0, result.registered());
        // The same handset on two shelves is a phone sold twice, and telling the
        // seller to contact support beats telling them "duplicate".
        assertTrue(result.rejected().get(0).message().contains("contact support"),
                result.rejected().get(0).message());
    }

    @Test
    void aForPartsGradeHasToSayWhatIsWrongWithIt() {
        InventoryResponses.Registered result = service.registerHandsets(OWNER,
                new InventoryRequests.RegisterImeiUnits(PRODUCT, VARIANT, List.of(
                        new InventoryRequests.ImeiUnitEntry(IMEI, null, null,
                                ImeiGrade.FOR_PARTS, null, null, null, null, null))));

        assertEquals(0, result.registered());
        assertTrue(result.rejected().get(0).message().contains("what is wrong"),
                result.rejected().get(0).message());
    }

    @Test
    void writingOffAHandsetTakesItOffTheShelf() {
        ImeiUnit unit = ImeiUnit.builder()
                .id(500L).imei(IMEI).vendor(vendor()).product(variant.getProduct())
                .variant(variant).status(ImeiStatus.IN_STOCK).grade(ImeiGrade.B_GRADE).build();
        when(handsets.findByIdAndVendorId(500L, VENDOR)).thenReturn(Optional.of(unit));
        when(handsets.countSellableForVariant(VARIANT)).thenReturn(0L);

        service.updateHandset(OWNER, 500L, new InventoryRequests.UpdateImeiUnit(
                null, null, ImeiStatus.WRITTEN_OFF, null, null, "Water damage"));

        assertEquals(ImeiStatus.WRITTEN_OFF, unit.getStatus());
        // Without the shelf following, the shop goes on selling a phone that is
        // in a drawer with water damage.
        assertEquals(0, variant.getStock());
    }

    @Test
    void aSellerCannotBlockOrUnblockAHandset() {
        ImeiUnit unit = ImeiUnit.builder()
                .id(501L).imei(IMEI).vendor(vendor()).product(variant.getProduct())
                .variant(variant).status(ImeiStatus.IN_STOCK).grade(ImeiGrade.A_GRADE).build();
        when(handsets.findByIdAndVendorId(501L, VENDOR)).thenReturn(Optional.of(unit));

        // Setting it would let a seller flag a rival's stock.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.updateHandset(OWNER, 501L, new InventoryRequests.UpdateImeiUnit(
                        null, null, ImeiStatus.BLOCKED, null, null, null)));
        assertTrue(refused.getMessage().contains("support"), refused.getMessage());

        // Clearing it would let them launder a stolen handset, which is most of
        // the reason to record an IMEI at all.
        unit.setStatus(ImeiStatus.BLOCKED);
        assertThrows(BadRequestException.class,
                () -> service.updateHandset(OWNER, 501L, new InventoryRequests.UpdateImeiUnit(
                        null, null, ImeiStatus.IN_STOCK, null, null, null)));
    }

    @Test
    void aSoldHandsetIsNotEditedByHand() {
        ImeiUnit unit = ImeiUnit.builder()
                .id(502L).imei(IMEI).vendor(vendor()).product(variant.getProduct())
                .variant(variant).status(ImeiStatus.SOLD).grade(ImeiGrade.A_GRADE).build();
        when(handsets.findByIdAndVendorId(502L, VENDOR)).thenReturn(Optional.of(unit));

        assertThrows(BadRequestException.class,
                () -> service.updateHandset(OWNER, 502L, new InventoryRequests.UpdateImeiUnit(
                        ImeiGrade.C_GRADE, null, null, null, null, null)));
    }

    @Test
    void aHandsetCannotBeMarkedSoldByHandEither() {
        ImeiUnit unit = ImeiUnit.builder()
                .id(503L).imei(IMEI).vendor(vendor()).product(variant.getProduct())
                .variant(variant).status(ImeiStatus.IN_STOCK).grade(ImeiGrade.A_GRADE).build();
        when(handsets.findByIdAndVendorId(503L, VENDOR)).thenReturn(Optional.of(unit));

        // The order it goes out on marks it, so the record and the sale agree.
        assertThrows(BadRequestException.class,
                () -> service.updateHandset(OWNER, 503L, new InventoryRequests.UpdateImeiUnit(
                        null, null, ImeiStatus.SOLD, null, null, null)));
    }

    private static InventoryRequests.ImeiUnitEntry unit(String imei) {
        return new InventoryRequests.ImeiUnitEntry(imei, null, null,
                ImeiGrade.A_GRADE, null, new BigDecimal("7000.00"), 92, null, null);
    }
}
