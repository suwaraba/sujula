package com.sujula.controller;

import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.service.buyerorder.BuyerOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may reach which URL — the one thing a service test cannot answer.
 *
 * <p>Three of these endpoints are open and the rest are not, and getting that
 * backwards in either direction is serious: an open {@code /orders} publishes
 * everybody's address, and a closed {@code /track} makes the whole
 * accountless-recipient case unreachable. So the rules are asserted against a
 * booted application with its real filter chain rather than read off the config.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BuyerOrderSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BuyerOrderService orders;

    /**
     * Refused, and nothing of the order leaks in the refusal.
     *
     * <p>The status is 403 rather than 401 because this chain configures no
     * authentication entry point, so Spring Security falls back to
     * {@code Http403ForbiddenEntryPoint} — the same answer every other
     * authenticated endpoint on this API gives an anonymous caller. Asserted as
     * it is rather than as it arguably should be: a token-based API telling a
     * signed-out client "forbidden" instead of "sign in" is worth changing, but
     * changing it moves every endpoint at once and is not this surface's call.
     */
    @Test
    void aBuyersOrdersAreNotReadableWithoutSigningIn() throws Exception {
        mvc.perform(get("/orders")).andExpect(status().isForbidden());
        mvc.perform(get("/orders/1")).andExpect(status().isForbidden());
        mvc.perform(get("/orders/1/tracking")).andExpect(status().isForbidden());
        mvc.perform(get("/orders/1/invoice")).andExpect(status().isForbidden());
    }

    @Test
    void theTrackingPageIsOpenAndReachesTheController() throws Exception {
        when(orders.publicTracking(anyString())).thenReturn(new BuyerOrderResponses.PublicTracking(
                "K7MPQ4RTVX2ND9YH", "IN_TRANSIT", "On its way.", 2, 0,
                "Serrekunda", "GM", null, null, null, LocalDateTime.now(), List.of()));

        // A 200 here, rather than a 401, is the whole point: the recipient has
        // no account to sign in with.
        mvc.perform(get("/track/K7MPQ4RTVX2ND9YH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationCity").value("Serrekunda"))
                .andExpect(jsonPath("$.stage").value("IN_TRANSIT"));
    }

    @Test
    void theInvoiceLinkIsOpenButAForgedTokenIsRejected() throws Exception {
        // Reaching the controller at all proves the path is permitted; the 400
        // proves the signature is still what decides.
        mvc.perform(get("/invoices/not-a-real-token")).andExpect(status().isBadRequest());
    }
}
