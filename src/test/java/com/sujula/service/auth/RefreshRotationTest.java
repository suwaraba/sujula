package com.sujula.service.auth;

import com.sujula.dto.request.auth.AuthRequests;
import com.sujula.dto.response.auth.AuthResponses;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.auth.MfaRecoveryCodeRepository;
import com.sujula.repository.auth.PhoneVerificationRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.EmailService;
import com.sujula.service.auth.impl.AuthMapper;
import com.sujula.service.auth.impl.AuthServiceImpl;
import com.sujula.service.auth.impl.OAuthExchange;
import com.sujula.service.auth.impl.SessionFactory;
import com.sujula.service.auth.impl.SessionReplayGuard;
import com.sujula.service.security.LoginAttemptTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refresh-token rotation, and what happens when a spent token comes back.
 *
 * <p>Rotation is single use. The legitimate client always holds the newest
 * token, so a request carrying one that has already been rotated away did not
 * come from it — the token was copied. The only safe response is to end the
 * session: the real user signs in again, and the copy is worth nothing.
 */
class RefreshRotationTest {

    private UserSessionRepository sessions;
    private SessionReplayGuard replayGuard;
    private TokenService tokenService;
    private AuthServiceImpl authService;
    private User owner;

    @BeforeEach
    void setUp() {
        Environment environment = new MockEnvironment();
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("a-test-signing-key-of-at-least-32-bytes");
        tokenService = new TokenService(properties, environment);
        // @PostConstruct does not run on an instance built by hand.
        tokenService.resolveSigningKey();

        sessions = mock(UserSessionRepository.class);
        replayGuard = mock(SessionReplayGuard.class);

        owner = new User();
        owner.setId(11L);
        owner.setEmail("aminata@example.gm");
        owner.setFirstName("Aminata");
        owner.setLastName("Jallow");
        owner.setRole(UserRole.CUSTOMER);
        owner.setEnabled(true);
        owner.setBlocked(false);

        SessionFactory sessionFactory = new SessionFactory(sessions, tokenService, properties);
        when(sessions.save(any(UserSession.class))).thenAnswer(call -> {
            UserSession session = call.getArgument(0);
            if (session.getId() == null) {
                session.setId(99L);
            }
            return session;
        });

        authService = new AuthServiceImpl(
                mock(UserRepository.class), sessions, mock(MfaRecoveryCodeRepository.class),
                mock(PhoneVerificationRepository.class), mock(PasswordEncoder.class), tokenService,
                new TotpService(), sessionFactory, new AuthMapper(), properties,
                mock(LoginAttemptTracker.class), mock(EmailService.class), mock(OAuthExchange.class),
                replayGuard, environment,
                mock(com.sujula.service.notification.SmsSender.class));
    }

