package com.sujula.service.admin.impl;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.admin.AdminModerationRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminModerationResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Review;
import com.sujula.model.admin.CommissionRate;
import com.sujula.model.admin.ModerationCase;
import com.sujula.model.admin.Sanction;
import com.sujula.model.aftersales.ReviewReport;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.ModerationCaseStatus;
import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.constant.SanctionType;
import com.sujula.model.products.Product;
import com.sujula.model.store.KycDocument;
import com.sujula.model.user.Payout;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.admin.CommissionRateRepository;
import com.sujula.repository.admin.ModerationCaseRepository;
import com.sujula.repository.admin.SanctionRepository;
import com.sujula.repository.aftersales.ReviewReportRepository;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ReviewRepository;
import com.sujula.repository.store.KycDocumentRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.EmailService;
import com.sujula.service.admin.AdminModerationService;
import com.sujula.service.admin.SanctionRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * The four moderation queues, and the two cascades that live nowhere else.
 *
 * <p>Suspending a store takes its listings down and holds its money, in one
 * place, because a suspension applied in three places is one that gets applied
 * in two. Changing a commission writes an effective-dated row rather than a
 * number, because an order priced under one rate is an order settled under it.
 */
@Slf4j
@Service
public class AdminModerationServiceImpl implements AdminModerationService {

    /** How long a case has before the queue calls it overdue. */
    private static final int CASE_SLA_HOURS = 48;
    private static final int SEVERE_CASE_SLA_HOURS = 8;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final VendorRepository vendors;
    private final ProductRepository products;
    private final ReviewRepository reviews;
    private final ReviewReportRepository reviewReports;
    private final KycDocumentRepository kyc;
    private final PayoutRepository payouts;
    private final CommissionRateRepository commissions;
    private final ModerationCaseRepository cases;
    private final SanctionRepository sanctions;
    private final SanctionRegistry registry;
    private final AuditService audit;
    private final EmailService email;

    public AdminModerationServiceImpl(VendorRepository vendors, ProductRepository products,
                                      ReviewRepository reviews,
                                      ReviewReportRepository reviewReports,
                                      KycDocumentRepository kyc, PayoutRepository payouts,
                                      CommissionRateRepository commissions,
                                      ModerationCaseRepository cases,
                                      SanctionRepository sanctions, SanctionRegistry registry,
                                      AuditService audit, EmailService email) {
        this.vendors = vendors;
        this.products = products;
        this.reviews = reviews;
        this.reviewReports = reviewReports;
        this.kyc = kyc;
        this.payouts = payouts;
        this.commissions = commissions;
        this.cases = cases;
        this.sanctions = sanctions;
        this.registry = registry;
        this.audit = audit;
        this.email = email;
    }

