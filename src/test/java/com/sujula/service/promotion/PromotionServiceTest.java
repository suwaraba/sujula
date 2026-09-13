package com.sujula.service.promotion;

import com.sujula.dto.request.promotion.PromotionRequests;
import com.sujula.dto.response.promotion.PromotionResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CouponType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PromotionStatus;
import com.sujula.model.constant.PromotionType;
import com.sujula.model.products.Coupon;
import com.sujula.model.products.Product;
import com.sujula.model.promotion.Promotion;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.CouponRepository;
import com.sujula.repository.product.CouponUsageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.promotion.PromotionRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.promotion.impl.PromotionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * A seller's discounts, and the one check that stops them fighting.
 *
 * <p>Most of these are about activation. Two promotions discounting the same
 * product over the same dates do not compound into a sensible price - they
 * compound into whichever the pricing code reaches first, which is a different
 * answer on different days and an argument with a buyer either way.
 */
@SuppressWarnings("unchecked")
class PromotionServiceTest {

    private static final Long OWNER = 980L;
    private static final Long INTRUDER = 981L;
    private static final Long VENDOR = 6300L;
    private static final Long PROMO = 8100L;

    private PromotionRepository promotions;
    private CouponRepository coupons;
    private CouponUsageRepository redemptions;
    private ProductRepository products;
    private VendorRepository vendors;
    private PromotionServiceImpl service;

    @BeforeEach
    void setUp() {
        promotions = mock(PromotionRepository.class);
        coupons = mock(CouponRepository.class);
        redemptions = mock(CouponUsageRepository.class);
        products = mock(ProductRepository.class);
        vendors = mock(VendorRepository.class);

        service = new PromotionServiceImpl(promotions, coupons, redemptions, products, vendors);

        when(vendors.findByUserId(OWNER)).thenReturn(Optional.of(vendor()));
        when(vendors.findByUserId(INTRUDER)).thenReturn(Optional.of(
                Vendor.builder().id(9999L).settlementCurrency("XOF").build()));
        when(promotions.save(any(Promotion.class))).thenAnswer(call -> call.getArgument(0));
        when(coupons.save(any(Coupon.class))).thenAnswer(call -> call.getArgument(0));
        when(promotions.findOverlapping(anyLong(), anyLong(), any(), any())).thenReturn(List.of());
        // Owned ids come back in one query rather than one per named product.
        when(products.findOwnedIds(any(), anyLong()))
                .thenAnswer(call -> List.copyOf((java.util.Collection<Long>) call.getArgument(0)));
        when(coupons.existsByCodeIgnoreCase(anyString())).thenReturn(false);
    }

    private static Vendor vendor() {
        return Vendor.builder().id(VENDOR).status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD").build();
    }

    private Promotion promotion(PromotionStatus status, Set<Long> productIds) {
        Promotion built = Promotion.builder()
                .id(PROMO).vendor(vendor()).name("Tobaski sale")
                .type(PromotionType.PERCENT).percentOff(new BigDecimal("15.00"))
                .currency("GMD").status(status)
                .productIds(new java.util.LinkedHashSet<>(productIds))
                .categoryIds(new java.util.LinkedHashSet<>())
                .startsAt(LocalDateTime.now().minusDays(1))
                .endsAt(LocalDateTime.now().plusDays(7))
                .timesApplied(0)
                .build();
        when(promotions.findByIdAndVendorId(PROMO, VENDOR)).thenReturn(Optional.of(built));
        when(promotions.findByIdAndVendorId(PROMO, 9999L)).thenReturn(Optional.empty());
        return built;
    }

    private static PromotionRequests.CreatePromotion percentOff(String percent) {
        return new PromotionRequests.CreatePromotion(
                "Tobaski sale", "15% off phones", PromotionType.PERCENT,
                new BigDecimal(percent), null, null, null, null, null, null, null,
                Set.of(1301L), null,
                LocalDateTime.now(), LocalDateTime.now().plusDays(7));
    }

    // -- C2 ------------------------------------------------------------------

