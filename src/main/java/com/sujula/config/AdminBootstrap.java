package com.sujula.config;

import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.AuditService;
import com.sujula.service.security.AdminBootstrapProperties;
import com.sujula.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes sure an administrator exists.
 *
 * <p>Nothing else can create one: registration hardcodes CUSTOMER, and the only
 * other role change in the system — a vendor being approved — needs an admin to
 * perform it. Without this, a fresh deployment has no way to approve its first
 * seller, and no way to appoint the admin who would.
 *
 * <p>Runs on every start and is safe to. It creates the account when it is
 * missing, raises it to ADMIN when it exists as something else, and otherwise
 * does nothing. It never rewrites the password of an account that already
 * exists: an operator changing a config value must not be able to take over a
 * live administrator, and a forgotten password is what the reset flow is for.
 */
@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AdminBootstrapProperties properties;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          AuditService auditService, AdminBootstrapProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureAdminExists() {
        if (!properties.isConfigured()) {
            warnIfNoAdminAtAll();
            return;
        }

        String email = properties.getEmail().trim().toLowerCase();
        userRepository.findByEmailIgnoreCase(email)
                .ifPresentOrElse(this::promoteIfNeeded, () -> create(email));
    }

    private void create(String email) {
        boolean generated = properties.getPassword().isBlank();
        String password = generated ? Utils.generateSecureToken().substring(0, 16) : properties.getPassword();

        User admin = new User();
        admin.setEmail(email);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setFirstName(properties.getFirstName());
        admin.setLastName(properties.getLastName());
        admin.setRole(UserRole.ADMIN);
        admin.setEnabled(true);
        // Verified on creation: there is nobody to approve the approver, and an
        // admin who cannot sign in until they click an email is the same
        // deadlock in a different shape.
        admin.setEmailVerified(true);
        admin.setPreferredCurrency("GMD");
        admin.setPreferredLanguage("en");
        User saved = userRepository.save(admin);

        auditService.recordSystemAction(AuditAction.ADMIN_BOOTSTRAPPED, "USER", saved.getId(), email,
                "First administrator created from configuration at startup",
                generated ? "Password generated at startup" : "Password supplied by configuration");

        if (generated) {
            log.warn("""

                    ================================================================
                     A first administrator has been created: {}
                     Temporary password: {}
                     This was printed because sujula.security.bootstrap-admin.password
                     is not set. Sign in and change it now — anyone who can read these
                     logs can read this password.
                    ================================================================""", email, password);
        } else {
            log.info("[Bootstrap] Administrator {} created from configuration", email);
        }
    }

    private void promoteIfNeeded(User existing) {
        if (existing.getRole() == UserRole.ADMIN) {
            log.debug("[Bootstrap] Administrator {} already exists", existing.getEmail());
            return;
        }
        UserRole previous = existing.getRole();
        existing.setRole(UserRole.ADMIN);
        existing.setEnabled(true);
        userRepository.save(existing);

        auditService.recordSystemAction(AuditAction.ADMIN_PROMOTED, "USER", existing.getId(),
                existing.getEmail(), "Raised to administrator by the startup bootstrap",
                "Previous role: " + previous);

        log.warn("[Bootstrap] {} was {} and has been raised to ADMIN by configuration",
                existing.getEmail(), previous);
    }

    /**
     * Says so, loudly, when a deployment has no administrator and no way to get
     * one — the failure is otherwise invisible until someone tries to approve a
     * vendor weeks later.
     */
    private void warnIfNoAdminAtAll() {
        if (userRepository.countByRole(UserRole.ADMIN) == 0) {
            log.warn("[Bootstrap] There is no administrator on this platform and the bootstrap is off. "
                    + "Vendors cannot be approved until one exists. Set "
                    + "sujula.security.bootstrap-admin.email (and .password) and restart.");
        }
    }
}
