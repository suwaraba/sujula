package com.sujula.service.store;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.store.StoreRequests;
import com.sujula.dto.response.store.StoreResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.BankAccountType;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.constant.KycDocumentType;
import com.sujula.model.constant.KycStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.StorePermission;
import com.sujula.model.constant.StoreStaffStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.store.KycDocument;
import com.sujula.model.store.StoreStaff;
import com.sujula.model.user.BankAccount;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.store.KycDocumentRepository;
import com.sujula.repository.store.StoreStaffRepository;
import com.sujula.repository.user.BankAccountRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.geo.GeocodingGateway;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import com.sujula.service.security.FieldEncryptionService;
import com.sujula.service.security.StepUpVerifier;
import com.sujula.service.store.impl.StoreServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Opening and running a store.
 *
 * <p>The cases here are the ones where a reasonable implementation is quietly
 * wrong: a currency inferred from an address, a PATCH that blanks the fields it
 * was not sent, a staff grant that reaches further than a store, a payout
 * destination that moves without anybody proving who they are.
 */
class StoreServiceTest {

    private static final Long OWNER = 900L;
    private static final Long INTRUDER = 901L;
    private static final Long STORE = 5000L;
    private static final Long ASSISTANT = 902L;

    private VendorRepository vendors;
    private UserRepository users;
    private KycDocumentRepository kycDocuments;
    private StoreStaffRepository staff;
    private BankAccountRepository bankAccounts;
    private GeocodingGateway geocoding;
    private StepUpVerifier stepUp;
    private FieldEncryptionService crypto;
    private AuditService audit;
    private StoreServiceImpl service;

    @BeforeEach
    void setUp() {
        vendors = mock(VendorRepository.class);
        users = mock(UserRepository.class);
        kycDocuments = mock(KycDocumentRepository.class);
        staff = mock(StoreStaffRepository.class);
        bankAccounts = mock(BankAccountRepository.class);
        geocoding = mock(GeocodingGateway.class);
        stepUp = mock(StepUpVerifier.class);
        crypto = mock(FieldEncryptionService.class);
        audit = mock(AuditService.class);

        service = new StoreServiceImpl(vendors, users, kycDocuments, staff, bankAccounts,
                geocoding, CurrencyCatalogue.of(new ReferenceDataProperties()),
                stepUp, crypto, audit);

        ReflectionTestUtils.setField(service, "geocodingLanguage", "en");
        ReflectionTestUtils.setField(service, "staffInviteTtl", Duration.ofDays(7));
        ReflectionTestUtils.setField(service, "maxStaff", 25);

        when(vendors.save(any(Vendor.class))).thenAnswer(call -> call.getArgument(0));
        when(staff.save(any(StoreStaff.class))).thenAnswer(call -> call.getArgument(0));
        when(bankAccounts.save(any(BankAccount.class))).thenAnswer(call -> call.getArgument(0));
        when(kycDocuments.save(any(KycDocument.class))).thenAnswer(call -> call.getArgument(0));
        when(kycDocuments.findLiveForVendor(anyLong())).thenReturn(List.of());
        when(kycDocuments.findLiveOfType(anyLong(), any())).thenReturn(Optional.empty());
        when(staff.findForVendor(anyLong())).thenReturn(List.of());
        when(bankAccounts.findDefaultForVendor(anyLong())).thenReturn(Optional.empty());
        when(geocoding.forward(anyString(), anyString())).thenReturn(Optional.empty());
        when(users.findById(OWNER)).thenReturn(Optional.of(owner()));
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
    }

    private static User owner() {
        User user = new User();
        user.setId(OWNER);
        user.setFirstName("Lamin");
        user.setLastName("Touray");
        user.setEmail("lamin@sujula.gm");
        user.setPassword("$2a$10$hash");
        user.setRole(UserRole.CUSTOMER);
        return user;
    }

