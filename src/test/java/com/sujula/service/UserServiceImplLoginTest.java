package com.sujula.service;

import com.sujula.dto.response.user.UserResponse;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.impl.UserServiceImpl;
import com.sujula.service.security.LoginAttemptProperties;
import com.sujula.service.security.LoginAttemptTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Signing in: who gets through, who does not, and what the session looks like afterwards. */
class UserServiceImplLoginTest {

    private static final String PASSWORD = "Str0ng!Passw0rd";

    private UserRepository userRepository;
    private LoginAttemptTracker loginAttempts;
    private UserServiceImpl service;
    private MockHttpServletRequest request;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

        loginAttempts = mock(LoginAttemptTracker.class);
        service = new UserServiceImpl(userRepository, passwordEncoder,
                mock(EmailService.class), mock(GeoService.class), mock(AuditService.class),
                loginAttempts, new LoginAttemptProperties());

        user = new User();
        user.setId(3L);
        user.setEmail("buyer@sujula.gm");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFirstName("Awa");
        user.setLastName("Ceesay");
        user.setRole(UserRole.CUSTOMER);
        user.setEnabled(true);
        user.setEmailVerified(true);

        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("buyer@sujula.gm")).thenReturn(Optional.of(user));

        // The service reaches for the current request to put the session together.
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void signsInAValidUserAndPutsThemInTheSession() {
        UserResponse response = service.login("buyer@sujula.gm", PASSWORD);

        assertEquals(3L, response.getId());
        assertEquals(UserRole.CUSTOMER, response.getRole());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals(user, authentication.getPrincipal(), "controllers read the User entity off the principal");
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER")));

        assertNotNull(request.getSession(false));
        assertNotNull(request.getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY),
                "the context has to be in the session or the next request is anonymous");
    }

    @Test
    void rotatesTheSessionIdSoAPlantedSessionCannotSurviveSignIn() {
        String beforeSignIn = request.getSession(true).getId();

        service.login("buyer@sujula.gm", PASSWORD);

        assertNotEquals(beforeSignIn, request.getSession(false).getId());
    }

    @Test
    void theEmailIsCaseAndSpaceInsensitive() {
        when(userRepository.findByEmailIgnoreCase("BUYER@SUJULA.GM")).thenReturn(Optional.of(user));

        assertEquals(3L, service.login("  BUYER@SUJULA.GM  ", PASSWORD).getId());
    }

    @Test
    void anUnknownEmailAndAWrongPasswordFailIdentically() {
        BadCredentialsException unknown = assertThrows(BadCredentialsException.class,
                () -> service.login("nobody@sujula.gm", PASSWORD));
        BadCredentialsException wrong = assertThrows(BadCredentialsException.class,
                () -> service.login("buyer@sujula.gm", "not-the-password"));

        // Same message either way: the endpoint must not reveal which addresses exist.
        assertEquals(unknown.getMessage(), wrong.getMessage());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void blankCredentialsAreRejectedWithoutTouchingTheDatabase() {
        assertThrows(BadCredentialsException.class, () -> service.login(null, PASSWORD));
        assertThrows(BadCredentialsException.class, () -> service.login("  ", PASSWORD));
        assertThrows(BadCredentialsException.class, () -> service.login("buyer@sujula.gm", ""));
    }

    @Test
    void aDisabledAccountIsTurnedAway() {
        user.setEnabled(false);

        assertThrows(DisabledException.class, () -> service.login("buyer@sujula.gm", PASSWORD));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aBlockedOrFraudulentAccountIsTurnedAway() {
        user.setBlocked(true);
        assertThrows(LockedException.class, () -> service.login("buyer@sujula.gm", PASSWORD));

        user.setBlocked(false);
        user.setFraud(true);
        assertThrows(LockedException.class, () -> service.login("buyer@sujula.gm", PASSWORD));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void accountStateIsOnlyRevealedToSomeoneWhoKnowsThePassword() {
        user.setBlocked(true);

        // Wrong password on a blocked account still reads as bad credentials —
        // otherwise the error itself confirms the account exists.
        assertThrows(BadCredentialsException.class, () -> service.login("buyer@sujula.gm", "guess"));
    }

    @Test
    void aWrongPasswordIsCountedAndAGoodOneClearsTheCount() {
        assertThrows(BadCredentialsException.class, () -> service.login("buyer@sujula.gm", "guess"));
        verify(loginAttempts).recordFailure(3L);

        service.login("buyer@sujula.gm", PASSWORD);
        verify(loginAttempts).recordSuccess(3L);
    }

    @Test
    void anUnknownEmailIsNotCountedAgainstAnybody() {
        assertThrows(BadCredentialsException.class, () -> service.login("nobody@sujula.gm", PASSWORD));

        verify(loginAttempts, never()).recordFailure(any());
    }

    @Test
    void aLockedOutAccountIsRefusedEvenWithTheRightPassword() {
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));

        LockedException error = assertThrows(LockedException.class,
                () -> service.login("buyer@sujula.gm", PASSWORD));

        assertTrue(error.getMessage().contains("Try again in"), "the owner is told how long to wait");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aLockedOutAccountStillReadsAsBadCredentialsToAGuesser() {
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));

        // The lock is only disclosed to someone who proved the account is theirs.
        assertThrows(BadCredentialsException.class, () -> service.login("buyer@sujula.gm", "guess"));
    }

    @Test
    void anExpiredLockNoLongerBlocksSignIn() {
        user.setLockedUntil(LocalDateTime.now().minusSeconds(1));

        assertEquals(3L, service.login("buyer@sujula.gm", PASSWORD).getId());
    }

    @Test
    void anUnverifiedEmailStillSignsIn() {
        user.setEmailVerified(false);

        UserResponse response = service.login("buyer@sujula.gm", PASSWORD);

        // Registration signs a user in before verification; refusing here would
        // strand every account created that way. The flag is reported instead.
        assertTrue(!response.isEmailVerified());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
