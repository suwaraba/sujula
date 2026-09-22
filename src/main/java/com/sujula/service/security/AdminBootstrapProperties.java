package com.sujula.service.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The first administrator, created at startup.
 *
 * <p>Without one the platform is deadlocked: registration only ever creates
 * customers, and a vendor cannot be approved without an admin — so nobody can
 * sell and nobody can be made an admin either.
 *
 * <p>Supply the password through the environment. Leaving it blank has the
 * bootstrap generate one and print it once at startup, which is fine for a
 * first local run and not fine for a server whose logs are shipped somewhere.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.security.bootstrap-admin")
public class AdminBootstrapProperties {

    /** Set false once a real admin exists and is no longer managed from config. */
    private boolean enabled = true;

    private String email = "";

    /** Blank means "generate one and log it". */
    private String password = "";

    private String firstName = "Platform";

    private String lastName = "Administrator";

    public boolean isConfigured() {
        return enabled && !email.isBlank();
    }
}