    /** A Gambian seller working out of a warehouse in Dakar — the awkward case. */
    private static StoreRequests.CreateStore application(String currency) {
        return new StoreRequests.CreateStore(
                "Kombo Electronics", "Phones and accessories", "shop@kombo.gm", "+2203100002",
                null,
                "12 Rue Félix Faure", "Dakar", "Dakar", null, "SN",
                null, null,
                currency, null, null);
    }

    private Vendor store(PartnerStatus status) {
        Vendor vendor = Vendor.builder()
                .id(STORE)
                .user(owner())
                .storeName("Kombo Electronics")
                .storeSlug("kombo-electronics")
                .status(status)
                .settlementCurrency("GMD")
                .handlingDays(2)
                .addressStreet("12 Rue Félix Faure")
                .addressCity("Dakar")
                .addressCountryCode("SN")
                .returnPolicy("Fourteen days, unopened.")
                .operatingHours(new ArrayList<>())
                .build();
        when(vendors.findByIdAndOwnerId(STORE, OWNER)).thenReturn(Optional.of(vendor));
        when(vendors.findByIdAndOwnerIdWithHours(STORE, OWNER)).thenReturn(Optional.of(vendor));
        when(vendors.findByIdAndOwnerId(STORE, INTRUDER)).thenReturn(Optional.empty());
        when(vendors.findByIdAndOwnerIdWithHours(STORE, INTRUDER)).thenReturn(Optional.empty());
        return vendor;
    }

    // ── C1: the two locations ────────────────────────────────────────────────

    @Test
    void theSettlementCurrencyIsNotReadOffTheAddress() {
        when(vendors.existsByUserId(OWNER)).thenReturn(false);

        // The shop stands in Senegal. Ask for nothing and the answer must be the
        // platform's base currency, never XOF inferred from the country: a
        // Gambian trading out of Dakar banks in Banjul, and guessing here would
        // re-denominate their whole business without telling them.
        StoreResponses.Store created = service.create(OWNER, application(null));

        assertEquals("SN", created.address().countryCode());
        assertEquals("GMD", created.settlementCurrency());
    }

    @Test
    void andTheCurrencyTheSellerNamedIsTheOneThatSticks() {
        when(vendors.existsByUserId(OWNER)).thenReturn(false);

        StoreResponses.Store created = service.create(OWNER, application("XOF"));

        assertEquals("XOF", created.settlementCurrency());
        assertEquals("SN", created.address().countryCode());
    }

    @Test
    void anUnknownCurrencyIsRefusedRatherThanStored() {
        when(vendors.existsByUserId(OWNER)).thenReturn(false);
        assertThrows(RuntimeException.class, () -> service.create(OWNER, application("ZZZ")));
    }

    @Test
    void aSellerWhoDroppedTheirOwnPinOutranksTheGeocoder() {
        when(vendors.existsByUserId(OWNER)).thenReturn(false);
        when(geocoding.forward(anyString(), anyString())).thenReturn(Optional.of(
                new GeoAddress("somewhere else", 0.0, 0.0, "Senegal", "SN", "Dakar",
                        GeocodeConfidence.APPROXIMATE)));

        StoreRequests.CreateStore request = new StoreRequests.CreateStore(
                "Kombo Electronics", null, null, null, null,
                "12 Rue Félix Faure", "Dakar", null, null, "SN",
                14.6937, -17.4441, null, null, null);

        StoreResponses.Store created = service.create(OWNER, request);

        assertEquals(GeocodeConfidence.USER_CONFIRMED, created.address().confidence());
        assertEquals(14.6937, created.address().latitude());
        assertTrue(created.address().dispatchable());
    }

