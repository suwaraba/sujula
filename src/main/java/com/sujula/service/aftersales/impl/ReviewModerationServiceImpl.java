package com.sujula.service.aftersales.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Review;
import com.sujula.model.aftersales.ReviewReport;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.ReviewRepository;
import com.sujula.repository.aftersales.ReviewReportRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.aftersales.ContactDetailFilter;
import com.sujula.service.aftersales.ReviewModerationService;

import lombok.extern.slf4j.Slf4j;

/**
 * Editing, withdrawing, answering and reporting a review.
 *
 * <p>Ownership is in the query on every write: the author's id for an edit, the
 * seller's for a reply. A review belonging to somebody else is not found rather
 * than refused, for the usual reason — "forbidden" confirms a guessed id names a
 * live row.
 */
@Slf4j
@Service
public class ReviewModerationServiceImpl implements ReviewModerationService {

    /**
     * How long the author has to change or withdraw it.
     *
     * <p>Long enough to fix a mistake or add what arrived a day later; short
     * enough that a seller cannot offer to settle in exchange for a rewrite, and
     * that nobody is negotiating over a review a month after the sale.
     */
    private static final Duration EDIT_WINDOW = Duration.ofHours(48);

    private final ReviewRepository reviews;
    private final ReviewReportRepository reports;
    private final VendorRepository vendors;
    private final UserRepository users;
    private final ContactDetailFilter filter;

    public ReviewModerationServiceImpl(ReviewRepository reviews, ReviewReportRepository reports,
                                       VendorRepository vendors, UserRepository users,
                                       ContactDetailFilter filter) {
        this.reviews = reviews;
        this.reports = reports;
        this.vendors = vendors;
        this.users = users;
        this.filter = filter;
    }

