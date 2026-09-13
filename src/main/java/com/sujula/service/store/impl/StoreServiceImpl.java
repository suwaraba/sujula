package com.sujula.service.store.impl;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.store.StoreRequests;
import com.sujula.dto.response.store.StoreResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.KycDocumentType;
import com.sujula.model.constant.KycStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.StorePermission;
import com.sujula.model.constant.StoreStaffStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.store.KycDocument;
import com.sujula.model.store.StoreOperatingHours;
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
import com.sujula.service.security.FieldEncryptionService;
import com.sujula.service.security.StepUpVerifier;
import com.sujula.service.store.StoreService;
import com.sujula.util.Utils;

import lombok.extern.slf4j.Slf4j;

/**
 * {@inheritDoc}
 *
 * <p>Three things run through this class.
 *
 * <p><strong>Ownership is the query.</strong> {@link #requireOwn} resolves by id
 * and owner together, so a store belonging to somebody else is not found rather
 * than found and refused.
 *
 * <p><strong>The two locations stay apart.</strong> Geocoding touches only the
 * address; currency resolution touches only the currency; and
 * {@link #resolveSettlementCurrency} is deliberately not given the address to
 * read a default off.
 *
 * <p><strong>Standing is derived, never set.</strong> A store's KYC status is
 * computed from its documents every time it is asked for, so the summary and the
 * rows cannot disagree.
 */
@Slf4j
@Service
public class StoreServiceImpl implements StoreService {

    private final VendorRepository vendors;
    private final UserRepository users;
    private final KycDocumentRepository kycDocuments;
    private final StoreStaffRepository staff;
    private final BankAccountRepository bankAccounts;
    private final GeocodingGateway geocoding;
    private final CurrencyCatalogue currencies;
    private final StepUpVerifier stepUp;
    private final FieldEncryptionService crypto;
    private final AuditService audit;

    @Value("${sujula.geocoding.default-language:en}")
    private String geocodingLanguage;

    @Value("${sujula.store.staff-invite-ttl:7d}")
    private Duration staffInviteTtl;

    @Value("${sujula.store.max-staff:25}")
    private int maxStaff;

    public StoreServiceImpl(VendorRepository vendors, UserRepository users,
                            KycDocumentRepository kycDocuments, StoreStaffRepository staff,
                            BankAccountRepository bankAccounts, GeocodingGateway geocoding,
                            CurrencyCatalogue currencies, StepUpVerifier stepUp,
                            FieldEncryptionService crypto, AuditService audit) {
        this.vendors = vendors;
        this.users = users;
        this.kycDocuments = kycDocuments;
        this.staff = staff;
        this.bankAccounts = bankAccounts;
        this.geocoding = geocoding;
        this.currencies = currencies;
        this.stepUp = stepUp;
        this.crypto = crypto;
        this.audit = audit;
    }

    // ── Store ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public StoreResponses.Store create(Long userId, StoreRequests.CreateStore request) {
        User owner = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (vendors.existsByUserId(userId)) {
            throw new BadRequestException(
                    "You already have a store. Edit that one rather than opening a second.");
        }

        Vendor store = Vendor.builder()
                .user(owner)
                .storeName(request.storeName().trim())
                .storeSlug(uniqueSlug(request.storeName()))
                .description(trimToNull(request.description()))
                .storeEmail(lower(request.storeEmail()))
                .storePhone(trimToNull(request.storePhone()))
                .website(trimToNull(request.website()))
                .addressStreet(trimToNull(request.addressStreet()))
                .addressCity(trimToNull(request.addressCity()))
                .addressState(trimToNull(request.addressState()))
                .addressPostalCode(trimToNull(request.addressPostalCode()))
                .addressCountryCode(upper(request.addressCountryCode()))
                .businessRegistrationNumber(trimToNull(request.businessRegistrationNumber()))
                .taxNumber(trimToNull(request.taxNumber()))
                // Nothing is sold until somebody has looked at the documents.
                .status(PartnerStatus.PENDING_KYC)
                .settlementCurrency(resolveSettlementCurrency(request.settlementCurrency()))
                .build();

        // Two separate calls on purpose, each given only its own half.
        placeOnMap(store, request.latitude(), request.longitude());

        Vendor saved;
        try {
            saved = vendors.save(store);
        } catch (DataIntegrityViolationException clash) {
            throw new BadRequestException(
                    "A store already exists for this account or store email.");
        }

        // The role follows the store, so the seller's own back office resolves.
        // Selling rights do not: those come from the status, which is PENDING_KYC.
        if (owner.getRole() == UserRole.CUSTOMER) {
            owner.setRole(UserRole.VENDOR);
            users.save(owner);
        }

