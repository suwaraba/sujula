package com.sujula.dto.request.store;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.sujula.model.constant.BankAccountType;
import com.sujula.model.constant.KycDocumentType;
import com.sujula.model.constant.StorePermission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What a seller sends about their own store. */
public final class StoreRequests {

    private StoreRequests() {}

    private static final String ISO_COUNTRY = "^[A-Za-z]{2}$";
    private static final String ISO_CURRENCY = "^[A-Za-z]{3}$";

    /**
     * Opening a shop.
     *
     * @param settlementCurrency what this seller is paid in, and what they list
     *                           in. A payment-side fact, and nothing about the
     *                           address below may set it: a Gambian selling from
     *                           a warehouse in Dakar still settles in GMD if that
     *                           is where their bank is
     * @param addressCountryCode where the goods are, which is a delivery-side
     *                           fact and decides distances, not currencies
     */
    public record CreateStore(
            @NotBlank @Size(max = 100) String storeName,
            @Size(max = 2000) String description,
            @Email @Size(max = 150) String storeEmail,
            @Size(max = 25) String storePhone,
            @Size(max = 255) String website,

            @NotBlank @Size(max = 255) String addressStreet,
            @NotBlank @Size(max = 100) String addressCity,
            @Size(max = 100) String addressState,
            @Size(max = 20) String addressPostalCode,
            @NotBlank @Pattern(regexp = ISO_COUNTRY) String addressCountryCode,

            /** Sent when the seller dropped a pin themselves; geocoded when absent. */
            Double latitude,
            Double longitude,

            @Pattern(regexp = ISO_CURRENCY) String settlementCurrency,

            @Size(max = 60) String businessRegistrationNumber,
            @Size(max = 60) String taxNumber) {}

    /**
     * Editing the store. Every field is optional and null means "leave it".
     *
     * <p>A PATCH whose absent fields blanked the record would let a client that
     * only meant to change the phone number wipe the returns policy — and the
     * seller would not find out until a buyer asked to send something back.
     */
    public record UpdateStore(
            @Size(max = 100) String storeName,
            @Size(max = 2000) String description,
            @Email @Size(max = 150) String storeEmail,
            @Size(max = 25) String storePhone,
            @Size(max = 255) String website,

            @Size(max = 5000) String returnPolicy,
            @Size(max = 5000) String shippingPolicy,
            @Size(max = 5000) String storePolicy,
            @Min(0) @Max(60) Integer handlingDays,

            Boolean vacationMode,
            @Size(max = 300) String vacationMessage,

            @Valid PickupAddress pickupAddress,
            @Valid List<OperatingHours> operatingHours) {}

    /**
     * Where a driver collects, when that is not the store address.
     *
     * <p>Its own object so that sending it means "change the collection point"
     * and omitting it means "leave it alone" — a flat patch cannot express the
     * difference between clearing one field of an address and clearing none.
     */
    public record PickupAddress(
            @Size(max = 255) String street,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @Pattern(regexp = ISO_COUNTRY) String countryCode,
            Double latitude,
            Double longitude,
            @Size(max = 400) String instructions,
            /** True to go back to collecting from the store address. */
            boolean clear) {}

    public record OperatingHours(
            @NotNull DayOfWeek day,
            boolean closed,
            LocalTime opensAt,
            LocalTime closesAt) {}

    // ── KYC ──────────────────────────────────────────────────────────────────

    /**
     * A batch of documents, already uploaded to storage.
     *
     * <p>The bytes never pass through this API: the client presigns an upload,
     * puts the file straight into object storage and sends the resulting key
     * here. A passport scan travelling through an application server is a
     * passport scan in three access logs.
     */
    public record SubmitKyc(@NotEmpty @Valid List<KycUpload> documents) {}

    public record KycUpload(
            @NotNull KycDocumentType type,
            @NotBlank @Size(max = 500) String fileUrl,
            @Size(max = 255) String originalFilename,
            @Size(max = 100) String contentType,
            @Min(1) Long sizeBytes,
            java.time.LocalDate expiresOn) {}

    // ── Staff ────────────────────────────────────────────────────────────────

    /**
     * @param permissions what they may do. Absent means least privilege — see
     *                    {@link StorePermission#leastPrivilege()} — rather than
     *                    everything, because "I just added them quickly" should
     *                    not be a way to lose a catalogue
     */
    public record InviteStaff(
            @NotBlank @Email @Size(max = 200) String email,
            @Size(max = 150) String displayName,
            Set<StorePermission> permissions) {}

    /** Replaces the permission set outright; a partial grant is not a thing. */
    public record UpdateStaff(
            @NotNull Set<StorePermission> permissions,
            @Size(max = 150) String displayName) {}

    // ── Payout destination ───────────────────────────────────────────────────

    /**
     * Where this store's money goes.
     *
     * @param password  the caller's own password, re-entered. This is the single
     *                  most valuable thing an attacker inside a vendor account
     *                  can change, and a bearer token only proves somebody held
     *                  a credential an hour ago
     * @param totpCode  their authenticator code, required when the account has
     *                  MFA. Without it the sensitive endpoint would be easier to
     *                  pass than the sign-in that reached it
     */
    public record PutBankAccount(
            @NotNull BankAccountType accountType,
            @NotBlank @Size(max = 150) String accountHolderName,
            @Size(max = 150) String bankName,

            @Size(max = 40) String accountNumber,
            @Size(max = 40) String routingNumber,
            @Size(max = 40) String iban,
            @Size(max = 20) String swiftCode,

            @Size(max = 25) String mobileMoneyPhone,
            @Size(max = 60) String mobileMoneyProvider,

            @NotBlank String password,
            String totpCode) {

        /**
         * Redacted, because a record's generated {@code toString} prints every
         * component and this one holds a password and an account number.
         *
         * <p>Nobody writes {@code log.info("{}", request)} on purpose. It
         * happens through a validation handler, a debug line left in, or a
         * framework that renders the bound object in an error — and the leak is
         * a credential in a log file that is shipped somewhere else.
         */
        @Override
        public String toString() {
            return "PutBankAccount[accountType=" + accountType
                    + ", accountHolderName=" + accountHolderName
                    + ", bankName=" + bankName + ", secrets redacted]";
        }

        /**
         * What identifies this attempt, for the idempotency fingerprint.
         *
         * <p>The fingerprint is stored, and a digest of a body containing a
         * password is an unsalted password hash sitting in a table. So the
         * credentials are left out of it — and only the credentials: the account
         * details stay in, so that the same key sent with a different account
         * number is still caught as a reused key rather than quietly replaying
         * the first answer and dropping the second.
         */
        public Object idempotencyView() {
            return List.of(String.valueOf(accountType),
                           String.valueOf(accountHolderName), String.valueOf(bankName),
                           String.valueOf(accountNumber), String.valueOf(routingNumber),
                           String.valueOf(iban), String.valueOf(swiftCode),
                           String.valueOf(mobileMoneyPhone), String.valueOf(mobileMoneyProvider));
        }
    }
}
