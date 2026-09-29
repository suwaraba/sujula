package com.sujula.controller;

import com.sujula.exceptions.GlobalExceptionHandler;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.model.order.Order;
import com.sujula.service.OrderService;
import com.sujula.service.PaymentService;
import com.sujula.service.payment.PaymentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The superseded creation URLs must stop at the controller before service work begins. */
class OrderControllerLegacyCreationTest {

    private final OrderService orders = mock(OrderService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new OrderController(orders), new PaymentController(payments, new PaymentProperties()))
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
    void historicGuestLookupRemainsReachable() throws Exception {
        when(orders.findGuestOrder("SJL-1008", "guest@example.com")).thenReturn(new Order());

        mvc.perform(get("/api/guest/orders/lookup")
                        .param("orderNumber", "SJL-1008")
                        .param("email", "guest@example.com"))
                .andExpect(status().isOk());

        verify(orders).findGuestOrder("SJL-1008", "guest@example.com");
    }

    @Test
    void historicGuestCancellationRemainsReachable() throws Exception {
        when(orders.cancelGuestOrder("SJL-1008", "guest@example.com")).thenReturn(new Order());

        mvc.perform(post("/api/guest/orders/SJL-1008/cancel")
                        .param("email", "guest@example.com"))
                .andExpect(status().isOk());

        verify(orders).cancelGuestOrder("SJL-1008", "guest@example.com");
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
}
