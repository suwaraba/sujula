package com.sujula.service.vendorcatalogue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.products.Product;

/**
 * The only thing allowed to move a listing between states.
 *
 * <p>It exists because of one denormalised column. Every public catalogue query
 * in this system filters on {@code Product.active}, and the moderation ladder
 * lives in {@code Product.status}. Two fields describing the same fact will
 * disagree the first time somebody sets one without the other, and the way they
 * disagree is a suspended listing that carries on selling.
 *
 * <p>So {@code active} has exactly one writer, here, and it is always derived:
 * {@code active == (status == PUBLISHED)}, with no path that sets it directly.
 * Rewriting every catalogue query to read the enum would be the purer fix and a
 * much larger change to the part of the codebase under the most load; this is
 * the small one, and what makes it safe is that the invariant is checkable and
 * checked.
 *
 * <p>The transitions themselves are enforced from
 * {@link ProductStatus#allowedNext()} rather than by a chain of ifs at each call
 * site, so adding a state means editing the enum and nothing else.
 */
@Component
public class ProductLifecycle {

    /** Separates fields in the content digest, so "ab|c" and "a|bc" differ. */
    private static final String FIELD_SEPARATOR = "\u001f";

    /**
     * Moves a listing, or explains why it cannot move.
     *
     * <p>Refuses rather than no-ops on an impossible transition. "Publish" on a
     * suspended listing is a seller trying to undo a moderator, and answering it
     * with silence would leave them believing it worked.
     */
    public void transition(Product product, ProductStatus to) {
        ProductStatus from = product.getStatus();

        if (from == to) {
            return;   // asking twice is not an error
        }
        if (!from.allowedNext().contains(to)) {
            throw new BadRequestException(refusal(from, to));
        }

        LocalDateTime now = LocalDateTime.now();
        switch (to) {
            case IN_REVIEW -> product.setSubmittedForReviewAt(now);
            case PUBLISHED -> {
                // First time only. A relist is not a new listing, and resetting
                // this would make "new arrivals" a list of old stock.
                if (product.getPublishedAt() == null) {
                    product.setPublishedAt(now);
                }
                product.setUnpublishedAt(null);
            }
            case UNPUBLISHED -> product.setUnpublishedAt(now);
            case ARCHIVED -> product.setArchivedAt(now);
            default -> { /* DRAFT, APPROVED, REJECTED, SUSPENDED carry no stamp of their own */ }
        }

        product.setStatus(to);
        syncVisibility(product);
    }

    /**
     * A moderator's decision, which is not one of the seller's moves.
     *
     * <p>{@link ProductStatus#allowedNext()} is the seller's transition table
     * and nothing in it reaches APPROVED, deliberately: a seller who could move
     * their own listing there would have no moderation at all. This is the other
     * door, and only a moderator surface may open it.
     *
     * <p>The digest is stamped here rather than at the call site, because this
     * is the moment approval is granted and it is what every later "does this
     * edit need re-reviewing" is measured against. A moderator endpoint that
     * forgot to stamp it would leave a listing approved once and editable for
     * ever, with no way to see that its content had changed - so the stamping
     * is not something a caller can forget.
     */
    public void approve(Product product, com.sujula.model.user.User moderator) {
        if (product.getStatus() != ProductStatus.IN_REVIEW) {
            throw new BadRequestException(
                    "Only a listing waiting for review can be approved; this one is "
                    + product.getStatus() + ".");
        }
        product.setStatus(ProductStatus.APPROVED);
        product.setReviewedBy(moderator);
        product.setReviewedAt(LocalDateTime.now());
        product.setRejectionReason(null);
        markContentApproved(product);
        syncVisibility(product);
    }

    /**
     * The other decision, which always carries a reason.
     *
     * <p>Required rather than optional. A refusal with no reason sends the
     * seller back to upload the same thing again, and it is the single
     * commonest way an onboarding funnel dies.
     */
    public void reject(Product product, com.sujula.model.user.User moderator, String reason) {
        if (product.getStatus() != ProductStatus.IN_REVIEW) {
            throw new BadRequestException(
                    "Only a listing waiting for review can be rejected; this one is "
                    + product.getStatus() + ".");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Give a reason the seller can act on.");
        }
        product.setStatus(ProductStatus.REJECTED);
        product.setReviewedBy(moderator);
        product.setReviewedAt(LocalDateTime.now());
        product.setRejectionReason(reason.trim());
        // The approval digest goes with the approval. Leaving it would make a
        // later edit look like a change to something that was never approved.
        product.setApprovedContentHash(null);
        syncVisibility(product);
    }

    /**
     * Forces {@code active} to agree with {@code status}.
     *
     * <p>Called on every transition and after every write that could touch
     * either. The invariant this class exists to maintain is one line, and this
     * is it.
     */
    public void syncVisibility(Product product) {
        product.setActive(product.getStatus() != null && product.getStatus().isLive());
    }

    /**
     * Whether an edit changes what a moderator actually looked at.
     *
     * <p>The alternative designs are both wrong. Re-reviewing every edit means a
     * seller cannot correct a typo or restock without going back into a queue,
     * so they stop using the queue. Re-reviewing nothing means approval is
     * granted once and the listing becomes whatever the seller likes afterwards
     * - approve a phone case, sell something else.
     *
     * <p>So: a digest of the fields moderation is about - what it is called,
     * what it says, what it costs, where it sits. Stock, images and delivery
     * scope are not in it.
     */
    public boolean contentChanged(Product product) {
        return product.getApprovedContentHash() != null
                && !product.getApprovedContentHash().equals(contentHash(product));
    }

    /** Records the content as approved, so later edits can be measured against it. */
    public void markContentApproved(Product product) {
        product.setApprovedContentHash(contentHash(product));
    }

    /**
     * A digest of the moderated fields.
     *
     * <p>The price is in it because the commonest listing fraud on a marketplace
     * is approval at one price followed by a quiet rise, and because a buyer in
     * Madrid sending a month's income cannot walk into the shop and argue.
     */
    public String contentHash(Product product) {
        String material = Stream.of(
                        product.getName(),
                        product.getShortDescription(),
                        product.getDescription(),
                        Objects.toString(product.getPrice(), ""),
                        product.getPriceCurrency(),
                        product.getCategory() == null ? "" : String.valueOf(product.getCategory().getId()),
                        product.getBrand() == null ? "" : String.valueOf(product.getBrand().getId()),
                        product.getCondition() == null ? "" : product.getCondition().name())
                .map(part -> part == null ? "" : part.trim())
                .reduce((a, b) -> a + FIELD_SEPARATOR + b)
                .orElse("");

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** A refusal a seller can act on, rather than "invalid state transition". */
    private static String refusal(ProductStatus from, ProductStatus to) {
        return switch (from) {
            case IN_REVIEW -> "This listing is being reviewed. You can withdraw it, but not change "
                    + "it until we have finished looking at it.";
            case SUSPENDED -> "This listing was taken down by us. Contact support - it cannot be "
                    + "put back up from here.";
            case ARCHIVED -> "This listing is archived, and archiving is final.";
            case DRAFT -> to == ProductStatus.PUBLISHED
                    ? "A listing has to be reviewed before it can go on sale. Send it for review first."
                    : "A draft can be sent for review or archived, and nothing else yet.";
            case REJECTED -> "This listing was not accepted. Edit it and send it for review again.";
            default -> "A listing cannot go from " + from + " to " + to + ".";
        };
    }
}
