package com.sujula.service.vendorcatalogue;

import com.sujula.dto.request.vendorcatalogue.VendorProductRequests;
import com.sujula.dto.response.vendorcatalogue.VendorProductResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.products.Category;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductImage;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.BrandRepository;
import com.sujula.repository.product.CategoryRepository;
import com.sujula.repository.product.ProductImageRepository;
import com.sujula.repository.product.ProductOptionRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductTranslationRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import com.sujula.service.reference.ReferenceDataService;
import com.sujula.service.vendorcatalogue.impl.VendorCatalogueServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Writing listings, and the queue between a seller and the catalogue.
 *
 * <p>Most of these are about the moderation ladder, because that is where a
 * plausible implementation is quietly wrong in a way that only shows up as
 * something being sold that should not be: a draft that can publish itself, an
 * approval that survives a rewrite, a suspension a seller can lift, a delete
 * that takes a buyer's receipt with it.
 */
class VendorCatalogueServiceTest {

    private static final Long OWNER = 950L;
    private static final Long INTRUDER = 951L;
    private static final Long VENDOR = 6000L;
    private static final Long PRODUCT = 7000L;

    private ProductRepository products;
    private VendorRepository vendors;
    private CategoryRepository categories;
    private BrandRepository brands;
    private ProductVariantRepository variants;
    private ProductImageRepository images;
    private ProductOptionRepository options;
    private ProductTranslationRepository translations;
    private ProductLifecycle lifecycle;
    private VendorCatalogueServiceImpl service;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        vendors = mock(VendorRepository.class);
        categories = mock(CategoryRepository.class);
        brands = mock(BrandRepository.class);
        variants = mock(ProductVariantRepository.class);
        images = mock(ProductImageRepository.class);
        options = mock(ProductOptionRepository.class);
        translations = mock(ProductTranslationRepository.class);
        lifecycle = new ProductLifecycle();

        ReferenceDataProperties properties = new ReferenceDataProperties();
        service = new VendorCatalogueServiceImpl(products, vendors, categories, brands, variants,
                images, options, translations, lifecycle,
                new ReferenceDataService(properties, CurrencyCatalogue.of(properties)));

