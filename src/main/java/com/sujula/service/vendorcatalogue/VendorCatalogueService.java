package com.sujula.service.vendorcatalogue;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.vendorcatalogue.VendorProductRequests;
import com.sujula.dto.response.vendorcatalogue.VendorProductResponses;
import com.sujula.model.constant.ProductStatus;

/**
 * A seller's own catalogue: writing listings, getting them approved, putting
 * them on sale, and moving several hundred at a time.
 *
 * <p>Every method takes the caller's user id and resolves the listing by id
 * <em>and</em> vendor in a single query. No method here can be made to reach
 * another seller's product by changing an argument.
 *
 * <p><strong>Nothing goes on sale without a moderator.</strong> A listing is
 * created as a DRAFT, sent for review, approved, and only then published - by
 * the seller, when they are ready, which is a separate decision from being
 * allowed to. An edit that changes what the moderator looked at sends it back,
 * because the alternative is approval at one price and a quiet rise afterwards.
 *
 * <p><strong>The listing price is the payout price.</strong> A product is priced
 * in the vendor's settlement currency and in no other, so the figure a buyer is
 * converted from and the figure the seller is owed are the same number. A seller
 * listing in EUR while banking in GMD would put an unsnapshotted conversion
 * between the two.
 */
public interface VendorCatalogueService {

    /**
     * The seller's listings, drafts and suspended ones included.
     *
     * <p>Deliberately unlike the public catalogue, which shows only what is
     * live. A back office that hid drafts would hide exactly the rows the seller
     * has to act on.
     */
    VendorProductResponses.Page list(Long userId, ProductStatus status, String search,
                                     boolean includeArchived, Pageable pageable);

    /** Created as a DRAFT, whatever the request says. */
    VendorProductResponses.Detail create(Long userId, VendorProductRequests.CreateProduct request);

    VendorProductResponses.Detail get(Long userId, Long productId);

    /**
     * Edits a listing. Absent fields are left alone.
     *
     * <p>If the edit changes a moderated field on a listing that had been
     * approved, it goes back into the queue and comes off sale. The response
     * says so - a seller whose product silently vanished after a price change
     * will believe the platform broke.
     */
    VendorProductResponses.Saved update(Long userId, Long productId,
                                        VendorProductRequests.UpdateProduct request);

    /**
     * Archives the listing, or deletes it if nobody ever ordered it.
     *
     * <p>Never a hard delete of something that has been bought. An order line
     * points at the product, and a buyer's receipt, invoice and review all have
     * to keep resolving years afterwards.
     */
    VendorProductResponses.Removed remove(Long userId, Long productId);

    // -- Lifecycle -----------------------------------------------------------

    VendorProductResponses.Saved submitForReview(Long userId, Long productId,
                                                 VendorProductRequests.SubmitForReview request);

    /** Only from APPROVED or UNPUBLISHED. A draft cannot be put on sale. */
    VendorProductResponses.Saved publish(Long userId, Long productId);

    /** Takes it off sale. The approval survives, so it can go back up. */
    VendorProductResponses.Saved unpublish(Long userId, Long productId);

    // -- Variants ------------------------------------------------------------

    VendorProductResponses.Variant addVariant(Long userId, Long productId,
                                              VendorProductRequests.CreateVariant request);

    VendorProductResponses.Variant updateVariant(Long userId, Long productId, Long variantId,
                                                 VendorProductRequests.UpdateVariant request);

    /** Archived rather than deleted once it has been ordered, for the same reason. */
    VendorProductResponses.Removed removeVariant(Long userId, Long productId, Long variantId);

    // -- Media ---------------------------------------------------------------

    /** Confirms a file already in storage. The bytes never pass through this API. */
    VendorProductResponses.Media confirmMedia(Long userId, Long productId,
                                              VendorProductRequests.ConfirmMedia request);

    java.util.List<VendorProductResponses.Media> reorderMedia(
            Long userId, Long productId, VendorProductRequests.ReorderMedia request);

    void removeMedia(Long userId, Long productId, Long mediaId);

    // -- Translations --------------------------------------------------------

    /**
     * Writes the listing in another language.
     *
     * <p>Not a nicety here: the vendor writes French, the payer reads Spanish
     * and the recipient reads English, and the listing text is the only
     * description any of them gets.
     */
    VendorProductResponses.Translation putTranslation(
            Long userId, Long productId, String locale,
            VendorProductRequests.PutTranslation request);
}
