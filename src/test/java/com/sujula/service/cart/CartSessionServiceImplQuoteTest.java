package com.sujula.service.cart;

import com.sujula.dto.request.cart.CartRequests;
import com.sujula.dto.response.cart.CartQuoteResponse;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.model.constant.CouponScope;
import com.sujula.model.constant.CouponType;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartCoupon;
import com.sujula.model.order.CartQuote;
import com.sujula.model.order.CartQuoteVendorSnapshot;
import com.sujula.model.products.Coupon;
import com.sujula.model.user.Vendor;
import com.sujula.repository.order.CartQuoteRepository;
import com.sujula.repository.order.CartRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.CartService;
import com.sujula.service.cart.impl.CartSessionServiceImpl;
import com.sujula.service.delivery.DeliveryContextService;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartSessionServiceImplQuoteTest {

    private CartRepository carts;
    private CartQuoteRepository quotes;
    private CartService cartService;
    private CartSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        carts = mock(CartRepository.class);
        quotes = mock(CartQuoteRepository.class);
        cartService = mock(CartService.class);
        CurrencyCatalogue currencies = CurrencyCatalogue.of(new ReferenceDataProperties());
        service = new CartSessionServiceImpl(
                carts,
                quotes,
                mock(UserRepository.class),
                cartService,
                mock(DeliveryContextService.class),
                currencies);
        ReflectionTestUtils.setField(service, "quoteTtl", Duration.ofMinutes(15));
        when(quotes.save(any(CartQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void persistsCouponIdentityAndAssignsRoundingRemainderDeterministically() {
        Cart cart = cart("EUR");
        Coupon platform = coupon(9001L, "PLATFORM", CouponScope.PLATFORM);
        Coupon vendorCoupon = coupon(7002L, "VENDOR-B", CouponScope.VENDOR);
        Vendor vendorB = Vendor.builder().id(20L).settlementCurrency("EUR").build();
        cart.setAppliedCoupons(new ArrayList<>(List.of(
                CartCoupon.builder().cart(cart).coupon(platform).vendorKey(0L).build(),
                CartCoupon.builder().cart(cart).coupon(vendorCoupon).vendor(vendorB)
                        .vendorKey(20L).build())));

        CartResponse priced = CartResponse.builder()
                .displayCurrency("EUR")
                .deliveryContextId(cart.getDeliveryContextId())
                .totalsComplete(true)
                .deliverable(true)
                .subtotal(amount("30.00"))
                .discount(amount("0.03"))
                .shipping(amount("3.33"))
                .total(amount("33.30"))
                .vendors(List.of(
                        vendorGroup(10L, "GMD", "0.02000000",
                                "20.00", "0.01", "0.50", "0.01", "1.11",
                                line(101L, 10L, "GMD", "1000.00", "20.00", "1.11")),
                        vendorGroup(20L, "EUR", "1.00000000",
                                "10.00", "0.01", "0.01", "0.01", "2.22",
                                line(202L, 20L, "EUR", "10.00", "10.00", "2.22"))))
                .build();
        arrange(cart, priced);

        CartQuoteResponse response = service.quote(
                cart.getToken(), null, new CartRequests.Quote(DeliveryMode.HOME_DELIVERY, null));

        CartQuote saved = savedQuote();
        assertEquals(platform.getId(), saved.getPlatformCouponId());
        assertEquals(platform.getCode(), saved.getPlatformCouponCode());
        assertEquals(amount("0.03"), saved.getDiscount());
        assertEquals(saved.getDiscount(), saved.getVendorSnapshots().stream()
                .map(CartQuoteVendorSnapshot::getDiscountDisplay)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        CartQuoteVendorSnapshot vendorA = snapshot(saved, 10L);
        CartQuoteVendorSnapshot vendorBSnapshot = snapshot(saved, 20L);
        assertEquals(amount("0.01"), vendorA.getDiscountDisplay());
        assertEquals(amount("0.50"), vendorA.getDiscountNative());
        assertEquals(amount("0.02"), vendorBSnapshot.getDiscountDisplay(),
                "the highest vendor id is the stable remainder absorber");
        assertEquals(amount("0.02"), vendorBSnapshot.getDiscountNative());
        assertEquals(amount("0.02"), vendorBSnapshot.getPlatformDiscountShareDisplay());
        assertEquals(vendorCoupon.getId(), vendorBSnapshot.getVendorCouponId());
        assertEquals(vendorCoupon.getCode(), vendorBSnapshot.getVendorCouponCode());
        assertEquals(amount("33.30"), response.total());
    }

    @Test
    void roundsEveryPersistedXofAmountBeforeSavingTheQuote() {
        Cart cart = cart("XOF");
        CartResponse priced = CartResponse.builder()
                .displayCurrency("XOF")
                .deliveryContextId(cart.getDeliveryContextId())
                .totalsComplete(true)
                .deliverable(true)
                .subtotal(amount("1250.50"))
                .discount(amount("20.50"))
                .shipping(amount("10.50"))
                .total(amount("1240.50"))
                .vendors(List.of(vendorGroup(10L, "XOF", "1.00000000",
                        "1250.50", "20.50", "20.50", "0", "10.50",
                        line(101L, 10L, "XOF", "1250.50", "1250.50", "10.50"))))
                .build();
        arrange(cart, priced);

        service.quote(cart.getToken(), null,
                new CartRequests.Quote(DeliveryMode.HOME_DELIVERY, null));

        CartQuote saved = savedQuote();
        assertEquals(new BigDecimal("1251"), saved.getSubtotal());
        assertEquals(new BigDecimal("21"), saved.getDiscount());
        assertEquals(new BigDecimal("11"), saved.getShipping());
        assertEquals(new BigDecimal("1241"), saved.getTotal());
        assertWhole(saved.getSubtotal());
        assertWhole(saved.getDiscount());
        assertWhole(saved.getShipping());
        assertWhole(saved.getTotal());
        saved.getLines().forEach(line -> {
            assertWhole(line.getUnitPriceNative());
            assertWhole(line.getLineTotalNative());
            assertWhole(line.getUnitPrice());
            assertWhole(line.getLineTotal());
            assertWhole(line.getDeliveryCost());
        });
        saved.getVendorSnapshots().forEach(snapshot -> {
            assertWhole(snapshot.getDiscountDisplay());
            assertWhole(snapshot.getDiscountNative());
            assertWhole(snapshot.getPlatformDiscountShareDisplay());
        });
    }

    private void arrange(Cart cart, CartResponse priced) {
        when(carts.findByTokenWithItems(cart.getToken())).thenReturn(Optional.of(cart));
        when(cartService.getCart(any(CartOwner.class), anyString())).thenReturn(priced);
    }

    private CartQuote savedQuote() {
        ArgumentCaptor<CartQuote> captor = ArgumentCaptor.forClass(CartQuote.class);
        verify(quotes).save(captor.capture());
        return captor.getValue();
    }

    private static Cart cart(String currency) {
        return Cart.builder()
                .id(1L)
                .token("quote-cart-token")
                .sessionId("guest-session")
                .displayCurrency(currency)
                .deliveryContextId("delivery-context")
                .build();
    }

    private static Coupon coupon(long id, String code, CouponScope scope) {
        return Coupon.builder()
                .id(id)
                .code(code)
                .scope(scope)
                .type(CouponType.FIXED_AMOUNT)
                .value(BigDecimal.ONE)
                .build();
    }

    private static CartResponse.VendorGroup vendorGroup(
            long vendorId,
            String nativeCurrency,
            String rate,
            String subtotal,
            String discount,
            String discountNative,
            String platformShare,
            String shipping,
            CartResponse.CartItemResponse line) {
        return CartResponse.VendorGroup.builder()
                .vendorId(vendorId)
                .storeName("Vendor " + vendorId)
                .nativeCurrency(nativeCurrency)
                .exchangeRate(amount(rate))
                .convertible(true)
                .items(List.of(line))
                .subtotalNative(amount(line.getLineTotalNative().toPlainString()))
                .discountNative(amount(discountNative))
                .totalNative(amount(line.getLineTotalNative().toPlainString())
                        .subtract(amount(discountNative)))
                .subtotal(amount(subtotal))
                .discount(amount(discount))
                .platformDiscountShare(amount(platformShare))
                .shipping(amount(shipping))
                .total(amount(subtotal).subtract(amount(discount)).add(amount(shipping)))
                .deliverable(true)
                .checkoutable(true)
                .build();
    }

    private static CartResponse.CartItemResponse line(
            long productId,
            long vendorId,
            String nativeCurrency,
            String nativeTotal,
            String displayTotal,
            String delivery) {
        return CartResponse.CartItemResponse.builder()
                .productId(productId)
                .vendorId(vendorId)
                .quantity(1)
                .nativeCurrency(nativeCurrency)
                .unitPriceNative(amount(nativeTotal))
                .lineTotalNative(amount(nativeTotal))
                .unitPrice(amount(displayTotal))
                .lineTotal(amount(displayTotal))
                .deliveryCost(amount(delivery))
                .deliverable(true)
                .purchasable(true)
                .build();
    }

    private static CartQuoteVendorSnapshot snapshot(CartQuote quote, long vendorId) {
        return quote.getVendorSnapshots().stream()
                .filter(snapshot -> snapshot.getVendorId().equals(vendorId))
                .findFirst()
                .orElseThrow();
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static void assertWhole(BigDecimal amount) {
        assertTrue(amount.stripTrailingZeros().scale() <= 0,
                () -> amount + " contains a fractional XOF amount");
    }
}
