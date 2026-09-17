package com.sujula.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminPlatformResponses;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.service.admin.AdminPlatformService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who works the dispute queue, and who decides one.
 *
 * <p>Support does everything an agent does on the telephone — reads the queue,
 * takes a dispute, writes an internal note, arranges a call. Deciding moves a
 * seller's money, and that is an administrator's. This test is what would notice
 * an endpoint that called {@code decider} where it meant {@code staff}, or the
 * reverse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPlatformRoutingTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private AdminPlatformService platform;

    @Test
    void thePlatformSurfaceIsNotOpen() throws Exception {
        mvc.perform(get("/admin/disputes")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/dashboard")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit-log")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/jobs")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/feature-flags")).andExpect(status().isForbidden());

        verify(platform, never()).dashboard();
    }

    @Test
    void aBuyerCannotReadTheDisputeQueueEvenThoughTheyRaisedOneOfThem() throws Exception {
        mvc.perform(get("/admin/disputes").with(authentication(auth(950L, UserRole.CUSTOMER))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/callbacks").with(authentication(auth(951L, UserRole.CUSTOMER))))
                .andExpect(status().isForbidden());

        verify(platform, never()).queue(any(), anyBoolean(), any(), anyBoolean(), any(), any(),
                anyBoolean(), any());
    }

    @Test
    void supportWorksTheQueue() throws Exception {
        when(platform.queue(any(), anyBoolean(), any(), anyBoolean(), any(), any(), anyBoolean(),
                any())).thenReturn(emptyPage());
        when(platform.outstandingCallbacks(any())).thenReturn(emptyPage());
        when(platform.jobs()).thenReturn(List.of());
        when(platform.flags()).thenReturn(List.of());
        when(platform.dashboard()).thenReturn(new AdminPlatformResponses.Dashboard(
                java.time.LocalDateTime.now(), List.of(), 0, 0, 0, 0, 0, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, List.of()));

        Authentication support = auth(960L, UserRole.SUPPORT);
        mvc.perform(get("/admin/disputes").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/callbacks").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/dashboard").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/jobs").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/feature-flags").with(authentication(support)))
                .andExpect(status().isOk());
    }

    @Test
    void supportTakesADisputeAndWritesNotesAndArrangesCalls() throws Exception {
        Authentication support = auth(961L, UserRole.SUPPORT);

        mvc.perform(post("/admin/disputes/7/assign").with(authentication(support)).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/disputes/7/notes").with(authentication(support)).with(csrf())
                        .contentType("application/json")
                        .content("{\"body\":\"Buyer has filed three of these.\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/admin/disputes/7/request-callback").with(authentication(support))
                        .with(csrf()).contentType("application/json")
                        .content("{\"reason\":\"She needs to describe the packaging.\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void supportCannotDecideADisputeOrBroadcastOrMoveAFlag() throws Exception {
        Authentication support = auth(962L, UserRole.SUPPORT);

        mvc.perform(post("/admin/disputes/7/resolve").with(authentication(support)).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"outcome":"FOR_BUYER","resolutionNote":"Cracked on arrival.",
                                 "password":"x"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/announcements").with(authentication(support)).with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"Closed\",\"body\":\"No collections Tuesday.\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/admin/feature-flags/delivery.safe-drop").with(authentication(support))
                        .with(csrf()).contentType("application/json")
                        .content("{\"enabled\":false,\"reason\":\"Two parcels missing.\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/jobs/finance-exports/run").with(authentication(support))
                        .with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());

        verify(platform, never()).resolve(any(), any(), any());
        verify(platform, never()).announce(any(), any());
        verify(platform, never()).setFlag(any(), any(), any());
        verify(platform, never()).triggerJob(any(), any(), any());
    }

    @Test
    void supportCannotReadTheAuditLog() throws Exception {
        // The log names every administrator's decision about every account. An
        // agent needs the dispute in front of them, not the record of who has
        // been suspending people.
        mvc.perform(get("/admin/audit-log").with(authentication(auth(963L, UserRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(platform, never()).auditLog(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void decidingWithoutAPasswordNeverReachesTheService() throws Exception {
        mvc.perform(post("/admin/disputes/7/resolve")
                        .with(authentication(auth(964L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json")
                        .content("{\"outcome\":\"FOR_BUYER\",\"resolutionNote\":\"Cracked.\"}"))
                .andExpect(status().isBadRequest());

        verify(platform, never()).resolve(any(), any(), any());
    }

    @Test
    void decidingWithoutSayingWhyNeverReachesTheService() throws Exception {
        // Both parties are shown this. A decision with no reasoning is one
        // neither of them can argue with or accept.
        mvc.perform(post("/admin/disputes/7/resolve")
                        .with(authentication(auth(965L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json")
                        .content("{\"outcome\":\"FOR_BUYER\",\"resolutionNote\":\"  \","
                                + "\"password\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verify(platform, never()).resolve(any(), any(), any());
    }

    @Test
    void omittingAnOptionalFlagDoesNotMakeTheWholeBodyInvalid() throws Exception {
        // requireReturn is optional and absent here. It was a primitive boolean
        // once, and this binder rejects a body with a primitive missing as "not
        // valid JSON" — so the most ordinary request a client makes could not be
        // made at all. It reaches the service now, which is what 403 proves:
        // support is refused by the ROLE rather than by the binder.
        mvc.perform(post("/admin/disputes/7/resolve")
                        .with(authentication(auth(968L, UserRole.SUPPORT))).with(csrf())
                        .contentType("application/json")
                        .content("{\"outcome\":\"FOR_VENDOR\",\"resolutionNote\":\"No case.\","
                                + "\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void movingAFlagWithoutAReasonNeverReachesTheService() throws Exception {
        // A flag with no reason beside it is one nobody dares turn back on.
        mvc.perform(patch("/admin/feature-flags/delivery.safe-drop")
                        .with(authentication(auth(966L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());

        verify(platform, never()).setFlag(any(), any(), any());
    }

    @Test
    void aCallbackWithNoReasonNeverReachesTheService() throws Exception {
        mvc.perform(post("/admin/disputes/7/request-callback")
                        .with(authentication(auth(967L, UserRole.SUPPORT))).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(platform, never()).requestCallback(any(), any(), any());
    }

    private static <T> PagedResponse<T> emptyPage() {
        return PagedResponse.<T>builder()
                .content(List.of()).page(0).size(20).totalElements(0).totalPages(0).last(true)
                .build();
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