    @Test
    void aShopNoGeocoderKnowsStillOpens() {
        when(vendors.existsByUserId(OWNER)).thenReturn(false);
        // Most of the country has no street numbering. Refusing here would shut
        // out the sellers this marketplace is mainly for.
        StoreResponses.Store created = service.create(OWNER, application(null));

        assertEquals(PartnerStatus.PENDING_KYC, created.status());
        assertNull(created.address().latitude());
        assertEquals(GeocodeConfidence.NONE, created.address().confidence());
        // ...and the client is told to ask for a pin, because every collection
        // from this store is priced from it.
        assertTrue(created.address().needsPinConfirmation());
        assertFalse(created.address().dispatchable());
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    @Test
    void aNewStoreCannotTradeYet() {
        when(vendors.existsByUserId(OWNER)).thenReturn(false);

        StoreResponses.Store created = service.create(OWNER, application(null));

        assertEquals(PartnerStatus.PENDING_KYC, created.status());
        assertEquals(KycStatus.NOT_STARTED, created.kycStatus());
        assertFalse(created.canTrade());
        assertTrue(created.blockedReason().contains("identity documents"), created.blockedReason());
    }

    @Test
    void aSecondStoreIsRefused() {
        when(vendors.existsByUserId(OWNER)).thenReturn(true);
        assertThrows(BadRequestException.class, () -> service.create(OWNER, application(null)));
        verify(vendors, never()).save(any());
    }

    // ── Ownership ────────────────────────────────────────────────────────────

    @Test
    void somebodyElsesStoreIsNotFoundRatherThanForbidden() {
        store(PartnerStatus.APPROVED);

        assertThrows(ResourceNotFoundException.class, () -> service.get(INTRUDER, STORE));
        assertThrows(ResourceNotFoundException.class, () -> service.staff(INTRUDER, STORE));
        assertThrows(ResourceNotFoundException.class, () -> service.kycState(INTRUDER, STORE));
        assertThrows(ResourceNotFoundException.class, () -> service.removeStaff(INTRUDER, STORE, ASSISTANT));
    }

    // ── Editing ──────────────────────────────────────────────────────────────

    @Test
    void aPatchLeavesAloneWhatItDoesNotMention() {
        Vendor vendor = store(PartnerStatus.APPROVED);

        service.update(OWNER, STORE, new StoreRequests.UpdateStore(
                null, null, null, "+2203109999", null,
                null, null, null, null, null, null, null, null));

        assertEquals("+2203109999", vendor.getStorePhone());
        // The returns policy was not in the request, so it is still there. A
        // PATCH that blanked it would surface the first time a buyer asked to
        // send something back.
        assertEquals("Fourteen days, unopened.", vendor.getReturnPolicy());
        assertEquals("Kombo Electronics", vendor.getStoreName());
    }

    @Test
    void renamingTheStoreDoesNotMoveItsSlug() {
        Vendor vendor = store(PartnerStatus.APPROVED);

        service.update(OWNER, STORE, new StoreRequests.UpdateStore(
                "Kombo Phones & More", null, null, null, null,
                null, null, null, null, null, null, null, null));

        assertEquals("Kombo Phones & More", vendor.getStoreName());
        // Buyers have the old link saved and search engines have it indexed.
        assertEquals("kombo-electronics", vendor.getStoreSlug());
    }

    @Test
    void openingHoursThatCloseBeforeTheyOpenAreRefused() {
        store(PartnerStatus.APPROVED);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.update(OWNER, STORE, hours(new StoreRequests.OperatingHours(
                        DayOfWeek.MONDAY, false, LocalTime.of(22, 0), LocalTime.of(2, 0)))));

        assertTrue(refused.getMessage().contains("Overnight"), refused.getMessage());
    }

    @Test
    void anOpenDayWithNoTimesIsRefused() {
        store(PartnerStatus.APPROVED);

        assertThrows(BadRequestException.class,
                () -> service.update(OWNER, STORE, hours(new StoreRequests.OperatingHours(
                        DayOfWeek.MONDAY, false, null, null))));
    }

    @Test
    void aDayListedTwiceIsRefused() {
        store(PartnerStatus.APPROVED);

        assertThrows(BadRequestException.class,
                () -> service.update(OWNER, STORE,
                        hours(new StoreRequests.OperatingHours(DayOfWeek.MONDAY, true, null, null),
                              new StoreRequests.OperatingHours(DayOfWeek.MONDAY, false,
                                      LocalTime.of(9, 0), LocalTime.of(17, 0)))));
    }

