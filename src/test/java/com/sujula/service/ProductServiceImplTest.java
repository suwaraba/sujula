package com.sujula.service;

import com.sujula.dto.response.product.ProductResponse;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.BrandRepository;
import com.sujula.repository.product.CategoryRepository;
import com.sujula.repository.product.ProductImageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.impl.ProductCreateUpdateServiceImpl;
import com.sujula.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What each of the catalogue's four reads is allowed to return: the shop front
 * sees published products only, and a seller sees only their own.
 */
class ProductServiceImplTest {

    private ProductRepository productRepository;
    private VendorRepository vendorRepository;
    private ProductServiceImpl service;

    private Vendor vendor;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        vendorRepository = mock(VendorRepository.class);

        service = new ProductServiceImpl(
                productRepository,
                mock(ProductImageRepository.class),
                mock(CategoryRepository.class),
                mock(BrandRepository.class),
                mock(VendorService.class),
                mock(StorageService.class),
                vendorRepository,
                mock(ProductVariantRepository.class),
                mock(ExchangeRateService.class),
                mock(ProductCreateUpdateServiceImpl.class));

        User owner = new User();
        owner.setId(4L);
        vendor = Vendor.builder()
                .id(2L)
                .user(owner)
                .storeName("Kombo Electronics")
                .storeSlug("kombo-electronics")
                .settlementCurrency("GMD")
                .build();
    }

    private Product product(long id, Vendor productVendor, boolean active) {
        Product product = new Product();
        product.setId(id);
        product.setName("Kettle");
        product.setSlug("kettle-" + id);
        product.setPrice(new BigDecimal("450.00"));
        product.setPriceCurrency("GMD");
        product.setStock(3);
        product.setActive(active);
        product.setVendor(productVendor);
        return product;
    }

    // ── Shop front ───────────────────────────────────────────────────────────

    @Test
    void publishedProductIsReturnedToAnyone() {
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L, vendor, true)));

        ProductResponse response = service.findPublishedById(7L);

        assertEquals(7L, response.getId());
        assertEquals("GMD", response.getPriceCurrency());
    }

    @Test
    void unpublishedProductIsNotFoundRatherThanForbidden() {
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L, vendor, false)));

        // Withdrawing a listing is an unpublish, never a row delete — but to a
        // shopper it has to be indistinguishable from a product that never was.
        assertThrows(ResourceNotFoundException.class, () -> service.findPublishedById(7L));
    }

    @Test
    void missingProductIsNotFound() {
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findPublishedById(7L));
    }

    // ── Seller back office ───────────────────────────────────────────────────

    @Test
    void vendorSeesOwnUnpublishedProducts() {
        when(vendorRepository.findByUserId(4L)).thenReturn(Optional.of(vendor));
        Page<Product> page = new PageImpl<>(List.of(product(7L, vendor, false)));
        when(productRepository.findByVendorIdOrderByCreatedAtDesc(eq(2L), any(Pageable.class)))
                .thenReturn(page);

        Page<ProductResponse> result = service.findMyProducts(4L, PageRequest.of(0, 20));

        assertEquals(1, result.getContent().size());
        // Otherwise a seller could unpublish a product and never find it again.
        assertFalse(result.getContent().get(0).isActive());
    }

    @Test
    void vendorReadingOwnProductGetsIt() {
        when(vendorRepository.findByUserId(4L)).thenReturn(Optional.of(vendor));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L, vendor, false)));

        assertEquals(7L, service.findMineById(4L, 7L).getId());
    }

    @Test
    void vendorCannotReadAnotherVendorsProduct() {
        Vendor rival = Vendor.builder().id(99L).storeName("Rival").build();
        when(vendorRepository.findByUserId(4L)).thenReturn(Optional.of(vendor));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L, rival, true)));

        // Not found, not forbidden: a probe should not confirm the id was real,
        // and stock levels are the other seller's business.
        assertThrows(ResourceNotFoundException.class, () -> service.findMineById(4L, 7L));
    }

    @Test
    void userWithoutAVendorAccountHasNoCatalogue() {
        when(vendorRepository.findByUserId(4L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.findMyProducts(4L, PageRequest.of(0, 20)));
    }
}