    @Test
    void aFixedDiscountIsDenominatedInTheSellersOwnCurrency() {
        service.create(OWNER, new PromotionRequests.CreatePromotion(
                "500 off", null, PromotionType.FIXED, null, new BigDecimal("500.00"),
                null, null, null, null, null, null, Set.of(1301L), null,
                null, LocalDateTime.now().plusDays(3)));

        ArgumentCaptor<Promotion> saved = ArgumentCaptor.forClass(Promotion.class);
        verify(promotions).save(saved.capture());

        // There is no currency field on the request, and this is why: a seller
        // funding an amount struck in EUR would be funding a number that moves
        // with the market between writing the promotion and the order landing.
        assertEquals("GMD", saved.getValue().getCurrency());
    }

    // -- Ownership -----------------------------------------------------------

    @Test
    void anotherSellersPromotionIsNotFound() {
        promotion(PromotionStatus.ACTIVE, Set.of(1301L));

        assertThrows(ResourceNotFoundException.class, () -> service.activate(INTRUDER, PROMO));
        assertThrows(ResourceNotFoundException.class, () -> service.delete(INTRUDER, PROMO));
    }

    @Test
    void aPromotionCannotNameSomebodyElsesListing() {
        // The batch returns only what this seller owns - here, nothing.
        // doReturn rather than when(...): re-stubbing through when() calls the
        // mock, which runs the existing answer with null arguments first.
        org.mockito.Mockito.doReturn(List.of())
                .when(products).findOwnedIds(any(), anyLong());

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.create(OWNER, new PromotionRequests.CreatePromotion(
                        "Sale", null, PromotionType.PERCENT, new BigDecimal("10"),
                        null, null, null, null, null, null, null, Set.of(4242L), null,
                        null, null)));

