package com.sujula.controller;

import com.sujula.exceptions.GlobalExceptionHandler;
import com.sujula.model.order.Order;
import com.sujula.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The superseded creation URLs must stop at the controller before service work begins. */
class OrderControllerLegacyCreationTest {

    private final OrderService orders = mock(OrderService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders))
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
    void guestCreationRemainsAvailableAndIsNotChangedToTheMigrationResponse() throws Exception {
        when(orders.createGuestOrder(any())).thenReturn(new Order());

        mvc.perform(post("/api/guest/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"guestName":"Awa Jallow","guestEmail":"awa@example.com",
                                 "guestPhone":"+2207000000","shippingFullName":"Fatou Jallow",
                                 "shippingPhone":"+2207111111","shippingStreet":"1 Main Road",
                                 "shippingCity":"Banjul","shippingCountry":"GM"}
                                """))
                .andExpect(status().isCreated());

        verify(orders).createGuestOrder(any());
    }
}
