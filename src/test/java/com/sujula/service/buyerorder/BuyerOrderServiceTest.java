package com.sujula.service.buyerorder;

import com.sujula.dto.request.buyerorder.BuyerOrderRequests;
import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Review;
import com.sujula.model.constant.DeliveryStatus;
import com.sujula.model.constant.FxSource;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.Delivery;
import com.sujula.model.delivery.DeliveryTracking;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.OrderStatusHistory;
import com.sujula.model.order.RefundRequest;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.DeliveryRepository;
import com.sujula.repository.delivery.DeliveryTrackingRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.OrderStatusHistoryRepository;
import com.sujula.repository.order.RefundRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ReviewRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.buyerorder.impl.BuyerOrderServiceImpl;
import com.sujula.service.invoice.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a buyer may do to their own order, and what happens to everybody else's.
 *
 * <p>The cases worth having here are the ones where a plausible implementation
 * is wrong in a way nobody notices until money has moved: a cancellation that
 * quietly stops another seller's shipment, a refund that fires twice because a
 * button was tapped twice, a receipt confirmation that releases escrow for goods
 * still on a shelf, a public page that answers a stranger with a home address.
 */
class BuyerOrderServiceTest {

    private static final Long BUYER = 700L;
    private static final Long INTRUDER = 701L;
    private static final Long ORDER = 4000L;
    private static final Long SLICE_A = 4100L;   // Banjul, still packing
    private static final Long SLICE_B = 4200L;   // Dakar, already with a driver

    private OrderRepository orders;
    private VendorOrderRepository vendorOrders;
    private RefundRequestRepository refunds;
    private OrderStatusHistoryRepository history;
    private DeliveryRepository deliveries;
    private DeliveryTrackingRepository tracking;
    private ReviewRepository reviews;
    private ProductRepository products;
    private UserRepository users;
    private PickupPointRepository pickupPoints;
    private InvoiceService invoices;
    private BuyerOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        vendorOrders = mock(VendorOrderRepository.class);
        refunds = mock(RefundRequestRepository.class);
        history = mock(OrderStatusHistoryRepository.class);
        deliveries = mock(DeliveryRepository.class);
        tracking = mock(DeliveryTrackingRepository.class);
        reviews = mock(ReviewRepository.class);
        products = mock(ProductRepository.class);
        users = mock(UserRepository.class);
        pickupPoints = mock(PickupPointRepository.class);
        invoices = mock(InvoiceService.class);

        service = new BuyerOrderServiceImpl(orders, vendorOrders, refunds, history, deliveries,
                tracking, reviews, products, users, pickupPoints, invoices);

