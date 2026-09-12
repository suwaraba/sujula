package com.sujula.service.auth;

import com.sujula.dto.request.auth.MeRequests;
import com.sujula.dto.response.auth.AuthResponses;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.auth.AccountDataRequest;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.DataRequestStatus;
import com.sujula.model.constant.DataRequestType;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.auth.AccountDataRequestRepository;
import com.sujula.repository.auth.OAuthAccountRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.auth.impl.AccountServiceImpl;
import com.sujula.service.auth.impl.AuthMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership of account data, which is the property this layer is built around.
 *
 * <p>The tests that matter here are the negative ones. A caller must not be able
 * to reach another account's session, and the way that is guaranteed is that the
 * lookup carries the owner in the query rather than checking it afterwards — so
 * a foreign id is not found rather than found and refused, and a probe learns
 * nothing about whether the id was real.
 */
class AccountOwnershipTest {

    private static final Long OWNER = 11L;
    private static final Long INTRUDER = 66L;

    private UserRepository users;
    private UserSessionRepository sessions;
    private AccountDataRequestRepository dataRequests;
    private AccountServiceImpl accountService;
    private User owner;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        sessions = mock(UserSessionRepository.class);
        dataRequests = mock(AccountDataRequestRepository.class);
        OAuthAccountRepository oauthAccounts = mock(OAuthAccountRepository.class);
        VendorRepository vendors = mock(VendorRepository.class);

        owner = new User();
        owner.setId(OWNER);
        owner.setEmail("aminata@example.gm");
        owner.setFirstName("Aminata");
        owner.setLastName("Jallow");
        owner.setRole(UserRole.CUSTOMER);
        owner.setEnabled(true);

        when(users.findById(OWNER)).thenReturn(Optional.of(owner));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(oauthAccounts.findByUserId(anyLong())).thenReturn(List.of());