        ReflectionTestUtils.setField(service, "maxImages", 12);

        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));
        when(images.findByProductIdOrderBySortOrderAsc(anyLong())).thenReturn(List.of());
        when(variants.findByProductId(anyLong())).thenReturn(List.of());
        when(options.findByProductIdOrderBySortOrderAsc(anyLong())).thenReturn(List.of());
        when(translations.findByProductIdOrderByLocaleAsc(anyLong())).thenReturn(List.of());
        when(categories.findById(anyLong())).thenReturn(Optional.of(category()));
        when(products.hasBeenOrdered(anyLong())).thenReturn(false);
        when(vendors.findByUserId(OWNER)).thenReturn(Optional.of(vendor(PartnerStatus.APPROVED)));
        when(vendors.findByUserId(INTRUDER)).thenReturn(Optional.of(otherVendor()));
    }

    private static Category category() {
        Category category = new Category();
        category.setId(500L);
        category.setName("Phones");
        return category;
    }

    private static Vendor vendor(PartnerStatus status) {
        User owner = new User();
        owner.setId(OWNER);
        return Vendor.builder()
                .id(VENDOR).user(owner)
                .storeName("Kombo Electronics").storeSlug("kombo-electronics")
                .status(status)
                .settlementCurrency("GMD")
                .addressCountryCode("GM")
                .latitude(13.4383).longitude(-16.6781)
                .build();
    }

    private static Vendor otherVendor() {
        return Vendor.builder().id(9999L).status(PartnerStatus.APPROVED)
                .settlementCurrency("XOF").build();
    }

    private Product product(ProductStatus status) {
        Product product = Product.builder()
                .id(PRODUCT)
                .vendor(vendor(PartnerStatus.APPROVED))
                .name("Kettle")
                .slug("kettle-a1b2c3")
                .description("1.7 litres, stainless steel.")
                .price(new BigDecimal("450.00"))
                .priceCurrency("GMD")
                .sku("KET-01")
                .stock(10)
                .lowStockThreshold(3)
                .category(category())
                .status(status)
                .images(new ArrayList<>())
                .variants(new ArrayList<>())
                .build();
        lifecycle.syncVisibility(product);
        if (status.isApproved()) {
            // What the moderator saw.
            lifecycle.markContentApproved(product);
        }
        when(products.findByIdAndVendorId(PRODUCT, VENDOR)).thenReturn(Optional.of(product));
        when(products.findByIdAndVendorId(PRODUCT, 9999L)).thenReturn(Optional.empty());
        return product;
    }

    private static VendorProductRequests.CreateProduct newListing() {
        return new VendorProductRequests.CreateProduct(
                "Kettle", "1.7 litres", "Stainless steel, 1.7 litres.",
                new BigDecimal("450.00"), null,
                "KET-01", 10, 3, false,
                500L, null, null, null,
                1.2, null, null);
    }

    // -- Ownership -----------------------------------------------------------

    @Test
    void anotherSellersListingIsNotFoundRatherThanForbidden() {
        product(ProductStatus.PUBLISHED);

        assertThrows(ResourceNotFoundException.class, () -> service.get(INTRUDER, PRODUCT));
        assertThrows(ResourceNotFoundException.class, () -> service.publish(INTRUDER, PRODUCT));
        assertThrows(ResourceNotFoundException.class, () -> service.remove(INTRUDER, PRODUCT));
    }

    // -- Creating ------------------------------------------------------------

    @Test
    void aNewListingIsADraftAndCannotBeSeenByAnybody() {
        VendorProductResponses.Detail created = service.create(OWNER, newListing());

        assertEquals(ProductStatus.DRAFT, created.status());
        assertFalse(created.live());
        assertFalse(created.moderation().canPublish());
        assertTrue(created.moderation().canSubmit());
    }

    @Test
    void thePriceIsInTheStoresOwnCurrencyAndTheRequestCannotSayOtherwise() {
        // The listing currency IS the payout currency. There is no field for it
        // on the way in, and this asserts the service does not invent one: a
        // seller listing in EUR while banking in GMD would put an FX conversion
        // between what a buyer pays and what the seller is owed that nobody
        // snapshotted.
        VendorProductResponses.Detail created = service.create(OWNER, newListing());

        assertEquals("GMD", created.currency());
    }

    @Test
    void aListingInheritsWhereTheGoodsActuallyAre() {
        service.create(OWNER, newListing());

        org.mockito.ArgumentCaptor<Product> saved =
                org.mockito.ArgumentCaptor.forClass(Product.class);
        verify(products).save(saved.capture());

        // Copied from the store rather than asked for per product. Delivery is
        // priced from this pin, a listing whose pin disagrees with its shop is a
        // collection sent to the wrong place, and no seller retypes their own
        // address four hundred times.
        assertEquals("GM", saved.getValue().getCountry());
        assertEquals(13.4383, saved.getValue().getLatitude());
        assertEquals(-16.6781, saved.getValue().getLongitude());
    }

    @Test
    void aSellerWhoseStoreIsNotApprovedCannotStartListing() {
        when(vendors.findByUserId(OWNER)).thenReturn(Optional.of(vendor(PartnerStatus.PENDING_KYC)));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.create(OWNER, newListing()));
        assertTrue(refused.getMessage().contains("verification"), refused.getMessage());
    }

    @Test
    void twoListingsCannotShareOneCode() {
        when(products.existsBySkuIgnoreCaseAndVendorId("KET-01", VENDOR)).thenReturn(true);

        assertThrows(BadRequestException.class, () -> service.create(OWNER, newListing()));
    }

    // -- The moderation ladder -----------------------------------------------

    @Test
    void aDraftCannotPutItselfOnSale() {
        product(ProductStatus.DRAFT);

        // The guard that makes the queue real rather than advisory.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.publish(OWNER, PRODUCT));
        assertTrue(refused.getMessage().contains("review"), refused.getMessage());
    }

    @Test
    void anApprovedListingGoesOnSaleWhenTheSellerSaysSo() {
        Product listing = product(ProductStatus.APPROVED);

        VendorProductResponses.Saved saved = service.publish(OWNER, PRODUCT);

        assertEquals(ProductStatus.PUBLISHED, saved.status());
        assertTrue(saved.live());
        assertTrue(listing.isActive());
        assertNotNull(listing.getPublishedAt());
    }

    @Test
    void aSuspendedListingCannotBePutBackUpByItsSeller() {
        product(ProductStatus.SUSPENDED);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.publish(OWNER, PRODUCT));
        assertTrue(refused.getMessage().contains("approved")
                || refused.getMessage().contains("support"), refused.getMessage());
    }

    @Test
    void unpublishingKeepsTheApprovalSoItCanGoBackUp() {
        Product listing = product(ProductStatus.PUBLISHED);

        service.unpublish(OWNER, PRODUCT);
        assertEquals(ProductStatus.UNPUBLISHED, listing.getStatus());
        assertFalse(listing.isActive());

        VendorProductResponses.Saved again = service.publish(OWNER, PRODUCT);
        assertEquals(ProductStatus.PUBLISHED, again.status());
        assertTrue(again.live());
    }

    @Test
    void republishingDoesNotResetTheFirstPublicationDate() {
        Product listing = product(ProductStatus.PUBLISHED);
        var firstPublished = java.time.LocalDateTime.of(2026, 1, 1, 9, 0);
        listing.setPublishedAt(firstPublished);

        service.unpublish(OWNER, PRODUCT);
        service.publish(OWNER, PRODUCT);

        // Otherwise "new arrivals" becomes a list of old stock somebody relisted.
        assertEquals(firstPublished, listing.getPublishedAt());
    }

    @Test
    void aListingWithNoDescriptionIsNotSentForReview() {
        Product listing = product(ProductStatus.DRAFT);
        listing.setDescription(null);

        BadRequestException refused = assertThrows(BadRequestException.class, () ->
                service.submitForReview(OWNER, PRODUCT,
                        new VendorProductRequests.SubmitForReview(null)));

        // Buyers here are often thousands of miles from the goods.
        assertTrue(refused.getMessage().contains("description"), refused.getMessage());
    }

    @Test
    void aListingWithNoCategoryOrPriceIsNotSentForReview() {
        Product noPrice = product(ProductStatus.DRAFT);
        noPrice.setPrice(BigDecimal.ZERO);
        assertThrows(BadRequestException.class, () -> service.submitForReview(OWNER, PRODUCT,
                new VendorProductRequests.SubmitForReview(null)));

        Product noCategory = product(ProductStatus.DRAFT);
        noCategory.setCategory(null);
        assertThrows(BadRequestException.class, () -> service.submitForReview(OWNER, PRODUCT,
                new VendorProductRequests.SubmitForReview(null)));
    }

    // -- Edits and re-review -------------------------------------------------

    @Test
    void changingThePriceOfAnApprovedListingSendsItBackAndSaysSo() {
        Product listing = product(ProductStatus.PUBLISHED);

        VendorProductResponses.Saved saved = service.update(OWNER, PRODUCT, patch(
                null, new BigDecimal("900.00"), null));

        // The commonest listing fraud there is: approved cheap, quietly raised.
        assertTrue(saved.needsReview());
        assertEquals(ProductStatus.IN_REVIEW, saved.status());
        assertFalse(saved.live());
        assertFalse(listing.isActive());
        // And the seller is told, rather than finding out from their sales.
        assertTrue(saved.message().contains("back for review"), saved.message());
    }

    @Test
    void rewritingTheDescriptionAlsoSendsItBack() {
        product(ProductStatus.PUBLISHED);

        VendorProductResponses.Saved saved = service.update(OWNER, PRODUCT,
                patchDescription("Actually a different product entirely."));

        assertTrue(saved.needsReview());
    }

    @Test
    void butRestockingDoesNot() {
        Product listing = product(ProductStatus.PUBLISHED);

        VendorProductResponses.Saved saved = service.update(OWNER, PRODUCT, patchStock(99));

        // A seller who had to re-enter the queue to change a stock count would
        // stop using the queue.
        assertFalse(saved.needsReview());
        assertEquals(ProductStatus.PUBLISHED, saved.status());
        assertTrue(saved.live());
        assertEquals(99, listing.getStock());
    }

    @Test
    void aListingAMdoeratorIsHoldingCannotBeEdited() {
        product(ProductStatus.IN_REVIEW);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.update(OWNER, PRODUCT, patchStock(5)));

        // Deciding about text that no longer exists is how something unapproved
        // ends up approved.
        assertTrue(refused.getMessage().contains("review"), refused.getMessage());
    }

    @Test
    void editingARefusedListingMakesItADraftAgain() {
        Product listing = product(ProductStatus.REJECTED);
        listing.setRejectionReason("The photograph is of a different product.");

        VendorProductResponses.Saved saved = service.update(OWNER, PRODUCT,
                patchDescription("Corrected."));

        assertEquals(ProductStatus.DRAFT, saved.status());
        assertFalse(saved.needsReview());
    }

    @Test
    void aPatchLeavesAloneWhatItDoesNotMention() {
        Product listing = product(ProductStatus.DRAFT);

        service.update(OWNER, PRODUCT, patchStock(42));

        assertEquals("1.7 litres, stainless steel.", listing.getDescription());
        assertEquals(0, new BigDecimal("450.00").compareTo(listing.getPrice()));
        assertEquals("Kettle", listing.getName());
    }

    @Test
    void renamingAListingDoesNotMoveItsSlug() {
        Product listing = product(ProductStatus.DRAFT);

        service.update(OWNER, PRODUCT, patch("Electric Kettle 1.7L", null, null));

        assertEquals("Electric Kettle 1.7L", listing.getName());
        // Buyers have the link saved and search engines have it indexed.
        assertEquals("kettle-a1b2c3", listing.getSlug());
    }

    // -- Removal -------------------------------------------------------------

    @Test
    void aListingNobodyOrderedIsGenuinelyDeleted() {
        product(ProductStatus.DRAFT);
        when(products.hasBeenOrdered(PRODUCT)).thenReturn(false);

        VendorProductResponses.Removed removed = service.remove(OWNER, PRODUCT);

        assertTrue(removed.deleted());
        verify(products).delete(any(Product.class));
    }

    @Test
    void aListingSomebodyBoughtIsArchivedInstead() {
        Product listing = product(ProductStatus.PUBLISHED);
        when(products.hasBeenOrdered(PRODUCT)).thenReturn(true);

        VendorProductResponses.Removed removed = service.remove(OWNER, PRODUCT);

        assertFalse(removed.deleted());
        assertEquals(ProductStatus.ARCHIVED, removed.status());
        verify(products, never()).delete(any(Product.class));
        // And it comes off sale on the way out.
        assertFalse(listing.isActive());
        assertNotNull(listing.getArchivedAt());
    }

    @Test
    void theOrderLinesDecideThatAndNotTheSoldCounter() {
        Product listing = product(ProductStatus.PUBLISHED);
        // Sold counter says nothing was ever bought - a cancelled order leaves
        // it at zero - while an order line still points at the listing.
        listing.setTotalSold(0);
        when(products.hasBeenOrdered(PRODUCT)).thenReturn(true);

        assertFalse(service.remove(OWNER, PRODUCT).deleted());
    }

    @Test
    void archivingTwiceIsNotAnError() {
        product(ProductStatus.ARCHIVED);

        VendorProductResponses.Removed removed = service.remove(OWNER, PRODUCT);
        assertFalse(removed.deleted());
        assertTrue(removed.message().contains("already"), removed.message());
    }

    // -- Translations --------------------------------------------------------

    @Test
    void aLocaleThisPlatformDoesNotServeIsRefused() {
        product(ProductStatus.DRAFT);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.putTranslation(OWNER, PRODUCT, "klingon",
                        new VendorProductRequests.PutTranslation("nuqneH", null, null, false)));

        // Otherwise a seller writes a translation nothing ever reads.
        assertTrue(refused.getMessage().contains("Supported"), refused.getMessage());
    }

    @Test
    void aLocaleIsMatchedHoweverItWasCased() {
        product(ProductStatus.DRAFT);
        when(translations.findByProductIdAndLocaleIgnoreCase(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(translations.save(any())).thenAnswer(call -> call.getArgument(0));

        VendorProductResponses.Translation saved = service.putTranslation(OWNER, PRODUCT, "FR-sn",
                new VendorProductRequests.PutTranslation("Bouilloire", null, null, true));

        assertEquals("fr-SN", saved.locale());
        assertTrue(saved.machineTranslated());
    }

    // -- Media ---------------------------------------------------------------

    @Test
    void theFirstImageBecomesTheCardImageWhetherOrNotAnybodyAsked() {
        product(ProductStatus.DRAFT);
        when(images.save(any(ProductImage.class))).thenAnswer(call -> call.getArgument(0));

        VendorProductResponses.Media media = service.confirmMedia(OWNER, PRODUCT,
                new VendorProductRequests.ConfirmMedia(
                        "products/kettle-1.jpg", "kettle.jpg", "image/jpeg", 90000L, "A kettle", null));

        // A listing whose card has no picture is one nobody clicks.
        assertTrue(media.isDefault());
        assertEquals(0, media.sortOrder());
    }

    @Test
    void aListingCannotHoldMoreImagesThanTheLimit() {
        product(ProductStatus.DRAFT);
        ReflectionTestUtils.setField(service, "maxImages", 2);
        when(images.findByProductIdOrderBySortOrderAsc(PRODUCT)).thenReturn(List.of(
                ProductImage.builder().id(1L).build(), ProductImage.builder().id(2L).build()));

        assertThrows(BadRequestException.class, () -> service.confirmMedia(OWNER, PRODUCT,
                new VendorProductRequests.ConfirmMedia("products/x.jpg", null, null, null, null, null)));
    }

    @Test
    void aPartialReorderIsRefusedRatherThanLeavingCollidingPositions() {
        product(ProductStatus.DRAFT);
        when(images.findByProductIdOrderBySortOrderAsc(PRODUCT)).thenReturn(new ArrayList<>(List.of(
                ProductImage.builder().id(1L).sortOrder(0).build(),
                ProductImage.builder().id(2L).sortOrder(1).build(),
                ProductImage.builder().id(3L).sortOrder(2).build())));

        assertThrows(BadRequestException.class, () -> service.reorderMedia(OWNER, PRODUCT,
                new VendorProductRequests.ReorderMedia(List.of(3L, 1L))));
    }

    @Test
    void reorderingHandsTheCardImageToWhateverIsNowFirst() {
        product(ProductStatus.DRAFT);
        ProductImage first = ProductImage.builder().id(1L).sortOrder(0).isDefault(true).build();
        ProductImage second = ProductImage.builder().id(2L).sortOrder(1).isDefault(false).build();
        when(images.findByProductIdOrderBySortOrderAsc(PRODUCT))
                .thenReturn(new ArrayList<>(List.of(first, second)));

        service.reorderMedia(OWNER, PRODUCT, new VendorProductRequests.ReorderMedia(List.of(2L, 1L)));

        assertTrue(second.isDefault());
        assertFalse(first.isDefault());
    }

    @Test
    void deletingTheCardImageDoesNotLeaveTheListingWithoutOne() {
        product(ProductStatus.DRAFT);
        ProductImage lead = ProductImage.builder().id(1L).sortOrder(0).isDefault(true).build();
        ProductImage next = ProductImage.builder().id(2L).sortOrder(1).isDefault(false).build();
        when(images.findByProductIdOrderBySortOrderAsc(PRODUCT))
                .thenReturn(new ArrayList<>(List.of(lead, next)));

        service.removeMedia(OWNER, PRODUCT, 1L);

        assertTrue(next.isDefault());
        assertEquals(0, next.getSortOrder());
    }

    // -- Patch helpers -------------------------------------------------------

    private static VendorProductRequests.UpdateProduct patch(String name, BigDecimal price,
                                                             String description) {
        return new VendorProductRequests.UpdateProduct(
                name, null, description, price, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static VendorProductRequests.UpdateProduct patchStock(int stock) {
        return new VendorProductRequests.UpdateProduct(
                null, null, null, null, null, null, stock, null, null,
                null, null, null, null, null, null, null);
    }

    private static VendorProductRequests.UpdateProduct patchDescription(String description) {
        return patch(null, null, description);
    }
}
