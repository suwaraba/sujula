package com.sujula.dto.response.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.SanctionType;
import com.sujula.model.constant.UserRole;

/**
 * What an administrator is shown about somebody else's account.
 *
 * <p>More than the person sees of themselves, and deliberately less than
 * everything. There is no password hash here, no TOTP secret, no reset token and
 * no session token — an administrative screen that displayed a credential would
 * be a credential sitting on a screen in an office, and the whole point of the
 * impersonation endpoint is that reading somebody's account should require an
 * audit row rather than a glance.
 */
public final class AdminUserResponses {

    private AdminUserResponses() {}

    /** A row in the search results. */
    public record UserRow(
            Long id, String email, String name, String phone,
            UserRole role, String countryCode,
            boolean enabled, boolean blocked, boolean emailVerified, boolean mfaEnabled,
            /** What is holding them out, if anything. Null when nothing is. */
            String lockedBy,
            LocalDateTime lockedUntil,
            LocalDateTime createdAt, LocalDateTime lastSeenAt) {}

    /**
     * One account in full, with what it has actually done.
     *
     * <p>The activity summary is the half that matters. An agent deciding
     * whether somebody is a fraud or a first-time buyer with a bad connection
     * needs the shape of their history, and fetching it from six endpoints is
     * how they end up deciding on one of them.
     */
    public record UserDetail(
            UserRow account,
            Activity activity,
            List<SanctionRow> sanctions,
            List<SessionRow> sessions,
            List<CaseRow> openCases,
            /** The store, where this account has one. */
            VendorSummary vendor,
            String note) {}

    /**
     * What this account has done, in numbers.
     *
     * <p>Money is per currency and never summed. A buyer who has spent 400 EUR
     * and 12,000 GMD has not spent 12,400 of anything, and a total that added
     * them would be the one figure on this page nobody could act on.
     */
    public record Activity(
            int orders, int ordersCancelled, int returns, int disputes,
            List<Spend> spendByCurrency,
            int reviewsWritten, int reviewsReported,
            int productsListed,
            int deliveriesCompleted,
            LocalDateTime firstOrderAt, LocalDateTime lastOrderAt) {}

    public record Spend(String currency, BigDecimal total, int orders) {}

    public record SanctionRow(
            Long id, SanctionType type, String reason, String reasonText,
            String restrictedPermission,
            LocalDateTime issuedAt, String issuedBy,
            LocalDateTime expiresAt,
            LocalDateTime liftedAt, String liftedBy, String liftedReason,
            boolean active) {}

    /**
     * A device this account is signed in on.
     *
     * <p>No token and no hash of one. What is here is what an agent needs to
     * tell somebody "the session from Banjul yesterday" — and {@code
     * impersonatedBy} so an administrator's own sessions on the account are
     * visible as what they are rather than as devices nobody recognises.
     */
    public record SessionRow(
            Long id, String deviceLabel, String ipAddress, String countryCode,
            LocalDateTime createdAt, LocalDateTime lastSeenAt, LocalDateTime expiresAt,
            LocalDateTime revokedAt, String revokedReason,
            String impersonatedBy) {}

    public record CaseRow(Long id, String reference, String reason, String status,
                          String subjectLabel, LocalDateTime dueBy, boolean overdue) {}

    /**
     * The store this account runs, where it runs one.
     *
     * <p>{@code lastChangedAt} rather than an approval date: a vendor row has no
     * separate approved-at column, and inventing one from the last update would
     * be a date that reads like a fact and is not. The approval itself is in the
     * audit log, which is where somebody asking "when was this store approved"
     * should be sent.
     */
    public record VendorSummary(Long id, String storeName, String status,
                                String settlementCurrency, LocalDateTime lastChangedAt) {}

    // ── Acknowledgements ─────────────────────────────────────────────────────

    public record UserCreated(Long id, String email, UserRole role,
                              /** Whether the account was told to set its own password. */
                              boolean setupEmailSent,
                              String message) {}

    public record AccountChanged(Long id, boolean enabled, SanctionRow sanction,
                                 int sessionsEnded, String message) {}

    public record RoleChanged(Long id, UserRole from, UserRole to, int sessionsEnded,
                              String message) {}

    public record SessionsEnded(Long id, int ended, String message) {}

    public record MfaReset(Long id, boolean wasEnabled, int sessionsEnded, String message) {}

    /**
     * A session opened on somebody else's behalf.
     *
     * <p>No refresh token. An impersonation is deliberately short and cannot be
     * extended: an administrator who needs longer asks again, which writes a
     * second audit row — and two rows for an hour is the record this endpoint
     * exists to leave.
     */
    public record ImpersonationOpened(
            Long userId, String actingAs, String accessToken, LocalDateTime expiresAt,
            Long sessionId, String reason, String warning) {}
}