    // ── The author ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReviewView edit(Long userId, Long reviewId,
                                               AfterSalesRequests.EditReview request) {
        Review review = requireAuthor(reviewId, userId);
        requireInsideTheWindow(review, "change");

        boolean changed = false;
        if (request.rating() != null && !request.rating().equals(review.getRating())) {
            review.setRating(request.rating());
            changed = true;
        }
        if (request.title() != null && !request.title().equals(review.getTitle())) {
            review.setTitle(request.title());
            changed = true;
        }
        if (request.comment() != null && !request.comment().equals(review.getComment())) {
            // The same filter the messages surface uses. A review is a public
            // page, so a telephone number in one is published rather than sent
            // to one person — and the usual way one gets there is a seller
            // asking the buyer to put it in.
            ContactDetailFilter.Result cleaned = filter.clean(request.comment());
            review.setComment(cleaned.cleaned());
            changed = true;
        }

        if (!changed) {
            // Nothing to record. Stamping editedAt for a request that changed
            // nothing would show "edited" beside a review nobody touched.
            return viewOf(review);
        }

        review.setEditedAt(LocalDateTime.now());
        review.setEditCount((review.getEditCount() == null ? 0 : review.getEditCount()) + 1);
        reviews.save(review);

        log.info("[Reviews] {} edited by its author (edit {})", reviewId, review.getEditCount());
        return viewOf(review);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReviewDeleted delete(Long userId, Long reviewId) {
        Review review = requireAuthor(reviewId, userId);
        requireInsideTheWindow(review, "withdraw");

        // Soft, because the review is attached to a product's average rating and
        // — once the seller has answered it — to somebody else's words. Removing
        // the row would take the reply with it and leave the arithmetic
        // describing a review nobody can read.
        review.setDeletedAt(LocalDateTime.now());
        reviews.save(review);

        log.info("[Reviews] {} withdrawn by its author", reviewId);
        return new AfterSalesResponses.ReviewDeleted(reviewId,
                "Your review has been taken down. It no longer counts towards the product's "
                        + "rating.");
    }

    // ── The seller ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReviewView reply(Long userId, Long reviewId,
                                                AfterSalesRequests.ReplyToReview request) {
        Review review = requireSellerOfTheProduct(reviewId, userId);

        if (review.getVendorReply() != null && !review.getVendorReply().isBlank()) {
            throw new BadRequestException(
                    "You have already answered this review, and there is one answer per review. "
                            + "If there is more to sort out, message the buyer about the order "
                            + "instead — that conversation is private and this one is not.");
        }
        if (!review.isVisible()) {
            throw new BadRequestException("This review is no longer shown, so there is nothing to "
                    + "answer.");
        }

        // Filtered, because a reply is published under a review that anybody
        // reading the product page will see. "Call me on 3100077 and I will sort
        // it" is the single most common way a seller moves somebody off the
        // platform, and it is the one place it would be read by everybody.
        ContactDetailFilter.Result cleaned = filter.clean(request.body());
        review.setVendorReply(cleaned.cleaned());
        review.setVendorRepliedAt(LocalDateTime.now());
        reviews.save(review);

        log.info("[Reviews] {} answered by the seller{}", reviewId,
                cleaned.changed() ? " (contact details removed)" : "");
        return viewOf(review);
    }

    // ── Anybody ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ReviewReported report(Long userId, Long reviewId,
                                                     AfterSalesRequests.ReportReview request) {
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId));

        if (review.getUser() != null && userId.equals(review.getUser().getId())) {
            throw new BadRequestException(
                    "This is your own review. You can change it or take it down instead.");
        }

        Optional<ReviewReport> already = reports.findByReviewIdAndReportedById(reviewId, userId);
        if (already.isPresent()) {
            // Not an error. Somebody who reports twice is telling us the same
            // thing twice, and refusing loudly would only send them to support.
            return new AfterSalesResponses.ReviewReported(reviewId,
                    review.getReportCount() == null ? 1 : review.getReportCount(),
                    "You have already reported this review. Somebody will read it.");
        }

        reports.save(ReviewReport.builder()
                .review(review)
                .reportedBy(users.findById(userId).orElseThrow(
                        () -> new ResourceNotFoundException("User", userId)))
                .reason(request.reason())
                .detail(request.detail())
                .build());

        // Recounted rather than incremented, like every other summary here. The
        // table already refuses a second report from the same person, so this
        // counts people rather than complaints.
        int counted = (int) reports.countForReview(reviewId);
        review.setReportCount(counted);
        reviews.save(review);

        log.info("[Reviews] {} reported as {} by user {} — now {} report(s)",
                reviewId, request.reason(), userId, counted);

        return new AfterSalesResponses.ReviewReported(reviewId, counted,
                // Said plainly, because a reporter left to assume it was removed
                // reports again — and a seller who believes reporting works
                // reports every review below four stars.
                "Thank you. Somebody will read it. The review stays up while they do: we take "
                        + "reviews down when they break a rule, not when somebody disagrees with "
                        + "them.");
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private Review requireAuthor(Long reviewId, Long userId) {
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId));
        if (review.getUser() == null || !userId.equals(review.getUser().getId())) {
            throw new ResourceNotFoundException("Review", reviewId);
        }
        if (review.getDeletedAt() != null) {
            throw new BadRequestException("You have already taken this review down.");
        }
        return review;
    }

    /**
     * The seller whose product this review is about.
     *
     * <p>Resolved through the product's vendor rather than trusted from
     * anywhere: a seller replying to a review on somebody else's listing would
     * be putting words under a competitor's product.
     */
    private Review requireSellerOfTheProduct(Long reviewId, Long userId) {
        Vendor vendor = vendors.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId));
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId));

        Product product = review.getProduct();
        boolean theirs = product != null && product.getVendor() != null
                && vendor.getId().equals(product.getVendor().getId());
        if (!theirs) {
            throw new ResourceNotFoundException("Review", reviewId);
        }
        return review;
    }

    private static void requireInsideTheWindow(Review review, String verb) {
        LocalDateTime written = review.getCreatedAt();
        if (written == null) {
            return;
        }
        LocalDateTime closes = written.plus(EDIT_WINDOW);
        if (closes.isBefore(LocalDateTime.now())) {
            throw new BadRequestException(
                    "The " + EDIT_WINDOW.toHours() + "-hour window to " + verb + " this review "
                            + "closed on " + closes + ". It stays as you wrote it — which is also "
                            + "why nobody can talk you into rewriting one later.");
        }
    }

    // ── View ─────────────────────────────────────────────────────────────────

    private static AfterSalesResponses.ReviewView viewOf(Review review) {
        long hoursLeft = 0;
        if (review.getCreatedAt() != null) {
            long remaining = Duration.between(LocalDateTime.now(),
                    review.getCreatedAt().plus(EDIT_WINDOW)).toHours();
            hoursLeft = Math.max(0, remaining);
        }
        return new AfterSalesResponses.ReviewView(
                review.getId(),
                review.getProduct() == null ? null : review.getProduct().getId(),
                review.getProduct() == null ? null : review.getProduct().getName(),
                review.getRating() == null ? 0 : review.getRating(),
                review.getTitle(), review.getComment(),
                review.isVerified(),
                review.getVendorReply(), review.getVendorRepliedAt(),
                review.getEditedAt(), review.getEditCount() == null ? 0 : review.getEditCount(),
                hoursLeft,
                review.getCreatedAt());
    }
}
