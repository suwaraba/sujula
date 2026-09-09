package com.sujula.service.security;

import com.sujula.model.constant.UserRole;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * How many failed sign-ins an account tolerates, per role.
 *
 * <p>The budget is set by what an account can do, not by who owns it. An admin
 * can move money, approve vendors and read every customer's details, so five
 * wrong passwords is already generous; a vendor, driver or pickup operator can
 * reach one shop's data, so fifteen. Shoppers are deliberately absent: locking
 * a customer out is itself an attack — anyone who knows an email address could
 * shut them out of their own basket — and for an account that holds no
 * privileges the cure is worse than the disease.
 *
 * <p>A role with no entry here, or an entry of zero or less, is never locked
 * out. Note that this leaves customer accounts with no brute-force protection
 * at all until request throttling is added in front of the endpoint.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.security.login")
public class LoginAttemptProperties {

    /** Consecutive failures a role may accumulate before the account locks. */
    private Map<UserRole, Integer> maxAttempts = defaultMaxAttempts();

    /**
     * How long a lockout lasts. It expires on its own so that support is not in
     * the loop for every mistyped password — and so that an attacker who learns
     * an admin's address cannot keep them out indefinitely.
     */
    private Duration lockoutDuration = Duration.ofMinutes(15);

    /**
     * Failures this role may accumulate, or empty when the role is never
     * locked out.
     */
    public java.util.Optional<Integer> maxAttemptsFor(UserRole role) {
        if (role == null) {
            return java.util.Optional.empty();
        }
        Integer limit = maxAttempts.get(role);
        return (limit != null && limit > 0) ? java.util.Optional.of(limit) : java.util.Optional.empty();
    }

    private static Map<UserRole, Integer> defaultMaxAttempts() {
        Map<UserRole, Integer> limits = new EnumMap<>(UserRole.class);
        limits.put(UserRole.ADMIN, 5);
        limits.put(UserRole.VENDOR, 15);
        limits.put(UserRole.DELIVERY, 15);
        limits.put(UserRole.PICKUP_OPERATOR, 15);
        // UserRole.CUSTOMER intentionally absent — shoppers are not locked out.
        return limits;
    }
}
