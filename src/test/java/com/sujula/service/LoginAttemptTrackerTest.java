package com.sujula.service;

import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.security.LoginAttemptProperties;
import com.sujula.service.security.LoginAttemptTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** The escalation ladder: when the owner is warned, when they are sent a way back in, when the door shuts. */
class LoginAttemptTrackerTest {

    private LoginAttemptProperties properties;
    private EmailService emailService;
    private LoginAttemptTracker tracker;
    private User user;

    @BeforeEach
    void setUp() {
        UserRepository userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        properties = new LoginAttemptProperties();

        user = new User();
        user.setId(1L);
        user.setEmail("awa@sujula.gm");
        user.setFirstName("Awa");
        user.setLastName("Ceesay");
        user.setRole(UserRole.CUSTOMER);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        tracker = new LoginAttemptTracker(userRepository, emailService, properties);
    }

    // ── Admin: 3 → reset link, 4 → locked ────────────────────────────────────

    @Test
    void anAdminIsSentAResetLinkOnTheThirdFailureAndLockedOnTheFourth() {
        user.setRole(UserRole.ADMIN);

        assertNull(tracker.recordFailure(1L));
        assertNull(tracker.recordFailure(1L));
        verify(emailService, never()).sendPasswordResetEmail(any(), any(), any());

        assertNull(tracker.recordFailure(1L), "the third failure warns, it does not lock");
        verify(emailService).sendPasswordResetEmail(eq("awa@sujula.gm"), eq("Awa Ceesay"), any());
        assertNotNull(user.getPasswordResetToken(), "the link has to be backed by a real token");
        assertTrue(user.getPasswordResetTokenExpiry().isAfter(LocalDateTime.now()));

        assertNotNull(tracker.recordFailure(1L), "the fourth locks the account");
        assertTrue(user.isLockedOut());
        assertEquals(4, user.getFailedLoginAttempts());
    }

    @Test
    void anAdminGetsNoWarningStep() {
        user.setRole(UserRole.ADMIN);

        for (int attempt = 0; attempt < 4; attempt++) {
            tracker.recordFailure(1L);
        }
        verify(emailService, never()).sendFailedSignInWarningEmail(any(), any(), anyInt(), anyInt());
    }

    // ── Everyone else: 3 → notice, 5 → reset link, 7 → locked ────────────────

    @Test
    void everyoneElseIsWarnedAtThreeSentALinkAtFiveAndLockedAtSeven() {
        tracker.recordFailure(1L);
        tracker.recordFailure(1L);
        verify(emailService, never()).sendFailedSignInWarningEmail(any(), any(), anyInt(), anyInt());

        assertNull(tracker.recordFailure(1L));
        // Warned, and told how much room is left: 7 - 3.
        verify(emailService).sendFailedSignInWarningEmail("awa@sujula.gm", "Awa Ceesay", 3, 4);
        assertNull(user.getPasswordResetToken(), "a warning must not hand out a reset token");

        assertNull(tracker.recordFailure(1L));
        assertNull(tracker.recordFailure(1L));
        verify(emailService).sendPasswordResetEmail(eq("awa@sujula.gm"), eq("Awa Ceesay"), any());
        assertNotNull(user.getPasswordResetToken());

        assertNull(tracker.recordFailure(1L));
        assertNotNull(tracker.recordFailure(1L), "the seventh failure locks the account");
        assertTrue(user.isLockedOut());
        assertEquals(7, user.getFailedLoginAttempts());
    }

    @Test
    void theLadderIsTheSameForEveryNonAdminRole() {
        for (UserRole role : new UserRole[]{UserRole.CUSTOMER, UserRole.VENDOR,
                UserRole.DELIVERY, UserRole.PICKUP_OPERATOR}) {
            user.setRole(role);
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);

            for (int attempt = 1; attempt < 7; attempt++) {
                assertNull(tracker.recordFailure(1L), role + " locked early, on attempt " + attempt);
            }
            assertNotNull(tracker.recordFailure(1L), role + " should lock on the seventh failure");
        }
    }

    // ── The mail cannon problem ──────────────────────────────────────────────

    @Test
    void eachStepMailsOnceNoMatterHowLongTheAttackRunsFor() {
        user.setRole(UserRole.ADMIN);
        for (int attempt = 0; attempt < 30; attempt++) {
            tracker.recordFailure(1L);
        }

        // Otherwise a login form becomes a way to bombard someone's inbox.
        verify(emailService, times(1)).sendPasswordResetEmail(any(), any(), any());
        verifyNoMoreInteractions(emailService);
    }

    @Test
    void aLockedAccountIsNotPushedFurtherOutByMoreGuessing() {
        user.setRole(UserRole.ADMIN);
        for (int attempt = 0; attempt < 4; attempt++) {
            tracker.recordFailure(1L);
        }
        LocalDateTime lockedUntil = user.getLockedUntil();

        for (int attempt = 0; attempt < 20; attempt++) {
            assertEquals(lockedUntil, tracker.recordFailure(1L));
        }
        assertEquals(lockedUntil, user.getLockedUntil(),
                "otherwise anyone knowing the address could keep the owner out for good");
        assertEquals(4, user.getFailedLoginAttempts());
    }

    // ── Resets ───────────────────────────────────────────────────────────────

    @Test
    void theCountStartsOverOnceALockHasExpired() {
        user.setRole(UserRole.ADMIN);
        user.setFailedLoginAttempts(4);
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));

        assertNull(tracker.recordFailure(1L), "an expired lock gives a fresh budget");
        assertEquals(1, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void aSuccessfulSignInClearsEverything() {
        user.setFailedLoginAttempts(6);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));

        tracker.recordSuccess(1L);

        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void anAdminUnlockLiftsItImmediately() {
        user.setFailedLoginAttempts(7);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(15));

        tracker.clear(user);

        assertFalse(user.isLockedOut());
        assertEquals(0, user.getFailedLoginAttempts());
    }

    @Test
    void everyStepAndTheDurationAreConfigurable() {
        properties.getRoles().put(UserRole.CUSTOMER,
                LoginAttemptProperties.LoginPolicy.of(0, 0, 2));
        properties.setLockoutDuration(Duration.ofHours(2));

        assertNull(tracker.recordFailure(1L));
        LocalDateTime lockedUntil = tracker.recordFailure(1L);

        assertNotNull(lockedUntil);
        assertTrue(lockedUntil.isAfter(LocalDateTime.now().plusMinutes(110)));
        // Steps switched off send nothing.
        verifyNoMoreInteractions(emailService);
    }
}
