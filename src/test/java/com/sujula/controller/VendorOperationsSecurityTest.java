package com.sujula.controller;

import com.sujula.service.inventory.InventoryService;
import com.sujula.service.promotion.PromotionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nothing on the stock or discount surface is open.
 *
 * <p>What an unguarded route here would hand out is worth being explicit about:
 * a shop's stock figures and cost prices, the IMEIs of every handset they hold,
 * their margins, and — through a coupon's redemptions — their customer list.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VendorOperationsSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InventoryService inventory;

    @MockitoBean
    private PromotionService promotions;

    @Test
    void stockAndHandsetsRefuseAnAnonymousCaller() throws Exception {
        mvc.perform(get("/vendor/inventory")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/vendor/inventory/1")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/inventory/bulk")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/inventory/1/movements")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/imei-units")).andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/imei-units")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/vendor/imei-units/1")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void promotionsAndCouponsDoToo() throws Exception {
        mvc.perform(get("/vendor/promotions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/promotions")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/promotions/1/activate")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/vendor/promotions/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/coupons")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/coupons/1/redemptions")).andExpect(status().isUnauthorized());
    }

    @Test
    void andTheServicesAreNeverReached() throws Exception {
        mvc.perform(get("/vendor/inventory"));
        mvc.perform(get("/vendor/imei-units"));
        mvc.perform(get("/vendor/coupons/1/redemptions"));

        // Refused by the chain rather than by a check inside the service. A rule
        // that let the request through and trusted the service to notice would
        // be one forgotten ownership check away from handing over a shop's
        // cost prices and its customer list.
        verify(inventory, never()).list(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        verify(inventory, never()).handsets(any(), any(), any(), any());
        verify(promotions, never()).redemptions(any(), any(), any());
    }
}