        audit.record(AuditAction.VENDOR_STORE_CREATED, "VENDOR", saved.getId(),
                saved.getStoreName(),
                "Store opened, awaiting identity documents",
                "Collects from " + saved.getAddressCity() + ", " + saved.getAddressCountryCode()
                        + "; settles in " + saved.getSettlementCurrency());

        log.info("[Store] {} opened store {} ({}) in {} — settles in {}",
                userId, saved.getId(), saved.getStoreSlug(), saved.getAddressCountryCode(),
                saved.getSettlementCurrency());

        return toStore(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public StoreResponses.Store get(Long userId, Long storeId) {
        return toStore(requireOwnWithHours(storeId, userId));
    }

    @Override
    @Transactional
    public StoreResponses.Store update(Long userId, Long storeId,
                                       StoreRequests.UpdateStore request) {
        Vendor store = requireOwnWithHours(storeId, userId);

        // Null means "leave it". A PATCH that blanked absent fields would let a
        // client changing a phone number wipe the returns policy, and nobody
        // would notice until a buyer asked to send something back.
        if (request.storeName() != null && !request.storeName().isBlank()) {
            // The slug deliberately does not follow. It is in links buyers saved.
            store.setStoreName(request.storeName().trim());
        }
        if (request.description() != null) {
            store.setDescription(trimToNull(request.description()));
        }
        if (request.storeEmail() != null) {
            store.setStoreEmail(lower(request.storeEmail()));
        }
        if (request.storePhone() != null) {
            store.setStorePhone(trimToNull(request.storePhone()));
        }
        if (request.website() != null) {
            store.setWebsite(trimToNull(request.website()));
        }
        if (request.returnPolicy() != null) {
            store.setReturnPolicy(trimToNull(request.returnPolicy()));
        }
        if (request.shippingPolicy() != null) {
            store.setShippingPolicy(trimToNull(request.shippingPolicy()));
        }
        if (request.storePolicy() != null) {
            store.setStorePolicy(trimToNull(request.storePolicy()));
        }
        if (request.handlingDays() != null) {
            store.setHandlingDays(request.handlingDays());
        }
        if (request.vacationMode() != null) {
            store.setVacationMode(request.vacationMode());
            if (!request.vacationMode()) {
                store.setVacationMessage(null);
            }
        }
        if (request.vacationMessage() != null) {
            store.setVacationMessage(trimToNull(request.vacationMessage()));
        }
        if (request.pickupAddress() != null) {
            applyPickupAddress(store, request.pickupAddress());
        }
        if (request.operatingHours() != null) {
            applyOperatingHours(store, request.operatingHours());
        }

        Vendor saved = vendors.save(store);
        log.info("[Store] {} updated store {}", userId, storeId);
        return toStore(saved);
    }

    // ── The two locations, resolved separately ───────────────────────────────

    /**
     * What this seller is paid in.
     *
     * <p>Not given the address, and that is the point. Reading a currency off
     * the country a shop stands in is wrong in exactly the case this marketplace
     * is built around: a Gambian seller working out of a warehouse in Dakar
     * banks in Banjul and settles in GMD, and inferring XOF from the address
     * would re-denominate their entire business without telling them.
     *
     * <p>So: what they said, or the platform's base currency — never a guess
     * from a location.
     */
    private String resolveSettlementCurrency(String requested) {
        if (requested == null || requested.isBlank()) {
            return currencies.baseCurrency();
        }
        return currencies.require(requested);
    }

    /**
     * Places the store's address on a map, and records how well.
     *
     * <p>Given the address and nothing else — no currency, no country
     * preference, nothing about the seller's banking. What comes out of here
     * decides distances and collection, and distances are all it may decide.
     *
     * <p>Best effort: most of the country has no street numbering, and a
     * geocoder that cannot find a compound in Brikama must not stop somebody
     * opening a shop. An unplaced address prices delivery from a scope fallback
     * and asks the seller to drop a pin.
     */
    private void placeOnMap(Vendor store, Double givenLat, Double givenLng) {
        if (GeocodingGateway.isValidCoordinate(givenLat, givenLng)) {
            // The seller dragged a pin onto their own shop. That outranks any
            // geocoder's opinion about the address, and is never overwritten.
            store.setLatitude(givenLat);
            store.setLongitude(givenLng);
            store.setGeocodeConfidence(GeocodeConfidence.USER_CONFIRMED);
            store.setGeocodedAt(LocalDateTime.now());
            return;
        }

        Optional<GeoAddress> located = geocoding.forward(
                addressText(store.getAddressStreet(), store.getAddressCity(),
                            store.getAddressState(), store.getAddressPostalCode(),
                            store.getAddressCountryCode()),
                geocodingLanguage);

        if (located.isEmpty()) {
            store.setLatitude(null);
            store.setLongitude(null);
            store.setGeocodeConfidence(GeocodeConfidence.NONE);
            store.setGeocodedAt(null);
            log.info("[Store] Could not place store {} on a map; collection will price from a "
                    + "fallback distance until the seller confirms a pin", store.getStoreSlug());
            return;
        }

        GeoAddress found = located.get();
        store.setLatitude(found.getLatitude());
        store.setLongitude(found.getLongitude());
        store.setGeocodeConfidence(found.getConfidence());
        store.setGeocodedAt(LocalDateTime.now());
    }

    private void applyPickupAddress(Vendor store, StoreRequests.PickupAddress pickup) {
        if (pickup.clear()) {
            store.setPickupStreet(null);
            store.setPickupCity(null);
            store.setPickupState(null);
            store.setPickupPostalCode(null);
            store.setPickupCountryCode(null);
            store.setPickupLatitude(null);
            store.setPickupLongitude(null);
            store.setPickupGeocodeConfidence(null);
            store.setPickupInstructions(null);
            return;
        }

        store.setPickupStreet(trimToNull(pickup.street()));
        store.setPickupCity(trimToNull(pickup.city()));
        store.setPickupState(trimToNull(pickup.state()));
        store.setPickupPostalCode(trimToNull(pickup.postalCode()));
        store.setPickupCountryCode(upper(pickup.countryCode()));
        store.setPickupInstructions(trimToNull(pickup.instructions()));

        if (GeocodingGateway.isValidCoordinate(pickup.latitude(), pickup.longitude())) {
            store.setPickupLatitude(pickup.latitude());
            store.setPickupLongitude(pickup.longitude());
            store.setPickupGeocodeConfidence(GeocodeConfidence.USER_CONFIRMED);
            return;
        }

        Optional<GeoAddress> located = geocoding.forward(
                addressText(pickup.street(), pickup.city(), pickup.state(),
                            pickup.postalCode(), pickup.countryCode()),
                geocodingLanguage);

        store.setPickupLatitude(located.map(GeoAddress::getLatitude).orElse(null));
        store.setPickupLongitude(located.map(GeoAddress::getLongitude).orElse(null));
        store.setPickupGeocodeConfidence(
                located.map(GeoAddress::getConfidence).orElse(GeocodeConfidence.NONE));
    }

    /**
     * Replaces the week outright.
     *
     * <p>A whole week at a time rather than a day at a time, because the thing a
     * seller is actually deciding is "when am I open", and applying that as
     * seven independent edits leaves a window where Tuesday says one thing and
     * Wednesday another.
     */
    private void applyOperatingHours(Vendor store, List<StoreRequests.OperatingHours> requested) {
        for (StoreRequests.OperatingHours day : requested) {
            if (day.closed()) {
                continue;
            }
            if (day.opensAt() == null || day.closesAt() == null) {
                throw new BadRequestException(
                        "Give an opening and a closing time for " + day.day() + ", or mark it closed.");
            }
            if (!day.closesAt().isAfter(day.opensAt())) {
                // Overnight opening is a real thing and this does not model it.
                // Saying so beats storing 22:00–02:00 and dispatching against it.
                throw new BadRequestException(
                        "Closing time must be after opening time on " + day.day()
                        + ". Overnight hours are not supported yet.");
            }
        }

        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
        for (StoreRequests.OperatingHours day : requested) {
            if (!seen.add(day.day())) {
                throw new BadRequestException(day.day() + " is listed twice.");
            }
        }

        store.getOperatingHours().clear();
        requested.stream()
                .sorted(Comparator.comparing(StoreRequests.OperatingHours::day))
                .map(day -> StoreOperatingHours.builder()
                        .vendor(store)
                        .dayOfWeek(day.day())
                        .closed(day.closed())
                        .opensAt(day.closed() ? null : day.opensAt())
                        .closesAt(day.closed() ? null : day.closesAt())
                        .build())
                .forEach(store.getOperatingHours()::add);
    }

    // ── KYC ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public StoreResponses.KycState submitKyc(Long userId, Long storeId,
                                             StoreRequests.SubmitKyc request) {
        Vendor store = requireOwn(storeId, userId);

        if (store.getStatus() == PartnerStatus.SUSPENDED) {
            throw new BadRequestException(
                    "This store is suspended. Documents cannot be resubmitted until that is lifted.");
        }

        LocalDateTime now = LocalDateTime.now();
        for (StoreRequests.KycUpload upload : request.documents()) {
            // A new document of a type retires the previous one rather than
            // overwriting it. The rejected version and the reason it was refused
            // are the record of why onboarding took three weeks.
            kycDocuments.findLiveOfType(store.getId(), upload.type())
                    .ifPresent(previous -> {
                        previous.setSupersededAt(now);
                        kycDocuments.save(previous);
                    });

            kycDocuments.save(KycDocument.builder()
                    .vendor(store)
                    .type(upload.type())
                    .status(KycDocumentStatus.SUBMITTED)
                    .fileUrl(upload.fileUrl().trim())
                    .originalFilename(trimToNull(upload.originalFilename()))
                    .contentType(trimToNull(upload.contentType()))
                    .sizeBytes(upload.sizeBytes())
                    .expiresOn(upload.expiresOn())
                    .build());
        }

        List<KycDocument> live = kycDocuments.findLiveForVendor(store.getId());
        KycStatus status = kycStatusOf(store, live);

        // Complete means it moves to the reviewer's queue. The applicant's part
        // is done, and leaving it in PENDING_KYC would mean a queue of
        // applications nobody is waiting on mixed with ones somebody is.
        if (status == KycStatus.IN_REVIEW && store.getStatus() == PartnerStatus.PENDING_KYC) {
            store.setStatus(PartnerStatus.PENDING);
            vendors.save(store);
            log.info("[Store] Store {} submitted a complete document set and is now awaiting review",
                    store.getId());
        }

        audit.record(AuditAction.VENDOR_KYC_SUBMITTED, "VENDOR", store.getId(),
                store.getStoreName(),
                request.documents().size() + " document(s) submitted",
                // The types, never the storage keys. An audit list is read by
                // more people than the documents themselves should be.
                request.documents().stream().map(upload -> upload.type().name())
                        .collect(Collectors.joining(", ")));

        log.info("[Store] {} submitted {} document(s) for store {} — {}",
                userId, request.documents().size(), storeId, status);

        return toKycState(store, live);
    }

