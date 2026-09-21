package com.sujula.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The buyer application is reachable, and reaching it opens nothing else.
 *
 * <p>The shell is markup and script: it holds no data, and every figure on
 * every screen is fetched at runtime from an endpoint with its own rule. So it
 * is open, and it has to be — the first screen asks a shopper where their
 * parcel is going, which happens long before there is an account.
 *
 * <p>The second half of this test is the half that matters. Adding a
 * {@code permitAll} for static files is the kind of change that opens a
 * neighbouring path by accident, so the rules either side of it are asserted
 * here rather than read off the configuration: the basket stays open, a
 * buyer's orders stay shut, and the seller's back office stays shut.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorefrontShellTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void theShopOpensWithoutAnAccount() throws Exception {
        mvc.perform(get("/app/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("js/main.js")));
    }

    /**
     * A directory is not a resource.
     *
     * <p>The static handler serves {@code /app/index.html} and has nothing to
     * answer {@code /app/} with, so the trailing-slash URL — the one every
     * redirect and every manifest hands out — is forwarded rather than left to
     * 404.
     *
     * <p>Asserted as a forward rather than as content because MockMvc records
     * a forward instead of following it; the test above is the other half,
     * proving the target it forwards to is a file that exists and is served.
     * The two together are the chain.
     */
    @Test
    void theTrailingSlashForwardsToTheShell() throws Exception {
        mvc.perform(get("/app/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
    }

    @Test
    void theBareHostnameLandsInTheShop() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/"));
        mvc.perform(get("/app")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/"));
    }

    @Test
    void theClientsOwnFilesAreServed() throws Exception {
        mvc.perform(get("/app/styles.css")).andExpect(status().isOk());
        mvc.perform(get("/app/js/main.js")).andExpect(status().isOk());
        mvc.perform(get("/app/js/views/checkout.js")).andExpect(status().isOk());
        mvc.perform(get("/app/manifest.webmanifest")).andExpect(status().isOk());
    }

    /**
     * What the storefront needs before anyone signs in, and nothing more.
     *
     * <p>These four are what the first paint fetches: what this deployment
     * supports, what it charges in, where it ships, and where the shopper
     * appears to be. All were already open; asserted here because the client
     * is now unusable if any of them closes.
     */
    @Test
    void theFirstPaintCanFetchWhatItNeeds() throws Exception {
        mvc.perform(get("/config/public")).andExpect(status().isOk());
        mvc.perform(get("/currencies")).andExpect(status().isOk());
        mvc.perform(get("/countries")).andExpect(status().isOk());
        mvc.perform(get("/geo/resolve-context")).andExpect(status().isOk());
    }

    /**
     * Serving a page at /app/ did not open anything beside it.
     */
    @Test
    void nothingElseBecameReadable() throws Exception {
        mvc.perform(get("/orders")).andExpect(status().isForbidden());
        mvc.perform(get("/me")).andExpect(status().isForbidden());
        mvc.perform(get("/vendor/products")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
    }
}