    @Test
    void vacationModeClosesTheShopWithoutSuspendingIt() {
        Vendor vendor = store(PartnerStatus.APPROVED);

        StoreResponses.Store updated = service.update(OWNER, STORE, new StoreRequests.UpdateStore(
                null, null, null, null, null, null, null, null, null,
                true, "Back on the 20th.", null, null));

        assertFalse(updated.canTrade());
        // Still approved. Conflating the seller's own decision with the
        // platform's would put "suspended" on their storefront.
        assertEquals(PartnerStatus.APPROVED, updated.status());
        assertTrue(updated.blockedReason().contains("away"), updated.blockedReason());
        assertTrue(vendor.isVacationMode());
    }

    private static StoreRequests.UpdateStore hours(StoreRequests.OperatingHours... days) {
        return new StoreRequests.UpdateStore(null, null, null, null, null, null, null, null,
                null, null, null, null, List.of(days));
    }

    // ── KYC ──────────────────────────────────────────────────────────────────

    @Test
    void aCompleteSubmissionMovesTheStoreIntoTheReviewersQueue() {
        Vendor vendor = store(PartnerStatus.PENDING_KYC);
        when(kycDocuments.findLiveForVendor(STORE)).thenReturn(List.of(
                document(KycDocumentType.NATIONAL_ID), document(KycDocumentType.PROOF_OF_ADDRESS)));

        StoreResponses.KycState state = service.submitKyc(OWNER, STORE, new StoreRequests.SubmitKyc(
                List.of(upload(KycDocumentType.NATIONAL_ID), upload(KycDocumentType.PROOF_OF_ADDRESS))));

        assertEquals(KycStatus.IN_REVIEW, state.status());
        // PENDING_KYC waits on the applicant; PENDING waits on a reviewer. A
        // single status would make the review queue mostly rows nobody has sent.
        assertEquals(PartnerStatus.PENDING, vendor.getStatus());
    }

    @Test
    void aTraderWithNoRegistrationNumberIsNotAskedForACertificate() {
        Vendor vendor = store(PartnerStatus.PENDING_KYC);
        vendor.setBusinessRegistrationNumber(null);
        vendor.setTaxNumber(null);
        when(kycDocuments.findLiveForVendor(STORE)).thenReturn(List.of(
                document(KycDocumentType.NATIONAL_ID), document(KycDocumentType.PROOF_OF_ADDRESS)));

        // A market trader in Serrekunda has an ID and nothing else. Demanding a
        // company's paperwork turns away most of the sellers here.
        assertEquals(KycStatus.IN_REVIEW, service.kycState(OWNER, STORE).status());
    }

    @Test
    void butACompanyThatClaimedATaxNumberIsAskedForTheCertificate() {
        Vendor vendor = store(PartnerStatus.PENDING_KYC);
        vendor.setTaxNumber("SN-99-123456");
        when(kycDocuments.findLiveForVendor(STORE)).thenReturn(List.of(
                document(KycDocumentType.NATIONAL_ID), document(KycDocumentType.PROOF_OF_ADDRESS)));

        StoreResponses.KycState state = service.kycState(OWNER, STORE);

        assertEquals(KycStatus.INCOMPLETE, state.status());
        assertTrue(state.missing().contains(KycDocumentType.TAX_CERTIFICATE), state.missing().toString());
    }

    @Test
    void resubmittingSupersedesTheOldDocumentRatherThanErasingIt() {
        store(PartnerStatus.PENDING_KYC);
        KycDocument previous = document(KycDocumentType.NATIONAL_ID);
        when(kycDocuments.findLiveOfType(STORE, KycDocumentType.NATIONAL_ID))
                .thenReturn(Optional.of(previous));

        service.submitKyc(OWNER, STORE,
                new StoreRequests.SubmitKyc(List.of(upload(KycDocumentType.NATIONAL_ID))));

        // The old row stays, marked. It and the reason it was refused are the
        // record of why onboarding took three weeks.
        assertNotNull(previous.getSupersededAt());
        assertFalse(previous.isLive());
    }