    @Override
    @Transactional(readOnly = true)
    public StoreResponses.KycState kycState(Long userId, Long storeId) {
        Vendor store = requireOwn(storeId, userId);
        return toKycState(store, kycDocuments.findLiveForVendor(storeId));
    }

    /**
     * What this particular store still has to produce.
     *
     * <p>Conditional on what they claim to be, because a single required list
     * excludes most of the sellers this marketplace exists for. A market trader
     * in Serrekunda has a national ID and no registration certificate; demanding
     * one turns them away. A registered company that gave a tax number is asked
     * for the certificate behind it, because they said it existed.
     */
    private static Set<KycDocumentType> requiredFor(Vendor store) {
        Set<KycDocumentType> required = EnumSet.of(KycDocumentType.PROOF_OF_ADDRESS);
        if (notBlank(store.getBusinessRegistrationNumber())) {
            required.add(KycDocumentType.BUSINESS_REGISTRATION);
        }
        if (notBlank(store.getTaxNumber())) {
            required.add(KycDocumentType.TAX_CERTIFICATE);
        }
        return required;
    }

    /** Identity is satisfied by any one of several documents, so it is counted apart. */
    private static boolean hasIdentity(List<KycDocument> live) {
        return live.stream().anyMatch(document -> document.getType().isIdentity());
    }

