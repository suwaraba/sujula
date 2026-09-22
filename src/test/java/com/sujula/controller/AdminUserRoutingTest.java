package com.sujula.controller;

import java.time.LocalDateTime;
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
import com.sujula.dto.response.admin.AdminUserResponses;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.service.admin.AdminUserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may reach the administrative surface, and who may only look at it.
 *
 * <p>The split between ADMIN and SUPPORT is the thing being pinned here. A path
 * rule cannot express it — a read and a write sit next to each other under the
 * same prefix — so it is enforced per endpoint, and this test is what would
 * notice an endpoint that called {@code staff} where it meant {@code decider}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserRoutingTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private AdminUserService adminUsers;

    // ── The outer gate ───────────────────────────────────────────────────────

    @Test
    void theAdminSurfaceIsNotOpen() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/users/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/users").with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        verify(adminUsers, never()).search(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anOrdinaryUserCannotReachItEvenSignedIn() throws Exception {
        mvc.perform(get("/admin/users").with(authentication(auth(700L, UserRole.CUSTOMER))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").with(authentication(auth(701L, UserRole.VENDOR))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").with(authentication(auth(702L, UserRole.DELIVERY))))
                .andExpect(status().isForbidden());

        verify(adminUsers, never()).search(any(), any(), any(), any(), any(), any(), any());
    }

    // ── Support reads ────────────────────────────────────────────────────────

    @Test
    void supportCanSearchAndReadAProfile() throws Exception {
        when(adminUsers.search(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyPage());
        when(adminUsers.detail(any(), eq(9L))).thenReturn(null);

        mvc.perform(get("/admin/users").with(authentication(auth(800L, UserRole.SUPPORT))))
                .andExpect(status().isOk())
                // These rows carry names, phone numbers and email addresses; a
                // shared cache holding them would serve one agent's search to
                // the next person through the proxy.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/admin/users/9").with(authentication(auth(800L, UserRole.SUPPORT))))
                .andExpect(status().isOk());

        verify(adminUsers).search(any(), any(), any(), any(), any(), any(), any());
        verify(adminUsers).detail(any(), eq(9L));
    }

    // ── And decides nothing ──────────────────────────────────────────────────

    @Test
    void supportCannotSuspendDeactivateOrChangeARole() throws Exception {
        Authentication desk = auth(800L, UserRole.SUPPORT);

        mvc.perform(post("/admin/users/9/suspend").with(authentication(desk)).with(csrf())
                        .contentType("application/json")
                        .content("{\"days\":7,\"reason\":\"Because.\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/9/deactivate").with(authentication(desk)).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"Because.\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/9/roles").with(authentication(desk)).with(csrf())
                        .contentType("application/json")
                        .content("{\"role\":\"ADMIN\",\"reason\":\"Because.\"}"))
                .andExpect(status().isForbidden());

        verify(adminUsers, never()).suspend(any(), any(), any());
        verify(adminUsers, never()).deactivate(any(), any(), any());
        verify(adminUsers, never()).changeRole(any(), any(), any());
    }

    @Test
    void supportCannotImpersonateOrResetSomebodysSecondFactor() throws Exception {
        Authentication desk = auth(800L, UserRole.SUPPORT);

        mvc.perform(post("/admin/users/9/impersonate").with(authentication(desk)).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"Looking.\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/9/reset-mfa").with(authentication(desk)).with(csrf())
                        .contentType("application/json")
                        .content("{\"password\":\"x\",\"reason\":\"Lost phone.\"}"))
                .andExpect(status().isForbidden());

        verify(adminUsers, never()).impersonate(any(), any(), any());
        verify(adminUsers, never()).resetMfa(any(), any(), any());
    }

    // ── An administrator decides ─────────────────────────────────────────────

    @Test
    void anAdministratorReachesTheWrites() throws Exception {
        Authentication ops = auth(900L, UserRole.ADMIN);
        when(adminUsers.suspend(any(), eq(9L), any())).thenReturn(
                new AdminUserResponses.AccountChanged(9L, false, null, 2, "Suspended."));
        when(adminUsers.impersonate(any(), eq(9L), any())).thenReturn(
                new AdminUserResponses.ImpersonationOpened(9L, "Fatou", "tok",
                        LocalDateTime.now().plusMinutes(15), 5L, "Support case", "Short."));

        mvc.perform(post("/admin/users/9/suspend").with(authentication(ops)).with(csrf())
                        .contentType("application/json")
                        .content("{\"days\":7,\"reason\":\"Off-platform payment.\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/users/9/impersonate").with(authentication(ops)).with(csrf())
                        .contentType("application/json")
                        .content("{\"reason\":\"They cannot see their order.\",\"minutes\":15}"))
                .andExpect(status().isOk())
                // The response carries a live credential, so it must not be
                // stored anywhere on the way back.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));

        verify(adminUsers).suspend(any(), eq(9L), any());
        verify(adminUsers).impersonate(any(), eq(9L), any());
    }

    // ── What the binder refuses ──────────────────────────────────────────────

    @Test
    void aSuspensionWithNoReasonNeverReachesTheService() throws Exception {
        mvc.perform(post("/admin/users/9/suspend")
                        .with(authentication(auth(900L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json").content("{\"days\":7}"))
                .andExpect(status().isBadRequest());

        verify(adminUsers, never()).suspend(any(), any(), any());
    }

    @Test
    void aSuspensionLongerThanAYearIsABanAndIsRefusedAsOne() throws Exception {
        mvc.perform(post("/admin/users/9/suspend")
                        .with(authentication(auth(900L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json")
                        .content("{\"days\":400,\"reason\":\"Forever.\"}"))
                .andExpect(status().isBadRequest());

        verify(adminUsers, never()).suspend(any(), any(), any());
    }

    @Test
    void impersonationWithNoReasonIsRefusedBecauseTheReasonIsTheWholeControl() throws Exception {
        mvc.perform(post("/admin/users/9/impersonate")
                        .with(authentication(auth(900L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json").content("{\"minutes\":15}"))
                .andExpect(status().isBadRequest());

        verify(adminUsers, never()).impersonate(any(), any(), any());
    }

    @Test
    void anEditWithNoReasonIsRefused() throws Exception {
        mvc.perform(patch("/admin/users/9")
                        .with(authentication(auth(900L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json").content("{\"firstName\":\"Fatou\"}"))
                .andExpect(status().isBadRequest());

        verify(adminUsers, never()).patch(any(), any(), any());
    }

    private static PagedResponse<AdminUserResponses.UserRow> emptyPage() {
        return PagedResponse.<AdminUserResponses.UserRow>builder()
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
