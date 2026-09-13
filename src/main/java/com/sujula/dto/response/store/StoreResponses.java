package com.sujula.dto.response.store;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.BankAccountType;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.KycDocumentType;
import com.sujula.model.constant.KycStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.StorePermission;
import com.sujula.model.constant.StoreStaffStatus;

/**
 * What a seller sees of their own store.
 *
 * <p>Records rather than entities, and it matters here as much as anywhere: a
 * {@code Vendor} reaches its {@code User} — password hash, TOTP secret, failed
 * sign-in count — and its {@code BankAccount} list, which after the converter
 * runs holds a decrypted account number. Serialising the entity would hand a
 * client both.
 */
public final class StoreResponses {

    private StoreResponses() {}

    /**
     * The store as its owner sees it.
     *
     * @param settlementCurrency what this seller is paid in — a payment-side
     *                           fact, kept beside but never derived from the
     *                           address, which is a delivery-side one
     * @param canTrade           whether the store may actually list and sell
     *                           today, which is not the same as being approved:
     *                           a vendor on holiday is approved and closed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Store(
            Long id,
            String storeName,
            String storeSlug,
            String description,
            String storeEmail,
            String storePhone,
            String website,
            String logoUrl,
            String bannerUrl,

            PartnerStatus status,
            KycStatus kycStatus,
            boolean canTrade,
            String blockedReason,

            String settlementCurrency,

            StoreAddress address,
            StoreAddress pickupAddress,
            List<Hours> operatingHours,

            String returnPolicy,
            String shippingPolicy,
            String storePolicy,
            Integer handlingDays,
            boolean vacationMode,
            String vacationMessage,

            String businessRegistrationNumber,
            String taxNumber,

            PayoutDestination payoutDestination,
            int staffCount,

            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /**
     * An address, with how well it was placed on a map.
     *
     * @param confidence         how much to believe the pin
     * @param needsPinConfirmation whether the client should ask the seller to
     *                           drag it — a centroid can be a kilometre out, and
     *                           every collection from this store is priced from it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StoreAddress(
            String street,
            String city,
            String state,
            String postalCode,
            String countryCode,
            Double latitude,
            Double longitude,
            GeocodeConfidence confidence,
            boolean needsPinConfirmation,
            boolean dispatchable,
            String instructions,
            LocalDateTime geocodedAt) {}

    public record Hours(DayOfWeek day, boolean closed, LocalTime opensAt, LocalTime closesAt) {}

    // ── KYC ──────────────────────────────────────────────────────────────────

    /**
     * Verification, as an applicant needs to read it.
     *
     * @param missing    what still has to be sent. The single most useful field
     *                   here: an applicant told only "incomplete" re-uploads the
     *                   document they already sent
     * @param documents  every live document with its own decision and reason
     */
    public record KycState(
            KycStatus status,
            PartnerStatus storeStatus,
            List<KycDocumentType> missing,
            List<String> actionsRequired,
            List<KycDoc> documents,
            LocalDateTime submittedAt,
            LocalDateTime decidedAt) {}

    /**
     * @param fileUrl deliberately absent. The applicant does not need the storage
     *                key handed back to them, and a URL to somebody's passport
     *                that appears in a JSON response appears in a browser cache
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KycDoc(
            Long id,
            KycDocumentType type,
            KycDocumentStatus status,
            String originalFilename,
            Long sizeBytes,
            LocalDate expiresOn,
            String rejectionReason,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt) {}

    // ── Staff ────────────────────────────────────────────────────────────────

    /**
     * @param userId null while the invitation is outstanding — the person
     *               invited may have no account here yet, which is ordinary
     *               rather than exceptional
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StaffMember(
            Long id,
            Long userId,
            String email,
            String displayName,
            StoreStaffStatus status,
            Set<StorePermission> permissions,
            boolean owner,
            LocalDateTime invitedAt,
            LocalDateTime inviteExpiresAt,
            LocalDateTime acceptedAt,
            LocalDateTime revokedAt) {}

    public record StaffList(List<StaffMember> members, int limit) {}

    // ── Payout destination ───────────────────────────────────────────────────

    /**
     * Where the money goes, described without saying where the money goes.
     *
     * <p>There is no field here that could be used to send money anywhere. The
     * account number is four digits, which is enough for the seller to recognise
     * their own account and useless to anybody who reads this response out of a
     * log, a cache or a screenshot. The full value exists in one place — an
     * encrypted column — and leaves it only when a payout runs.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PayoutDestination(
            Long id,
            BankAccountType accountType,
            String accountHolderName,
            String bankName,
            String accountNumberLast4,
            String ibanLast4,
            String mobileMoneyLast4,
            String mobileMoneyProvider,
            String swiftCode,
            /** The vendor's settlement currency, never the buyer's display one. */
            String currency,
            boolean verified,
            LocalDateTime lastChangedAt) {}

    /** What a client needs to draw the confirmation dialog before it sends one. */
    public record StepUpRequirement(boolean passwordRequired, boolean authenticatorCodeRequired) {}
}
