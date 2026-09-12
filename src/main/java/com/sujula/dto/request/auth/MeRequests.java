package com.sujula.dto.request.auth;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What the {@code /me} endpoints accept. */
public final class MeRequests {

    private MeRequests() {}

    /**
     * A partial update: every field is optional and only the ones present are
     * applied, which is what PATCH means and what a profile form needs.
     *
     * <p>Absent on purpose: email, phone and role. The first two are identifiers
     * that have to be proved rather than typed — changing them goes through
     * verification — and the third is granted by approval, never by the account
     * asking for it.
     */
    public record UpdateProfile(
            @Size(min = 1, max = 100) String firstName,
            @Size(min = 1, max = 100) String lastName,
            @Size(min = 3, max = 3)
            @Pattern(regexp = "^[A-Za-z]{3}$|^$", message = "Currency must be a three-letter ISO 4217 code")
            String preferredCurrency,
            @Size(max = 10) String preferredLanguage,
            @Size(max = 500) String profileImageUrl) {}
}
