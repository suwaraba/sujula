package com.sujula.service.impl;

import com.sujula.model.Address;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.FxSource;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartQuote;
import com.sujula.model.order.CartQuoteLine;
import com.sujula.model.order.CartQuoteVendorSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.OrderStatusHistoryRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.product.CouponRepository;
import com.sujula.repository.product.CouponUsageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.CartService;
import com.sujula.service.DeliveryPricingService;
import com.sujula.service.EmailService;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.NotificationService;
import com.sujula.service.inventory.StockLedger;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderServiceFrozenQuoteTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final VendorOrderRepository vendorOrders = mock(VendorOrderRepository.class);
    private final OrderStatusHistoryRepository statusHistory = mock(OrderStatusHistoryRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductVariantRepository variants = mock(ProductVariantRepository.class);
    private final StockLedger stock = mock(StockLedger.class);
    private final UserRepository users = mock(UserRepository.class);
    private final VendorRepository vendors = mock(VendorRepository.class);
    private final AddressRepository addresses = mock(AddressRepository.class);
    private final PickupPointRepository pickupPoints = mock(PickupPointRepository.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CouponUsageRepository couponUsages = mock(CouponUsageRepository.class);
    private final ExchangeRateService exchangeRates = mock(ExchangeRateService.class);
    private final CartService carts = mock(CartService.class);
    private final DeliveryPricingService delivery = mock(DeliveryPricingService.class);
    private final EmailService email = mock(EmailService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private OrderServiceImpl service;
    private User customer;
    private Address address;
    private Product gmdProduct;
    private Product xofProduct;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orders, vendorOrders, statusHistory, products, variants,
                stock, users, vendors, addresses, pickupPoints, coupons, couponUsages,
                exchangeRates, carts, delivery, email, notifications,
                CurrencyCatalogue.of(new ReferenceDataProperties()));

        customer = User.builder().id(7L).email("buyer@example.test").build();
        address = Address.builder().id(9L).user(customer).label("Home")
                .fullName("Buyer Example").phone("+2203000000").street("1 Test Street")
                .city("Banjul").countryCode("GM").build();

        Vendor gmdVendor = Vendor.builder().id(11L).storeName("GMD Store")
                .storeSlug("gmd-store").status(PartnerStatus.ACTIVE)
                .settlementCurrency("USD")
                .defaultCommissionRate(new BigDecimal("10.00")).build();
        Vendor xofVendor = Vendor.builder().id(22L).storeName("XOF Store")
                .storeSlug("xof-store").status(PartnerStatus.ACTIVE)
                .settlementCurrency("GMD")
                .defaultCommissionRate(new BigDecimal("7.50")).build();

        // Deliberately unrelated live prices/currencies: the held quote is authoritative.
        gmdProduct = Product.builder().id(101L).name("Quoted GMD item").slug("quoted-gmd")
                .sku("GMD-LIVE").price(new BigDecimal("9999.99")).priceCurrency("USD")
                .vendor(gmdVendor).stock(50).active(true).build();
        xofProduct = Product.builder().id(202L).name("Quoted XOF item").slug("quoted-xof")
                .sku("XOF-LIVE").price(new BigDecimal("8888.88")).priceCurrency("GMD")
                .vendor(xofVendor).stock(50).active(true).build();

        when(users.findById(7L)).thenReturn(Optional.of(customer));
        when(addresses.findLiveByIdAndUserId(9L, 7L)).thenReturn(Optional.of(address));
        when(products.findByIdForUpdate(101L)).thenReturn(Optional.of(gmdProduct));
        when(products.findByIdForUpdate(202L)).thenReturn(Optional.of(xofProduct));
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(vendorOrders.save(any(VendorOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsTwoVendorOrderFromFrozenQuoteWithoutLiveMonetaryLookups() {
        CartQuote quote = frozenQuote();

        Order order = service.createFromQuote(7L, 9L, "frozen", quote);

        assertMoney("32.51", order.getSubtotal());
        assertMoney("2.51", order.getDiscount());
        assertMoney("3.57", order.getShippingCost());
        assertMoney("33.57", order.getTotal());
        assertEquals("EUR", order.getCurrency());
        assertEquals("PLATFORM15", order.getCouponCode());
        assertSame(address, order.getShippingAddress());

        assertEquals(2, order.getVendorOrders().size());
        VendorOrder gmd = slice(order, 11L);
        assertEquals("GMD", gmd.getNativeCurrency());
        assertMoney("1000.00", gmd.getSubtotalNative());
        assertMoney("100.00", gmd.getDiscountNative());
        assertMoney("900.00", gmd.getTotalNative());
        assertMoney("90.00", gmd.getCommissionNative());
        assertMoney("810.00", gmd.getPayoutNative());
        assertMoney("60.00", gmd.getDeliveryNative());
        assertMoney("18.00", gmd.getTotal());

        VendorOrder xof = slice(order, 22L);
        assertEquals("XOF", xof.getNativeCurrency());
        assertMoney("1251", xof.getSubtotalNative());
        assertMoney("51", xof.getDiscountNative());
        assertMoney("1200", xof.getTotalNative());
        assertMoney("90", xof.getCommissionNative());
        assertMoney("1110", xof.getPayoutNative());
        assertMoney("237", xof.getDeliveryNative());
        assertMoney("12.00", xof.getTotal());
        assertEquals("VENDOR51", xof.getCouponCode());

        assertEquals(FxSource.HELD_QUOTE, gmd.getFx().getSource());
        assertEquals("held-order-test", gmd.getFx().getQuoteId());
        assertMoney("0.02000000", gmd.getFx().getRate());
        assertEquals(FxSource.HELD_QUOTE, xof.getFx().getSource());
        assertMoney("0.01000000", xof.getFx().getRate());

        assertMoney("1000.00", gmd.getItems().get(0).getUnitPrice());
        assertMoney("20.00", gmd.getItems().get(0).getUnitPriceConverted());
        assertMoney("1251", xof.getItems().get(0).getUnitPrice());
        assertMoney("12.51", xof.getItems().get(0).getUnitPriceConverted());

        verify(stock).adjustProduct(gmdProduct, -1, com.sujula.model.constant.StockMovementReason.SALE,
                null, "Reserved for an order", null);
        verify(stock).adjustProduct(xofProduct, -1, com.sujula.model.constant.StockMovementReason.SALE,
                null, "Reserved for an order", null);
        verify(carts).clearCart(any());
        verify(coupons, never()).findById(any());
        verifyNoInteractions(exchangeRates, delivery, couponUsages);
    }

    @Test
    void sameAggregateDiscountWithDifferentVendorAllocationsChangesEachSettlement() {
        Order original = service.createFromQuote(7L, 9L, null, frozenQuote());

        CartQuote reallocatedQuote = frozenQuote();
        CartQuoteVendorSnapshot gmdAllocation = reallocatedQuote.getVendorSnapshots().get(0);
        gmdAllocation.setDiscountDisplay(new BigDecimal("1.00"));
        gmdAllocation.setDiscountNative(new BigDecimal("50.00"));
        gmdAllocation.setPlatformDiscountShareDisplay(new BigDecimal("0.50"));
        CartQuoteVendorSnapshot xofAllocation = reallocatedQuote.getVendorSnapshots().get(1);
        xofAllocation.setDiscountDisplay(new BigDecimal("1.51"));
        xofAllocation.setDiscountNative(new BigDecimal("151"));
        xofAllocation.setPlatformDiscountShareDisplay(new BigDecimal("1.00"));

        Order reallocated = service.createFromQuote(7L, 9L, null, reallocatedQuote);

        assertMoney("2.51", original.getDiscount());
        assertMoney("2.51", reallocated.getDiscount());
        assertMoney("900.00", slice(original, 11L).getTotalNative());
        assertMoney("950.00", slice(reallocated, 11L).getTotalNative());
        assertMoney("1200", slice(original, 22L).getTotalNative());
        assertMoney("1100", slice(reallocated, 22L).getTotalNative());
    }

    private CartQuote frozenQuote() {
        LocalDateTime rateAt = LocalDateTime.of(2026, 9, 30, 12, 0);
        Cart cart = Cart.builder().id(3L).user(customer).displayCurrency("EUR").build();
        CartQuote quote = CartQuote.builder().id("held-order-test").cart(cart).user(customer)
                .cartFingerprint("fingerprint").displayCurrency("EUR")
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(new BigDecimal("32.51")).discount(new BigDecimal("2.51"))
                .shipping(new BigDecimal("3.57")).tax(new BigDecimal("0.00"))
                .total(new BigDecimal("33.57")).platformCouponId(501L)
                .platformCouponCode("PLATFORM15").complete(true)
                .createdAt(rateAt).expiresAt(rateAt.plusDays(2)).build();

        CartQuoteLine gmd = CartQuoteLine.builder().quote(quote).productId(101L).vendorId(11L)
                .quantity(1).listingCurrency("GMD")
                .unitPriceNative(new BigDecimal("1000.00"))
                .lineTotalNative(new BigDecimal("1000.00"))
                .unitPrice(new BigDecimal("20.00")).lineTotal(new BigDecimal("20.00"))
                .deliveryCost(new BigDecimal("1.20")).deliverable(true)
                .fx(FxSnapshot.published("GMD", "EUR", new BigDecimal("0.02000000"), rateAt))
                .build();
        CartQuoteLine xof = CartQuoteLine.builder().quote(quote).productId(202L).vendorId(22L)
                .quantity(1).listingCurrency("XOF")
                .unitPriceNative(new BigDecimal("1251"))
                .lineTotalNative(new BigDecimal("1251"))
                .unitPrice(new BigDecimal("12.51")).lineTotal(new BigDecimal("12.51"))
                .deliveryCost(new BigDecimal("2.37")).deliverable(true)
                .fx(FxSnapshot.published("XOF", "EUR", new BigDecimal("0.01000000"), rateAt))
                .build();
        CartQuoteVendorSnapshot gmdSnapshot = CartQuoteVendorSnapshot.builder()
                .quote(quote).vendorId(11L).discountDisplay(new BigDecimal("2.00"))
                .discountNative(new BigDecimal("100.00"))
                .platformDiscountShareDisplay(new BigDecimal("1.00")).build();
        CartQuoteVendorSnapshot xofSnapshot = CartQuoteVendorSnapshot.builder()
                .quote(quote).vendorId(22L).discountDisplay(new BigDecimal("0.51"))
                .discountNative(new BigDecimal("51"))
                .platformDiscountShareDisplay(new BigDecimal("0.50"))
                .vendorCouponId(601L).vendorCouponCode("VENDOR51").build();
        quote.setLines(List.of(gmd, xof));
        quote.setVendorSnapshots(List.of(gmdSnapshot, xofSnapshot));
        return quote;
    }

    private static VendorOrder slice(Order order, Long vendorId) {
        return order.getVendorOrders().stream()
                .filter(candidate -> vendorId.equals(candidate.getVendor().getId()))
                .findFirst().orElseThrow();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