    // ── Stores ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminModerationResponses.StoreRow> stores(
            User staff, String query, PartnerStatus status, String country,
            Boolean payoutsHeld, Pageable pageable) {
        Page<Vendor> page = vendors.adminSearch(blankToNull(query), status, upper(country),
                payoutsHeld, pageable);
        return PagedResponse.of(page.map(this::storeRow));
    }

    @Override
    @Transactional
    public AdminModerationResponses.StoreDecision approveStore(
            User staff, Long vendorId, AdminModerationRequests.ApproveStore request) {
        Vendor vendor = requireVendor(vendorId);

        if (vendor.getStatus() == PartnerStatus.APPROVED) {
            return new AdminModerationResponses.StoreDecision(vendorId, PartnerStatus.APPROVED,
                    0, vendor.arePayoutsHeld(), 0, null, "That store is already approved.");
        }
        if (hasOutstandingKyc(vendorId)) {
            // Approving a store whose documents nobody has read is approving a
            // seller nobody has identified — and the money goes to whoever is
            // behind it.
            throw new BadRequestException(
                    "This store still has documents waiting in the KYC queue. Decide those first: "
                            + "approving a seller nobody has identified is approving whoever is "
                            + "behind the account.");
        }

        vendor.setStatus(PartnerStatus.APPROVED);
        vendors.save(vendor);

        notifyStore(vendor, "APPROVED", request == null ? null : request.note());
        audit.record(AuditAction.STORE_APPROVED, "VENDOR", vendorId, vendor.getStoreName(),
                staff.getEmail() + " approved the store",
                request == null ? null : request.note());

        return new AdminModerationResponses.StoreDecision(vendorId, PartnerStatus.APPROVED,
                0, vendor.arePayoutsHeld(), 0, null,
                "Approved. They can list and sell from now.");
    }

    @Override
    @Transactional
    public AdminModerationResponses.StoreDecision rejectStore(
            User staff, Long vendorId, AdminModerationRequests.RejectStore request) {
        Vendor vendor = requireVendor(vendorId);

        vendor.setStatus(PartnerStatus.REJECTED);
        vendor.setAdminNote(request.reason());
        vendors.save(vendor);

        notifyStore(vendor, "REJECTED", request.reason());
        audit.record(AuditAction.STORE_REJECTED, "VENDOR", vendorId, vendor.getStoreName(),
                staff.getEmail() + " rejected the store application", request.reason());

        return new AdminModerationResponses.StoreDecision(vendorId, PartnerStatus.REJECTED,
                0, vendor.arePayoutsHeld(), 0, null,
                "Rejected, and they have been told why so they can fix it and apply again.");
    }

    @Override
    @Transactional
    public AdminModerationResponses.StoreDecision suspendStore(
            User staff, Long vendorId, AdminModerationRequests.SuspendStore request) {
        Vendor vendor = requireVendor(vendorId);
        boolean holdMoney = request.holdPayouts() == null || request.holdPayouts();
        LocalDateTime now = LocalDateTime.now();

        vendor.setStatus(PartnerStatus.SUSPENDED);
        vendor.setAdminNote(request.reason());

        // The cascade, in one place. A suspension applied in three places is one
        // that gets applied in two.
        int suspended = 0;
        for (Product product : products.findByVendorId(vendorId)) {
            if (product.getStatus() == ProductStatus.PUBLISHED
                    || product.getStatus() == ProductStatus.APPROVED) {
                product.setStatus(ProductStatus.SUSPENDED);
                product.setActive(false);
                products.save(product);
                suspended++;
            }
        }

        int held = 0;
        if (holdMoney) {
            vendor.setPayoutsHeldAt(now);
            vendor.setPayoutsHeldReason(request.reason());
            // Every currency, not one. A store being stopped has money in
            // flight in whatever currencies it sells in, and holding only one
            // of them would pay out the rest.
            for (Payout payout : payouts.findUnsettledForVendor(vendorId)) {
                // Held rather than cancelled, and the difference is a promise:
                // a cancelled payout is over and they must ask again; this one
                // resumes when the hold comes off. The seller earned it.
                payout.setStatus(PayoutStatus.ON_HOLD);
                payout.setFailureReason("Held while the store is suspended: " + request.reason());
                payouts.save(payout);
                held++;
            }
        }
        vendors.save(vendor);

        ModerationCase raised = openCase(staff, ModerationReason.orDefault(request.category()),
                "VENDOR", vendorId, vendor.getStoreName(),
                vendor.getUser(), vendor, "STAFF", request.reason(), null);

        notifyStore(vendor, "SUSPENDED", request.reason());
        audit.record(AuditAction.STORE_SUSPENDED, "VENDOR", vendorId, vendor.getStoreName(),
                staff.getEmail() + " suspended the store — " + suspended + " listing(s) down, "
                        + held + " payout(s) held", request.reason());

        return new AdminModerationResponses.StoreDecision(vendorId, PartnerStatus.SUSPENDED,
                suspended, holdMoney, held, raised.getReference(),
                "Suspended. " + suspended + " listing(s) came down"
                        + (holdMoney
                           ? " and " + held + " payout(s) are held — the money is theirs and is "
                             + "waiting, not cancelled."
                           : ", and their payouts were left running."));
    }

    @Override
    @Transactional
    public AdminModerationResponses.CommissionChanged changeCommission(
            User staff, Long vendorId, AdminModerationRequests.ChangeCommission request) {
        Vendor vendor = requireVendor(vendorId);
        LocalDateTime now = LocalDateTime.now();

        if (request.effectiveFrom().isBefore(now)) {
            // Backdating would re-price orders that have already been settled
            // and paid out, and the money to claw back would have to come from
            // somewhere.
            throw new BadRequestException(
                    "A commission change cannot start in the past. Orders placed before now were "
                            + "priced at the old rate and have been settled at it — changing that "
                            + "would mean taking money back from payouts that have already gone.");
        }

        BigDecimal previous = currentRate(vendorId, now);

        // The open-ended row is closed rather than edited, so "what was I paying
        // in September" still has an answer.
        commissions.findOpenEnded(vendorId).ifPresent(open -> {
            open.setEffectiveUntil(request.effectiveFrom());
            commissions.save(open);
        });

        commissions.save(CommissionRate.builder()
                .vendor(vendor)
                .rate(request.rate())
                .effectiveFrom(request.effectiveFrom())
                .setBy(staff)
                .note(request.note())
                .build());

        // The column on the vendor is a cache of the live row, kept so the
        // common read does not need a join. It is only correct once the new rate
        // starts, so it is only written when the change is immediate.
        if (!request.effectiveFrom().isAfter(now)) {
            vendor.applyDefaultCommissionRate(request.rate());
            vendors.save(vendor);
        }

        audit.record(AuditAction.STORE_COMMISSION_CHANGED, "VENDOR", vendorId,
                vendor.getStoreName(),
                staff.getEmail() + " set the commission to " + request.rate() + "% from "
                        + request.effectiveFrom(),
                request.note());

        return new AdminModerationResponses.CommissionChanged(
                vendorId, previous, request.rate(), request.effectiveFrom(),
                // Always zero, and said rather than left silent, because it is
                // the question the seller asks.
                0, commissionHistory(vendorId, now),
                "From " + request.effectiveFrom() + ". Orders already placed keep the rate they "
                        + "were priced at — this changes nothing that has happened.");
    }

    // ── KYC ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminModerationResponses.KycRow> kycQueue(
            User staff, KycDocumentStatus status, Long vendorId, Pageable pageable) {
        return PagedResponse.of(kyc.findQueue(status, vendorId, pageable).map(this::kycRow));
    }

    @Override
    @Transactional
    public AdminModerationResponses.KycDecision approveKyc(
            User staff, Long documentId, AdminModerationRequests.ApproveKyc request) {
        KycDocument document = requireKyc(documentId);
        requireUndecided(document);

        document.setStatus(KycDocumentStatus.ACCEPTED);
        document.setReviewedBy(staff);
        document.setReviewedAt(LocalDateTime.now());
        kyc.save(document);

        Vendor vendor = document.getVendor();
        PartnerStatus storeStatus = vendor == null ? null : vendor.getStatus();

        // A store waiting on its documents moves on by itself once the last one
        // is approved. Leaving it PENDING_KYC would mean a seller whose papers
        // are all in order waiting for somebody to notice.
        if (vendor != null && vendor.getStatus() == PartnerStatus.PENDING_KYC
                && !hasOutstandingKyc(vendor.getId())) {
            vendor.setStatus(PartnerStatus.PENDING);
            vendors.save(vendor);
            storeStatus = PartnerStatus.PENDING;
        }

        audit.record(AuditAction.KYC_APPROVED, "VENDOR",
                vendor == null ? null : vendor.getId(),
                vendor == null ? "unknown store" : vendor.getStoreName(),
                staff.getEmail() + " approved a " + document.getType() + " document",
                request == null ? null : request.note());

        return new AdminModerationResponses.KycDecision(documentId, KycDocumentStatus.ACCEPTED,
                storeStatus,
                storeStatus == PartnerStatus.PENDING
                        ? "Approved, and that was the last one — the store is now waiting for a "
                          + "decision on the application itself."
                        : "Approved.");
    }

    @Override
    @Transactional
    public AdminModerationResponses.KycDecision rejectKyc(
            User staff, Long documentId, AdminModerationRequests.RejectKyc request) {
        KycDocument document = requireKyc(documentId);
        requireUndecided(document);

        document.setStatus(KycDocumentStatus.REJECTED);
        document.setRejectionReason(request.reason());
        document.setReviewedBy(staff);
        document.setReviewedAt(LocalDateTime.now());
        kyc.save(document);

        Vendor vendor = document.getVendor();
        audit.record(AuditAction.KYC_REJECTED, "VENDOR",
                vendor == null ? null : vendor.getId(),
                vendor == null ? "unknown store" : vendor.getStoreName(),
                staff.getEmail() + " rejected a " + document.getType() + " document",
                request.reason());

        return new AdminModerationResponses.KycDecision(documentId, KycDocumentStatus.REJECTED,
                vendor == null ? null : vendor.getStatus(),
                "Rejected. They have been told what is wrong so they can send a better one — a "
                        + "rejection with no reason is one they cannot act on.");
    }

    // ── Products ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminModerationResponses.ProductRow> productQueue(
            User staff, ProductStatus status, Long vendorId, Pageable pageable) {
        return PagedResponse.of(products
                .findModerationQueue(status == null ? ProductStatus.IN_REVIEW : status,
                        vendorId, pageable)
                .map(this::productRow));
    }

    @Override
    @Transactional
    public AdminModerationResponses.ProductDecision approveProduct(
            User staff, Long productId, AdminModerationRequests.ApproveProduct request) {
        Product product = requireProduct(productId);

        product.setStatus(ProductStatus.PUBLISHED);
        product.setActive(true);
        product.setReviewedBy(staff);
        product.setReviewedAt(LocalDateTime.now());
        product.setPublishedAt(LocalDateTime.now());
        product.setRejectionReason(null);
        products.save(product);

        audit.record(AuditAction.PRODUCT_APPROVED, "PRODUCT", productId, product.getName(),
                staff.getEmail() + " approved the listing",
                request == null ? null : request.note());

        return new AdminModerationResponses.ProductDecision(productId, ProductStatus.PUBLISHED,
                null, "Published. It is on the site now.");
    }

    @Override
    @Transactional
    public AdminModerationResponses.ProductDecision rejectProduct(
            User staff, Long productId, AdminModerationRequests.RejectProduct request) {
        Product product = requireProduct(productId);

        product.setStatus(ProductStatus.REJECTED);
        product.setActive(false);
        product.setReviewedBy(staff);
        product.setReviewedAt(LocalDateTime.now());
        // The reason code and the words together: the code is what the platform
        // reports on, the words are what the seller can act on.
        product.setRejectionReason(request.reason() + ": " + request.detail());
        products.save(product);

        audit.record(AuditAction.PRODUCT_REJECTED, "PRODUCT", productId, product.getName(),
                staff.getEmail() + " rejected the listing (" + request.reason() + ")",
                request.detail());

        return new AdminModerationResponses.ProductDecision(productId, ProductStatus.REJECTED,
                null, "Rejected. The seller can fix it and send it back for review.");
    }

    @Override
    @Transactional
    public AdminModerationResponses.ProductDecision suspendProduct(
            User staff, Long productId, AdminModerationRequests.SuspendProduct request) {
        Product product = requireProduct(productId);

        product.setStatus(ProductStatus.SUSPENDED);
        product.setActive(false);
        product.setRejectionReason(request.reason() + ": " + request.detail());
        products.save(product);

        // A suspension is a policy decision, so it gets a case whether or not
        // anybody asked for one. Without it there is no record to answer "why
        // has this seller had four listings taken down".
        ModerationCase raised = openCase(staff, request.reason(), "PRODUCT", productId,
                product.getName() + (product.getVendor() == null ? ""
                        : " (" + product.getVendor().getStoreName() + ")"),
                product.getVendor() == null ? null : product.getVendor().getUser(),
                product.getVendor(), "STAFF", request.detail(), null);

        if (Boolean.TRUE.equals(request.openCaseAgainstSeller())
                && product.getVendor() != null) {
            openCase(staff, request.reason(), "VENDOR", product.getVendor().getId(),
                    product.getVendor().getStoreName(), product.getVendor().getUser(),
                    product.getVendor(), "STAFF",
                    "Raised alongside the suspension of listing " + productId + ": "
                            + request.detail(), null);
        }

        audit.record(AuditAction.PRODUCT_SUSPENDED, "PRODUCT", productId, product.getName(),
                staff.getEmail() + " suspended the listing (" + request.reason() + ")",
                request.detail());

        return new AdminModerationResponses.ProductDecision(productId, ProductStatus.SUSPENDED,
                raised.getReference(),
                "Taken down, and a case raised so there is a record of why.");
    }

    @Override
    @Transactional
    public AdminModerationResponses.ProductDecision createProduct(
            User staff, AdminModerationRequests.CreateProduct request) {
        Vendor vendor = requireVendor(request.vendorId());
        if (vendor.getStatus() != PartnerStatus.APPROVED) {
            throw new BadRequestException(
                    "That store is " + readable(vendor.getStatus()) + ", so it cannot have "
                            + "listings — not even ones added on its behalf.");
        }

        Product product = Product.builder()
                .name(request.name().trim())
                .slug(slugify(request.name()) + "-" + shortCode())
                .description(request.description())
                .price(request.price())
                .priceCurrency(upper(request.priceCurrency()))
                .stock(request.stock() == null ? 0 : request.stock())
                .vendor(vendor)
                .country(vendor.getPickupCountryCode())
                // Published straight away rather than queued: an administrator
                // creating a listing has already made the decision the queue
                // exists to make, and leaving it IN_REVIEW would mean waiting
                // for themselves.
                .status(ProductStatus.PUBLISHED)
                .active(true)
                .publishedAt(LocalDateTime.now())
                .reviewedBy(staff)
                .reviewedAt(LocalDateTime.now())
                .build();
        Product saved = products.save(product);

        audit.record(AuditAction.PRODUCT_CREATED_BY_ADMIN, "PRODUCT", saved.getId(),
                saved.getName(),
                staff.getEmail() + " listed this on behalf of " + vendor.getStoreName(),
                request.reason());

        return new AdminModerationResponses.ProductDecision(saved.getId(), ProductStatus.PUBLISHED,
                null, "Listed on their behalf and published. It is on their store now.");
    }

    @Override
    @Transactional
    public AdminModerationResponses.ProductDecision patchProduct(
            User staff, Long productId, AdminModerationRequests.PatchProduct request) {
        Product product = requireProduct(productId);
        List<String> changed = new ArrayList<>();

        if (request.name() != null && !request.name().equals(product.getName())) {
            product.setName(request.name().trim());
            changed.add("name");
        }
        if (request.description() != null) {
            product.setDescription(request.description());
            changed.add("description");
        }
        if (request.price() != null && request.price().compareTo(product.getPrice()) != 0) {
            product.setPrice(request.price());
            changed.add("price");
        }
        if (request.stock() != null && !request.stock().equals(product.getStock())) {
            product.setStock(request.stock());
            changed.add("stock");
        }

        if (changed.isEmpty()) {
            return new AdminModerationResponses.ProductDecision(productId, product.getStatus(),
                    null, "Nothing changed.");
        }
        products.save(product);

        audit.record(AuditAction.PRODUCT_EDITED_BY_ADMIN, "PRODUCT", productId, product.getName(),
                staff.getEmail() + " edited " + String.join(", ", changed)
                        + " on somebody else's listing",
                request.reason());

        return new AdminModerationResponses.ProductDecision(productId, product.getStatus(), null,
                "Changed " + String.join(", ", changed)
                        + ". The seller can see the edit in their own listing.");
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminModerationResponses.ReviewRow> reviewQueue(
            User staff, Boolean hiddenOnly, Pageable pageable) {
        return PagedResponse.of(reviews.findReported(hiddenOnly, pageable).map(this::reviewRow));
    }

    @Override
    @Transactional
    public AdminModerationResponses.ReviewDecision publishReview(
            User staff, Long reviewId, AdminModerationRequests.ModerateReview request) {
        Review review = requireReview(reviewId);

        review.setHiddenAt(null);
        reviews.save(review);
        int cleared = closeReports(review, staff, request.note());

        audit.record(AuditAction.REVIEW_PUBLISHED, "REVIEW", reviewId,
                "Review on " + productNameOf(review),
                staff.getEmail() + " left the review up", request.note());

        return new AdminModerationResponses.ReviewDecision(reviewId, true, cleared,
                "The review stays up. " + cleared + " report(s) closed — reviews come down when "
                        + "they break a rule, not when somebody disagrees with them.");
    }

    @Override
    @Transactional
    public AdminModerationResponses.ReviewDecision rejectReview(
            User staff, Long reviewId, AdminModerationRequests.ModerateReview request) {
        Review review = requireReview(reviewId);
        if (request.reason() == null) {
            // Taking a review down needs a rule it broke. "A seller objected" is
            // not a rule, and a marketplace that removed reviews on request
            // would have none worth reading.
            throw new BadRequestException(
                    "Say which rule it breaks. A review is not taken down because somebody "
                            + "disagrees with it — that is what the reply is for.");
        }

        review.setHiddenAt(LocalDateTime.now());
        reviews.save(review);
        int cleared = closeReports(review, staff, request.note());

        audit.record(AuditAction.REVIEW_REJECTED, "REVIEW", reviewId,
                "Review on " + productNameOf(review),
                staff.getEmail() + " took the review down (" + request.reason() + ")",
                request.note());

        return new AdminModerationResponses.ReviewDecision(reviewId, false, cleared,
                "Taken down. The rating arithmetic no longer counts it, and the row survives so "
                        + "the decision can be explained.");
    }

    // ── Cases ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminModerationResponses.CaseRow> cases(
            User staff, ModerationCaseStatus status, ModerationReason reason, Long assigneeId,
            Pageable pageable) {
        return PagedResponse.of(cases.findQueue(status, reason, assigneeId, pageable)
                .map(this::caseRow));
    }

    @Override
    @Transactional
    public AdminModerationResponses.CaseResolved resolveCase(
            User staff, Long caseId, AdminModerationRequests.ResolveCase request) {
        ModerationCase row = cases.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Case", caseId));
        if (!row.getStatus().isOpen()) {
            throw new BadRequestException("That case has already been decided.");
        }

        boolean upheld = Boolean.TRUE.equals(request.upheld());
        Sanction issued = null;

        if (upheld && request.sanctionType() != null) {
            if (row.getAccountable() == null) {
                throw new BadRequestException(
                        "There is nobody on this case to sanction. It names a "
                                + row.getSubjectType().toLowerCase(Locale.ROOT)
                                + " but no account answerable for it.");
            }
            issued = registry.issue(row.getAccountable(), Sanction.builder()
                    .type(request.sanctionType())
                    .reason(row.getReason())
                    .reasonText(request.note())
                    .restrictedPermission(request.restrictedPermission())
                    .expiresAt(expiryFor(request))
                    .issuedBy(staff)
                    .build());
            registry.linkToCase(issued, row);
            audit.record(AuditAction.SANCTION_ISSUED, "USER", row.getAccountable().getId(),
                    row.getAccountable().getEmail(),
                    staff.getEmail() + " issued a " + request.sanctionType() + " from case "
                            + row.getReference(), request.note());
        }

        row.setStatus(upheld ? ModerationCaseStatus.RESOLVED : ModerationCaseStatus.DISMISSED);
        row.setOutcome(upheld ? "UPHELD" : "DISMISSED");
        row.setResolutionNote(request.note());
        row.setResolvedBy(staff);
        row.setResolvedAt(LocalDateTime.now());
        row.setSanction(issued);
        cases.save(row);

        audit.record(AuditAction.MODERATION_CASE_RESOLVED, "CASE", caseId, row.getReference(),
                staff.getEmail() + " " + (upheld ? "upheld" : "dismissed") + " the case",
                request.note());

        boolean locked = issued != null && issued.locksTheAccount();
        return new AdminModerationResponses.CaseResolved(caseId, row.getReference(),
                row.getStatus(), row.getOutcome(),
                issued == null ? null : issued.getType(),
                issued == null ? null : issued.getExpiresAt(),
                locked,
                issued == null
                        // Said plainly, because it is the commonest outcome and
                        // a queue that produced a punishment every time would be
                        // a queue producing them because it exists.
                        ? (upheld ? "Upheld, with no sanction. The record stands."
                                  : "Dismissed. Nothing happens to the account.")
                        : "A " + readable(issued.getType()) + " was issued"
                          + (locked ? " and the account is locked." : "."));
    }

    private static LocalDateTime expiryFor(AdminModerationRequests.ResolveCase request) {
        if (request.sanctionType() == null || !request.sanctionType().expires()) {
            return null;
        }
        if (request.suspensionDays() == null) {
            throw new BadRequestException(
                    "A " + readable(request.sanctionType()) + " needs a number of days. Without "
                            + "one there is nothing to tell the person about when they get their "
                            + "account back.");
        }
        return LocalDateTime.now().plusDays(request.suspensionDays());
    }

    // ── Cases: raising them ──────────────────────────────────────────────────

    /**
     * Raises a case, or joins the one already open about the same thing.
     *
     * <p>Joining rather than raising a second is what keeps the queue about
     * subjects instead of about reports: three people reporting one listing is
     * one thing to decide, not three.
     */
    private ModerationCase openCase(User staff, ModerationReason reason, String subjectType,
                                    Long subjectId, String subjectLabel, User accountable,
                                    Vendor vendor, String source, String description,
                                    String evidence) {
        return cases.findOpenAbout(subjectType, subjectId).orElseGet(() -> {
            LocalDateTime now = LocalDateTime.now();
            ModerationCase row = ModerationCase.builder()
                    .reference(newCaseReference())
                    .status(ModerationCaseStatus.OPEN)
                    .reason(reason)
                    .subjectType(subjectType)
                    .subjectId(subjectId)
                    // Carried rather than joined: it still reads correctly after
                    // the listing is deleted, and a case about nothing is a
                    // case nobody can decide.
                    .subjectLabel(subjectLabel)
                    .accountable(accountable)
                    .vendor(vendor)
                    .raisedBy(staff)
                    .source(source)
                    .description(description)
                    .evidenceUrls(evidence)
                    // Frozen here. A deadline recomputed from today's policy
                    // would move every time the policy did, and a queue sorted
                    // by a moving deadline is one where the oldest case is never
                    // the most urgent.
                    .dueBy(now.plusHours(reason.isSevere() ? SEVERE_CASE_SLA_HOURS
                                                           : CASE_SLA_HOURS))
                    .build();
            ModerationCase saved = cases.save(row);
            audit.record(AuditAction.MODERATION_CASE_RAISED, subjectType, subjectId, subjectLabel,
                    (staff == null ? "the platform" : staff.getEmail())
                            + " raised case " + saved.getReference() + " (" + reason + ")",
                    description);
            return saved;
        });
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private Vendor requireVendor(Long vendorId) {
        return vendors.findById(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", vendorId));
    }

    private Product requireProduct(Long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private Review requireReview(Long reviewId) {
        return reviews.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId));
    }

    private KycDocument requireKyc(Long documentId) {
        return kyc.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    private static void requireUndecided(KycDocument document) {
        if (document.getStatus() == KycDocumentStatus.ACCEPTED
                || document.getStatus() == KycDocumentStatus.REJECTED) {
            throw new BadRequestException(
                    "That document was already " + readable(document.getStatus()) + " on "
                            + document.getReviewedAt() + ". If that was wrong, ask the seller to "
                            + "upload it again — a decision is not edited.");
        }
        if (document.getSupersededAt() != null) {
            // The seller has already replaced it. Deciding the old one would
            // refuse them for a picture they had themselves improved on.
            throw new BadRequestException(
                    "The seller has replaced that document with a newer one. Decide that instead.");
        }
    }

    private boolean hasOutstandingKyc(Long vendorId) {
        return kyc.findQueue(KycDocumentStatus.SUBMITTED, vendorId,
                org.springframework.data.domain.PageRequest.of(0, 1)).hasContent();
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private AdminModerationResponses.StoreRow storeRow(Vendor vendor) {
        return new AdminModerationResponses.StoreRow(
                vendor.getId(), vendor.getStoreName(), vendor.getStoreSlug(), vendor.getStatus(),
                vendor.getUser() == null ? null : vendor.getUser().getEmail(),
                vendor.getPickupCountryCode(), vendor.getSettlementCurrency(),
                currentRate(vendor.getId(), LocalDateTime.now()),
                vendor.arePayoutsHeld(), vendor.getPayoutsHeldReason(), vendor.getPayoutsHeldAt(),
                (int) products.countLiveForVendor(vendor.getId()),
                vendor.getUser() == null ? 0
                        : cases.findOpenAgainst(vendor.getUser().getId()).size(),
                vendor.getCreatedAt());
    }

    /**
     * What this store is charged right now, from the effective-dated rows.
     *
     * <p>Falls back to the cached column and then to the platform default, in
     * that order — a store that predates the commission table has no row and is
     * still being charged something.
     */
    private BigDecimal currentRate(Long vendorId, LocalDateTime at) {
        List<CommissionRate> applicable = commissions.findApplicable(vendorId, at);
        if (!applicable.isEmpty()) {
            return applicable.get(0).getRate();
        }
        return vendors.findById(vendorId)
                .map(Vendor::getDefaultCommissionRate)
                .orElse(BigDecimal.TEN);
    }

    private List<AdminModerationResponses.CommissionRow> commissionHistory(Long vendorId,
                                                                           LocalDateTime now) {
        List<AdminModerationResponses.CommissionRow> rows = new ArrayList<>();
        for (CommissionRate rate : commissions.findHistory(vendorId)) {
            rows.add(new AdminModerationResponses.CommissionRow(
                    rate.getRate(), rate.getEffectiveFrom(), rate.getEffectiveUntil(),
                    rate.getSetBy() == null ? "the platform" : rate.getSetBy().getEmail(),
                    rate.getNote(), rate.appliesAt(now)));
        }
        return rows;
    }

    private AdminModerationResponses.KycRow kycRow(KycDocument document) {
        Vendor vendor = document.getVendor();
        boolean completes = vendor != null
                && document.getStatus() == KycDocumentStatus.SUBMITTED
                && kyc.findQueue(KycDocumentStatus.SUBMITTED, vendor.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 2))
                   .getTotalElements() == 1;

        return new AdminModerationResponses.KycRow(
                document.getId(),
                vendor == null ? null : vendor.getId(),
                vendor == null ? null : vendor.getStoreName(),
                document.getType() == null ? null : document.getType().name(),
                document.getStatus(), document.getFileUrl(), document.getOriginalFilename(),
                document.getExpiresOn(), document.getSubmittedAt(), document.getReviewedAt(),
                document.getReviewedBy() == null ? null : document.getReviewedBy().getEmail(),
                document.getRejectionReason(),
                completes);
    }

    private AdminModerationResponses.ProductRow productRow(Product product) {
        Vendor vendor = product.getVendor();
        return new AdminModerationResponses.ProductRow(
                product.getId(), product.getName(), product.getStatus(),
                vendor == null ? null : vendor.getId(),
                vendor == null ? null : vendor.getStoreName(),
                product.getPrice(), product.getPriceCurrency(), product.getStock(),
                null,
                product.getSubmittedForReviewAt(), product.getRejectionReason(),
                cases.findOpenAbout("PRODUCT", product.getId()).isPresent() ? 1 : 0);
    }

    private AdminModerationResponses.ReviewRow reviewRow(Review review) {
        Set<String> why = new LinkedHashSet<>();
        for (ReviewReport report : reviewReports.findAll()) {
            if (report.getReview() != null && report.getReview().getId().equals(review.getId())) {
                why.add(report.getReason().name());
            }
        }
        return new AdminModerationResponses.ReviewRow(
                review.getId(),
                review.getProduct() == null ? null : review.getProduct().getId(),
                productNameOf(review),
                review.getProduct() == null || review.getProduct().getVendor() == null
                        ? null : review.getProduct().getVendor().getStoreName(),
                review.getRating() == null ? 0 : review.getRating(),
                review.getTitle(), review.getComment(),
                // A first name. A moderator does not need a surname to decide
                // whether a review breaks a rule.
                review.getUser() == null ? null : review.getUser().getFirstName(),
                review.isVerified(),
                review.getReportCount() == null ? 0 : review.getReportCount(),
                List.copyOf(why),
                review.getHiddenAt() != null,
                review.getCreatedAt());
    }

    private AdminModerationResponses.CaseRow caseRow(ModerationCase row) {
        LocalDateTime now = LocalDateTime.now();
        return new AdminModerationResponses.CaseRow(
                row.getId(), row.getReference(), row.getStatus(),
                row.getReason() == null ? null : row.getReason().name(),
                row.getSubjectType(), row.getSubjectId(), row.getSubjectLabel(),
                row.getAccountable() == null ? null : row.getAccountable().getEmail(),
                row.getVendor() == null ? null : row.getVendor().getStoreName(),
                row.getSource(),
                row.getRaisedBy() == null ? "the platform" : row.getRaisedBy().getEmail(),
                row.getAssignedTo() == null ? null : row.getAssignedTo().getEmail(),
                row.getDueBy(), row.isOverdue(now),
                // How many times this account has been here before. A fourth
                // report is a different thing from a first, and an administrator
                // should not have to go and look.
                row.getAccountable() == null ? 0
                        : sanctions.countHistory(row.getAccountable().getId()),
                row.getCreatedAt());
    }

    // ── Small things ─────────────────────────────────────────────────────────

    private int closeReports(Review review, User staff, String note) {
        int cleared = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ReviewReport report : reviewReports.findAll()) {
            if (report.getReview() != null && report.getReview().getId().equals(review.getId())
                    && report.getReviewedAt() == null) {
                report.setReviewedAt(now);
                report.setReviewedBy(staff);
                report.setModeratorNote(note);
                reviewReports.save(report);
                cleared++;
            }
        }
        return cleared;
    }

    private void notifyStore(Vendor vendor, String status, String reason) {
        if (vendor.getUser() == null || vendor.getUser().getEmail() == null) {
            return;
        }
        try {
            email.sendVendorStatusChangeEmail(vendor.getUser().getEmail(), vendor.getStoreName(),
                    status, reason == null ? "" : reason);
        } catch (RuntimeException failed) {
            // The decision is recorded either way. A send that failed is a
            // support problem, not a reason to leave a store un-approved.
            log.warn("[Admin] Could not tell store {} it was {}: {}",
                    vendor.getId(), status, failed.toString());
        }
    }

    private static String productNameOf(Review review) {
        return review.getProduct() == null ? "a deleted product" : review.getProduct().getName();
    }

    private static String readable(Enum<?> value) {
        return value == null ? "unknown"
                : value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return slug.replaceAll("(^-|-$)", "");
    }

    private static String shortCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
        }
        return code.toString().toLowerCase(Locale.ROOT);
    }

    private String newCaseReference() {
        for (int attempt = 0; attempt < 6; attempt++) {
            StringBuilder code = new StringBuilder("CASE-");
            for (int i = 0; i < 10; i++) {
                code.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
            }
            String candidate = code.toString();
            if (!cases.existsByReference(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not mint a unique case reference");
    }
}
