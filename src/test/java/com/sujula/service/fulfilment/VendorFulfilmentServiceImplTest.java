package com.sujula.service.fulfilment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sujula.dto.request.fulfilment.FulfilmentRequests;
import com.sujula.dto.response.fulfilment.FulfilmentResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.constant.StockMovementReason;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.inventory.ImeiUnit;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.RefundRequest;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.inventory.ImeiUnitRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.RefundRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.fulfilment.impl.VendorFulfilmentServiceImpl;
import com.sujula.service.inventory.StockLedger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A seller working an order, and the four rules that stop them going further.
 *
 * <p>The shop is in Banjul. The payment came from London and the parcel is for
 * Serrekunda, which is the ordinary shape of an order here rather than an
 * unusual one.
 */
class VendorFulfilmentServiceImplTest {

    private VendorOrderRepository vendorOrders;
    private VendorRepository vendors;
    private OrderRepository orders;
    private RefundRequestRepository refunds;
    private UserRepository users;
    private ImeiUnitRepository imeiUnits;
    private HandoverCodeRepository handoverCodes;
    private StockLedger ledger;
    private ParcelLabelRenderer labels;
    private PickupPointRepository pickupPoints;

    private VendorFulfilmentServiceImpl service;

    private Vendor vendor;
    private Order order;
    private final List<HandoverCode> issued = new ArrayList<>();

    private static final long VENDOR_USER = 4L;
    private static final long VENDOR_ID = 50L;
    private static final long SLICE_ID = 11L;

