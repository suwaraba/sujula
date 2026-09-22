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

import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.service.UserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may change somebody else's account on the older {@code /api/users}
 * surface — and, more importantly, <em>when</em> the refusal happens.
 *
 * <h2>What this test is actually pinning</h2>
 *
 * <p>Every method here used to carry {@code @PostAuthorize}, which runs after
 * the annotated method returns. The transaction lives on the service method, so
 * it had already committed by then: an ordinary customer could call
 * {@code DELETE /api/users/{id}/permanent}, be answered 403, and the account
 * would be gone. The status was right and the row was gone anyway.
 *
 * <p>So <strong>the status assertions are the smaller half of every test
 * below.</strong> The half that matters is {@code verify(users, never())} — the
 * service must not be reached at all. A 403 with the service invoked is the bug
 * this file exists to catch, and it is invisible to a test that only reads the
 * response code.
 *
 * <p>There are two locks and both are checked here: the {@code @PreAuthorize}
 * on each method, and the path rules {@code SecurityConfig} carries over the
 * administrative routes. The second is what holds when somebody adds a method
 * and forgets the first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAccountSecurityTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private UserService users;

    private static final long AMINATA = 1050L;
    private static final long SOMEBODY_ELSE = 1007L;

    // ── Nobody at all ────────────────────────────────────────────────────────

    @Test
    void anAnonymousCallerReachesNoneOfIt() throws Exception {
        mvc.perform(get("/api/users?role=CUSTOMER")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/" + SOMEBODY_ELSE + "/permanent").with(csrf()))
                .andExpect(status().isUnauthorized());

        verify(users, never()).findAll(any(), any());
        verify(users, never()).deleteAccountPermanently(anyLong());
    }

    // ── A signed-in customer, which is the case that was wrong ───────────────

    /**
     * The regression this file was written for.
     *
     * <p>Under {@code @PostAuthorize} this returned 403 <em>and deleted the
     * account</em>. The {@code never()} is the assertion; the status is
     * corroboration.
     */
    @Test
    void aCustomerCannotPermanentlyDeleteAnotherAccount() throws Exception {
        mvc.perform(delete("/api/users/" + SOMEBODY_ELSE + "/permanent")
                        .with(authentication(customer(AMINATA))).with(csrf()))
                .andExpect(status().isForbidden());

        verify(users, never()).deleteAccountPermanently(anyLong());
    }

    @Test
    void aCustomerCannotDeleteAnotherAccount() throws Exception {
        mvc.perform(delete("/api/users/" + SOMEBODY_ELSE)
                        .with(authentication(customer(AMINATA))).with(csrf()))
                .andExpect(status().isForbidden());

        verify(users, never()).deleteById(anyLong());
    }

    @Test
    void aCustomerCannotEditAnotherProfile() throws Exception {
        mvc.perform(put("/api/users/" + SOMEBODY_ELSE)
                        .with(authentication(customer(AMINATA))).with(csrf())
                        .contentType("application/json")
                        .content("{\"firstName\":\"Taken\",\"lastName\":\"Over\","
                                + "\"email\":\"attacker@example.com\","
                                + "\"password\":\"Sujula123!\"}"))
                .andExpect(status().isForbidden());

        verify(users, never()).updateUser(anyLong(), any());
    }

    /**
     * Blocking, flagging and their inverses, in one sweep.
     *
     * <p>Six endpoints that each shut or reopen somebody's account. They are
     * gated twice — a {@code @PreAuthorize} on the method and a path rule in
     * {@code SecurityConfig} — so this passes even if one of the two is removed,
     * which is the point of having both.
     */
    @Test
    void aCustomerCannotShutOrReopenAnotherAccount() throws Exception {
        Authentication aminata = customer(AMINATA);
        String base = "/api/users/" + SOMEBODY_ELSE;

        for (String path : List.of(base + "/block", base + "/unblock",
                                   base + "/enable", base + "/disable",
                                   base + "/unlock")) {
            mvc.perform(patch(path).with(authentication(aminata)).with(csrf())
                            .contentType("application/json").content("{\"blocked\":true}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(patch(base + "/fraud?fraud=true")
                        .with(authentication(aminata)).with(csrf()))
                .andExpect(status().isForbidden());

        verify(users, never()).blockUser(anyLong(), anyBoolean(), anyBoolean());
        verify(users, never()).unblockUser(anyLong());
        verify(users, never()).enableUser(anyLong());
        verify(users, never()).disableUser(anyLong());
        verify(users, never()).unlockUser(anyLong());
        verify(users, never()).markFraud(anyLong(), anyBoolean());
    }

    /**
     * Reads, which were also {@code @PostAuthorize}.
     *
     * <p>Nothing is written, so the consequence was smaller — but the query ran
     * and the rows were loaded before the refusal. The list of every account on
     * the platform is not a thing to assemble and then discard.
     */
    @Test
    void aCustomerCannotListOrSearchAccounts() throws Exception {
        Authentication aminata = customer(AMINATA);

        mvc.perform(get("/api/users?role=CUSTOMER").with(authentication(aminata)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/by-email?email=someone@example.gm")
                        .with(authentication(aminata)))
                .andExpect(status().isForbidden());

        verify(users, never()).findAll(any(), any());
        verify(users, never()).findByEmail(anyString());
    }

    // ── What a customer may still do to their own ────────────────────────────

    /**
     * The fix must not have closed the self-service half.
     *
     * <p>{@code @userSecurity.isSelf} compares the caller against {@code #id},
     * which is a method argument — so it evaluates before the call exactly as
     * it did after one, and the owner still gets through.
     */
    @Test
    void aCustomerMayStillChangeTheirOwnPreferences() throws Exception {
        mvc.perform(patch("/api/users/" + AMINATA + "/preferences")
                        .with(authentication(customer(AMINATA))).with(csrf())
                        .contentType("application/json")
                        .content("{\"preferredCurrency\":\"GMD\"}"))
                .andExpect(status().isOk());

        // The caller's own id reached the service, which is the whole of what
        // this test is about. The VALUES are matched loosely on purpose:
        // UpdatePreferencesRequest names its fields `PreferredCurrency` and
        // `PreferredLanguage`, with a capital initial, and binding here is by
        // field name — so the conventional lower-camel key a client sends does
        // not bind and both arrive null. That is a real defect on this surface
        // and a separate one from the authorisation this file pins; asserting
        // the broken shape here would only make it harder to fix.
        verify(users).updatePreferences(eq(AMINATA), any(), any());
    }

    @Test
    void aCustomerMayStillReadTheirOwnProfile() throws Exception {
        mvc.perform(get("/api/users/" + AMINATA).with(authentication(customer(AMINATA))))
                .andExpect(status().isOk());

        verify(users).getCurrentUser(AMINATA);
    }

    /** And not somebody else's, which is the other half of the same rule. */
    @Test
    void aCustomerMayNotReadAnotherProfile() throws Exception {
        mvc.perform(get("/api/users/" + SOMEBODY_ELSE).with(authentication(customer(AMINATA))))
                .andExpect(status().isForbidden());

        verify(users, never()).getCurrentUser(SOMEBODY_ELSE);
    }

    // ── And that an administrator is not locked out by any of it ─────────────

    @Test
    void anAdministratorStillReachesTheAdministrativeHalf() throws Exception {
        mvc.perform(delete("/api/users/" + SOMEBODY_ELSE + "/permanent")
                        .with(authentication(admin(900L))).with(csrf()))
                .andExpect(status().isNoContent());

        verify(users).deleteAccountPermanently(SOMEBODY_ELSE);
    }

    private static Authentication customer(Long userId) {
        return auth(userId, UserRole.CUSTOMER);
    }

    private static Authentication admin(Long userId) {
        return auth(userId, UserRole.ADMIN);
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