    @Test
    void aRejectedDocumentOutranksEverythingElseAndSaysWhy() {
        store(PartnerStatus.PENDING);
        KycDocument rejected = document(KycDocumentType.NATIONAL_ID);
        rejected.setStatus(com.sujula.model.constant.KycDocumentStatus.REJECTED);
        rejected.setRejectionReason("The photograph is too blurred to read.");
        when(kycDocuments.findLiveForVendor(STORE)).thenReturn(List.of(
                rejected, document(KycDocumentType.PROOF_OF_ADDRESS)));

        StoreResponses.KycState state = service.kycState(OWNER, STORE);

        assertEquals(KycStatus.ACTION_REQUIRED, state.status());
        assertEquals(1, state.actionsRequired().size());
        assertTrue(state.actionsRequired().get(0).contains("too blurred"),
                state.actionsRequired().toString());
    }

    @Test
    void theStorageKeyOfAPassportNeverComesBackOut() {
        store(PartnerStatus.PENDING);
        when(kycDocuments.findLiveForVendor(STORE))
                .thenReturn(List.of(document(KycDocumentType.PASSPORT)));

        String rendered = service.kycState(OWNER, STORE).toString();

        assertFalse(rendered.contains("kyc/secret-object-key"), rendered);
    }

    private static KycDocument document(KycDocumentType type) {
        return KycDocument.builder()
                .id(1L).type(type).fileUrl("https://storage.invalid/kyc/secret-object-key")
                .status(com.sujula.model.constant.KycDocumentStatus.SUBMITTED)
                .build();
    }

    private static StoreRequests.KycUpload upload(KycDocumentType type) {
        return new StoreRequests.KycUpload(type, "https://storage.invalid/kyc/secret-object-key",
                "id.jpg", "image/jpeg", 120_000L, null);
    }

    // ── Staff ────────────────────────────────────────────────────────────────

    @Test
    void anInviteWithNoPermissionsGrantsTheLeastRatherThanTheMost() {
        store(PartnerStatus.APPROVED);
        when(staff.findByVendorIdAndEmailIgnoreCase(eq(STORE), anyString()))
                .thenReturn(Optional.empty());

        StoreResponses.StaffMember invited = service.invite(OWNER, STORE,
                new StoreRequests.InviteStaff("binta@example.gm", "Binta", null));

        assertEquals(StorePermission.leastPrivilege(), invited.permissions());
        assertEquals(StoreStaffStatus.INVITED, invited.status());
        // No account yet, which is the ordinary case rather than an edge one.
        assertNull(invited.userId());
    }

    @Test
    void theInvitationTokenIsStoredHashedAndNeverReturned() {
        store(PartnerStatus.APPROVED);
        when(staff.findByVendorIdAndEmailIgnoreCase(eq(STORE), anyString()))
                .thenReturn(Optional.empty());

        StoreResponses.StaffMember invited = service.invite(OWNER, STORE,
                new StoreRequests.InviteStaff("binta@example.gm", null, Set.of()));

        ArgumentCaptor<StoreStaff> saved = ArgumentCaptor.forClass(StoreStaff.class);
        verify(staff).save(saved.capture());

        // SHA-256 hex. A dump of live invitations would otherwise be a way into
        // other people's shops.
        assertEquals(64, saved.getValue().getInviteTokenHash().length());
        assertFalse(invited.toString().contains(saved.getValue().getInviteTokenHash()));
    }

    @Test
    void theOwnerCannotBeRescopedOrRemovedThroughTheStaffEndpoints() {
        store(PartnerStatus.APPROVED);

        assertThrows(BadRequestException.class, () -> service.updateStaff(OWNER, STORE, OWNER,
                new StoreRequests.UpdateStaff(Set.of(StorePermission.ORDERS_VIEW), null)));
        assertThrows(BadRequestException.class, () -> service.removeStaff(OWNER, STORE, OWNER));
    }