    @BeforeEach
    void setUp() {
        vendorOrders = mock(VendorOrderRepository.class);
        vendors = mock(VendorRepository.class);
        orders = mock(OrderRepository.class);
        refunds = mock(RefundRequestRepository.class);
        users = mock(UserRepository.class);
        imeiUnits = mock(ImeiUnitRepository.class);
        handoverCodes = mock(HandoverCodeRepository.class);
        ledger = mock(StockLedger.class);
        labels = mock(ParcelLabelRenderer.class);
        pickupPoints = mock(PickupPointRepository.class);

        service = new VendorFulfilmentServiceImpl(vendorOrders, vendors, orders, refunds, users,
                imeiUnits, handoverCodes, ledger, labels,
                new FulfilmentView(imeiUnits, pickupPoints));

        vendor = Vendor.builder()
                .id(VENDOR_ID)
                .storeName("Kombo Electronics")
                .settlementCurrency("GMD")
                .pickupCountryCode("GM")
                .build();
        when(vendors.findByUserId(VENDOR_USER)).thenReturn(Optional.of(vendor));

        order = new Order();
        order.setId(7L);
        order.setOrderNumber("SJL-TEST0001");
        order.setCurrency("GBP");
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setStatus(OrderStatus.PROCESSING);
        order.setShippingFullName("Isatou Ceesay");
        order.setShippingCity("Serrekunda");
        order.setShippingCountry("GM");

        when(vendorOrders.save(any(VendorOrder.class))).thenAnswer(i -> i.getArgument(0));
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(imeiUnits.save(any(ImeiUnit.class))).thenAnswer(i -> i.getArgument(0));
        when(refunds.save(any(RefundRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(refunds.existsByReference(anyString())).thenReturn(false);
        when(refunds.findOpenForVendorOrder(anyLong(), any())).thenReturn(Optional.empty());
        when(imeiUnits.findByOrderItemId(anyLong())).thenReturn(List.of());
        when(handoverCodes.findLiveReleaseCodes(anyLong())).thenReturn(List.of());
        when(handoverCodes.save(any(HandoverCode.class))).thenAnswer(i -> {
            HandoverCode code = i.getArgument(0);
            code.setId((long) (issued.size() + 1));
            issued.add(code);
            return code;
        });
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** A plain, unserialised line: a kettle. */
    private OrderItem kettle(long id, int quantity) {
        Product product = new Product();
        product.setId(31L);
        product.setName("Kettle");

        ProductVariant variant = new ProductVariant();
        variant.setId(310L);
        variant.setProduct(product);

        return OrderItem.builder()
                .id(id).order(order).product(product).variant(variant).vendor(vendor)
                .quantity(quantity)
                .unitPrice(new BigDecimal("450.00")).totalPrice(new BigDecimal("900.00"))
                .currency("GMD").productName("Kettle").productSku("KET-1")
                .build();
    }

    /** A serialised line: phones, which have IMEI units behind the variant. */
    private OrderItem phones(long id, int quantity) {
        Product product = new Product();
        product.setId(32L);
        product.setName("Galaxy A16");

        ProductVariant variant = new ProductVariant();
        variant.setId(320L);
        variant.setProduct(product);

        when(imeiUnits.existsByVariantId(320L)).thenReturn(true);

        return OrderItem.builder()
                .id(id).order(order).product(product).variant(variant).vendor(vendor)
                .quantity(quantity)
                .unitPrice(new BigDecimal("8500.00")).totalPrice(new BigDecimal("8500.00"))
                .currency("GMD").productName("Galaxy A16").productSku("GA16")
                .variantSku("GA16-128-BLK")
                .build();
    }

    private VendorOrder slice(VendorOrderStatus status, OrderItem... items) {
        VendorOrder vendorOrder = VendorOrder.builder()
                .id(SLICE_ID).order(order).vendor(vendor).status(status)
                .nativeCurrency("GMD")
                .totalNative(new BigDecimal("900.00"))
                .total(new BigDecimal("11.20"))
                .fx(new FxSnapshot())
                .items(new ArrayList<>(List.of(items)))
                .build();
        for (OrderItem item : items) {
            item.setVendorOrder(vendorOrder);
        }
        when(vendorOrders.findByIdAndVendorId(SLICE_ID, VENDOR_ID)).thenReturn(Optional.of(vendorOrder));
        when(vendorOrders.findByOrderId(order.getId())).thenReturn(List.of(vendorOrder));
        return vendorOrder;
    }

    private ImeiUnit unit(String imei, Long variantId, ImeiStatus status) {
        ProductVariant variant = new ProductVariant();
        variant.setId(variantId);
        ImeiUnit unit = ImeiUnit.builder()
                .id(900L).imei(imei).vendor(vendor).variant(variant).status(status)
                .build();
        when(imeiUnits.findByImei(imei)).thenReturn(Optional.of(unit));
        return unit;
    }

    // ── Accept ───────────────────────────────────────────────────────────────

    @Test
    void acceptingANewOrderStartsItBeingPacked() {
        VendorOrder vendorOrder = slice(VendorOrderStatus.PENDING, kettle(90L, 2));

        FulfilmentResponses.Accepted accepted = service.accept(VENDOR_USER, SLICE_ID);

        assertEquals(VendorOrderStatus.PREPARING, accepted.status());
        assertNotNull(accepted.acceptedAt(), "the moment of acceptance is recorded, not just the fact");
        assertEquals(VendorOrderStatus.PREPARING, vendorOrder.getStatus());
    }

    @Test
    void acceptingTwiceIsTheSameAnswerRatherThanAnError() {
        // A double-tapped button on one bar of signal is the ordinary case here.
        VendorOrder vendorOrder = slice(VendorOrderStatus.PREPARING, kettle(90L, 2));
        LocalDateTime firstTime = LocalDateTime.now().minusMinutes(5);
        vendorOrder.setAcceptedAt(firstTime);

        FulfilmentResponses.Accepted again = service.accept(VENDOR_USER, SLICE_ID);

        assertEquals(VendorOrderStatus.PREPARING, again.status());
        assertEquals(firstTime, again.acceptedAt(), "the original acceptance time is not overwritten");
    }

    @Test
    void anAlreadyPackedOrderCannotBeAccepted() {
        slice(VendorOrderStatus.READY_FOR_PICKUP, kettle(90L, 2));
        assertThrows(BadRequestException.class, () -> service.accept(VENDOR_USER, SLICE_ID));
    }

    @Test
    void anotherSellersOrderIsNotFoundRatherThanForbidden() {
        // Not found, because "forbidden" would confirm the id was real.
        when(vendorOrders.findByIdAndVendorId(anyLong(), anyLong())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.accept(VENDOR_USER, 999L));
    }

    // ── Reject ───────────────────────────────────────────────────────────────

    @Test
    void rejectingRequiresAReasonTheBuyerCanRead() {
        slice(VendorOrderStatus.PENDING, kettle(90L, 2));

        assertThrows(BadRequestException.class,
                () -> service.reject(VENDOR_USER, SLICE_ID, new FulfilmentRequests.Reject("   ")));
        verify(vendorOrders, never()).save(any());
    }

    @Test
    void rejectingCancelsTheSlicePutsStockBackAndAsksForARefund() {
        VendorOrder vendorOrder = slice(VendorOrderStatus.PREPARING, kettle(90L, 2));

        FulfilmentResponses.Rejected rejected = service.reject(VENDOR_USER, SLICE_ID,
                new FulfilmentRequests.Reject("The last one was damaged in the stockroom"));

        assertEquals(VendorOrderStatus.CANCELLED, rejected.status());
        assertEquals("The last one was damaged in the stockroom", vendorOrder.getRejectionReason());
        assertNotNull(vendorOrder.getCancelledAt());

        // The goods go back on the shelf, through the ledger rather than around
        // it, and the movement says which order they came off.
        verify(ledger).adjustVariant(any(), eq(2), eq(StockMovementReason.RETURN),
                eq("SJL-TEST0001"), anyString(), any());
        assertEquals(1, rejected.itemsReturnedToStock());

        // And a refund is REQUESTED. Nothing here moves money.
        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refunds).save(captor.capture());
        assertEquals(RefundRequestStatus.REQUESTED, captor.getValue().getStatus());
        assertNotNull(rejected.refundReference());
    }

    @Test
    void theRefundIsThisSlicesAmountInBothCurrenciesRatherThanAProportion() {
        slice(VendorOrderStatus.PREPARING, kettle(90L, 2));

        service.reject(VENDOR_USER, SLICE_ID, new FulfilmentRequests.Reject("Out of stock"));

        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refunds).save(captor.capture());
        RefundRequest refund = captor.getValue();

        // What the buyer paid for this slice, in what they paid it in (C3) ...
        assertEquals(new BigDecimal("11.20"), refund.getAmount());
        assertEquals("GBP", refund.getCurrency());
        // ... and the same sum in the seller's currency, which is what is clawed
        // back from their payout. Both carried, so they cannot disagree later.
        assertEquals(new BigDecimal("900.00"), refund.getAmountNative());
    }

    @Test
    void oneSellerPullingOutDoesNotCancelAnothersShipment() {
        VendorOrder mine = slice(VendorOrderStatus.PREPARING, kettle(90L, 2));

        VendorOrder theirs = VendorOrder.builder()
                .id(12L).order(order).status(VendorOrderStatus.SHIPPED)
                .items(new ArrayList<>()).build();
        when(vendorOrders.findByOrderId(order.getId())).thenReturn(List.of(mine, theirs));

        FulfilmentResponses.Rejected rejected = service.reject(VENDOR_USER, SLICE_ID,
                new FulfilmentRequests.Reject("Cannot source it"));

        assertFalse(rejected.orderFullyCancelled());
        assertEquals(VendorOrderStatus.SHIPPED, theirs.getStatus(), "the other seller is untouched");
        assertNotEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void theOrderClosesOnlyWhenNothingIsLeftStanding() {
        slice(VendorOrderStatus.PREPARING, kettle(90L, 2));

        FulfilmentResponses.Rejected rejected = service.reject(VENDOR_USER, SLICE_ID,
                new FulfilmentRequests.Reject("Cannot source it"));

        assertTrue(rejected.orderFullyCancelled());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void rejectingAnUnpaidOrderAsksForNoRefund() {
        order.setPaymentStatus(PaymentStatus.PENDING);
        slice(VendorOrderStatus.PENDING, kettle(90L, 2));

        FulfilmentResponses.Rejected rejected = service.reject(VENDOR_USER, SLICE_ID,
                new FulfilmentRequests.Reject("Shop is closed for a fortnight"));

        assertNull(rejected.refundReference(), "nothing was taken, so there is nothing to give back");
        verify(refunds, never()).save(any());
    }

    @Test
    void askingTwiceDoesNotQueueTwoRefundsAgainstTheSameGoods() {
        slice(VendorOrderStatus.CANCELLED, kettle(90L, 2));
        doReturn(Optional.of(RefundRequest.builder().reference("REF-EXISTING").build()))
                .when(refunds).findOpenForVendorOrder(anyLong(), any());

        FulfilmentResponses.Rejected again = service.reject(VENDOR_USER, SLICE_ID,
                new FulfilmentRequests.Reject("Out of stock"));

        assertEquals("REF-EXISTING", again.refundReference());
        verify(refunds, never()).save(any());
        verify(ledger, never()).adjustVariant(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void goodsWithACourierCannotBeRejected() {
        slice(VendorOrderStatus.SHIPPED, kettle(90L, 2));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.reject(VENDOR_USER, SLICE_ID,
                        new FulfilmentRequests.Reject("Changed my mind")));
        assertTrue(error.getMessage().contains("courier"));
    }

    @Test
    void rejectingReleasesTheHandsetsBackToTheShelf() {
        OrderItem line = phones(91L, 1);
        slice(VendorOrderStatus.PREPARING, line);
        line.bindImei("356938035643809");

        ImeiUnit held = unit("356938035643809", 320L, ImeiStatus.RESERVED);
        held.setOrderItem(line);
        when(imeiUnits.findByOrderItemId(91L)).thenReturn(List.of(held));

        service.reject(VENDOR_USER, SLICE_ID, new FulfilmentRequests.Reject("Screen is cracked"));

        assertEquals(ImeiStatus.IN_STOCK, held.getStatus());
        assertNull(held.getOrderItem(), "and it no longer claims to be on an order");
        assertTrue(line.assignedImeiList().isEmpty());

        // A serialised variant's stock IS the count of sellable units, so
        // releasing the unit is the restock. Adding to the figure as well would
        // count the same phone twice.
        verify(ledger, never()).adjustVariant(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void rejectingKillsAnyCodeThatWouldStillOpenTheParcel() {
        slice(VendorOrderStatus.READY_FOR_PICKUP, kettle(90L, 2));
        HandoverCode live = HandoverCode.builder()
                .id(5L).code("123456").codeType(HandoverCodeType.VENDOR_RELEASE).build();
        when(handoverCodes.findLiveReleaseCodes(SLICE_ID)).thenReturn(List.of(live));

        service.reject(VENDOR_USER, SLICE_ID, new FulfilmentRequests.Reject("Stock was wrong"));

        assertNotNull(live.getInvalidatedAt(),
                "a parcel that is not going anywhere must not still have a code that opens it");
    }

    // ── Ready ────────────────────────────────────────────────────────────────

    @Test
    void packingIssuesACollectionCode() {
        VendorOrder vendorOrder = slice(VendorOrderStatus.PREPARING, kettle(90L, 2));

        FulfilmentResponses.Ready ready = service.ready(VENDOR_USER, SLICE_ID);

        assertEquals(VendorOrderStatus.READY_FOR_PICKUP, ready.status());
        assertNotNull(ready.readyAt());
        assertEquals(6, ready.releaseCode().code().length());
        assertTrue(ready.releaseCode().code().matches("\\d{6}"));
        assertEquals(1, issued.size());
        assertEquals(HandoverCodeType.VENDOR_RELEASE, issued.get(0).getCodeType());
        assertEquals(vendorOrder, issued.get(0).getVendorOrder(),
                "the code hangs off the vendor order, not a delivery");
        assertNull(issued.get(0).getDelivery());
    }

    @Test
    void anOrderCannotBePackedBeforeItIsAccepted() {
        slice(VendorOrderStatus.PENDING, kettle(90L, 2));
        assertThrows(BadRequestException.class, () -> service.ready(VENDOR_USER, SLICE_ID));
        assertTrue(issued.isEmpty(), "and no code is minted for an order that is not packed");
    }

    @Test
    void aPhoneOrderCannotBePackedUntilTheHandsetsAreScanned() {
        slice(VendorOrderStatus.PREPARING, phones(91L, 2));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.ready(VENDOR_USER, SLICE_ID));

        // The message says which line and how many, because a seller with a
        // bench of twenty phones needs to know which one to scan.
        assertTrue(error.getMessage().contains("Galaxy A16"), error.getMessage());
        assertTrue(error.getMessage().contains("2 of 2"), error.getMessage());
        assertTrue(issued.isEmpty());
    }

    @Test
    void aPartlyScannedPhoneLineIsStillRefused() {
        OrderItem line = phones(91L, 2);
        slice(VendorOrderStatus.PREPARING, line);
        line.bindImei("356938035643809");

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.ready(VENDOR_USER, SLICE_ID));
        assertTrue(error.getMessage().contains("1 of 2"), error.getMessage());
    }

    @Test
    void aFullyScannedPhoneOrderPacksNormally() {
        OrderItem line = phones(91L, 2);
        slice(VendorOrderStatus.PREPARING, line);
        line.bindImei("356938035643809");
        line.bindImei("356938035643791");

        assertEquals(VendorOrderStatus.READY_FOR_PICKUP, service.ready(VENDOR_USER, SLICE_ID).status());
    }

    @Test
    void packingTwiceDoesNotIssueASecondCode() {
        VendorOrder vendorOrder = slice(VendorOrderStatus.PREPARING, kettle(90L, 2));
        FulfilmentResponses.Ready first = service.ready(VENDOR_USER, SLICE_ID);

        // The seller's phone lost the response and retried.
        when(handoverCodes.findLiveReleaseCode(SLICE_ID)).thenReturn(Optional.of(issued.get(0)));
        FulfilmentResponses.Ready second = service.ready(VENDOR_USER, SLICE_ID);

        assertEquals(first.releaseCode().code(), second.releaseCode().code(),
                "a lost response must not quietly replace the code the driver was told");
        assertEquals(1, issued.size());
        assertEquals(VendorOrderStatus.READY_FOR_PICKUP, vendorOrder.getStatus());
    }

    // ── The collection code ──────────────────────────────────────────────────

    @Test
    void thereIsNoCodeUntilTheParcelIsPacked() {
        slice(VendorOrderStatus.PREPARING, kettle(90L, 2));
        when(handoverCodes.findLiveReleaseCode(SLICE_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.releaseCode(VENDOR_USER, SLICE_ID));
    }

    @Test
    void regeneratingKillsTheOldCodeAndIssuesANewOne() {
        VendorOrder vendorOrder = slice(VendorOrderStatus.READY_FOR_PICKUP, kettle(90L, 2));
        vendorOrder.setReleaseCodeIssueCount(1);

        HandoverCode old = HandoverCode.builder()
                .id(5L).code("111111").codeType(HandoverCodeType.VENDOR_RELEASE).build();
        when(handoverCodes.findLiveReleaseCodes(SLICE_ID)).thenReturn(List.of(old));
        when(handoverCodes.countReleaseCodesSince(anyLong(), any())).thenReturn(1L);

        FulfilmentResponses.ReleaseCode fresh = service.regenerateReleaseCode(VENDOR_USER, SLICE_ID);

        assertNotNull(old.getInvalidatedAt(), "a leaked code must be dead, not one of several that work");
        assertTrue(fresh.reissued());
        assertNotEquals("111111", fresh.code());
        assertEquals(2, fresh.timesIssued());
    }

    @Test
    void codesCannotBeReissuedWithoutLimit() {
        slice(VendorOrderStatus.READY_FOR_PICKUP, kettle(90L, 2));
        // The reason to reissue is also the reason somebody would want a stream.
        when(handoverCodes.countReleaseCodesSince(anyLong(), any())).thenReturn(5L);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.regenerateReleaseCode(VENDOR_USER, SLICE_ID));
        assertTrue(error.getMessage().contains("limit"), error.getMessage());
        assertTrue(issued.isEmpty(), "and nothing is minted when the limit refuses");
    }

    @Test
    void theRateLimitCountsRowsRatherThanTrustingTheCounterOnTheOrder() {
        VendorOrder vendorOrder = slice(VendorOrderStatus.READY_FOR_PICKUP, kettle(90L, 2));
        // A counter that anything can forget to increment is not a rate limit.
        vendorOrder.setReleaseCodeIssueCount(0);
        when(handoverCodes.countReleaseCodesSince(anyLong(), any())).thenReturn(9L);

        assertThrows(BadRequestException.class,
                () -> service.regenerateReleaseCode(VENDOR_USER, SLICE_ID));
        verify(handoverCodes).countReleaseCodesSince(eq(SLICE_ID), any());
    }

    @Test
    void aCodeBelongsToAPackedParcelAndNothingElse() {
        slice(VendorOrderStatus.PREPARING, kettle(90L, 2));
        assertThrows(BadRequestException.class,
                () -> service.regenerateReleaseCode(VENDOR_USER, SLICE_ID));
    }

    // ── Handsets ─────────────────────────────────────────────────────────────

    @Test
    void scanningAHandsetBindsItToTheLineAndHoldsTheUnit() {
        OrderItem line = phones(91L, 1);
        slice(VendorOrderStatus.PREPARING, line);
        ImeiUnit shelf = unit("356938035643809", 320L, ImeiStatus.IN_STOCK);

        FulfilmentResponses.ImeiAssigned assigned = service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                new FulfilmentRequests.AssignImei("356938035643809"));

        assertEquals("356938035643809", assigned.imei());
        assertTrue(assigned.lineComplete());
        assertTrue(assigned.orderReadyToPack());
        assertEquals(ImeiStatus.RESERVED, shelf.getStatus());
        assertEquals(line, shelf.getOrderItem());
        assertEquals("SJL-TEST0001", shelf.getSoldOnOrderNumber());
        assertEquals(List.of("356938035643809"), line.assignedImeiList());
    }

    @Test
    void aLineOfTwoPhonesHoldsBothRatherThanLosingTheSecond() {
        OrderItem line = phones(91L, 2);
        slice(VendorOrderStatus.PREPARING, line);
        unit("356938035643809", 320L, ImeiStatus.IN_STOCK);

        FulfilmentResponses.ImeiAssigned first = service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                new FulfilmentRequests.AssignImei("356938035643809"));
        assertFalse(first.lineComplete());
        assertEquals(1, first.requiredForLine() - first.boundToLine());

        unit("356938035643791", 320L, ImeiStatus.IN_STOCK);
        FulfilmentResponses.ImeiAssigned second = service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                new FulfilmentRequests.AssignImei("356938035643791"));

        assertTrue(second.lineComplete());
        assertEquals(List.of("356938035643809", "356938035643791"), second.imeis());
    }

    @Test
    void aThirdHandsetOnALineOfTwoIsRefused() {
        OrderItem line = phones(91L, 2);
        slice(VendorOrderStatus.PREPARING, line);
        line.bindImei("356938035643809");
        line.bindImei("356938035643791");
        unit("356938035643783", 320L, ImeiStatus.IN_STOCK);

        assertThrows(BadRequestException.class, () -> service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                new FulfilmentRequests.AssignImei("356938035643783")));
    }

    @Test
    void aHandsetOfTheWrongModelIsRefusedByName() {
        slice(VendorOrderStatus.PREPARING, phones(91L, 1));
        unit("356938035643809", 999L, ImeiStatus.IN_STOCK);   // a different variant

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                        new FulfilmentRequests.AssignImei("356938035643809")));
        assertTrue(error.getMessage().contains("GA16-128-BLK"), error.getMessage());
    }

    @Test
    void anotherShopsHandsetIsRefusedWithoutSayingWhoseItIs() {
        slice(VendorOrderStatus.PREPARING, phones(91L, 1));
        ImeiUnit theirs = unit("356938035643809", 320L, ImeiStatus.IN_STOCK);
        theirs.setVendor(Vendor.builder().id(77L).storeName("Serrekunda Phones").build());

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                        new FulfilmentRequests.AssignImei("356938035643809")));

        // Confirming which shop holds a given IMEI would make this endpoint a
        // lookup service for stolen handsets.
        assertFalse(error.getMessage().contains("Serrekunda Phones"), error.getMessage());
        assertTrue(error.getMessage().contains("not registered in your shop"), error.getMessage());
    }

    @Test
    void aStolenHandsetCannotBeSentToABuyer() {
        slice(VendorOrderStatus.PREPARING, phones(91L, 1));
        unit("356938035643809", 320L, ImeiStatus.BLOCKED);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                        new FulfilmentRequests.AssignImei("356938035643809")));
        assertTrue(error.getMessage().contains("stolen"), error.getMessage());
    }

    @Test
    void aHandsetHeldForAnotherOrderIsRefused() {
        OrderItem line = phones(91L, 1);
        slice(VendorOrderStatus.PREPARING, line);
        ImeiUnit held = unit("356938035643809", 320L, ImeiStatus.RESERVED);
        held.setOrderItem(OrderItem.builder().id(999L).build());

        assertThrows(BadRequestException.class, () -> service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                new FulfilmentRequests.AssignImei("356938035643809")));
    }

    @Test
    void aMistypedImeiIsCaughtByItsOwnCheckDigit() {
        slice(VendorOrderStatus.PREPARING, phones(91L, 1));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                        new FulfilmentRequests.AssignImei("356938035643800")));
        assertTrue(error.getMessage().contains("check digit"), error.getMessage());
        verify(imeiUnits, never()).save(any());
    }

    @Test
    void anUnserialisedLineHasNoImeiToRecord() {
        slice(VendorOrderStatus.PREPARING, kettle(90L, 2));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.assignImei(VENDOR_USER, SLICE_ID, 90L,
                        new FulfilmentRequests.AssignImei("356938035643809")));
        assertTrue(error.getMessage().contains("not sold handset by handset"), error.getMessage());
    }

    @Test
    void handsetsCannotBeChangedOnceTheParcelHasGone() {
        slice(VendorOrderStatus.SHIPPED, phones(91L, 1));
        assertThrows(BadRequestException.class, () -> service.assignImei(VENDOR_USER, SLICE_ID, 91L,
                new FulfilmentRequests.AssignImei("356938035643809")));
    }

    @Test
    void aLineOnSomebodyElsesOrderIsNotFound() {
        slice(VendorOrderStatus.PREPARING, phones(91L, 1));
        assertThrows(ResourceNotFoundException.class,
                () -> service.assignImei(VENDOR_USER, SLICE_ID, 12345L,
                        new FulfilmentRequests.AssignImei("356938035643809")));
    }

    // ── Label ────────────────────────────────────────────────────────────────

    @Test
    void aLabelIsRenderedFromTheDeliverySideOfTheOrder() {
        slice(VendorOrderStatus.PREPARING, kettle(90L, 2));
        when(labels.render(any(), any(), any()))
                .thenReturn(new VendorFulfilmentService.ParcelLabel(new byte[] {1}, "l.pdf", "application/pdf"));

        service.label(VENDOR_USER, SLICE_ID);

        ArgumentCaptor<FulfilmentResponses.Shipping> captor =
                ArgumentCaptor.forClass(FulfilmentResponses.Shipping.class);
        verify(labels).render(any(), eq(vendor), captor.capture());
        assertEquals("Isatou Ceesay", captor.getValue().recipientName());
        assertEquals("Serrekunda", captor.getValue().town());
    }

    @Test
    void thereIsNoLabelForAnOrderNobodyHasAcceptedYet() {
        slice(VendorOrderStatus.PENDING, kettle(90L, 2));
        assertThrows(BadRequestException.class, () -> service.label(VENDOR_USER, SLICE_ID));
        verify(labels, never()).render(any(), any(), any());
    }

    @Test
    void thereIsNoLabelForACancelledOrder() {
        slice(VendorOrderStatus.CANCELLED, kettle(90L, 2));
        assertThrows(BadRequestException.class, () -> service.label(VENDOR_USER, SLICE_ID));
    }

    // ── The ceiling ──────────────────────────────────────────────────────────

    @Test
    void nothingOnThisSurfaceCanSayAParcelWasCollectedOrDelivered() throws Exception {
        // C4 stated as a shape rather than a rule: there is no method to call.
        // SHIPPED is what presenting the release code produces and DELIVERED is
        // what the recipient proves, so a seller who could set either would be a
        // custody chain with a hole in it.
        for (var method : VendorFulfilmentService.class.getMethods()) {
            for (var parameter : method.getParameterTypes()) {
                assertNotEquals(VendorOrderStatus.class, parameter,
                        method.getName() + " takes a status, which lets a seller name their own");
            }
        }
        assertFalse(VendorOrderStatus.SHIPPED.isVendorSettable());
        assertFalse(VendorOrderStatus.DELIVERED.isVendorSettable());
    }
}