    private UserSession liveSession(String currentToken, String previousToken) {
        UserSession session = new UserSession();
        session.setId(99L);
        session.setUser(owner);
        session.setRefreshTokenHash(tokenService.sha256(currentToken));
        if (previousToken != null) {
            session.setPreviousTokenHash(tokenService.sha256(previousToken));
        }
        session.setCreatedAt(LocalDateTime.now().minusDays(1));
        session.setLastSeenAt(LocalDateTime.now().minusHours(1));
        session.setExpiresAt(LocalDateTime.now().plusDays(20));
        return session;
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    @Test
    void refreshIssuesADifferentTokenAndRemembersTheOldOne() {
        UserSession session = liveSession("token-one", null);
        when(sessions.findByRefreshTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.of(session));

        AuthResponses.Tokens tokens = authService.refresh(new AuthRequests.Refresh("token-one"));

        assertNotNull(tokens.refreshToken());
        assertNotEquals("token-one", tokens.refreshToken(),
                "a refresh that hands back the same token is not rotation");
        assertEquals(tokenService.sha256(tokens.refreshToken()), session.getRefreshTokenHash());
        assertEquals(tokenService.sha256("token-one"), session.getPreviousTokenHash(),
                "the spent token has to stay recognisable, or a replay reads as merely unknown");
        assertEquals(99L, tokens.sessionId());
    }

    @Test
    void theNewAccessTokenNamesTheSameSession() {
        when(sessions.findByRefreshTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.of(liveSession("token-one", null)));

        AuthResponses.Tokens tokens = authService.refresh(new AuthRequests.Refresh("token-one"));

        TokenService.AccessTokenClaims claims =
                tokenService.verifyAccessToken(tokens.accessToken()).orElseThrow();
        assertEquals(99L, claims.sessionId());
        assertEquals(11L, claims.userId());
    }

    /** Only the newest token works. The one before it is spent by definition. */
    @Test
    void theSupersededTokenNoLongerRefreshes() {
        when(sessions.findByRefreshTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.empty());
        when(sessions.findByPreviousTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.of(liveSession("token-two", "token-one")));

        assertThrows(BadCredentialsException.class,
                () -> authService.refresh(new AuthRequests.Refresh("token-one")));
    }

    // ── Replay ───────────────────────────────────────────────────────────────

    @Test
    void replayingASpentTokenEndsTheSession() {
        UserSession session = liveSession("token-two", "token-one");
        when(sessions.findByRefreshTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.empty());
        when(sessions.findByPreviousTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.of(session));

        assertThrows(BadCredentialsException.class,
                () -> authService.refresh(new AuthRequests.Refresh("token-one")));

        verify(replayGuard).revokeOnReplay(session);
    }

    /**
     * The revocation has to happen in a transaction of its own. Were it inlined
     * into the service, the exception that carries the 401 back would roll it
     * back and leave the stolen token's session live — which is why the guard is
     * a separate bean and why this asserts on the collaborator rather than on the
     * session's state.
     */
    @Test
    void theGuardRevokesWithTheReplayReasonRecorded() {
        UserSession session = liveSession("token-two", "token-one");
        UserSessionRepository store = mock(UserSessionRepository.class);
        when(store.findById(99L)).thenReturn(Optional.of(session));

        new SessionReplayGuard(store).revokeOnReplay(session);

        assertEquals(SessionRevocationReason.TOKEN_REPLAY, session.getRevokedReason());
        assertTrue(!session.isActive());
    }

    @Test
    void anAlreadyRevokedSessionIsNotRevokedTwice() {
        UserSession session = liveSession("token-two", "token-one");
        session.revoke(SessionRevocationReason.LOGOUT);
        UserSessionRepository store = mock(UserSessionRepository.class);

        new SessionReplayGuard(store).revokeOnReplay(session);

        assertEquals(SessionRevocationReason.LOGOUT, session.getRevokedReason(),
                "a replay after a deliberate sign-out must not rewrite why the session ended");
        verify(store, never()).save(any(UserSession.class));
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    @Test
    void anUnknownTokenIsRefusedWithoutTouchingTheGuard() {
        when(sessions.findByRefreshTokenHash(any())).thenReturn(Optional.empty());
        when(sessions.findByPreviousTokenHash(any())).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class,
                () -> authService.refresh(new AuthRequests.Refresh("never-issued")));

        verify(replayGuard, never()).revokeOnReplay(any());
    }

    @Test
    void anEndedSessionCannotBeRefreshed() {
        UserSession session = liveSession("token-one", null);
        session.revoke(SessionRevocationReason.LOGOUT);
        when(sessions.findByRefreshTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.of(session));

        assertThrows(BadCredentialsException.class,
                () -> authService.refresh(new AuthRequests.Refresh("token-one")));
    }

    @Test
    void anExpiredSessionCannotBeRefreshed() {
        UserSession session = liveSession("token-one", null);
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(sessions.findByRefreshTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.of(session));

        assertThrows(BadCredentialsException.class,
                () -> authService.refresh(new AuthRequests.Refresh("token-one")));
    }

    /** A session outliving the account it belongs to must not keep minting tokens. */
    @Test
    void aDisabledAccountCannotRefresh() {
        owner.setEnabled(false);
        when(sessions.findByRefreshTokenHash(tokenService.sha256("token-one")))
                .thenReturn(Optional.of(liveSession("token-one", null)));

        assertThrows(DisabledException.class,
                () -> authService.refresh(new AuthRequests.Refresh("token-one")));
    }

    /** Tokens are stored hashed; the value itself is never written down. */
    @Test
    void theStoredHashIsNotTheToken() {
        TokenService.RefreshToken issued = tokenService.generateRefreshToken();

        assertNotEquals(issued.value(), issued.hash());
        assertEquals(issued.hash(), tokenService.sha256(issued.value()));
    }
}
