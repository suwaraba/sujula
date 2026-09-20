package com.sujula.controller;

import com.sujula.dto.response.vendorcatalogue.VendorProductResponses;
import com.sujula.model.constant.CatalogueJobStatus;
import com.sujula.model.constant.CatalogueJobType;
import com.sujula.service.vendorcatalogue.CatalogueJobService;
import com.sujula.service.vendorcatalogue.VendorCatalogueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who reaches this surface, and which handler answers.
 *
 * <p>The routing half is not paperwork. {@code /vendor/products/export} and
 * {@code /vendor/products/{productId}} are the same shape, and if the literal
 * path loses to the variable one then "export my catalogue" becomes "product id
 * 'export' is not a number" - a 400 on a working feature, found by a seller
 * rather than by us. The only way to know which pattern wins is to ask a booted
 * dispatcher.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VendorCatalogueRoutingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VendorCatalogueService catalogue;

    @MockitoBean
    private CatalogueJobService jobs;

    @Test
    void nothingHereIsOpenToAnAnonymousCaller() throws Exception {
        mvc.perform(get("/vendor/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/products/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/products").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/products/1/publish")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/vendor/products/1/media/2")).andExpect(status().isUnauthorized());
        mvc.perform(put("/vendor/products/1/translations/fr-SN")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/products/export")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/imports/IMP-ABC")).andExpect(status().isUnauthorized());
    }

    @Test
    void andTheServiceIsNeverReachedThroughTheChain() throws Exception {
        mvc.perform(get("/vendor/products/1"));
        mvc.perform(get("/vendor/products/export"));

        // Refused by the filter chain rather than by a check inside the service.
        // A rule that let the request through and trusted the service to notice
        // would be one forgotten ownership check away from serving somebody
        // else's catalogue.
        verify(catalogue, never()).get(any(), any());
        verify(jobs, never()).startExport(any(), anyBoolean());
    }

    /**
     * The literal path must beat the variable one.
     *
     * <p>Signed in, deliberately. An anonymous request is refused by the filter
     * chain before the dispatcher ever picks a handler, so a 403 would prove
     * nothing about routing - it is exactly what a URL with no handler at all
     * would return. Only an authenticated request gets far enough to show which
     * pattern won, and the proof is which service method was called.
     */
    @Test
    void theLiteralPathsAreNotSwallowedByTheProductIdPattern() throws Exception {
        when(jobs.startExport(any(), anyBoolean())).thenReturn(job());
        when(jobs.template()).thenReturn(
                new VendorProductResponses.ImportTemplate(List.of("name"), List.of("sku")));

        mvc.perform(get("/vendor/products/export").with(authentication(sellerAuth())))
                .andExpect(status().isAccepted());
        mvc.perform(get("/vendor/products/import-template").with(authentication(sellerAuth())))
                .andExpect(status().isOk());

        // Had {productId} won, these would have died converting "export" to a
        // Long and the catalogue service would have been asked for product
        // number nothing.
        verify(jobs).startExport(any(), anyBoolean());
        verify(jobs).template();
        verify(catalogue, never()).get(any(), any());
    }

    /**
     * The principal this API actually carries.
     *
     * <p>{@code AuthenticatedCaller} reads the user off the authentication's
     * principal rather than a username, so a plain {@code user("lamin")} would
     * authenticate the request and then fail inside the controller for a reason
     * that has nothing to do with what is being tested.
     */
    private static org.springframework.security.core.Authentication sellerAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                seller(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_VENDOR")));
    }

    private static com.sujula.model.user.User seller() {
        com.sujula.model.user.User user = new com.sujula.model.user.User();
        user.setId(950L);
        user.setEmail("lamin@sujula.gm");
        user.setRole(com.sujula.model.constant.UserRole.VENDOR);
        return user;
    }

    private static VendorProductResponses.Job job() {
        return new VendorProductResponses.Job(
                "EXP-TEST", CatalogueJobType.EXPORT, CatalogueJobStatus.QUEUED,
                null, "csv", 0, 0, 0, null, List.of(), 0, 0, null, null,
                LocalDateTime.now(), null, null);
    }
}
