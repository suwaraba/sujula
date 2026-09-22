package com.sujula.service.vendorcatalogue;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.products.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one invariant that keeps a suspended listing off the catalogue.
 *
 * <p>{@code Product.active} is a mirror of {@code status == PUBLISHED}, and
 * every public query in this system filters on it. Two fields describing one
 * fact drift the moment somebody sets one without the other, and the way they
 * drift here is a listing that was pulled by moderation carrying on selling. So
 * the invariant is asserted across every state rather than assumed.
 */
class ProductLifecycleTest {

    private final ProductLifecycle lifecycle = new ProductLifecycle();

    private static Product listing(ProductStatus status) {
        return Product.builder()
                .id(1L).name("Kettle").description("1.7 litres.")
                .price(new BigDecimal("450.00")).priceCurrency("GMD")
                .status(status)
                // Deliberately the wrong way round to start with, so a
                // transition that forgot to sync would be caught rather than
                // passing on a lucky default.
                .active(!status.isLive())
                .build();
    }

    @ParameterizedTest
    @EnumSource(ProductStatus.class)
    void visibilityAlwaysFollowsTheStatusAndNeverTheOtherWayRound(ProductStatus status) {
        Product product = listing(status);

        lifecycle.syncVisibility(product);

        assertEquals(status == ProductStatus.PUBLISHED, product.isActive(),
                status + " left active=" + product.isActive());
    }

    @ParameterizedTest
    @EnumSource(ProductStatus.class)
    void everyLegalTransitionLeavesTheTwoInAgreement(ProductStatus from) {
        for (ProductStatus to : from.allowedNext()) {
            Product product = listing(from);
            lifecycle.syncVisibility(product);

            lifecycle.transition(product, to);

            assertEquals(to, product.getStatus());
            assertEquals(to == ProductStatus.PUBLISHED, product.isActive(),
                    from + " -> " + to + " left active=" + product.isActive());
        }
    }

    @Test
    void aSellerCannotPublishOutOfADraft() {
        Product product = consistent(ProductStatus.DRAFT);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> lifecycle.transition(product, ProductStatus.PUBLISHED));

