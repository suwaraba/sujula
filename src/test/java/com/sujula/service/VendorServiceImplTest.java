package com.sujula.service;

import com.sujula.dto.response.VendorStorefrontResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.impl.VendorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** The settlement currency, and what a shopper is allowed to see of a store. */
class VendorServiceImplTest {

    private VendorRepository vendorRepository;
    private ProductRepository productRepository;
    private VendorServiceImpl service;
    private Vendor vendor;

    @BeforeEach
    void setUp() {
        vendorRepository = mock(VendorRepository.class);
        productRepository = mock(ProductRepository.class);

        service = new VendorServiceImpl(vendorRepository, mock(UserRepository.class), productRepository,
                mock(EmailService.class), mock(AuditService.class), mock(GoogleMapsService.class));

        User owner = new User();
        owner.setId(4L);
        owner.setEmail("seller@sujula.gm");
        owner.setFirstName("Modou");
        owner.setLastName("Njie");

        vendor = Vendor.builder()
                .id(2L)
                .user(owner)
                .storeName("Kombo Electronics")
                .storeSlug("kombo-electronics")
                .description("Phones and accessories")
                .storeEmail("shop@kombo.gm")
                .addressStreet("14 Kairaba Avenue")
                .addressCity("Serekunda")
                .addressPostalCode("KSMD")
                .addressCountryCode("GM")
                .settlementCurrency("GMD")
                .status(PartnerStatus.APPROVED)
                .businessRegistrationNumber("BR-99812")
                .taxNumber("TIN-4471")
                .build();

        when(vendorRepository.findById(2L)).thenReturn(Optional.of(vendor));
        when(vendorRepository.findByStoreSlug("kombo-electronics")).thenReturn(Optional.of(vendor));
        when(vendorRepository.save(any(Vendor.class))).thenAnswer(i -> i.getArgument(0));
        when(productRepository.countByVendorIdAndPriceCurrencyNot(anyLong(), anyString())).thenReturn(0L);
    }

    // ── Settlement currency ──────────────────────────────────────────────────

    @Test
    void changesTheSettlementCurrencyWhenNothingIsListedInTheOldOne() {
        assertEquals("XOF", service.updateSettlementCurrency(2L, "xof").getSettlementCurrency());
        assertEquals("XOF", vendor.getSettlementCurrency());
    }

    @Test
    void refusesTheChangeWhileListingsAreStillPricedInTheOldCurrency() {
        when(productRepository.countByVendorIdAndPriceCurrencyNot(2L, "XOF")).thenReturn(7L);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.updateSettlementCurrency(2L, "XOF"));

        // Switching under live listings takes the store off sale without saying so:
        // checkout refuses any product priced in another currency.
        assertTrue(error.getMessage().contains("7 listing"));
        assertEquals("GMD", vendor.getSettlementCurrency());
        verify(vendorRepository, never()).save(any());
    }

    @Test
    void rejectsAnythingThatIsNotAnIsoCode() {
        assertThrows(BadRequestException.class, () -> service.updateSettlementCurrency(2L, "GMDD"));
        assertThrows(BadRequestException.class, () -> service.updateSettlementCurrency(2L, "G1D"));
        assertThrows(BadRequestException.class, () -> service.updateSettlementCurrency(2L, null));
    }

    @Test
    void changingToTheCurrentCurrencyIsAQuietNoOp() {
        service.updateSettlementCurrency(2L, "GMD");

        verify(vendorRepository, never()).save(any());
    }

    // ── Storefront ───────────────────────────────────────────────────────────

    @Test
    void theStorefrontShowsTheShopAndNotTheSellersBusiness() {
        VendorStorefrontResponse store = service.findStorefrontBySlug("kombo-electronics");

        assertEquals("Kombo Electronics", store.getStoreName());
        assertEquals("Serekunda", store.getAddressCity());
        assertEquals("GMD", store.getSettlementCurrency());

        // What a shopper has no business seeing is absent by construction — the
        // projection has no field for a balance, a tax number or the owner's email.
        assertTrue(java.util.Arrays.stream(VendorStorefrontResponse.class.getDeclaredFields())
                        .map(java.lang.reflect.Field::getName)
                        .noneMatch(name -> name.equals("balance") || name.equals("taxNumber")
                                || name.equals("businessRegistrationNumber") || name.equals("ownerEmail")),
                "the storefront projection must not carry the seller's private figures");
    }

    @Test
    void aStoreThatCannotTradeIsNotAStore() {
        vendor.setStatus(PartnerStatus.PENDING);

        // Not-found rather than forbidden: a pending application should not be
        // discoverable by guessing slugs.
        assertThrows(ResourceNotFoundException.class,
                () -> service.findStorefrontBySlug("kombo-electronics"));
    }

    @Test
    void anUnknownSlugIsNotFound() {
        when(vendorRepository.findByStoreSlug("nope")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findStorefrontBySlug("nope"));
    }

    // ── The rule that used to live in four places ────────────────────────────

    @Test
    void onlyApprovedAndActivePartnersMayTrade() {
        assertTrue(PartnerStatus.APPROVED.canTrade());
        assertTrue(PartnerStatus.ACTIVE.canTrade());
        assertTrue(!PartnerStatus.PENDING.canTrade());
        assertTrue(!PartnerStatus.SUSPENDED.canTrade());
        assertTrue(!PartnerStatus.REJECTED.canTrade());
    }

    @Test
    void aStorefrontOfANewVendorReportsNoTradingHistoryRatherThanNothing() {
        vendor.setRating(BigDecimal.ZERO);
        vendor.setTotalReviews(0);

        VendorStorefrontResponse store = service.findStorefrontBySlug("kombo-electronics");

        assertEquals(0, store.getRating().compareTo(BigDecimal.ZERO));
        assertEquals(0, store.getTotalReviews());
        assertNull(store.getWebsite(), "an unset field stays absent rather than empty");
    }
}