        when(orders.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));
        when(vendorOrders.save(any(VendorOrder.class))).thenAnswer(call -> call.getArgument(0));
        when(refunds.save(any(RefundRequest.class))).thenAnswer(call -> call.getArgument(0));
        when(refunds.findByOrderIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
        when(refunds.findOpenForVendorOrder(anyLong(), anyList())).thenReturn(Optional.empty());
        when(reviews.findProductIdsReviewedBy(anyLong())).thenReturn(List.of());
        when(deliveries.findByOrderItemOrderId(anyLong())).thenReturn(List.of());
        when(tracking.findTrail(any())).thenReturn(List.of());
        when(users.findById(BUYER)).thenReturn(Optional.of(user(BUYER)));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setFirstName("Fatou");
        user.setLastName("Ceesay");
        user.setEmail("fatou@example.es");
        return user;
    }

    private static Vendor vendor(Long id, String name) {
        Vendor vendor = new Vendor();
        vendor.setId(id);
        vendor.setStoreName(name);
        return vendor;
    }

    /**
     * A Madrid buyer, two sellers, one parcel each, going to Serrekunda.
     *
     * <p>Slice A is still pre-dispatch; slice B's status is whatever the test
     * asks for, which is how every "one seller has shipped" case is set up.
     */
    private Order order(VendorOrderStatus sliceB, PaymentStatus payment) {
        Order order = new Order();
        order.setId(ORDER);
        order.setOrderNumber("SJL-TESTONE");
        order.setTrackingCode("K7MPQ4RTVX2ND9YH");
        order.setCustomer(user(BUYER));
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(payment);
        order.setCurrency("EUR");
        order.setTotal(new BigDecimal("212.00"));
        order.setSubtotal(new BigDecimal("200.00"));
        order.setShippingFullName("Awa Ceesay");
        order.setShippingPhone("+2203001122");
        order.setShippingStreet("14 Kairaba Avenue");
        order.setShippingCity("Serrekunda");
        order.setShippingCountry("GM");

        VendorOrder a = slice(SLICE_A, vendor(10L, "Banjul Electronics"), VendorOrderStatus.PREPARING,
                "GMD", new BigDecimal("120.00"), new BigDecimal("8400.00"), new BigDecimal("70.00"));
        VendorOrder b = slice(SLICE_B, vendor(20L, "Dakar Mobile"), sliceB,
                "XOF", new BigDecimal("92.00"), new BigDecimal("60000"), new BigDecimal("652.17"));
        a.setOrder(order);
        b.setOrder(order);

        order.setVendorOrders(new ArrayList<>(List.of(a, b)));
        order.setItems(new ArrayList<>());
        order.getItems().addAll(a.getItems());
        order.getItems().addAll(b.getItems());
        return order;
    }

    private static VendorOrder slice(Long id, Vendor vendor, VendorOrderStatus status,
                                     String nativeCurrency, BigDecimal total,
                                     BigDecimal totalNative, BigDecimal rate) {
        VendorOrder slice = new VendorOrder();
        slice.setId(id);
        slice.setVendor(vendor);
        slice.setStatus(status);
        slice.setNativeCurrency(nativeCurrency);
        slice.setTotal(total);
        slice.setSubtotal(total);
        slice.setTotalNative(totalNative);
        slice.setPayoutNative(totalNative);
        slice.setFx(FxSnapshot.published(nativeCurrency, "EUR",
                BigDecimal.ONE.divide(rate, 8, java.math.RoundingMode.HALF_UP),
                LocalDateTime.of(2026, 3, 1, 9, 0)));

        OrderItem item = new OrderItem();
        item.setId(id + 1);
        item.setVendorOrder(slice);
        item.setVendor(vendor);
        item.setQuantity(1);
        item.setProductName("Phone");
        item.setUnitPriceConverted(total);
        item.setTotalPriceConverted(total);
        item.setDeliveryCost(BigDecimal.ZERO);
        slice.setItems(new ArrayList<>(List.of(item)));
        return slice;
    }

    private void given(Order order) {
        when(orders.findByIdAndCustomerId(ORDER, BUYER)).thenReturn(Optional.of(order));
        when(orders.findByIdAndCustomerId(ORDER, INTRUDER)).thenReturn(Optional.empty());
    }

    // ── Ownership ────────────────────────────────────────────────────────────

    @Test
    void somebodyElsesOrderIsNotFoundRatherThanForbidden() {
        given(order(VendorOrderStatus.PREPARING, PaymentStatus.PAID));

        // Not "forbidden": that would confirm the order exists, which is itself
        // worth withholding when the id is a small integer anyone can walk.
        assertThrows(ResourceNotFoundException.class, () -> service.detail(INTRUDER, ORDER));
        assertThrows(ResourceNotFoundException.class, () -> service.tracking(INTRUDER, ORDER));
        assertThrows(ResourceNotFoundException.class,
                () -> service.cancel(INTRUDER, ORDER, new BuyerOrderRequests.Cancel(null)));
        assertThrows(ResourceNotFoundException.class,
                () -> service.invoiceLink(INTRUDER, ORDER));
    }

    // ── Whole-order cancellation ─────────────────────────────────────────────

    @Test
    void wholeOrderCancelsOnlyWhileEverySellerIsStillPreDispatch() {
        given(order(VendorOrderStatus.PREPARING, PaymentStatus.PAID));

        BuyerOrderResponses.Cancelled result =
                service.cancel(BUYER, ORDER, new BuyerOrderRequests.Cancel("Changed my mind"));

        assertEquals(OrderStatus.CANCELLED, result.status());
        assertEquals(List.of(SLICE_A, SLICE_B), result.cancelledVendorOrderIds());
    }

    @Test
    void wholeOrderRefusesOnceOneSellerHasShipped() {
        given(order(VendorOrderStatus.SHIPPED, PaymentStatus.PAID));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.cancel(BUYER, ORDER, new BuyerOrderRequests.Cancel(null)));

        // And it says whose goods are in the air, because "cannot cancel" with
        // no reason is what makes somebody phone support.
        assertTrue(refused.getMessage().contains("Dakar Mobile"), refused.getMessage());
        verify(vendorOrders, never()).save(any());
        verify(refunds, never()).save(any());
    }

    @Test
    void cancellingAnAlreadyCancelledOrderIsNotAnError() {
        Order order = order(VendorOrderStatus.PREPARING, PaymentStatus.PAID);
        order.setStatus(OrderStatus.CANCELLED);
        given(order);

        BuyerOrderResponses.Cancelled result =
                service.cancel(BUYER, ORDER, new BuyerOrderRequests.Cancel(null));

        assertEquals(OrderStatus.CANCELLED, result.status());
        assertTrue(result.cancelledVendorOrderIds().isEmpty());
        verify(refunds, never()).save(any());
    }

    // ── Per-vendor cancellation (C3) ─────────────────────────────────────────

    @Test
    void cancellingOneSellerLeavesTheOtherAlone() {
        Order order = order(VendorOrderStatus.PREPARING, PaymentStatus.PAID);
        given(order);

        BuyerOrderResponses.Cancelled result = service.cancelVendorOrder(
                BUYER, ORDER, SLICE_A, new BuyerOrderRequests.Cancel("Found it locally"));

        VendorOrder untouched = order.getVendorOrders().stream()
                .filter(slice -> slice.getId().equals(SLICE_B)).findFirst().orElseThrow();

        assertEquals(VendorOrderStatus.PREPARING, untouched.getStatus());
        assertNull(untouched.getCancelledAt());
        // The order itself keeps going: one seller pulling out is not the end of it.
        assertEquals(OrderStatus.PROCESSING, result.status());
        assertEquals(List.of(SLICE_A), result.cancelledVendorOrderIds());
    }

    @Test
    void cancellingEverySliceSeparatelyEventuallyCancelsTheOrder() {
        Order order = order(VendorOrderStatus.PREPARING, PaymentStatus.PAID);
        given(order);

        service.cancelVendorOrder(BUYER, ORDER, SLICE_A, new BuyerOrderRequests.Cancel(null));
        BuyerOrderResponses.Cancelled second = service.cancelVendorOrder(
                BUYER, ORDER, SLICE_B, new BuyerOrderRequests.Cancel(null));

        assertEquals(OrderStatus.CANCELLED, second.status());
    }

    @Test
    void cancellingOneSellerAsksForMoneyBackRatherThanMovingIt() {
        given(order(VendorOrderStatus.PREPARING, PaymentStatus.PAID));

        service.cancelVendorOrder(BUYER, ORDER, SLICE_B,
                new BuyerOrderRequests.Cancel("Too slow"));

        ArgumentCaptor<RefundRequest> raised = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refunds).save(raised.capture());
        RefundRequest request = raised.getValue();

        // Requested, not completed. An administrator decides — money leaving the
        // platform is the one action no later API call can undo.
        assertEquals(RefundRequestStatus.REQUESTED, request.getStatus());
        assertNull(request.getCompletedAt());
        assertNull(request.getDecidedBy());

        // It is a refund of THIS seller's goods, not a proportion of the order.
        assertEquals(SLICE_B, request.getVendorOrder().getId());
        assertEquals(0, new BigDecimal("92.00").compareTo(request.getAmount()));
        assertEquals("EUR", request.getCurrency());

        // And it carries the rate the two sides were reconciled at. Re-deriving
        // it when the refund settles would make the buyer's figure and the
        // vendor's stop agreeing.
        assertNotNull(request.getFx());
        assertEquals("XOF", request.getFx().getNativeCurrency());
        assertEquals(FxSource.PUBLISHED_RATE, request.getFx().getSource());
        assertEquals(LocalDateTime.of(2026, 3, 1, 9, 0), request.getFx().getRateAt());
        assertEquals(0, new BigDecimal("60000").compareTo(request.getAmountNative()));
    }

    @Test
    void tappingCancelTwiceDoesNotQueueTwoRefunds() {
        given(order(VendorOrderStatus.PREPARING, PaymentStatus.PAID));
        // Second call: the first request is already open.
        when(refunds.findOpenForVendorOrder(anyLong(), anyList()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new RefundRequest()));

        service.cancelVendorOrder(BUYER, ORDER, SLICE_A, new BuyerOrderRequests.Cancel(null));
        service.cancelVendorOrder(BUYER, ORDER, SLICE_B, new BuyerOrderRequests.Cancel(null));

        verify(refunds, times(1)).save(any(RefundRequest.class));
    }

    @Test
    void anUnpaidOrderIsCancelledWithoutARefundRequest() {
        given(order(VendorOrderStatus.PREPARING, PaymentStatus.PENDING));

        BuyerOrderResponses.Cancelled result =
                service.cancel(BUYER, ORDER, new BuyerOrderRequests.Cancel(null));

        verify(refunds, never()).save(any());
        assertTrue(result.message().contains("Nothing was charged"), result.message());
    }

    @Test
    void aSecondCancellationStillAsksForItsOwnMoneyBack() {
        // The first refund moved the order to PARTIALLY_REFUNDED. Reading that
        // as "never paid" would cancel the second seller's goods and raise
        // nothing — the buyer keeps neither the phone nor the money.
        Order order = order(VendorOrderStatus.PREPARING, PaymentStatus.PARTIALLY_REFUNDED);
        given(order);

        service.cancelVendorOrder(BUYER, ORDER, SLICE_B, new BuyerOrderRequests.Cancel(null));

        ArgumentCaptor<RefundRequest> raised = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refunds).save(raised.capture());
        assertEquals(SLICE_B, raised.getValue().getVendorOrder().getId());
    }

    @Test
    void theHistoryRecordsWhatTheOrderWasBeforeItWasCancelled() {
        Order order = order(VendorOrderStatus.PREPARING, PaymentStatus.PAID);
        given(order);

        service.cancel(BUYER, ORDER, new BuyerOrderRequests.Cancel(null));

        ArgumentCaptor<OrderStatusHistory> written = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(history).save(written.capture());
        // PROCESSING became CANCELLED. A row saying CANCELLED became CANCELLED
        // is the history losing the only copy of what it was before.
        assertEquals(OrderStatus.PROCESSING, written.getValue().getFromStatus());
        assertEquals(OrderStatus.CANCELLED, written.getValue().getToStatus());
    }

    @Test
    void aDispatchedSliceCannotBeCancelled() {
        given(order(VendorOrderStatus.SHIPPED, PaymentStatus.PAID));

        assertThrows(BadRequestException.class, () -> service.cancelVendorOrder(
                BUYER, ORDER, SLICE_B, new BuyerOrderRequests.Cancel(null)));
    }

    // ── Receipt and escrow (C4) ──────────────────────────────────────────────

    @Test
    void receiptCannotBeConfirmedForGoodsNobodyHasSent() {
        given(order(VendorOrderStatus.PREPARING, PaymentStatus.PAID));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.confirmReceipt(BUYER, ORDER, SLICE_A,
                        new BuyerOrderRequests.ConfirmReceipt(null)));

        assertTrue(refused.getMessage().contains("not been dispatched"), refused.getMessage());
        verify(vendorOrders, never()).save(any());
    }

    @Test
    void confirmingReceiptWritesEvidenceBeforeItMovesTheStatus() {
        Order order = order(VendorOrderStatus.SHIPPED, PaymentStatus.PAID);
        given(order);

        VendorOrder slice = order.getVendorOrders().get(1);
        Delivery parcel = new Delivery();
        parcel.setId(9001L);
        parcel.setStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        parcel.setOrderItem(slice.getItems().get(0));
        when(deliveries.findByOrderItemOrderId(ORDER)).thenReturn(List.of(parcel));

        BuyerOrderResponses.ReceiptConfirmed result = service.confirmReceipt(
                BUYER, ORDER, SLICE_B, new BuyerOrderRequests.ConfirmReceipt("Got it"));

        // The custody chain records who said so. Without this the parcel reaches
        // DELIVERED with nobody having handed it to anybody.
        ArgumentCaptor<DeliveryTracking> evidence = ArgumentCaptor.forClass(DeliveryTracking.class);
        verify(tracking).save(evidence.capture());
        assertEquals(DeliveryStatus.DELIVERED, evidence.getValue().getStatus());
        assertEquals(BUYER, evidence.getValue().getRecordedBy().getId());
        assertEquals(parcel, evidence.getValue().getDelivery());

        assertEquals(VendorOrderStatus.DELIVERED, result.status());
        assertTrue(result.escrowReleased());
        assertNotNull(slice.getEscrowReleasedAt());
    }

    @Test
    void confirmingReceiptTwiceReleasesEscrowOnce() {
        Order order = order(VendorOrderStatus.SHIPPED, PaymentStatus.PAID);
        given(order);

        service.confirmReceipt(BUYER, ORDER, SLICE_B, new BuyerOrderRequests.ConfirmReceipt(null));
        LocalDateTime first = order.getVendorOrders().get(1).getEscrowReleasedAt();

        BuyerOrderResponses.ReceiptConfirmed again = service.confirmReceipt(
                BUYER, ORDER, SLICE_B, new BuyerOrderRequests.ConfirmReceipt(null));

        assertEquals(first, order.getVendorOrders().get(1).getEscrowReleasedAt());
        assertTrue(again.message().contains("already"), again.message());
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    @Test
    void reviewsAreRefusedUntilTheGoodsHaveArrived() {
        given(order(VendorOrderStatus.SHIPPED, PaymentStatus.PAID));

        assertThrows(BadRequestException.class, () -> service.review(BUYER, ORDER, SLICE_B + 1,
                new BuyerOrderRequests.PostReview(5, "Great", "Arrived fast")));
        verify(reviews, never()).save(any());
    }

    @Test
    void aReviewOfSomebodyElsesLineIsNotFound() {
        given(order(VendorOrderStatus.DELIVERED, PaymentStatus.PAID));

        assertThrows(ResourceNotFoundException.class, () -> service.review(BUYER, ORDER, 999999L,
                new BuyerOrderRequests.PostReview(5, null, null)));
    }

    // ── Invoice ──────────────────────────────────────────────────────────────

    @Test
    void thereIsNoInvoiceUntilTheOrderIsPaid() {
        given(order(VendorOrderStatus.PREPARING, PaymentStatus.PENDING));

        assertThrows(BadRequestException.class, () -> service.invoiceLink(BUYER, ORDER));
        verify(invoices, never()).link(any());
    }

    // ── The public page (C5) ─────────────────────────────────────────────────

    @Test
    void thePublicPageNamesNobodyAndQuotesNoPrice() {
        Order order = order(VendorOrderStatus.SHIPPED, PaymentStatus.PAID);
        when(orders.findByTrackingCode("K7MPQ4RTVX2ND9YH")).thenReturn(Optional.of(order));

        Delivery parcel = new Delivery();
        parcel.setId(9002L);
        parcel.setStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        when(deliveries.findByOrderItemOrderId(ORDER)).thenReturn(List.of(parcel));

        DeliveryTracking step = new DeliveryTracking();
        step.setDelivery(parcel);
        step.setStatus(DeliveryStatus.PICKED_UP);
        // A driver typed a name into the log. It must not reach this page.
        step.setDescription("Collected from Ousman at Banjul Electronics, +2207778899");
        step.setRecordedAt(LocalDateTime.of(2026, 3, 2, 10, 0));
        when(tracking.findTrail(any())).thenReturn(List.of(step));

        BuyerOrderResponses.PublicTracking page = service.publicTracking("K7MPQ4RTVX2ND9YH");

        String rendered = page.toString();
        assertFalse(rendered.contains("Awa Ceesay"), rendered);
        assertFalse(rendered.contains("+2203001122"), rendered);
        assertFalse(rendered.contains("Kairaba"), rendered);
        assertFalse(rendered.contains("Ousman"), rendered);
        assertFalse(rendered.contains("SJL-TESTONE"), rendered);
        assertFalse(rendered.contains("212.00"), rendered);

        // What it does say is enough to be useful and safe to forward.
        assertEquals("Serrekunda", page.destinationCity());
        assertEquals("GM", page.destinationCountry());
        assertEquals(1, page.parcels());
        assertEquals(0, page.parcelsDelivered());
        assertEquals("Collected from the seller.", page.events().get(0).description());
    }

    @Test
    void anUnknownTrackingCodeIsNotFound() {
        when(orders.findByTrackingCode("NOPE")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.publicTracking("NOPE"));
    }
}
