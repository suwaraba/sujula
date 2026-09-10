package com.sujula.service;

import com.sujula.dto.response.vendor.VendorOrderDetailResponse;
import com.sujula.dto.response.vendor.VendorOrderStatsResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.impl.VendorOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a vendor may see and do with an order.
 *
 * <p>The seller here is in Banjul and settles in dalasi; the buyer paid in
 * pounds. Nothing on the vendor's side of the app may say so.
 */
class VendorOrderServiceImplTest {

    private VendorOrderRepository vendorOrderRepository;
    private VendorRepository vendorRepository;
    private VendorOrderServiceImpl service;

    private Vendor vendor;
    private Order order;

    @BeforeEach
    void setUp() {
        vendorOrderRepository = mock(VendorOrderRepository.class);
        vendorRepository = mock(VendorRepository.class);
        service = new VendorOrderServiceImpl(vendorOrderRepository, vendorRepository);

        vendor = Vendor.builder()
                .id(50L)
                .storeName("Kombo Electronics")
                .settlementCurrency("GMD")
                .build();
        when(vendorRepository.findByUserId(4L)).thenReturn(Optional.of(vendor));

        order = new Order();
        order.setId(7L);
        order.setOrderNumber("SJL-TEST0001");
        // What the buyer paid, in the buyer's currency. None of it may surface.
        order.setCurrency("GBP");
        order.setTotal(new BigDecimal("142.50"));
        order.setShippingFullName("A Buyer");
        order.setShippingStreet("221B Baker Street");
        order.setShippingCity("London");
        order.setShippingLatitude(51.5237);
        order.setShippingLongitude(-0.1585);

        when(vendorOrderRepository.save(any(VendorOrder.class))).thenAnswer(i -> i.getArgument(0));
    }

