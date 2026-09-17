package com.sujula.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who can reach the machine surface.
 *
 * <p>Webhooks are open at the filter chain and defended inside the handler,
 * which is the only thing available: the caller is a machine in somebody else's
 * data centre with no account here. Probes are open because they have to answer
 * before anything is ready. Metrics are not open at all — they say when the
 * platform is struggling and which path to press on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookRoutingTest {

    @Autowired private MockMvc mvc;

    @Test
    void anUnsignedWebhookReachesTheHandlerAndIsRefusedThere() throws Exception {
        // 401, not 403: the filter chain lets it through on purpose, and what
        // refuses it is the missing signature. A 403 here would mean the route
        // was closed and the signature check never ran.
        mvc.perform(post("/webhooks/psp/wave")
                        .contentType("application/json")
                        .content("{\"id\":\"evt_1\",\"type\":\"payment.succeeded\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWebhookForAnUnconfiguredProviderIsRefused() throws Exception {
        // No secret is configured for "somebody-elses-psp" in the test profile,
        // and an unconfigured provider is refused rather than trusted — the
        // default state of a config map is empty.
        long now = Instant.now().getEpochSecond();
        byte[] body = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/webhooks/psp/somebody-elses-psp")
                        .header("X-Sujula-Signature", "deadbeef")
                        .header("X-Sujula-Timestamp", String.valueOf(now))
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRefusalSaysNothingAboutWhichCheckFailed() throws Exception {
        long now = Instant.now().getEpochSecond();

        // Telling somebody probing whether they got the signature or the clock
        // wrong is telling them how to make progress.
        mvc.perform(post("/webhooks/psp/wave")
                        .header("X-Sujula-Signature", "deadbeef")
                        .header("X-Sujula-Timestamp", String.valueOf(now))
                        .contentType("application/json").content("{\"id\":\"evt_3\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("signature"))));
    }

    @Test
    void everyWebhookPathInTheSpecExists() throws Exception {
        // Each answers 401 rather than 404, which is what proves the route is
        // mapped and reached the signature check.
        for (String path : List.of("/webhooks/psp/wave", "/webhooks/sms/wave",
                                   "/webhooks/kyc/wave", "/webhooks/messaging/wave")) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anOversizedBodyIsRefusedBeforeAnythingReadsIt() throws Exception {
        // An unauthenticated endpoint that parses arbitrarily large bodies is a
        // way to exhaust this process from outside.
        byte[] huge = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) 'a');

        mvc.perform(post("/webhooks/psp/wave")
                        .contentType("application/json").content(huge))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void aWebhookNeedsNoCsrfTokenBecauseItsSenderHasNoBrowser() throws Exception {
        // No .with(csrf()) anywhere in this class. If CSRF applied to
        // /webhooks/**, every one of these would be 403 instead of 401 — the
        // signature is what authenticates them, and it is strictly stronger.
        mvc.perform(post("/webhooks/psp/wave")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void probesAnswerWithoutAnAccount() throws Exception {
        // They have to work before anything is ready, which is the moment an
        // orchestrator most needs an answer.
        mvc.perform(get("/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void livenessSaysNothingAStrangerCanUse() throws Exception {
        // Versions, hostnames and driver details are what somebody scanning
        // wants, and an unauthenticated endpoint that volunteers them is doing
        // their reconnaissance for them.
        mvc.perform(get("/health/liveness"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsStringIgnoringCase("version"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsStringIgnoringCase("mysql"))));
    }

    @Test
    void theMetricsScrapeIsNotPublic() throws Exception {
        // It carries request counts, error rates and timings per endpoint —
        // enough to tell somebody outside when the platform is struggling and
        // which path to press on.
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus")
                        .with(authentication(auth(970L, UserRole.VENDOR))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus")
                        .with(authentication(auth(971L, UserRole.SUPPORT))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorCanScrapeIt() throws Exception {
        mvc.perform(get("/actuator/prometheus")
                        .with(authentication(auth(972L, UserRole.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void theActuatorSurfaceIsNarrowedToThreeEndpoints() throws Exception {
        // The default set includes /env and /configprops, which print
        // configuration — and this application's configuration includes webhook
        // signing secrets and a field-encryption key.
        Authentication admin = auth(973L, UserRole.ADMIN);
        mvc.perform(get("/actuator/env").with(authentication(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/configprops").with(authentication(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/beans").with(authentication(admin)))
                .andExpect(status().isNotFound());
    }

    private static Authentication auth(Long userId, UserRole role) {
        User user = new User();
        user.setId(userId);
        user.setEmail("user" + userId + "@sujula.gm");
        user.setRole(role);
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }
}
