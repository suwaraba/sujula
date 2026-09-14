package com.sujula.dto.request.admin;

import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.UserRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What an administrator sends about somebody else's account.
 *
 * <p>Every one of these that changes an account's standing carries a reason,
 * and none of them is optional. That is the difference between an
 * administrative action somebody can defend later and one they cannot — and the
 * person who will ask is the one it was done to.
 */
public final class AdminUserRequests {

    private AdminUserRequests() {}

    /**
     * Creating an actor by hand.
     *
     * <p>Used for the cases self-registration cannot serve: a driver signed up
     * at a desk, a counter operator with no email of their own, a second
     * administrator. No password is sent — one is minted and the account is made
     * to set it, because a password an administrator chose is one an
     * administrator knows.
     */
    public record CreateUser(
            @NotBlank(message = "An email address is required")
            @Email @Size(max = 200) String email,

            @NotBlank(message = "A first name is required") @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,

            @Pattern(regexp = "^\\+?[0-9 ()-]{6,30}$", message = "That is not a phone number")
            String phone,

            @NotNull(message = "Say what kind of account this is") UserRole role,

            @Pattern(regexp = "^[A-Za-z]{2}$", message = "A country is a two-letter code, like GM")
            String countryCode,

            /** Why it was created by hand rather than registered. Goes in the audit trail. */
            @NotBlank(message = "Say why this account is being created by hand")
            @Size(max = 500) String reason) {}

    /** Editing a profile on somebody's behalf, usually to fix a typo they reported. */
    public record PatchUser(
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Pattern(regexp = "^\\+?[0-9 ()-]{6,30}$", message = "That is not a phone number")
            String phone,
            @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode,
            @Size(max = 3) String preferredCurrency,
            @Size(max = 10) String preferredLanguage,

            @NotBlank(message = "Say why you are editing somebody else's profile")
            @Size(max = 500) String reason) {}

    /** Putting somebody back, which lifts whatever was holding them out. */
    public record Activate(
            @NotBlank(message = "Say why the lock is being lifted")
            @Size(max = 500) String reason) {}

    /** Shutting an account indefinitely. */
    public record Deactivate(
            @NotBlank(message = "A reason is required — the person will be told it")
            @Size(max = 500) String reason,

            ModerationReason category) {}

    /**
     * Shutting an account for a while.
     *
     * <p>A duration rather than an end date. An administrator thinks in "a
     * week", and a date computed here is one that cannot be off by a timezone
     * somebody forgot to convert.
     */
    public record Suspend(
            @NotNull(message = "How many days")
            @Min(value = 1, message = "At least a day")
            @Max(value = 365, message = "More than a year is a ban, not a suspension")
            Integer days,

            @NotBlank(message = "A reason is required — the person will be told it")
            @Size(max = 500) String reason,

            ModerationReason category) {}

    /**
     * Changing what kind of actor somebody is.
     *
     * <p>One role at a time, because this platform gives an account one. The
     * {@code scope} is what the change is limited to where that matters — a
     * country, for an administrator who should only see one market.
     */
    public record ChangeRole(
            @NotNull(message = "Which role") UserRole role,

            @NotBlank(message = "Say why — a role change is the one nobody remembers granting")
            @Size(max = 500) String reason,

            @Pattern(regexp = "^[A-Za-z]{2}$", message = "A scope is a two-letter country code")
            String scopeCountry) {}

    /** Ending every session somebody has open. */
    public record ForceLogout(
            @NotBlank(message = "Say why") @Size(max = 500) String reason) {}

    /**
     * Clearing somebody's second factor so they can set it up again.
     *
     * <p>Step-up: the administrator confirms their own password and code before
     * taking away somebody else's. This is the endpoint an attacker who has
     * reached an admin account wants most, because it turns account takeover
     * into account takeover of anybody.
     */
    public record ResetMfa(
            @NotBlank(message = "Confirm your own password") String password,

            @Pattern(regexp = "^[0-9]{6}$", message = "Your authenticator code is six digits")
            String totpCode,

            @NotBlank(message = "Say why — this removes somebody's protection")
            @Size(max = 500) String reason) {}

    /**
     * Opening a session as somebody else.
     *
     * <p>The reason is the whole control. Impersonation reads a person's
     * messages, their addresses and their orders, and nothing about the request
     * distinguishes support work from snooping except this field and the audit
     * row it lands in.
     */
    public record Impersonate(
            @NotBlank(message = "Say why you need to see their account as they see it")
            @Size(max = 500) String reason,

            /** What it is about, so the row can be found from the case later. */
            @Size(max = 40) String reference,

            @Min(value = 5, message = "At least five minutes")
            @Max(value = 60, message = "An hour is the most — ask again if you need longer")
            Integer minutes) {}
}