        assertTrue(refused.getMessage().contains("reviewed"), refused.getMessage());
        // The refusal changes nothing at all - not the status, and not the
        // visibility that follows it.
        assertEquals(ProductStatus.DRAFT, product.getStatus());
        assertFalse(product.isActive());
    }

    @Test
    void aSellerCannotLiftASuspension() {
        Product product = consistent(ProductStatus.SUSPENDED);

        assertThrows(BadRequestException.class,
                () -> lifecycle.transition(product, ProductStatus.PUBLISHED));
        assertThrows(BadRequestException.class,
                () -> lifecycle.transition(product, ProductStatus.DRAFT));

        assertEquals(ProductStatus.SUSPENDED, product.getStatus());
        assertFalse(product.isActive());
    }

    /** A listing already in agreement with itself, which is the real starting point. */
    private Product consistent(ProductStatus status) {
        Product product = listing(status);
        lifecycle.syncVisibility(product);
        return product;
    }

    @Test
    void archivingIsFinal() {
        Product product = listing(ProductStatus.ARCHIVED);

        for (ProductStatus anywhere : ProductStatus.values()) {
            if (anywhere == ProductStatus.ARCHIVED) {
                continue;
            }
            assertThrows(BadRequestException.class,
                    () -> lifecycle.transition(product, anywhere));
        }
    }

    @Test
    void askingForTheStateItIsAlreadyInIsNotAnError() {
        Product product = listing(ProductStatus.PUBLISHED);
        lifecycle.syncVisibility(product);

        lifecycle.transition(product, ProductStatus.PUBLISHED);

        assertEquals(ProductStatus.PUBLISHED, product.getStatus());
        assertTrue(product.isActive());
    }

    // -- The content digest --------------------------------------------------

    @Test
    void restockingIsNotAChangeAModeratorNeedsToSee() {
        Product product = listing(ProductStatus.PUBLISHED);
        lifecycle.markContentApproved(product);

        product.setStock(500);
        product.setLowStockThreshold(50);

        // A seller who had to re-enter the queue to change a stock count would
        // stop using the queue.
        assertFalse(lifecycle.contentChanged(product));
    }

    @Test
    void raisingThePriceIs() {
        Product product = listing(ProductStatus.PUBLISHED);
        lifecycle.markContentApproved(product);

        product.setPrice(new BigDecimal("9000.00"));

        // Approved cheap, quietly raised, is the commonest listing fraud there
        // is - and a buyer in Madrid sending a month's income cannot walk into
        // the shop and argue about it.
        assertTrue(lifecycle.contentChanged(product));
    }

    @Test
    void soIsRewritingWhatTheThingActuallyIs() {
        Product product = listing(ProductStatus.PUBLISHED);
        lifecycle.markContentApproved(product);

        product.setDescription("Actually a different product entirely.");
        assertTrue(lifecycle.contentChanged(product));
    }

    // -- The moderator's door, which is not the seller's ---------------------

    @Test
    void nothingASellerCanDoReachesApproved() {
        // The whole point of the transition table. If APPROVED were in any
        // seller-initiated set, there would be no moderation at all.
        for (ProductStatus from : ProductStatus.values()) {
            assertFalse(from.allowedNext().contains(ProductStatus.APPROVED),
                    from + " lets a seller approve their own listing");
        }
    }

    @Test
    void approvingRecordsWhatWasApproved() {
        Product product = consistent(ProductStatus.IN_REVIEW);
        product.setRejectionReason("An earlier refusal.");

        lifecycle.approve(product, moderator());

        // Stamped by the approval itself, not by whoever called it. A moderator
        // endpoint that forgot would leave a listing approved once and editable
        // for ever, with no way to see that its content had changed.
        assertNotNull(product.getApprovedContentHash());
        assertNotNull(product.getReviewedAt());
        assertNull(product.getRejectionReason());
        assertFalse(lifecycle.contentChanged(product));
        // Approved is not on sale: publishing is still the seller's decision.
        assertFalse(product.isActive());

        product.setPrice(new BigDecimal("9000.00"));
        assertTrue(lifecycle.contentChanged(product));
    }

    @Test
    void rejectingWithoutAReasonIsRefused() {
        Product product = consistent(ProductStatus.IN_REVIEW);

        // A refusal with no reason sends the seller back to upload the same
        // thing again.
        assertThrows(BadRequestException.class,
                () -> lifecycle.reject(product, moderator(), "   "));
        assertEquals(ProductStatus.IN_REVIEW, product.getStatus());
    }

    @Test
    void rejectingTakesTheApprovalWithIt() {
        Product product = consistent(ProductStatus.IN_REVIEW);
        lifecycle.markContentApproved(product);

        lifecycle.reject(product, moderator(), "The photograph is of a different product.");

        assertEquals(ProductStatus.REJECTED, product.getStatus());
        assertFalse(product.isActive());
        // Left behind, a later edit would look like a change to something that
        // was never approved.
        assertNull(product.getApprovedContentHash());
        assertTrue(product.getRejectionReason().contains("photograph"));
    }

    @Test
    void aListingNobodyIsReviewingCannotBeDecidedOn() {
        Product draft = consistent(ProductStatus.DRAFT);
        assertThrows(BadRequestException.class, () -> lifecycle.approve(draft, moderator()));
        assertThrows(BadRequestException.class, () -> lifecycle.reject(draft, moderator(), "no"));
    }

    private static com.sujula.model.user.User moderator() {
        com.sujula.model.user.User user = new com.sujula.model.user.User();
        user.setId(1001L);
        return user;
    }

    @Test
    void aListingThatWasNeverApprovedHasNothingToCompareAgainst() {
        Product product = listing(ProductStatus.DRAFT);

        // Null hash, not "unchanged". A draft has never been looked at, so
        // "did this change since approval" has no answer rather than the
        // answer no.
        assertFalse(lifecycle.contentChanged(product));
    }

    @Test
    void theDigestSeparatesFieldsSoTwoListingsDoNotCollide() {
        Product first = listing(ProductStatus.DRAFT);
        first.setName("ab");
        first.setShortDescription("c");

        Product second = listing(ProductStatus.DRAFT);
        second.setName("a");
        second.setShortDescription("bc");

        // Concatenated without a separator these would hash identically, and a
        // rewrite that only moved a boundary would slip past review.
        assertNotEquals(lifecycle.contentHash(first), lifecycle.contentHash(second));
    }
}
