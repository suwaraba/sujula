package com.sujula.config;

import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.AuditService;
import com.sujula.service.security.AdminBootstrapProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Making sure a deployment has someone who can approve the first seller. */
class AdminBootstrapTest {

    private UserRepository userRepository;
    private AuditService auditService;
    private PasswordEncoder passwordEncoder;
    private AdminBootstrapProperties properties;
    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        passwordEncoder = new BCryptPasswordEncoder();

        properties = new AdminBootstrapProperties();
        properties.setEmail("boss@sujula.gm");
        properties.setPassword("Str0ng!Bootstrap");

        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User saved = i.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        bootstrap = new AdminBootstrap(userRepository, passwordEncoder, auditService, properties);
    }

    @Test
    void createsTheFirstAdministratorWhenThereIsNone() {
        bootstrap.ensureAdminExists();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());

        User admin = saved.getValue();
        assertEquals("boss@sujula.gm", admin.getEmail());
        assertEquals(UserRole.ADMIN, admin.getRole());
        assertTrue(admin.isEnabled());
        assertTrue(admin.isEmailVerified(),
                "an admin who must click a verification email first is the same deadlock again");
        assertTrue(passwordEncoder.matches("Str0ng!Bootstrap", admin.getPassword()));
        assertNotEquals("Str0ng!Bootstrap", admin.getPassword(), "the password must be hashed");

        verify(auditService).recordSystemAction(eq(AuditAction.ADMIN_BOOTSTRAPPED), eq("USER"),
                anyLong(), eq("boss@sujula.gm"), any(), any());
    }

    @Test
    void generatesAPasswordWhenNoneIsConfigured() {
        properties.setPassword("");

        bootstrap.ensureAdminExists();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertTrue(saved.getValue().getPassword().startsWith("$2"), "still a BCrypt hash, not the raw value");
    }

    @Test
    void leavesAnExistingAdministratorAlone() {
        User existing = admin(UserRole.ADMIN, "already-hashed");
        when(userRepository.findByEmailIgnoreCase("boss@sujula.gm")).thenReturn(Optional.of(existing));

        bootstrap.ensureAdminExists();

        verify(userRepository, never()).save(any());
        assertEquals("already-hashed", existing.getPassword());
    }

    @Test
    void neverRewritesThePasswordOfAnAccountThatAlreadyExists() {
        User existing = admin(UserRole.CUSTOMER, "the-users-own-password");
        when(userRepository.findByEmailIgnoreCase("boss@sujula.gm")).thenReturn(Optional.of(existing));
        properties.setPassword("attacker-supplied");

        bootstrap.ensureAdminExists();

        // Promoted, but not taken over: changing a config value must not hand
        // whoever can edit it the keys to a live account.
        assertEquals(UserRole.ADMIN, existing.getRole());
        assertEquals("the-users-own-password", existing.getPassword());
        verify(auditService).recordSystemAction(eq(AuditAction.ADMIN_PROMOTED), eq("USER"),
                anyLong(), eq("boss@sujula.gm"), any(), any());
    }

    @Test
    void doesNothingWhenTheBootstrapIsOffButSaysSoWhenNobodyCanAdminister() {
        properties.setEnabled(false);
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(0L);

        bootstrap.ensureAdminExists();

        verify(userRepository, never()).save(any());
        verify(userRepository).countByRole(UserRole.ADMIN);
    }

    @Test
    void doesNothingWhenNoEmailIsConfigured() {
        properties.setEmail("  ");

        bootstrap.ensureAdminExists();

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    private static User admin(UserRole role, String password) {
        User user = new User();
        user.setId(9L);
        user.setEmail("boss@sujula.gm");
        user.setFirstName("Boss");
        user.setLastName("Person");
        user.setRole(role);
        user.setPassword(password);
        return user;
    }
}