    @Test
    void removingSomebodyKillsTheirOutstandingInviteLink() {
        store(PartnerStatus.APPROVED);
        StoreStaff member = StoreStaff.builder()
                .id(7L).email("binta@example.gm").status(StoreStaffStatus.ACTIVE)
                .permissions(new java.util.LinkedHashSet<>(Set.of(StorePermission.ORDERS_FULFIL)))
                .inviteTokenHash("a".repeat(64))
                .inviteExpiresAt(java.time.LocalDateTime.now().plusDays(3))
                .build();
        when(staff.findByVendorIdAndUserId(STORE, ASSISTANT)).thenReturn(Optional.of(member));

        service.removeStaff(OWNER, STORE, ASSISTANT);

        assertEquals(StoreStaffStatus.REVOKED, member.getStatus());
        assertNotNull(member.getRevokedAt());
        // Otherwise a link mailed last week still opens the shop.
        assertNull(member.getInviteTokenHash());
        assertTrue(member.getPermissions().isEmpty());
    }

    @Test
    void theStaffListLeadsWithTheOwner() {
        store(PartnerStatus.APPROVED);

        StoreResponses.StaffList list = service.staff(OWNER, STORE);

        assertTrue(list.members().get(0).owner());
        assertEquals(OWNER, list.members().get(0).userId());
    }

    // ── Payout destination ───────────────────────────────────────────────────

    @Test
    void thePayoutDestinationIsRefusedOutrightWhenThereIsNowhereSafeToPutIt() {
        store(PartnerStatus.APPROVED);
        doThrow(new BadRequestException("no key")).when(crypto).requireConfigured(anyString());

        assertThrows(BadRequestException.class,
                () -> service.putBankAccount(OWNER, STORE, bankRequest()));

        // Refused before the store is even loaded. Storing an account number in
        // clear is not the fallback.
        verify(bankAccounts, never()).save(any());
        verify(stepUp, never()).verify(any(), anyString(), any(), anyString());
    }

    @Test
    void aWrongPasswordChangesNothing() {
        store(PartnerStatus.APPROVED);
        doThrow(new BadCredentialsException("nope"))
                .when(stepUp).verify(any(), anyString(), any(), anyString());

        assertThrows(BadCredentialsException.class,
                () -> service.putBankAccount(OWNER, STORE, bankRequest()));

        verify(bankAccounts, never()).save(any());
    }

    @Test
    void aSavedDestinationComesBackAsFourDigitsAndNothingMore() {
        store(PartnerStatus.APPROVED);

        StoreResponses.PayoutDestination saved =
                service.putBankAccount(OWNER, STORE, bankRequest());

        assertEquals("4417", saved.accountNumberLast4());
        // Nothing on this record could be used to send money anywhere, which is
        // what makes it safe in a log, a cache or a screenshot.
        String rendered = saved.toString();
        assertFalse(rendered.contains("0123456789014417"), rendered);
        assertFalse(rendered.contains("hunter2"), rendered);
    }

    @Test
    void aPayoutIsDenominatedInTheSellersCurrencyNotTheBuyers() {
        Vendor vendor = store(PartnerStatus.APPROVED);
        vendor.setSettlementCurrency("GMD");

        StoreResponses.PayoutDestination saved =
                service.putBankAccount(OWNER, STORE, bankRequest());

        // The order may have been charged in EUR. What reaches the seller is
        // GMD, because that is what their bank takes.
        assertEquals("GMD", saved.currency());
    }

    @Test
    void changingWhereTheMoneyGoesAlwaysUnVerifiesIt() {
        store(PartnerStatus.APPROVED);
        BankAccount existing = BankAccount.builder()
                .id(3L).verified(true).isDefault(true).build();
        when(bankAccounts.findDefaultForVendor(STORE)).thenReturn(Optional.of(existing));

        StoreResponses.PayoutDestination saved =
                service.putBankAccount(OWNER, STORE, bankRequest());

        // Otherwise the quickest route to a verified account somebody else
        // controls is to change the number on one that has already been checked.
        assertFalse(saved.verified());
        assertEquals(3L, saved.id());
    }