        // Refused rather than silently dropped: a seller who thought they had
        // discounted something and had not would find out from their sales.
        assertTrue(refused.getMessage().contains("not one of yours"), refused.getMessage());
    }

    // -- Creation ------------------------------------------------------------

    @Test
    void aNewPromotionIsADraftAndDiscountsNothing() {
        PromotionResponses.Promotion created = service.create(OWNER, percentOff("15.00"));

        assertEquals(PromotionStatus.DRAFT, created.status());
        assertFalse(created.running());
        assertTrue(created.blockedReason().contains("Activate"), created.blockedReason());
    }

    @Test
    void aDiscountOverOneHundredPercentIsRefused() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.create(OWNER, percentOff("150.00")));
        assertTrue(refused.getMessage().contains("pay the buyer"), refused.getMessage());
    }

    @Test
    void eachTypeHasToCarryItsOwnFields() {
        // BUY_X_GET_Y with no quantities is a discount at a price nobody chose.
        assertThrows(BadRequestException.class, () -> service.create(OWNER,
                new PromotionRequests.CreatePromotion("BOGOF", null, PromotionType.BUY_X_GET_Y,
                        null, null, null, null, null, null, null, null, null, null, null, null)));

        // A bundle of one product is just a price.
        assertThrows(BadRequestException.class, () -> service.create(OWNER,
                new PromotionRequests.CreatePromotion("Bundle", null, PromotionType.BUNDLE,
                        null, null, null, null, null, new BigDecimal("9000"), null, null,
                        Set.of(1301L), null, null, null)));
    }

    @Test
    void freeShippingNeedsNothingButItsWindow() {
        PromotionResponses.Promotion created = service.create(OWNER,
                new PromotionRequests.CreatePromotion("Free delivery", null,
                        PromotionType.FREE_SHIPPING, null, null, null, null, null, null,
                        null, null, null, null, null, LocalDateTime.now().plusDays(5)));

        assertEquals(PromotionType.FREE_SHIPPING, created.type());
        assertTrue(created.storeWide());
    }

    @Test
    void aWindowThatEndsBeforeItStartsIsRefused() {
        assertThrows(BadRequestException.class, () -> service.create(OWNER,
                new PromotionRequests.CreatePromotion("Backwards", null, PromotionType.PERCENT,
                        new BigDecimal("10"), null, null, null, null, null, null, null, null, null,
                        LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(1))));
    }

    // -- Activation and overlap ----------------------------------------------

    @Test
    void activatingWithNothingInTheWayGoesLive() {
        promotion(PromotionStatus.DRAFT, Set.of(1301L));

        PromotionResponses.Activated result = service.activate(OWNER, PROMO);

        assertEquals(PromotionStatus.ACTIVE, result.status());
        assertTrue(result.running());
    }

    @Test
    void twoPromotionsOnTheSameProductCannotBothRun() {
        promotion(PromotionStatus.DRAFT, Set.of(1301L, 1302L));
        when(promotions.findOverlapping(anyLong(), anyLong(), any(), any())).thenReturn(List.of(
                Promotion.builder().id(9L).name("Ramadan offer").type(PromotionType.PERCENT)
                        .productIds(Set.of(1302L)).categoryIds(Set.of())
                        .startsAt(LocalDateTime.now().minusDays(2))
                        .endsAt(LocalDateTime.now().plusDays(10))
                        .build()));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.activate(OWNER, PROMO));

        // Naming the one in the way, because "conflicts with an existing
        // promotion" leaves a seller hunting through their own list.
        assertTrue(refused.getMessage().contains("Ramadan offer"), refused.getMessage());
        assertTrue(refused.getMessage().contains("predict"), refused.getMessage());
    }

    @Test
    void aStoreWidePromotionConflictsWithEverything() {
        promotion(PromotionStatus.DRAFT, Set.of());
        when(promotions.findOverlapping(anyLong(), anyLong(), any(), any())).thenReturn(List.of(
                Promotion.builder().id(9L).name("Phones only").type(PromotionType.PERCENT)
                        .productIds(Set.of(1301L)).categoryIds(Set.of())
                        .endsAt(LocalDateTime.now().plusDays(3))
                        .build()));

        assertThrows(BadRequestException.class, () -> service.activate(OWNER, PROMO));
    }

    @Test
    void butFreeShippingAndAPriceDiscountCanRunTogether() {
        Promotion shipping = promotion(PromotionStatus.DRAFT, Set.of());
        shipping.setType(PromotionType.FREE_SHIPPING);
        when(promotions.findOverlapping(anyLong(), anyLong(), any(), any())).thenReturn(List.of(
                Promotion.builder().id(9L).name("15% off").type(PromotionType.PERCENT)
                        .productIds(Set.of(1301L)).categoryIds(Set.of())
                        .endsAt(LocalDateTime.now().plusDays(3))
                        .build()));

        // One discounts the delivery leg and the other the goods, so they are
        // not competing for the same number.
        PromotionResponses.Activated result = service.activate(OWNER, PROMO);
        assertEquals(PromotionStatus.ACTIVE, result.status());
    }

    @Test
    void promotionsOnDifferentProductsCanBothRun() {
        promotion(PromotionStatus.DRAFT, Set.of(1301L));
        when(promotions.findOverlapping(anyLong(), anyLong(), any(), any())).thenReturn(List.of(
                Promotion.builder().id(9L).name("Cloth sale").type(PromotionType.PERCENT)
                        .productIds(Set.of(1305L)).categoryIds(Set.of())
                        .endsAt(LocalDateTime.now().plusDays(3))
                        .build()));

        assertEquals(PromotionStatus.ACTIVE, service.activate(OWNER, PROMO).status());
    }

    @Test
    void activatingTwiceIsNotAnError() {
        promotion(PromotionStatus.ACTIVE, Set.of(1301L));

        PromotionResponses.Activated result = service.activate(OWNER, PROMO);
        assertTrue(result.message().contains("already"), result.message());
    }

    @Test
    void aScheduledPromotionIsActiveWithoutRunningYet() {
        Promotion scheduled = promotion(PromotionStatus.DRAFT, Set.of(1301L));
        scheduled.setStartsAt(LocalDateTime.now().plusDays(3));

        PromotionResponses.Activated result = service.activate(OWNER, PROMO);

        assertEquals(PromotionStatus.ACTIVE, result.status());
        assertFalse(result.running());
    }

    // -- Removal -------------------------------------------------------------

    @Test
    void aPromotionNobodyUsedIsDeleted() {
        promotion(PromotionStatus.DRAFT, Set.of(1301L));

        service.delete(OWNER, PROMO);
        verify(promotions).delete(any(Promotion.class));
    }

    @Test
    void oneThatHasDiscountedSomethingIsCancelledInstead() {
        Promotion used = promotion(PromotionStatus.ACTIVE, Set.of(1301L));
        used.setTimesApplied(14);

        service.delete(OWNER, PROMO);

        // An order placed under it has to keep explaining why it cost what it
        // did, years later.
        verify(promotions, never()).delete(any(Promotion.class));
        assertEquals(PromotionStatus.CANCELLED, used.getStatus());
    }

    // -- Coupons -------------------------------------------------------------

    @Test
    void aCouponCodeIsUpperCasedAndVendorFunded() {
        PromotionResponses.Coupon created = service.createCoupon(OWNER,
                new PromotionRequests.CreateCoupon("tobaski10", "10% off", CouponType.PERCENTAGE,
                        new BigDecimal("10.00"), new BigDecimal("2000.00"), null,
                        100, 1, null, LocalDateTime.now().plusDays(30)));

        assertEquals("TOBASKI10", created.code());
        assertEquals("GMD", created.currency());

        ArgumentCaptor<Coupon> saved = ArgumentCaptor.forClass(Coupon.class);
        verify(coupons).save(saved.capture());
        // A seller cannot issue a coupon the platform pays for.
        assertEquals(com.sujula.model.constant.CouponScope.VENDOR, saved.getValue().getScope());
    }

    @Test
    void aCodeAlreadyInUseAnywhereIsRefused() {
        when(coupons.existsByCodeIgnoreCase("TOBASKI10")).thenReturn(true);

        // A buyer holding a word that means one thing in one shop and something
        // else in another is a support ticket nobody can resolve.
        assertThrows(BadRequestException.class, () -> service.createCoupon(OWNER,
                new PromotionRequests.CreateCoupon("tobaski10", null, CouponType.PERCENTAGE,
                        new BigDecimal("10"), null, null, null, null, null, null)));
    }

    @Test
    void theUsageLimitCannotBeSetBelowWhatHasAlreadyBeenRedeemed() {
        Coupon coupon = Coupon.builder().id(700L).code("TOBASKI10").vendor(vendor())
                .type(CouponType.PERCENTAGE).value(new BigDecimal("10"))
                .usageCount(40).usageLimit(100).active(true).build();
        when(coupons.findByIdAndVendorId(700L, VENDOR)).thenReturn(Optional.of(coupon));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.updateCoupon(OWNER, 700L, new PromotionRequests.UpdateCoupon(
                        null, null, null, null, 10, null, null, null, null)));

        assertTrue(refused.getMessage().contains("Turn it off"), refused.getMessage());
    }

    @Test
    void aFullyRedeemedCouponSaysSoRatherThanLookingLive() {
        Coupon coupon = Coupon.builder().id(701L).code("SPENT").vendor(vendor())
                .type(CouponType.PERCENTAGE).value(new BigDecimal("10"))
                .usageCount(100).usageLimit(100).active(true).build();
        when(coupons.findByIdAndVendorId(701L, VENDOR)).thenReturn(Optional.of(coupon));

        PromotionResponses.Coupon updated = service.updateCoupon(OWNER, 701L,
                new PromotionRequests.UpdateCoupon(null, null, null, null, null, null, null, null, null));

        assertFalse(updated.redeemable());
        assertEquals(0, updated.remaining());
        assertTrue(updated.blockedReason().contains("Fully redeemed"), updated.blockedReason());
    }

    @Test
    void aFreeShippingCouponNeedsNoValue() {
        PromotionResponses.Coupon created = service.createCoupon(OWNER,
                new PromotionRequests.CreateCoupon("FREESHIP", null, CouponType.FREE_SHIPPING,
                        null, null, null, null, 1, null, null));

        assertEquals(CouponType.FREE_SHIPPING, created.type());
        assertTrue(created.redeemable());
    }
}
