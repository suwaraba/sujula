package com.sujula.controller;

import com.sujula.exceptions.GlobalExceptionHandler;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.dto.request.order.UpdateOrderStatusRequest;
import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.dto.response.order.GuestOrderLookupResponse;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.order.Order;
import com.sujula.model.user.User;
import com.sujula.service.OrderService;
import com.sujula.service.PaymentService;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.payment.PaymentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The superseded creation URLs must stop at the controller before service work begins. */
class OrderControllerLegacyCreationTest {

    private final OrderService orders = mock(OrderService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final OrderController controller = new OrderController(orders);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    controller, new PaymentController(payments, new PaymentProperties(), idempotency))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void explicitAuthenticatedCreationIsGoneBeforeItCanReachOrderService() throws Exception {
        mvc.perform(post("/api/user/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(containsString("POST /checkout")));

        verifyNoInteractions(orders);
    }

    @Test
    void authenticatedCartCreationIsGoneBeforeItCanReachOrderService() throws Exception {
        mvc.perform(post("/api/user/orders/checkout-cart"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(containsString("POST /checkout")));

        verifyNoInteractions(orders);
    }

    @Test
    void administrativeCreationIsGoneBeforeItCanReachOrderService() throws Exception {
        mvc.perform(post("/api/admin/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(containsString("POST /checkout")));

        verifyNoInteractions(orders);
    }

    @Test
    void guestCreationIsGoneBeforeItCanReachOrderService() throws Exception {
        mvc.perform(post("/api/guest/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"guestName":"Awa Jallow","guestEmail":"awa@example.com",
                                 "guestPhone":"+2207000000","shippingFullName":"Fatou Jallow",
                                 "shippingPhone":"+2207111111","shippingStreet":"1 Main Road",
                                 "shippingCity":"Banjul","shippingCountry":"GM"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(containsString("guest checkout is no longer available")))
                .andExpect(jsonPath("$.message").value(containsString("POST /checkout")));

        verifyNoInteractions(orders);
    }

    @Test
    void historicGuestLookupReturnsOnlyTheRestrictedCompatibilityDto() throws Exception {
        when(orders.findGuestOrder("SJL-1008", "guest@example.com")).thenReturn(
                new GuestOrderLookupResponse(
                        "SJL-1008", OrderStatus.PENDING, PaymentStatus.PENDING,
                        "GMD", new BigDecimal("250.00"),
                        List.of(new GuestOrderLookupResponse.Line("Rice", 2)),
                        new GuestOrderLookupResponse.Destination("Banjul", "GM"),
                        LocalDateTime.of(2026, 9, 1, 10, 15)));

        mvc.perform(get("/api/guest/orders/lookup")
                        .param("orderNumber", "SJL-1008")
                        .param("email", "guest@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("SJL-1008"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.currency").value("GMD"))
                .andExpect(jsonPath("$.total").value(250.00))
                .andExpect(jsonPath("$.items[0].productName").value("Rice"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.destination.city").value("Banjul"))
                .andExpect(jsonPath("$.destination.country").value("GM"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.guestSessionId").doesNotExist())
                .andExpect(jsonPath("$.guestEmail").doesNotExist())
                .andExpect(jsonPath("$.guestPhone").doesNotExist())
                .andExpect(jsonPath("$.internalNotes").doesNotExist())
                .andExpect(jsonPath("$.trackingCode").doesNotExist())
                .andExpect(jsonPath("$.shippingFullName").doesNotExist())
                .andExpect(jsonPath("$.shippingPhone").doesNotExist())
                .andExpect(jsonPath("$.shippingStreet").doesNotExist())
                .andExpect(jsonPath("$.shippingAddress").doesNotExist())
                .andExpect(jsonPath("$.shippingLatitude").doesNotExist())
                .andExpect(jsonPath("$.shippingLongitude").doesNotExist())
                .andExpect(jsonPath("$.billingFullName").doesNotExist())
                .andExpect(jsonPath("$.billingStreet").doesNotExist())
                .andExpect(jsonPath("$.payment").doesNotExist())
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.transactionId").doesNotExist())
                .andExpect(jsonPath("$.reference").doesNotExist())
                .andExpect(jsonPath("$.collectionReference").doesNotExist());

        verify(orders).findGuestOrder("SJL-1008", "guest@example.com");
    }

    @Test
    void historicGuestLookupKeepsTheExistingNotFoundResponseForTheWrongPair() throws Exception {
        when(orders.findGuestOrder("SJL-1008", "wrong@example.com"))
                .thenThrow(new ResourceNotFoundException("Order", "no matching guest order"));

        mvc.perform(get("/api/guest/orders/lookup")
                        .param("orderNumber", "SJL-1008")
                        .param("email", "wrong@example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("no matching guest order")));

        verify(orders).findGuestOrder("SJL-1008", "wrong@example.com");
    }

    @Test
    void historicGuestCancellationIsGoneBeforeItCanReachOrderService() throws Exception {
        mvc.perform(post("/api/guest/orders/SJL-1008/cancel")
                        .param("email", "guest@example.com"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(
                        containsString("self-service guest cancellation is no longer available")))
                .andExpect(jsonPath("$.message").value(containsString("Contact support")));

        verifyNoInteractions(orders);
    }

    @Test
    void legacyBuyerCancellationIsGoneBeforeItCanReachOrderService() throws Exception {
        mvc.perform(post("/api/user/orders/91/cancel"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(
                        containsString("legacy buyer cancellation endpoint has been retired")))
                .andExpect(jsonPath("$.message").value(containsString("POST /orders/{orderId}/cancel")));

        verifyNoInteractions(orders);
    }

    @Test
    void adminCancelledStatusIsGoneBeforeItCanReachOrderService() throws Exception {
        mvc.perform(patch("/api/admin/orders/91/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(
                        containsString("legacy administrative status endpoint has been retired")))
                .andExpect(jsonPath("$.message").value(containsString("POST /admin/orders/{orderId}/cancel")));

        verifyNoInteractions(orders);
    }

    @Test
    void adminNonCancellationStatusStillDelegatesToTheExistingService() {
        Authentication admin = new UsernamePasswordAuthenticationToken(
                User.builder().id(17L).role(UserRole.ADMIN).build(), null, List.of());
        UpdateOrderStatusRequest request = UpdateOrderStatusRequest.builder()
                .status(OrderStatus.PROCESSING)
                .notes("Payment verified")
                .build();
        Order updated = mock(Order.class);
        when(orders.updateStatus(91L, 17L, request)).thenReturn(updated);

        assertEquals(updated, controller.updateStatus(admin, 91L, request).getBody());

        verify(orders).updateStatus(91L, 17L, request);
    }

    @Test
    void historicGuestPaymentRemainsReachable() throws Exception {
        when(payments.findForGuest("SJL-1008", "guest@example.com"))
                .thenReturn(PaymentResponse.builder().orderNumber("SJL-1008").build());

        mvc.perform(get("/api/guest/orders/SJL-1008/payment")
                        .param("email", "guest@example.com"))
                .andExpect(status().isOk());

        verify(payments).findForGuest("SJL-1008", "guest@example.com");
    }

    @Test
    void historicGuestPaymentMethodsRemainReachable() throws Exception {
        when(payments.availableMethodsForGuest("SJL-1008", "guest@example.com")).thenReturn(List.of());

        mvc.perform(get("/api/guest/orders/SJL-1008/payment/methods")
                        .param("email", "guest@example.com"))
                .andExpect(status().isOk());

        verify(payments).availableMethodsForGuest("SJL-1008", "guest@example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicGuestPaymentInitiationRemainsReachable() throws Exception {
        when(idempotency.execute(anyString(), anyString(), any(), eq(201),
                eq(PaymentResponse.class), any()))
                .thenAnswer(call -> ((Supplier<PaymentResponse>) call.getArgument(5)).get());
        when(payments.initiateForGuest(eq("SJL-1008"), eq("guest@example.com"),
                any(InitiatePaymentRequest.class), any()))
                .thenReturn(PaymentResponse.builder().orderNumber("SJL-1008").build());

        mvc.perform(post("/api/guest/orders/SJL-1008/payment")
                        .param("email", "guest@example.com")
                        .header("Idempotency-Key", "guest-payment-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"PAY_ON_DELIVERY\"}"))
                .andExpect(status().isCreated());

        verify(payments).initiateForGuest(eq("SJL-1008"), eq("guest@example.com"),
                any(InitiatePaymentRequest.class), any());
    }
}
