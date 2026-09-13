package com.sujula.controller;

import com.sujula.dto.request.store.StoreRequests;
import com.sujula.dto.response.store.StoreResponses;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.store.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A seller's own store.
 *
 * <p>The store id is in the path and the owner never is: every method hands the
 * service the authenticated caller's id, and the service resolves by id and
 * owner in one query. There is no parameter on this controller that could be
 * changed to reach another seller's shop.
 *
 * <p>Opening a store needs only an account — a customer becomes a seller by
 * asking. Everything after that needs the store, which in practice means
 * owning it.
 */
@RestController
@RequestMapping("/vendor/stores")
@PreAuthorize("isAuthenticated()")
@Tag(name = "stores", description = "Opening and running a store: profile, verification, staff, payouts")
public class StoreController {

    private static final String CREATE = "store.create";
    private static final String KYC = "store.kyc";
    private static final String INVITE = "store.staff.invite";
    private static final String BANK = "store.bank-account";

    private final StoreService stores;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public StoreController(StoreService stores, AuthenticatedCaller caller,
                           IdempotencyService idempotency) {
        this.stores = stores;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    // ── Store ────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Open a store",
               description = "Creates it in PENDING_KYC — registered, and unable to sell until "
                       + "identity documents have been sent and accepted. The address is placed on "
                       + "a map here, because every collection from this store is priced from that "
                       + "pin; a seller whose compound no geocoder knows is still let through, with "
                       + "the pin marked unconfirmed. The settlement currency is taken from the "
                       + "request or the platform default, and never read off the address: a "
                       + "Gambian trading out of Dakar still banks in GMD.")
    public ResponseEntity<StoreResponses.Store> create(
            Authentication authentication,
            @Parameter(description = "A unique value per attempt, so a retry is answered rather than repeated")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StoreRequests.CreateStore request) {

        Long userId = caller.userId(authentication);

        StoreResponses.Store store = idempotency.execute(
                IdempotencyService.scopeFor(userId, CREATE), idempotencyKey, request,
                HttpStatus.CREATED.value(), StoreResponses.Store.class,
                () -> stores.create(userId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(store);
    }

    @GetMapping("/{storeId}")
    @Operation(summary = "My store",
               description = "Somebody else's store is not found rather than forbidden: a 403 "
                       + "would confirm the store exists, and store ids are small integers.")
    public ResponseEntity<StoreResponses.Store> get(
            Authentication authentication, @PathVariable Long storeId) {
        return ResponseEntity.ok(stores.get(caller.userId(authentication), storeId));
    }

    @PatchMapping("/{storeId}")
    @Operation(summary = "Edit the store",
               description = "Name, policies, handling time, vacation mode, the collection address "
                       + "and the opening hours a driver is dispatched against. Absent fields are "
                       + "left alone. The slug does not follow a rename — it is in links buyers "
                       + "have saved.")
    public ResponseEntity<StoreResponses.Store> update(
            Authentication authentication, @PathVariable Long storeId,
            @Valid @RequestBody StoreRequests.UpdateStore request) {
        return ResponseEntity.ok(stores.update(caller.userId(authentication), storeId, request));
    }

    // ── Verification ─────────────────────────────────────────────────────────

    @PostMapping("/{storeId}/kyc")
    @Operation(summary = "Send identity and business documents",
               description = "Takes storage keys, not bytes: the client presigns an upload and puts "
                       + "the file straight into object storage, so a passport scan does not pass "
                       + "through three access logs on its way here. Re-sending a document "
                       + "supersedes the previous one rather than erasing it. A complete set moves "
                       + "the store to PENDING, which is the reviewer's queue.")
    public ResponseEntity<StoreResponses.KycState> submitKyc(
            Authentication authentication, @PathVariable Long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StoreRequests.SubmitKyc request) {

        Long userId = caller.userId(authentication);

        StoreResponses.KycState state = idempotency.execute(
                IdempotencyService.scopeFor(userId, KYC + ":" + storeId), idempotencyKey, request,
                HttpStatus.ACCEPTED.value(), StoreResponses.KycState.class,
                () -> stores.submitKyc(userId, storeId, request));

        return ResponseEntity.accepted().body(state);
    }

    @GetMapping("/{storeId}/kyc")
    @Operation(summary = "Where verification has got to",
               description = "What has been accepted, what is still missing, and — the field that "
                       + "actually matters — why anything was refused. An applicant told only "
                       + "'incomplete' re-uploads the document they already sent.")
    public ResponseEntity<StoreResponses.KycState> kyc(
            Authentication authentication, @PathVariable Long storeId) {
        return ResponseEntity.ok(stores.kycState(caller.userId(authentication), storeId));
    }

    // ── Staff ────────────────────────────────────────────────────────────────

    @GetMapping("/{storeId}/staff")
    @Operation(summary = "Who works here",
               description = "The owner first, then everyone invited — revoked rows included, so "
                       + "the list doubles as a record of who could see what and when.")
    public ResponseEntity<StoreResponses.StaffList> staff(
            Authentication authentication, @PathVariable Long storeId) {
        return ResponseEntity.ok(stores.staff(caller.userId(authentication), storeId));
    }

    @PostMapping("/{storeId}/staff")
    @Operation(summary = "Invite somebody",
               description = "By email, because the person being invited usually has no account "
                       + "here yet. Permissions are scoped to this store and drawn from a "
                       + "store-only set — there is no value a seller can send that reaches "
                       + "another vendor's data or the platform's. Omitting them grants the least "
                       + "privilege rather than the most.")
    public ResponseEntity<StoreResponses.StaffMember> invite(
            Authentication authentication, @PathVariable Long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StoreRequests.InviteStaff request) {

        Long userId = caller.userId(authentication);

        StoreResponses.StaffMember member = idempotency.execute(
                IdempotencyService.scopeFor(userId, INVITE + ":" + storeId), idempotencyKey, request,
                HttpStatus.CREATED.value(), StoreResponses.StaffMember.class,
                () -> stores.invite(userId, storeId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @PatchMapping("/{storeId}/staff/{staffUserId}")
    @Operation(summary = "Change what somebody may do",
               description = "Replaces the permission set outright. The owner cannot be rescoped "
                       + "through this endpoint — there is nothing to grant themselves.")
    public ResponseEntity<StoreResponses.StaffMember> updateStaff(
            Authentication authentication, @PathVariable Long storeId,
            @PathVariable Long staffUserId,
            @Valid @RequestBody StoreRequests.UpdateStaff request) {

        return ResponseEntity.ok(stores.updateStaff(
                caller.userId(authentication), storeId, staffUserId, request));
    }

    @DeleteMapping("/{storeId}/staff/{staffUserId}")
    @Operation(summary = "Remove somebody",
               description = "Revokes access and kills any outstanding invitation link. The row "
                       + "stays, marked, so a later question about who had access has an answer.")
    public ResponseEntity<Void> removeStaff(
            Authentication authentication, @PathVariable Long storeId,
            @PathVariable Long staffUserId) {

        stores.removeStaff(caller.userId(authentication), storeId, staffUserId);
        return ResponseEntity.noContent().build();
    }

    // ── Payouts ──────────────────────────────────────────────────────────────

    @PutMapping("/{storeId}/bank-account")
    @Operation(summary = "Where this store's money goes",
               description = "Requires the caller's password again, and their authenticator code "
                       + "when the account has one — a bearer token proves somebody held a "
                       + "credential an hour ago, which is not enough to redirect every future "
                       + "payout for a shop. The number is encrypted in the column and never comes "
                       + "back out of this API: what is returned is the last four digits. Changing "
                       + "the destination always marks it unverified.")
    public ResponseEntity<StoreResponses.PayoutDestination> putBankAccount(
            Authentication authentication, @PathVariable Long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StoreRequests.PutBankAccount request) {

        Long userId = caller.userId(authentication);

        // Fingerprinted on the request minus its credentials. The fingerprint
        // is stored, and a digest of a body containing a password is an
        // unsalted password hash in a database table.
        StoreResponses.PayoutDestination destination = idempotency.execute(
                IdempotencyService.scopeFor(userId, BANK + ":" + storeId), idempotencyKey,
                request.idempotencyView(),
                HttpStatus.OK.value(), StoreResponses.PayoutDestination.class,
                () -> stores.putBankAccount(userId, storeId, request));

        return ResponseEntity.ok(destination);
    }
}
