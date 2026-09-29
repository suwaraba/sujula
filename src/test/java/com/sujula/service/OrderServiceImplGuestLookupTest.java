package com.sujula.service;

import com.sujula.dto.response.order.GuestOrderLookupResponse;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
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
import com.sujula.service.impl.OrderServiceImpl;
import com.sujula.service.inventory.StockLedger;
import com.sujula.service.reference.CurrencyCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The temporary guest selector must map an entity to the restricted DTO in the service layer. */
class OrderServiceImplGuestLookupTest {

    private OrderRepository orders;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        service = new OrderServiceImpl(
                orders,
                mock(VendorOrderRepository.class),
                mock(OrderStatusHistoryRepository.class),
                mock(ProductRepository.class),
                mock(ProductVariantRepository.class),
                mock(StockLedger.class),
                mock(UserRepository.class),
                mock(VendorRepository.class),
                mock(AddressRepository.class),
                mock(PickupPointRepository.class),
                mock(CouponRepository.class),
                mock(CouponUsageRepository.class),
                mock(ExchangeRateService.class),
                mock(CartService.class),
                mock(DeliveryPricingService.class),
                mock(EmailService.class),
                mock(NotificationService.class),
                mock(CurrencyCatalogue.class));
    }

    @Test
    void mapsMatchingHistoricOrderToTheRestrictedLookupResponse() {
        Order order = new Order();
        order.setOrderNumber("SJL-1008");
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setCurrency("GMD");
        order.setTotal(new BigDecimal("250.00"));
        order.setShippingCity("Banjul");
        order.setShippingCountry("GM");
        order.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 15));
        order.setItems(List.of(OrderItem.builder().productName("Rice").quantity(2).build()));
        when(orders.findByOrderNumberAndGuestEmailIgnoreCase("SJL-1008", "guest@example.com"))
                .thenReturn(Optional.of(order));

        GuestOrderLookupResponse response = service.findGuestOrder("SJL-1008", "guest@example.com");

        assertEquals("SJL-1008", response.orderNumber());
        assertEquals(OrderStatus.PENDING, response.status());
        assertEquals(PaymentStatus.PENDING, response.paymentStatus());
        assertEquals("GMD", response.currency());
        assertEquals(new BigDecimal("250.00"), response.total());
        assertEquals(List.of(new GuestOrderLookupResponse.Line("Rice", 2)), response.items());
        assertEquals(new GuestOrderLookupResponse.Destination("Banjul", "GM"), response.destination());
        assertEquals(LocalDateTime.of(2026, 9, 1, 10, 15), response.createdAt());
        verify(orders).findByOrderNumberAndGuestEmailIgnoreCase("SJL-1008", "guest@example.com");
    }

    @Test
    void wrongHistoricOrderEmailPairKeepsTheExistingNotFoundBehavior() {
        when(orders.findByOrderNumberAndGuestEmailIgnoreCase("SJL-1008", "wrong@example.com"))
                .thenReturn(Optional.empty());

        ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                () -> service.findGuestOrder("SJL-1008", "wrong@example.com"));

        assertTrue(error.getMessage().contains("no matching guest order"));
        verify(orders).findByOrderNumberAndGuestEmailIgnoreCase("SJL-1008", "wrong@example.com");
    }
}