    private static KycStatus kycStatusOf(Vendor store, List<KycDocument> live) {
        if (live.isEmpty()) {
            return KycStatus.NOT_STARTED;
        }
        // A rejection outranks everything else: the applicant has to act, and
        // telling them they are "in review" while a document is refused is how
        // an application sits untouched for a month.
        if (live.stream().anyMatch(d -> d.getStatus() == KycDocumentStatus.REJECTED)) {
            return KycStatus.ACTION_REQUIRED;
        }
        if (!missingFor(store, live).isEmpty()) {
            return KycStatus.INCOMPLETE;
        }
        return live.stream().allMatch(d -> d.getStatus() == KycDocumentStatus.ACCEPTED)
                ? KycStatus.VERIFIED
                : KycStatus.IN_REVIEW;
    }

    private static List<KycDocumentType> missingFor(Vendor store, List<KycDocument> live) {
        Set<KycDocumentType> present = live.stream()
                .map(KycDocument::getType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(KycDocumentType.class)));

        List<KycDocumentType> missing = new ArrayList<>();
        if (!hasIdentity(live)) {
            // Named as one option rather than all three, so the client can say
            // "an ID, passport or licence" without enumerating a demand.
            missing.add(KycDocumentType.NATIONAL_ID);
        }
        requiredFor(store).stream().filter(type -> !present.contains(type)).forEach(missing::add);
        return missing;
    }

    // ── Staff ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public StoreResponses.StaffList staff(Long userId, Long storeId) {
        Vendor store = requireOwn(storeId, userId);

        List<StoreResponses.StaffMember> members = new ArrayList<>();
        // The owner first, and always present. They are not a row in this table
        // — there is no invitation to accept and no permission to withdraw — but
        // a staff list that omits the person who runs the shop reads as a bug.
        members.add(ownerRow(store));
        staff.findForVendor(storeId).stream().map(StoreServiceImpl::toStaff).forEach(members::add);

        return new StoreResponses.StaffList(members, maxStaff);
    }

    @Override
    @Transactional
    public StoreResponses.StaffMember invite(Long userId, Long storeId,
                                             StoreRequests.InviteStaff request) {
        Vendor store = requireOwn(storeId, userId);
        User owner = store.getUser();
        String email = lower(request.email());

        if (email == null) {
            throw new BadRequestException("An email address is required to invite somebody.");
        }
        if (owner != null && email.equalsIgnoreCase(owner.getEmail())) {
            throw new BadRequestException("You already own this store.");
        }
        if (staff.countLiveForVendor(storeId) >= maxStaff) {
            throw new BadRequestException(
                    "This store has reached its limit of " + maxStaff + " staff.");
        }

        Set<StorePermission> granted = scopePermissions(request.permissions());

        Optional<StoreStaff> existing =
                staff.findByVendorIdAndEmailIgnoreCase(storeId, email);

        StoreStaff member = existing.orElseGet(() -> StoreStaff.builder()
                .vendor(store)
                .email(email)
                .build());

        if (existing.isPresent() && existing.get().getStatus() == StoreStaffStatus.ACTIVE) {
            throw new BadRequestException(
                    "That person already works in this store. Change what they can do instead.");
        }

        member.setDisplayName(trimToNull(request.displayName()));
        member.setPermissions(new LinkedHashSet<>(granted));
        member.setStatus(StoreStaffStatus.INVITED);
        member.setInvitedBy(owner);
        member.setRevokedAt(null);
        member.setAcceptedAt(null);
        member.setInviteExpiresAt(LocalDateTime.now().plus(staffInviteTtl));

        // The token is the credential; only its digest is stored, like every
        // other bearer credential here. A dump of live invitations is a way into
        // other people's shops.
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        member.setInviteTokenHash(sha256(token));

        // Somebody who already has an account is linked now, so the invitation
        // shows up for them the next time they sign in rather than waiting on a
        // mail they may never open.
        users.findByEmailIgnoreCase(email).ifPresent(member::setUser);

        StoreStaff saved = staff.save(member);

        audit.record(AuditAction.VENDOR_STAFF_CHANGED, "VENDOR", storeId, store.getStoreName(),
                "Invited " + email + " to the store", "Granted " + granted);

        log.info("[Store] {} invited {} to store {} with {}",
                userId, email, storeId, granted);

        return toStaff(saved);
    }

    @Override
    @Transactional
    public StoreResponses.StaffMember updateStaff(Long userId, Long storeId, Long staffUserId,
                                                  StoreRequests.UpdateStaff request) {
        Vendor store = requireOwn(storeId, userId);
        refuseIfOwner(store, staffUserId,
                "You own this store; there is nothing to grant yourself.");

        StoreStaff member = requireStaff(storeId, staffUserId);

        if (member.getStatus() == StoreStaffStatus.REVOKED) {
            throw new BadRequestException(
                    "That person's access was removed. Invite them again to restore it.");
        }

        member.setPermissions(new LinkedHashSet<>(scopePermissions(request.permissions())));
        if (request.displayName() != null) {
            member.setDisplayName(trimToNull(request.displayName()));
        }

        audit.record(AuditAction.VENDOR_STAFF_CHANGED, "VENDOR", storeId, store.getStoreName(),
                "Changed what " + member.getEmail() + " may do",
                "Now " + member.getPermissions());

        log.info("[Store] {} rescoped staff {} on store {} to {}",
                userId, staffUserId, storeId, member.getPermissions());

        return toStaff(staff.save(member));
    }

    @Override
    @Transactional
    public void removeStaff(Long userId, Long storeId, Long staffUserId) {
        Vendor store = requireOwn(storeId, userId);
        refuseIfOwner(store, staffUserId,
                "You cannot remove yourself from your own store.");

        StoreStaff member = requireStaff(storeId, staffUserId);

        if (member.getStatus() == StoreStaffStatus.REVOKED) {
            return;   // removing twice is not an error
        }

        member.setStatus(StoreStaffStatus.REVOKED);
        member.setRevokedAt(LocalDateTime.now());
        // The invitation token dies with the access. Otherwise a link sent last
        // week still opens the shop.
        member.setInviteTokenHash(null);
        member.setInviteExpiresAt(null);
        member.getPermissions().clear();
        staff.save(member);

        audit.record(AuditAction.VENDOR_STAFF_CHANGED, "VENDOR", storeId, store.getStoreName(),
                "Removed " + member.getEmail() + " from the store");

        log.info("[Store] {} removed staff {} from store {}", userId, staffUserId, storeId);
    }

    /**
     * Narrows what an owner asked for to what an owner may grant.
     *
     * <p>Today this only substitutes least privilege for an empty request —
     * {@link StorePermission#ownerOnly()} is empty. It exists so that the day
     * somebody adds a grant that must stay with the owner, the place to say so
     * already exists and is already called from both write paths.
     */
    private static Set<StorePermission> scopePermissions(Set<StorePermission> requested) {
        if (requested == null || requested.isEmpty()) {
            return StorePermission.leastPrivilege();
        }
        Set<StorePermission> granted = new LinkedHashSet<>(requested);
        granted.removeAll(StorePermission.ownerOnly());
        return granted.isEmpty() ? StorePermission.leastPrivilege() : granted;
    }

    /**
     * One staff member of this store, addressed the way the client has them.
     *
     * <p>The path says {@code /staff/{userId}} because the owner thinks in terms
     * of people, not rows. But an invitation that has not been accepted has no
     * user at all — the point of inviting by email is that the person may never
     * have used the platform — so addressing it by user id is impossible, and
     * an owner who invited the wrong address would have no way to take it back.
     *
     * <p>So the value resolves as a user id first and, failing that, as the
     * staff row id the invite response returned. The order is fixed rather than
     * ambiguous: an active member always resolves by their user id, and only an
     * outstanding invitation falls through to the second lookup.
     */
    private StoreStaff requireStaff(Long storeId, Long staffUserId) {
        return staff.findByVendorIdAndUserId(storeId, staffUserId)
                .or(() -> staff.findById(staffUserId)
                        .filter(row -> row.getVendor() != null
                                && storeId.equals(row.getVendor().getId())))
                .orElseThrow(() -> new ResourceNotFoundException("Staff member", staffUserId));
    }

    private static void refuseIfOwner(Vendor store, Long staffUserId, String message) {
        if (store.getUser() != null && store.getUser().getId().equals(staffUserId)) {
            throw new BadRequestException(message);
        }
    }

    // ── Payout destination ───────────────────────────────────────────────────

    @Override
    @Transactional
    public StoreResponses.PayoutDestination putBankAccount(Long userId, Long storeId,
                                                           StoreRequests.PutBankAccount request) {
        // Refused before anything is read or validated: on a deployment with no
        // key there is nowhere safe to put an account number, and the answer is
        // to say so rather than to store one in clear.
        crypto.requireConfigured("Payout details");

        Vendor store = requireOwn(storeId, userId);
        User owner = store.getUser();

        // The proof and the act in one transaction. A bearer token says somebody
        // held a credential an hour ago; this is the one operation where that is
        // not good enough, because what it changes is where every future payout
        // for this shop is sent.
        stepUp.verify(owner, request.password(), request.totpCode(),
                "change your payout details");

        String accountNumber = trimToNull(request.accountNumber());
        String iban = trimToNull(request.iban());
        String mobileMoney = trimToNull(request.mobileMoneyPhone());

        if (accountNumber == null && iban == null && mobileMoney == null) {
            throw new BadRequestException(
                    "Give an account number, an IBAN or a mobile-money number.");
        }

        // Replaces rather than appends. A store with a list of old destinations
        // is a store where a payout run has a choice it can get wrong.
        BankAccount destination = bankAccounts.findDefaultForVendor(storeId)
                .orElseGet(() -> BankAccount.builder().vendor(store).isDefault(true).build());

        destination.setAccountType(request.accountType());
        destination.setAccountHolderName(request.accountHolderName().trim());
        // The column is NOT NULL and a mobile-money line has a provider rather
        // than a bank, so the provider stands in. Without this, the commonest
        // payout route in this market — paid to a phone — fails on a constraint
        // at flush time, after the step-up has already been spent.
        destination.setBankName(firstNotBlank(
                request.bankName(), request.mobileMoneyProvider(), "Unnamed institution"));
        destination.setRoutingNumber(trimToNull(request.routingNumber()));
        destination.setSwiftCode(trimToNull(request.swiftCode()));
        destination.setMobileMoneyProvider(trimToNull(request.mobileMoneyProvider()));

        // The encrypted value and its readable tail are set together, so a
        // display field can never be left describing a number that is gone.
        // Empty rather than null: the column predates this surface and is NOT
        // NULL, and a mobile-money destination genuinely has no account number.
        // The encryptor passes an empty value through untouched, and the last-4
        // beside it stays null, so nothing renders "••••" for a number that is
        // not there.
        destination.setAccountNumber(accountNumber == null ? "" : accountNumber);
        destination.setAccountNumberLast4(FieldEncryptionService.last4(accountNumber));
        destination.setIban(iban);
        destination.setIbanLast4(FieldEncryptionService.last4(iban));
        destination.setMobileMoneyPhone(mobileMoney);
        destination.setMobileMoneyLast4(FieldEncryptionService.last4(mobileMoney));

        // The vendor's own currency. Never the buyer's display currency: a
        // payout is denominated in what the seller banks in, whatever the order
        // was charged in.
        destination.setCurrency(store.getSettlementCurrency());

        // Changing the destination always un-verifies it. Otherwise the quickest
        // route to a verified account somebody else controls is to change the
        // number on one that has already been checked.
        destination.setVerified(false);
        destination.setDefault(true);
        destination.setLastChangedBy(owner);
        destination.setLastChangedAt(LocalDateTime.now());

        BankAccount saved = bankAccounts.save(destination);

        // Recorded because this is the single most valuable thing an attacker
        // inside a vendor's account can change, and the first question afterwards
        // is always when it changed. Four digits in the log, never the number.
        audit.record(AuditAction.VENDOR_PAYOUT_DESTINATION_CHANGED, "VENDOR", store.getId(),
                store.getStoreName(),
                "Payout destination changed to " + request.accountType() + " ••••"
                        + Objects.requireNonNullElse(displayTail(saved), "????"),
                "Re-authenticated; account marked unverified pending a fresh check.");

        log.info("[Store] Payout destination for store {} changed by user {} ({} ••••{})",
                storeId, userId, request.accountType(), displayTail(saved));

        return toPayoutDestination(saved);
    }

    private static String displayTail(BankAccount account) {
        return account.getAccountNumberLast4() != null ? account.getAccountNumberLast4()
                : account.getIbanLast4() != null ? account.getIbanLast4()
                : account.getMobileMoneyLast4();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Assembly
    // ─────────────────────────────────────────────────────────────────────────

    private StoreResponses.Store toStore(Vendor store) {
        List<KycDocument> live = kycDocuments.findLiveForVendor(store.getId());
        KycStatus kyc = kycStatusOf(store, live);

        return new StoreResponses.Store(
                store.getId(), store.getStoreName(), store.getStoreSlug(),
                store.getDescription(), store.getStoreEmail(), store.getStorePhone(),
                store.getWebsite(), store.getLogoUrl(), store.getBannerUrl(),
                store.getStatus(), kyc,
                store.getStatus().canTrade() && !store.isVacationMode(),
                blockedReason(store, kyc),
                store.getSettlementCurrency(),
                new StoreResponses.StoreAddress(
                        store.getAddressStreet(), store.getAddressCity(), store.getAddressState(),
                        store.getAddressPostalCode(), store.getAddressCountryCode(),
                        store.getLatitude(), store.getLongitude(),
                        store.getGeocodeConfidence(),
                        confidenceOf(store.getGeocodeConfidence()).needsConfirmation(),
                        confidenceOf(store.getGeocodeConfidence()).isDispatchable(),
                        null, store.getGeocodedAt()),
                pickupAddressOf(store),
                store.getOperatingHours().stream()
                        .sorted(Comparator.comparing(StoreOperatingHours::getDayOfWeek))
                        .map(hours -> new StoreResponses.Hours(
                                hours.getDayOfWeek(), hours.isClosed(),
                                hours.getOpensAt(), hours.getClosesAt()))
                        .toList(),
                store.getReturnPolicy(), store.getShippingPolicy(), store.getStorePolicy(),
                store.getHandlingDays(), store.isVacationMode(), store.getVacationMessage(),
                store.getBusinessRegistrationNumber(), store.getTaxNumber(),
                bankAccounts.findDefaultForVendor(store.getId())
                        .map(StoreServiceImpl::toPayoutDestination).orElse(null),
                (int) staff.countLiveForVendor(store.getId()),
                store.getCreatedAt(), store.getUpdatedAt());
    }

    /** Null when the store collects from its own address, which is the usual case. */
    private static StoreResponses.StoreAddress pickupAddressOf(Vendor store) {
        if (store.getPickupStreet() == null && store.getPickupCity() == null) {
            return null;
        }
        GeocodeConfidence confidence = confidenceOf(store.getPickupGeocodeConfidence());
        return new StoreResponses.StoreAddress(
                store.getPickupStreet(), store.getPickupCity(), store.getPickupState(),
                store.getPickupPostalCode(), store.getPickupCountryCode(),
                store.getPickupLatitude(), store.getPickupLongitude(),
                confidence, confidence.needsConfirmation(), confidence.isDispatchable(),
                store.getPickupInstructions(), null);
    }

    private static GeocodeConfidence confidenceOf(GeocodeConfidence value) {
        return value == null ? GeocodeConfidence.NONE : value;
    }

    /** Why this store cannot sell today, in one line, or null when it can. */
    private static String blockedReason(Vendor store, KycStatus kyc) {
        if (store.isVacationMode()) {
            return "Your store is closed while you are away. Turn vacation mode off to reopen.";
        }
        return switch (store.getStatus()) {
            case PENDING_KYC -> kyc == KycStatus.ACTION_REQUIRED
                    ? "Some of your documents were not accepted. Check the reasons and send them again."
                    : "Send your identity documents to finish opening your store.";
            case PENDING -> "Your documents are with our team. You will hear from us shortly.";
            case SUSPENDED -> "This store is suspended. Contact support.";
            case REJECTED -> "This application was not accepted.";
            case APPROVED, ACTIVE -> null;
        };
    }

    private StoreResponses.KycState toKycState(Vendor store, List<KycDocument> live) {
        List<StoreResponses.KycDoc> documents = live.stream()
                .map(document -> new StoreResponses.KycDoc(
                        document.getId(), document.getType(), document.getStatus(),
                        document.getOriginalFilename(), document.getSizeBytes(),
                        document.getExpiresOn(), document.getRejectionReason(),
                        document.getSubmittedAt(), document.getReviewedAt()))
                .toList();

        // The single most useful thing on this response. An applicant told only
        // "incomplete" re-uploads the document they already sent.
        List<String> actions = live.stream()
                .filter(document -> document.getStatus() == KycDocumentStatus.REJECTED)
                .map(document -> document.getType() + ": "
                        + Objects.requireNonNullElse(document.getRejectionReason(),
                                                     "please send this again"))
                .toList();

        return new StoreResponses.KycState(
                kycStatusOf(store, live), store.getStatus(),
                missingFor(store, live), actions, documents,
                live.stream().map(KycDocument::getSubmittedAt)
                        .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                live.stream().map(KycDocument::getReviewedAt)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
    }

    private static StoreResponses.StaffMember ownerRow(Vendor store) {
        User owner = store.getUser();
        return new StoreResponses.StaffMember(
                null,
                owner == null ? null : owner.getId(),
                owner == null ? null : owner.getEmail(),
                owner == null ? null : (owner.getFirstName() + " " + owner.getLastName()).trim(),
                StoreStaffStatus.ACTIVE,
                EnumSet.allOf(StorePermission.class),
                true,
                store.getCreatedAt(), null, store.getCreatedAt(), null);
    }

    private static StoreResponses.StaffMember toStaff(StoreStaff member) {
        return new StoreResponses.StaffMember(
                member.getId(),
                member.getUser() == null ? null : member.getUser().getId(),
                member.getEmail(), member.getDisplayName(), member.getStatus(),
                member.getPermissions() == null ? Set.of() : Set.copyOf(member.getPermissions()),
                false,
                member.getCreatedAt(), member.getInviteExpiresAt(),
                member.getAcceptedAt(), member.getRevokedAt());
    }

    private static StoreResponses.PayoutDestination toPayoutDestination(BankAccount account) {
        // Four digits and no more. Nothing on this record could be used to send
        // money anywhere, which is what makes it safe in a log or a screenshot.
        return new StoreResponses.PayoutDestination(
                account.getId(), account.getAccountType(), account.getAccountHolderName(),
                account.getBankName(), account.getAccountNumberLast4(), account.getIbanLast4(),
                account.getMobileMoneyLast4(), account.getMobileMoneyProvider(),
                account.getSwiftCode(), account.getCurrency(), account.isVerified(),
                account.getLastChangedAt());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Vendor requireOwn(Long storeId, Long userId) {
        return vendors.findByIdAndOwnerId(storeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
    }

    private Vendor requireOwnWithHours(Long storeId, Long userId) {
        return vendors.findByIdAndOwnerIdWithHours(storeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
    }

    private String uniqueSlug(String storeName) {
        String base = Utils.toSlug(storeName);
        if (base == null || base.isBlank()) {
            base = "store";
        }
        return vendors.existsByStoreSlug(base)
                ? base + "-" + UUID.randomUUID().toString().substring(0, 8)
                : base;
    }

    private static String addressText(String street, String city, String state,
                                      String postalCode, String countryCode) {
        return Stream.of(street, city, state, postalCode, countryCode)
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(", "));
    }

    private static String firstNotBlank(String... candidates) {
        for (String candidate : candidates) {
            String trimmed = trimToNull(candidate);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String lower(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
