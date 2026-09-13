package com.sujula.service;

import com.sujula.dto.response.fulfilment.FulfilmentResponses;
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
    private com.sujula.service.fulfilment.FulfilmentView view;
    private VendorOrderServiceImpl service;

    private Vendor vendor;
    private Order order;

    @BeforeEach
    void setUp() {
        vendorOrderRepository = mock(VendorOrderRepository.class);
        vendorRepository = mock(VendorRepository.class);
        // Real, over stubs for the two repositories behind it: the packability
        // and shipping answers this screen shows are the ones the fulfilment
        // endpoints enforce, and a mocked view would let the two drift in the
        // exact place this test exists to pin.
        com.sujula.repository.inventory.ImeiUnitRepository imeiUnits =
                mock(com.sujula.repository.inventory.ImeiUnitRepository.class);
        com.sujula.repository.PickupPointRepository pickupPoints =
                mock(com.sujula.repository.PickupPointRepository.class);
        view = new com.sujula.service.fulfilment.FulfilmentView(imeiUnits, pickupPoints);
        service = new VendorOrderServiceImpl(vendorOrderRepository, vendorRepository, view);

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

        // The payer: in London, paying in pounds. Nothing about this side of the
        // order reaches the seller at all.
        order.setBillingFullName("Fatou Ceesay");
        order.setBillingStreet("221B Baker Street");
        order.setBillingCity("London");
        order.setBillingCountry("GB");

        // The recipient: the sister in Serrekunda the parcel is actually for.
        // A different person, in a different country - which is the ordinary
        // case here rather than an edge one (C1).
        order.setShippingFullName("Isatou Ceesay");
        order.setShippingStreet("12 Kairaba Avenue");
        order.setShippingCity("Serrekunda");
        order.setShippingCountry("GM");
        order.setShippingPhone("+220 7712345");
        order.setShippingLatitude(13.4384);
        order.setShippingLongitude(-16.6781);

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
    void nothingAboutThePayerOrTheirCurrencyIsReturned() {
        String rendered = detailOf(VendorOrderStatus.PENDING).toString();

        // The payer's side of this exact order, field by field. A seller in
        // Banjul learns nothing about who paid or from where - that is C1, and
        // a seller who could see both sides could tell which of their buyers is
        // sending money home.
        assertFalse(rendered.contains("GBP"), "the payer's currency must not appear");
        assertFalse(rendered.contains("11.20"), "the payer's converted total must not appear");
        assertFalse(rendered.contains("142.50"), "the order total must not appear");
        assertFalse(rendered.contains("Fatou"), "the payer's name must not appear");
        assertFalse(rendered.contains("London"), "the payer's city must not appear");
        assertFalse(rendered.contains("Baker Street"), "the payer's address must not appear");
    }

    @Test
    void theSellerGetsTheDeliveryNameAndTownAndNoMoreOfIt() {
        VendorOrderDetailResponse detail = detailOf(VendorOrderStatus.PENDING);
        FulfilmentResponses.Shipping shipping = detail.getShipping();

        // What packing needs: who to write on the box, and where it is going.
        assertEquals("Isatou Ceesay", shipping.recipientName());
        assertEquals("Serrekunda", shipping.town());
        assertEquals("GM", shipping.country());

        // And what packing does not need. The platform routes the parcel and
        // the driver resolves the address from the label's QR, so printing it
        // here would hand every seller a home address they have no delivery to
        // make to.
        String rendered = detail.toString();
        assertFalse(rendered.contains("Kairaba"), "the recipient's street must not appear");
        assertFalse(rendered.contains("13.43"), "the recipient's coordinates must not appear");
        assertFalse(rendered.contains("7712345"), "the recipient's full phone must not appear");
        assertTrue(shipping.phoneHint().endsWith("345"),
                "but enough of it to confirm the right parcel");
    }

    @Test
    void aParcelLeavingTheCountryIsFlaggedAgainstTheVendorsOwnCountry() {
        // The comparison is delivery country against the SELLER's country. The
        // payer is in London and that is irrelevant: a Banjul seller shipping to
        // Serrekunda is domestic however far away the money came from.
        vendor.setPickupCountryCode("GM");
        assertFalse(detailOf(VendorOrderStatus.PENDING).getShipping().international());

        vendor.setPickupCountryCode("SN");
        assertTrue(detailOf(VendorOrderStatus.PENDING).getShipping().international());
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
    void aVendorAcceptsThenPacksAndStopsThere() {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.PENDING)));
        assertEquals(VendorOrderStatus.PREPARING,
                service.updateStatus(4L, 11L, VendorOrderStatus.PREPARING).getStatus());

        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.PREPARING)));
        assertEquals(VendorOrderStatus.READY_FOR_PICKUP,
                service.updateStatus(4L, 11L, VendorOrderStatus.READY_FOR_PICKUP).getStatus());
    }

    @Test
    void aVendorCannotDeclareTheirOwnParcelCollected() {
        when(vendorOrderRepository.findByIdAndVendorId(11L, 50L))
                .thenReturn(Optional.of(slice(VendorOrderStatus.READY_FOR_PICKUP)));

        // SHIPPED is what presenting the release code produces. A vendor who could
        // set it directly could report a parcel collected that is still on the shelf,
        // which is a custody chain with a hole in it.
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.updateStatus(4L, 11L, VendorOrderStatus.SHIPPED));
        assertTrue(error.getMessage().contains("SHIPPED"));
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
                () -> service.updateStatus(4L, 11L, VendorOrderStatus.PREPARING));
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
                .thenReturn(Optional.of(slice(VendorOrderStatus.PREPARING)));

        // A double-tapped button should not raise an error at the seller.
        assertEquals(VendorOrderStatus.PREPARING,
                service.updateStatus(4L, 11L, VendorOrderStatus.PREPARING).getStatus());
    }

    @Test
    void offeredTransitionsExcludeTheOnesTheServiceWouldRefuse() {
        VendorOrderDetailResponse shipped = detailOf(VendorOrderStatus.SHIPPED);

        // SHIPPED may legally become DELIVERED — but not at the vendor's word, so
        // a UI built from this list cannot render a button that would be refused.
        assertFalse(shipped.getAllowedNextStatuses().contains(VendorOrderStatus.DELIVERED));
        assertTrue(shipped.getAllowedNextStatuses().isEmpty());

        assertTrue(detailOf(VendorOrderStatus.PENDING).getAllowedNextStatuses()
                .containsAll(List.of(VendorOrderStatus.PREPARING, VendorOrderStatus.CANCELLED)));
    }

    // ── Statistics ───────────────────────────────────────────────────────────

    @Test
    void statsAreCountedPerStatusAndPaidInTheVendorsCurrency() {
        when(vendorOrderRepository.countByStatusForVendor(50L)).thenReturn(List.of(
                new Object[]{VendorOrderStatus.PENDING, 2L},
                new Object[]{VendorOrderStatus.READY_FOR_PICKUP, 1L},
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