    private VendorOrder slice(VendorOrderStatus status) {
        Product product = new Product();
        product.setId(31L);
        product.setName("Kettle");

        OrderItem item = OrderItem.builder()
                .id(90L)
                .order(order)
                .product(product)
                .vendor(vendor)
                .quantity(2)
                .unitPrice(new BigDecimal("450.00"))       // dalasi — what the vendor listed
                .totalPrice(new BigDecimal("900.00"))
                .currency("GMD")
                .unitPriceConverted(new BigDecimal("5.60")) // pounds — the buyer's side
                .totalPriceConverted(new BigDecimal("11.20"))
                .deliveryCost(new BigDecimal("1.20"))
                .productName("Kettle")
                .productSku("KET-1")
                .build();

        VendorOrder vendorOrder = VendorOrder.builder()
                .id(11L)
                .order(order)
                .vendor(vendor)
                .status(status)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("900.00"))
                .discountNative(BigDecimal.ZERO)
                .totalNative(new BigDecimal("900.00"))
                .commissionRate(new BigDecimal("10.00"))
                .commissionNative(new BigDecimal("90.00"))
                .deliveryNative(new BigDecimal("96.43"))
                .payoutNative(new BigDecimal("810.00"))
                .subtotal(new BigDecimal("11.20"))
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("11.20"))
                .items(List.of(item))
                .build();
        item.setVendorOrder(vendorOrder);
        return vendorOrder;
    }

    private VendorOrderDetailResponse detailOf(VendorOrderStatus status) {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L)).thenReturn(Optional.of(slice(status)));
        return service.findMyOrder(4L, 11L);
    }

    // ── Currency isolation ───────────────────────────────────────────────────

    @Test
    void everyAmountIsInTheVendorsOwnCurrency() {
        VendorOrderDetailResponse detail = detailOf(VendorOrderStatus.PENDING);

        assertEquals("GMD", detail.getCurrency());
        assertEquals(new BigDecimal("900.00"), detail.getGoodsTotal());
        assertEquals(new BigDecimal("810.00"), detail.getPayout());
        assertEquals(new BigDecimal("450.00"), detail.getLines().get(0).getUnitPrice());
    }

    @Test
    void nothingAboutTheBuyerOrTheirCurrencyIsReturned() {
        String rendered = detailOf(VendorOrderStatus.PENDING).toString();

        // The buyer's side of this exact order, field by field.
        assertFalse(rendered.contains("GBP"), "the buyer's currency must not appear");
        assertFalse(rendered.contains("11.20"), "the buyer's converted total must not appear");
        assertFalse(rendered.contains("142.50"), "the order total must not appear");
        assertFalse(rendered.contains("London"), "the buyer's city must not appear");
        assertFalse(rendered.contains("Baker Street"), "the buyer's address must not appear");
        assertFalse(rendered.contains("51.52"), "the buyer's coordinates must not appear");
        assertFalse(rendered.contains("A Buyer"), "the buyer's name must not appear");
    }

    @Test
    void theVendorGetsIdentifiersAndTheirOwnPrices() {
        VendorOrderDetailResponse detail = detailOf(VendorOrderStatus.PENDING);

        // Order id and product id are the handles; the money is computed here.
        assertEquals(11L, detail.getId());
        assertEquals("SJL-TEST0001", detail.getOrderNumber());
        assertEquals(31L, detail.getLines().get(0).getProductId());
        assertEquals(2, detail.getLines().get(0).getQuantity());
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    @Test
    void anotherVendorsSliceIsNotFound() {
        // The vendor id is part of the query, so the row never comes back at all.
        when(vendorOrderRepository.findByIdAndVendorId(anyLong(), anyLong())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findMyOrder(4L, 999L));
    }

    @Test
    void aUserWithNoVendorProfileHasNoQueue() {
        when(vendorRepository.findByUserId(8L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.stats(8L));
    }

    // ── Transitions ──────────────────────────────────────────────────────────

    @Test
    void aVendorAcceptsThenPacksThenShips() {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.PENDING)));
        assertEquals(VendorOrderStatus.CONFIRMED,
                service.updateStatus(4L, 11L, VendorOrderStatus.CONFIRMED).getStatus());

        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.CONFIRMED)));
        assertEquals(VendorOrderStatus.PROCESSING,
                service.updateStatus(4L, 11L, VendorOrderStatus.PROCESSING).getStatus());

        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.PROCESSING)));
        assertEquals(VendorOrderStatus.SHIPPED,
                service.updateStatus(4L, 11L, VendorOrderStatus.SHIPPED).getStatus());
    }

    @Test
    void aVendorCannotMarkTheirOwnOrderDelivered() {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.SHIPPED)));

        // Otherwise a seller could close an order the buyer never received.
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.updateStatus(4L, 11L, VendorOrderStatus.DELIVERED));
        assertTrue(error.getMessage().contains("courier"));
    }

    @Test
    void aVendorCannotRefund() {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.SHIPPED)));

        assertThrows(BadRequestException.class,
                () -> service.updateStatus(4L, 11L, VendorOrderStatus.REFUNDED));
    }

    @Test
    void statusCannotGoBackwards() {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.SHIPPED)));

        assertThrows(BadRequestException.class,
                () -> service.updateStatus(4L, 11L, VendorOrderStatus.CONFIRMED));
    }

    @Test
    void cancellingIsRefusedOnceTheGoodsAreWithACourier() {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.SHIPPED)));

        // At that point "cancelled" means a refund, which is not the seller's call.
        assertThrows(BadRequestException.class,
                () -> service.updateStatus(4L, 11L, VendorOrderStatus.CANCELLED));
    }

    @Test
    void settingTheStatusItAlreadyHasIsNotAnError() {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.CONFIRMED)));

        // A double-tapped button should not raise an error at the seller.
        assertEquals(VendorOrderStatus.CONFIRMED,
                service.updateStatus(4L, 11L, VendorOrderStatus.CONFIRMED).getStatus());
    }

    @Test
    void offeredTransitionsExcludeTheOnesTheServiceWouldRefuse() {
        VendorOrderDetailResponse shipped = detailOf(VendorOrderStatus.SHIPPED);

        // SHIPPED may legally become DELIVERED — but not at the vendor's word, so
        // a UI built from this list cannot render a button that would be refused.
        assertFalse(shipped.getAllowedNextStatuses().contains(VendorOrderStatus.DELIVERED));
        assertTrue(shipped.getAllowedNextStatuses().isEmpty());

        assertTrue(detailOf(VendorOrderStatus.PENDING).getAllowedNextStatuses()
                .containsAll(List.of(VendorOrderStatus.CONFIRMED, VendorOrderStatus.CANCELLED)));
    }

    // ── Statistics ───────────────────────────────────────────────────────────

    @Test
    void statsAreCountedPerStatusAndPaidInTheVendorsCurrency() {
        when(vendorOrderRepository.countByStatusForVendor(50L)).thenReturn(List.of(
                new Object[]{VendorOrderStatus.PENDING, 2L},
                new Object[]{VendorOrderStatus.PROCESSING, 1L},
                new Object[]{VendorOrderStatus.DELIVERED, 5L}));
        when(vendorOrderRepository.sumPayoutNative(anyLong(), any()))
                .thenReturn(new BigDecimal("4050.00"));

        VendorOrderStatsResponse stats = service.stats(4L);

        assertEquals("GMD", stats.getCurrency());
        assertEquals(3, stats.getAwaitingAction());
        assertEquals(new BigDecimal("4050.00"), stats.getEarnedToDate());
        // Every status is present even at zero, so a dashboard renders consistently.
        assertEquals(VendorOrderStatus.values().length, stats.getOrdersByStatus().size());
        assertEquals(0L, stats.getOrdersByStatus().get(VendorOrderStatus.REFUNDED));
    }
}
