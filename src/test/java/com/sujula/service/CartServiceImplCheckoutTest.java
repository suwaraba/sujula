package com.sujula.service;

import com.sujula.dto.response.order.CartResponse;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartItem;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.order.CartItemRepository;
import com.sujula.repository.order.CartRepository;
import com.sujula.repository.product.CouponRepository;
import com.sujula.repository.product.CouponUsageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.service.cart.CartProvisioner;
import com.sujula.service.delivery.DeliveryContextService;
import com.sujula.service.impl.CartServiceImpl;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CartServiceImplCheckoutTest {

    @Test
    void checkoutReadReturnsOnlyStructureWithoutLiveMonetaryRepricing() {
        CartRepository carts = mock(CartRepository.class);
        CartItemRepository items = mock(CartItemRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        ProductVariantRepository variants = mock(ProductVariantRepository.class);
        CouponRepository coupons = mock(CouponRepository.class);
        CouponUsageRepository couponUsages = mock(CouponUsageRepository.class);
        ExchangeRateService exchangeRates = mock(ExchangeRateService.class);
        CartProvisioner provisioner = mock(CartProvisioner.class);
        DeliveryPricingService delivery = mock(DeliveryPricingService.class);
        DeliveryContextService deliveryContexts = mock(DeliveryContextService.class);
        CartServiceImpl service = new CartServiceImpl(carts, items, products, variants, coupons,
                couponUsages, exchangeRates, provisioner, delivery, deliveryContexts,
                CurrencyCatalogue.of(new ReferenceDataProperties()));

        User buyer = User.builder().id(7L).build();
        Vendor vendor = Vendor.builder().id(11L).storeName("Frozen Store")
                .storeSlug("frozen-store").status(PartnerStatus.ACTIVE).build();
        Product product = Product.builder().id(101L).name("Changed live listing")
                .slug("changed-live-listing").price(new BigDecimal("9999.99"))
                .priceCurrency("USD").vendor(vendor).active(true).build();
        Cart cart = Cart.builder().id(3L).user(buyer).displayCurrency("EUR")
                .deliveryContextId("delivery-context-1").build();
        CartItem line = CartItem.builder().id(5L).cart(cart).product(product).vendor(vendor)
                .quantity(2).unitPrice(new BigDecimal("1000.00")).unitPriceCurrency("GMD")
                .build();
        cart.setItems(List.of(line));
        when(carts.findByIdForUpdate(3L)).thenReturn(Optional.of(cart));

        CartResponse result = service.getCartForCheckout(7L, 3L);

        assertEquals(3L, result.getCartId());
        assertEquals("EUR", result.getDisplayCurrency());
        assertEquals("delivery-context-1", result.getDeliveryContextId());
        assertEquals(1, result.getVendors().size());
        assertEquals(11L, result.getVendors().get(0).getVendorId());
        assertEquals(101L, result.getVendors().get(0).getItems().get(0).getProductId());
        assertEquals(2, result.getVendors().get(0).getItems().get(0).getQuantity());
        assertNull(result.getSubtotal());
        assertNull(result.getTotal());

        verify(items).findByCartIdWithProductGraph(3L);
        verify(items).findByCartIdWithVariantGraph(3L);
        verifyNoInteractions(products, variants, coupons, couponUsages, exchangeRates,
                provisioner, delivery, deliveryContexts);
    }
}