    @Test
    void aDestinationWithNoNumberAtAllIsRefused() {
        store(PartnerStatus.APPROVED);

        assertThrows(BadRequestException.class, () -> service.putBankAccount(OWNER, STORE,
                new StoreRequests.PutBankAccount(BankAccountType.CHECKING, "Lamin Touray",
                        "Trust Bank", null, null, null, null, null, null, "hunter2", null)));
    }

    @Test
    void theChangeIsAuditedWithFourDigitsAndNeverTheNumber() {
        store(PartnerStatus.APPROVED);

        service.putBankAccount(OWNER, STORE, bankRequest());

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(com.sujula.model.constant.AuditAction.VENDOR_PAYOUT_DESTINATION_CHANGED),
                eq("VENDOR"), eq(STORE), anyString(), summary.capture(), anyString());

        assertTrue(summary.getValue().contains("4417"), summary.getValue());
        assertFalse(summary.getValue().contains("0123456789014417"), summary.getValue());
    }

    @Test
    void aMobileMoneyDestinationNeedsNoBankName() {
        store(PartnerStatus.APPROVED);

        // Paid to a phone is the commonest payout route in this market, and it
        // has a provider rather than a bank. The column is NOT NULL, so a null
        // here fails on a constraint at flush — after the step-up has already
        // been spent and the caller has to type their password again.
        StoreResponses.PayoutDestination saved = service.putBankAccount(OWNER, STORE,
                new StoreRequests.PutBankAccount(BankAccountType.MOBILE_MONEY, "Lamin Touray",
                        null, null, null, null, null, "+2203100002", "Africell Money",
                        "hunter2", null));

        assertEquals("Africell Money", saved.bankName());
        assertEquals("0002", saved.mobileMoneyLast4());
        // Nothing renders "••••" for an account number that does not exist.
        assertNull(saved.accountNumberLast4());
    }

    @Test
    void anOutstandingInvitationCanStillBeCancelled() {
        store(PartnerStatus.APPROVED);
        // Binta has no account, so she has no user id to be addressed by. An
        // owner who invited the wrong address would otherwise have no way back.
        StoreStaff pending = StoreStaff.builder()
                .id(4242L).email("wrong.binta@example.gm").status(StoreStaffStatus.INVITED)
                .vendor(vendors.findByIdAndOwnerId(STORE, OWNER).orElseThrow())
                .permissions(new java.util.LinkedHashSet<>(Set.of(StorePermission.ORDERS_VIEW)))
                .inviteTokenHash("b".repeat(64))
                .build();
        when(staff.findByVendorIdAndUserId(STORE, 4242L)).thenReturn(Optional.empty());
        when(staff.findById(4242L)).thenReturn(Optional.of(pending));

        service.removeStaff(OWNER, STORE, 4242L);

        assertEquals(StoreStaffStatus.REVOKED, pending.getStatus());
        assertNull(pending.getInviteTokenHash());
    }

    @Test
    void aStaffRowFromAnotherStoreIsNotFoundByItsRowId() {
        store(PartnerStatus.APPROVED);
        Vendor somebodyElses = Vendor.builder().id(9999L).build();
        StoreStaff foreign = StoreStaff.builder()
                .id(4243L).email("someone@example.gm").status(StoreStaffStatus.ACTIVE)
                .vendor(somebodyElses).build();
        when(staff.findByVendorIdAndUserId(STORE, 4243L)).thenReturn(Optional.empty());
        when(staff.findById(4243L)).thenReturn(Optional.of(foreign));

        // The fallback lookup is still scoped to this store. Otherwise a row id
        // would reach across shops.
        assertThrows(ResourceNotFoundException.class,
                () -> service.removeStaff(OWNER, STORE, 4243L));
        assertEquals(StoreStaffStatus.ACTIVE, foreign.getStatus());
    }

    private static StoreRequests.PutBankAccount bankRequest() {
        return new StoreRequests.PutBankAccount(
                BankAccountType.CHECKING, "Lamin Touray", "Trust Bank",
                "0123456789014417", null, null, "TRBKGMGM", null, null,
                "hunter2", null);
    }
}