        accountService = new AccountServiceImpl(users, sessions, oauthAccounts, dataRequests,
                vendors, new PermissionResolver(vendors), new AuthMapper());
    }

    private UserSession session(Long id, Long userId) {
        User holder = new User();
        holder.setId(userId);
        UserSession session = new UserSession();
        session.setId(id);
        session.setUser(holder);
        session.setDeviceLabel("Chrome on Android");
        session.setCreatedAt(LocalDateTime.now().minusDays(2));
        session.setLastSeenAt(LocalDateTime.now().minusHours(3));
        session.setExpiresAt(LocalDateTime.now().plusDays(20));
        return session;
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    @Test
    void revokingOwnSessionEndsIt() {
        UserSession mine = session(5L, OWNER);
        when(sessions.findByIdAndUserId(5L, OWNER)).thenReturn(Optional.of(mine));

        accountService.revokeSession(OWNER, 5L);

        assertFalse(mine.isActive());
        assertEquals(SessionRevocationReason.LOGOUT, mine.getRevokedReason());
        verify(sessions).save(mine);
    }

    /**
     * The test this class exists for. The repository is asked for the id
     * <em>and</em> the owner together, so somebody else's session is simply not
     * there — there is no loaded entity for a missing ownership check to let
     * through.
     */
    @Test
    void anotherAccountsSessionIsNotFound() {
        when(sessions.findByIdAndUserId(5L, INTRUDER)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> accountService.revokeSession(INTRUDER, 5L));

        verify(sessions).findByIdAndUserId(5L, INTRUDER);
        verify(sessions, never()).findById(anyLong());
        verify(sessions, never()).save(any(UserSession.class));
    }

    /** Signing out a device that has already ended is not an error. */
    @Test
    void revokingAnEndedSessionIsIdempotent() {
        UserSession ended = session(5L, OWNER);
        ended.revoke(SessionRevocationReason.LOGOUT_ALL);
        when(sessions.findByIdAndUserId(5L, OWNER)).thenReturn(Optional.of(ended));

        accountService.revokeSession(OWNER, 5L);

        assertEquals(SessionRevocationReason.LOGOUT_ALL, ended.getRevokedReason(),
                "the original reason must survive — it is why the session ended");
        verify(sessions, never()).save(any(UserSession.class));
    }

    @Test
    void theCallersOwnDeviceIsMarkedCurrent() {
        when(sessions.findByUserIdOrderByLastSeenAtDesc(OWNER))
                .thenReturn(List.of(session(5L, OWNER), session(6L, OWNER)));

        List<AuthResponses.Session> listed = accountService.listSessions(OWNER, 5L);

        assertEquals(2, listed.size());
        assertTrue(listed.get(0).current());
        assertFalse(listed.get(1).current());
    }

    @Test
    void aCookieSessionMarksNoDeviceCurrent() {
        when(sessions.findByUserIdOrderByLastSeenAtDesc(OWNER))
                .thenReturn(List.of(session(5L, OWNER)));

        assertFalse(accountService.listSessions(OWNER, null).get(0).current());
    }

    // ── Data rights ──────────────────────────────────────────────────────────

    /**
     * Retrying a timed-out request must not queue a second erasure. Two erasure
     * jobs racing over one account is not a situation worth having.
     */
    @Test
    void askingTwiceReturnsTheOpenRequest() {
        AccountDataRequest open = AccountDataRequest.builder()
                .user(owner)
                .type(DataRequestType.ERASURE)
                .status(DataRequestStatus.PENDING)
                .reference("ERA-EXISTING")
                .build();
        when(dataRequests.findOpen(eqOwner(), eqErasure(), any())).thenReturn(Optional.of(open));

        AuthResponses.DataRequest result = accountService.requestErasure(OWNER);

        assertEquals("ERA-EXISTING", result.reference());
        verify(dataRequests, never()).save(any(AccountDataRequest.class));
    }

    @Test
    void aFirstRequestIsQueued() {
        when(dataRequests.findOpen(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(dataRequests.save(any(AccountDataRequest.class)))
                .thenAnswer(call -> call.getArgument(0));

        AuthResponses.DataRequest result = accountService.requestExport(OWNER);

        assertEquals(DataRequestType.EXPORT, result.type());
        assertEquals(DataRequestStatus.PENDING, result.status());
        assertTrue(result.reference().startsWith("EXP-"));
        assertNull(result.downloadUrl(), "nothing is downloadable until the job has run");
    }

    @Test
    void anExportNeverAskedForIsNotFound() {
        when(dataRequests.findLatest(OWNER, DataRequestType.EXPORT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> accountService.latestExport(OWNER));
    }

    // ── Profile ──────────────────────────────────────────────────────────────

    /** A PATCH that blanked every omitted field would wipe a profile on a partial form. */
    @Test
    void anOmittedFieldIsLeftAlone() {
        owner.setPreferredCurrency("GMD");
        owner.setProfileImageUrl("https://cdn.example/a.png");

        AuthResponses.Profile updated = accountService.updateMe(
                OWNER, new MeRequests.UpdateProfile("Awa", null, null, null, null));

        assertEquals("Awa", updated.firstName());
        assertEquals("Jallow", owner.getLastName());
        assertEquals("GMD", owner.getPreferredCurrency());
        assertEquals("https://cdn.example/a.png", owner.getProfileImageUrl());
    }

    /** Clearing a picture is a thing people do, and an empty string is how a form says so. */
    @Test
    void anEmptyImageUrlClearsThePicture() {
        owner.setProfileImageUrl("https://cdn.example/a.png");

        accountService.updateMe(OWNER, new MeRequests.UpdateProfile(null, null, null, null, ""));

        assertNull(owner.getProfileImageUrl());
    }

    @Test
    void theProfileResponseCarriesNoCredentials() {
        owner.setPassword("$2a$10$notarealhash");
        owner.setTotpSecret("JBSWY3DPEHPK3PXP");

        AuthResponses.Profile profile = accountService.updateMe(
                OWNER, new MeRequests.UpdateProfile(null, null, null, null, null));

        String rendered = profile.toString();
        assertFalse(rendered.contains("notarealhash"));
        assertFalse(rendered.contains("JBSWY3DPEHPK3PXP"));
    }

    /**
     * A Gambian account with no stated preference is quoted in dalasi; a buyer
     * seen from London is not, merely because the platform's home currency is.
     */
    @Test
    void currencyFallsBackToWhereTheAccountIsSeen() {
        when(sessions.countByUserIdAndRevokedAtIsNull(OWNER)).thenReturn(2L);

        owner.setDetectedCountryCode("GB");
        assertEquals("GBP", accountService.getMe(OWNER).resolvedCurrency());

        owner.setDetectedCountryCode("SN");
        assertEquals("XOF", accountService.getMe(OWNER).resolvedCurrency());

        owner.setDetectedCountryCode(null);
        assertEquals("GMD", accountService.getMe(OWNER).resolvedCurrency());

        owner.setPreferredCurrency("usd");
        assertEquals("USD", accountService.getMe(OWNER).resolvedCurrency(),
                "a stated preference wins over anything inferred");
    }

    @Test
    void meReportsTheActiveDeviceCount() {
        when(sessions.countByUserIdAndRevokedAtIsNull(OWNER)).thenReturn(4L);

        assertEquals(4, accountService.getMe(OWNER).activeSessions());
    }

    private static Long eqOwner() {
        return org.mockito.ArgumentMatchers.eq(OWNER);
    }

    private static DataRequestType eqErasure() {
        return org.mockito.ArgumentMatchers.eq(DataRequestType.ERASURE);
    }
}
