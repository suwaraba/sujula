package com.sujula.controller;

import com.sujula.service.store.StoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nothing on this surface is open.
 *
 * <p>Worth asserting against a booted filter chain rather than reading off the
 * config, because the store surface is the one place on this API where a rule
 * that failed open would expose a seller's payout destination, their staff list
 * and the storage keys of their identity documents in one go.
 *
 * <p>The status is 403 rather than 401 for the same reason it is everywhere else
 * here: this chain configures no authentication entry point, so Spring Security
 * falls back to {@code Http403ForbiddenEntryPoint}. Asserted as it is rather
 * than as it arguably should be — changing it moves every endpoint at once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StoreSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StoreService stores;

    @Test
    void everyStoreEndpointRefusesAnAnonymousCaller() throws Exception {
        mvc.perform(post("/vendor/stores").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/vendor/stores/1")).andExpect(status().isForbidden());
        mvc.perform(get("/vendor/stores/1/kyc")).andExpect(status().isForbidden());
        mvc.perform(get("/vendor/stores/1/staff")).andExpect(status().isForbidden());
        mvc.perform(delete("/vendor/stores/1/staff/2")).andExpect(status().isForbidden());
        mvc.perform(put("/vendor/stores/1/bank-account")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void andTheServiceIsNeverReached() throws Exception {
        mvc.perform(get("/vendor/stores/1"));
        mvc.perform(get("/vendor/stores/1/kyc"));
        mvc.perform(put("/vendor/stores/1/bank-account")
                .contentType("application/json").content("{}"));

        // Refused by the chain, not by a check inside the service. A rule that
        // let the request through and relied on the service to notice would be
        // one forgotten ownership check away from serving somebody else's shop.
        verify(stores, never()).get(org.mockito.ArgumentMatchers.any(),
                                    org.mockito.ArgumentMatchers.any());
        verify(stores, never()).kycState(org.mockito.ArgumentMatchers.any(),
                                         org.mockito.ArgumentMatchers.any());
        verify(stores, never()).putBankAccount(org.mockito.ArgumentMatchers.any(),
                                               org.mockito.ArgumentMatchers.any(),
                                               org.mockito.ArgumentMatchers.any());
    }
}
