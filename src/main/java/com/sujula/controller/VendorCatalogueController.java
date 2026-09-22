package com.sujula.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.vendorcatalogue.VendorProductRequests;
import com.sujula.dto.response.vendorcatalogue.VendorProductResponses;
import com.sujula.model.constant.ProductStatus;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.vendorcatalogue.CatalogueJobService;
import com.sujula.service.vendorcatalogue.VendorCatalogueService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * A seller's own catalogue.
 *
 * <p>The product id is in the path and the seller never is: every method hands
 * the service the authenticated caller's id, and the service resolves by id and
 * vendor in one query. No parameter here can be changed to reach another
 * seller's listing.
 *
 * <p>The lifecycle is the shape of this controller. A listing is written as a
 * DRAFT, sent for review, approved by a moderator, and published by the seller
 * when they are ready - four verbs rather than a status field, because a status
 * a client can set is a moderation queue a client can skip.
 */
@RestController
@RequestMapping("/vendor")
@PreAuthorize("isAuthenticated()")
@Tag(name = "vendor-catalogue", description = "A seller's own listings: writing, moderation, media, bulk")
public class VendorCatalogueController {

    private static final int MAX_PAGE_SIZE = 100;

    private static final String CREATE = "catalogue.product.create";
    private static final String SUBMIT = "catalogue.product.submit";
    private static final String PUBLISH = "catalogue.product.publish";
    private static final String UNPUBLISH = "catalogue.product.unpublish";
    private static final String VARIANT = "catalogue.variant.create";
    private static final String MEDIA = "catalogue.media.confirm";
    private static final String IMPORT = "catalogue.import";

    private final VendorCatalogueService catalogue;
    private final CatalogueJobService jobs;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public VendorCatalogueController(VendorCatalogueService catalogue, CatalogueJobService jobs,
                                     AuthenticatedCaller caller, IdempotencyService idempotency) {
        this.catalogue = catalogue;
        this.jobs = jobs;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    // -- Products ------------------------------------------------------------

    @GetMapping("/products")
    @Operation(summary = "My listings",
               description = "Drafts and suspended listings included, unlike every public "
                       + "catalogue query - a back office that hid them would hide exactly the "
                       + "rows the seller has to act on. Comes with per-status counts so the tabs "
                       + "do not need a second request.")
    public ResponseEntity<VendorProductResponses.Page> list(
            Authentication authentication,
            @Parameter(description = "Narrow to one rung of the ladder")
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(catalogue.list(
                caller.userId(authentication), status, search, includeArchived, pageable));
    }

    @PostMapping("/products")
    @Operation(summary = "Write a listing",
               description = "Created as a DRAFT whatever the request says - there is no status "
                       + "field on the way in, because a seller who could name the status could "
                       + "name PUBLISHED. The price is in the store's settlement currency and the "
                       + "request cannot override it: the listing currency and the payout currency "
                       + "are the same number by construction.")
    public ResponseEntity<VendorProductResponses.Detail> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VendorProductRequests.CreateProduct request) {

        Long userId = caller.userId(authentication);
        VendorProductResponses.Detail created = idempotency.execute(
                IdempotencyService.scopeFor(userId, CREATE), idempotencyKey, request,
                HttpStatus.CREATED.value(), VendorProductResponses.Detail.class,
                () -> catalogue.create(userId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/products/{productId}")
    @Operation(summary = "One listing",
               description = "Another seller's is not found rather than forbidden: a 403 would "
                       + "confirm it exists, which tells a competitor how many listings we have.")
    public ResponseEntity<VendorProductResponses.Detail> get(
            Authentication authentication, @PathVariable Long productId) {
        return ResponseEntity.ok(catalogue.get(caller.userId(authentication), productId));
    }

    @PatchMapping("/products/{productId}")
    @Operation(summary = "Edit a listing",
               description = "Absent fields are left alone. Changing what a moderator looked at - "
                       + "the name, the description, the price, the category - sends an approved "
                       + "listing back into the queue and takes it off sale, and the response says "
                       + "so. Stock, images and delivery scope do not. Editing is refused outright "
                       + "while a moderator is holding it, because deciding about text that no "
                       + "longer exists is how something unapproved ends up approved.")
    public ResponseEntity<VendorProductResponses.Saved> update(
            Authentication authentication, @PathVariable Long productId,
            @Valid @RequestBody VendorProductRequests.UpdateProduct request) {
        return ResponseEntity.ok(catalogue.update(caller.userId(authentication), productId, request));
    }

    @DeleteMapping("/products/{productId}")
    @Operation(summary = "Archive a listing",
               description = "Archived, not deleted, whenever anybody has ordered it: an order "
                       + "line points at the listing, and a buyer's receipt, invoice and review "
                       + "all have to keep resolving years later. A listing nobody ever ordered is "
                       + "genuinely removed.")
    public ResponseEntity<VendorProductResponses.Removed> remove(
            Authentication authentication, @PathVariable Long productId) {
        return ResponseEntity.ok(catalogue.remove(caller.userId(authentication), productId));
    }

    // -- Lifecycle -----------------------------------------------------------

    @PostMapping("/products/{productId}/submit-for-review")
    @Operation(summary = "Send it to be reviewed",
               description = "Checks first that there is something to review: a price, a category "
                       + "and a description. Buyers here are often thousands of miles from the "
                       + "goods and the description is all they have.")
    public ResponseEntity<VendorProductResponses.Saved> submitForReview(
            Authentication authentication, @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) VendorProductRequests.SubmitForReview request) {

        Long userId = caller.userId(authentication);
        VendorProductRequests.SubmitForReview body =
                request == null ? new VendorProductRequests.SubmitForReview(null) : request;

        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, SUBMIT + ":" + productId), idempotencyKey, body,
                HttpStatus.OK.value(), VendorProductResponses.Saved.class,
                () -> catalogue.submitForReview(userId, productId, body)));
    }

    @PostMapping("/products/{productId}/publish")
    @Operation(summary = "Put it on sale",
               description = "Only a listing a moderator has approved. This is the guard that "
                       + "makes the review queue real rather than advisory.")
    public ResponseEntity<VendorProductResponses.Saved> publish(
            Authentication authentication, @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, PUBLISH + ":" + productId), idempotencyKey,
                productId, HttpStatus.OK.value(), VendorProductResponses.Saved.class,
                () -> catalogue.publish(userId, productId)));
    }

    @PostMapping("/products/{productId}/unpublish")
    @Operation(summary = "Take it off sale",
               description = "The approval survives, so it can go back up without going through "
                       + "the queue again.")
    public ResponseEntity<VendorProductResponses.Saved> unpublish(
            Authentication authentication, @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, UNPUBLISH + ":" + productId), idempotencyKey,
                productId, HttpStatus.OK.value(), VendorProductResponses.Saved.class,
                () -> catalogue.unpublish(userId, productId)));
    }

    // -- Variants ------------------------------------------------------------

    @PostMapping("/products/{productId}/variants")
    @Operation(summary = "Add a variant",
               description = "One value per option the listing defines, and no two variants with "
                       + "the same combination - two that are both 'Large, Red' cannot be told "
                       + "apart by the buyer, the picker, or the stock count.")
    public ResponseEntity<VendorProductResponses.Variant> addVariant(
            Authentication authentication, @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VendorProductRequests.CreateVariant request) {

        Long userId = caller.userId(authentication);
        VendorProductResponses.Variant variant = idempotency.execute(
                IdempotencyService.scopeFor(userId, VARIANT + ":" + productId), idempotencyKey,
                request, HttpStatus.CREATED.value(), VendorProductResponses.Variant.class,
                () -> catalogue.addVariant(userId, productId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(variant);
    }

    @PatchMapping("/products/{productId}/variants/{variantId}")
    @Operation(summary = "Edit a variant")
    public ResponseEntity<VendorProductResponses.Variant> updateVariant(
            Authentication authentication, @PathVariable Long productId,
            @PathVariable Long variantId,
            @Valid @RequestBody VendorProductRequests.UpdateVariant request) {

        return ResponseEntity.ok(catalogue.updateVariant(
                caller.userId(authentication), productId, variantId, request));
    }

    @DeleteMapping("/products/{productId}/variants/{variantId}")
    @Operation(summary = "Remove a variant",
               description = "Turned off rather than deleted once the listing has sold, for the "
                       + "same reason a listing is archived: orders point at it.")
    public ResponseEntity<VendorProductResponses.Removed> removeVariant(
            Authentication authentication, @PathVariable Long productId,
            @PathVariable Long variantId) {
        return ResponseEntity.ok(catalogue.removeVariant(
                caller.userId(authentication), productId, variantId));
    }

    // -- Media ---------------------------------------------------------------

    @PostMapping("/products/{productId}/media")
    @Operation(summary = "Confirm an uploaded image",
               description = "Takes a storage key, not bytes. Presign an upload first, put the "
                       + "file straight into storage, then send the key here - an image that "
                       + "travels through an application server is an image in three access logs "
                       + "and a heap dump. The first image confirmed becomes the card image, "
                       + "because a listing whose card has no picture is one nobody clicks.")
    public ResponseEntity<VendorProductResponses.Media> confirmMedia(
            Authentication authentication, @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VendorProductRequests.ConfirmMedia request) {

        Long userId = caller.userId(authentication);
        VendorProductResponses.Media media = idempotency.execute(
                IdempotencyService.scopeFor(userId, MEDIA + ":" + productId), idempotencyKey,
                request, HttpStatus.CREATED.value(), VendorProductResponses.Media.class,
                () -> catalogue.confirmMedia(userId, productId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(media);
    }

    @PatchMapping("/products/{productId}/media/reorder")
    @Operation(summary = "Reorder the gallery",
               description = "Every image, exactly once. A partial order would leave the ones not "
                       + "mentioned colliding with the ones that were. Whatever ends up first "
                       + "becomes the card image.")
    public ResponseEntity<List<VendorProductResponses.Media>> reorderMedia(
            Authentication authentication, @PathVariable Long productId,
            @Valid @RequestBody VendorProductRequests.ReorderMedia request) {
        return ResponseEntity.ok(catalogue.reorderMedia(
                caller.userId(authentication), productId, request));
    }

    @DeleteMapping("/products/{productId}/media/{mediaId}")
    @Operation(summary = "Remove an image",
               description = "Closes the gap in the order and hands the card image to whatever is "
                       + "now first, so a listing is never left without one.")
    public ResponseEntity<Void> removeMedia(
            Authentication authentication, @PathVariable Long productId,
            @PathVariable Long mediaId) {
        catalogue.removeMedia(caller.userId(authentication), productId, mediaId);
        return ResponseEntity.noContent().build();
    }

    // -- Translations --------------------------------------------------------

    @PutMapping("/products/{productId}/translations/{locale}")
    @Operation(summary = "Write the listing in another language",
               description = "Not a nicety on this marketplace: the seller writes French, the "
                       + "person paying reads Spanish and the person receiving reads English, and "
                       + "the listing text is the only description any of them gets. Blank fields "
                       + "fall back to the original, so a partial translation is allowed - a "
                       + "translated name beside the original description beats neither.")
    public ResponseEntity<VendorProductResponses.Translation> putTranslation(
            Authentication authentication, @PathVariable Long productId,
            @PathVariable String locale,
            @Valid @RequestBody VendorProductRequests.PutTranslation request) {

        return ResponseEntity.ok(catalogue.putTranslation(
                caller.userId(authentication), productId, locale, request));
    }

    // -- Bulk ----------------------------------------------------------------

    @PostMapping("/products/bulk-import")
    @Operation(summary = "Import a spreadsheet",
               description = "CSV or XLSX, detected from the file's own bytes rather than its "
                       + "name. Queued rather than run here: a seller uploading four hundred rows "
                       + "over a mobile connection loses the response long before the work "
                       + "finishes. Every row it creates is a DRAFT - an import that could publish "
                       + "would be the way past moderation. Poll the returned reference for "
                       + "row-level errors.")
    public ResponseEntity<VendorProductResponses.Job> bulkImport(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VendorProductRequests.BulkImport request) {

        Long userId = caller.userId(authentication);
        VendorProductResponses.Job job = idempotency.execute(
                IdempotencyService.scopeFor(userId, IMPORT), idempotencyKey, request,
                HttpStatus.ACCEPTED.value(), VendorProductResponses.Job.class,
                () -> jobs.startImport(userId, request));

        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/imports/{reference}")
    @Operation(summary = "How an import went",
               description = "Row by row. 'Failed' has wasted the seller's evening; \"row 37: "
                       + "there is no category called 'Phonez'\" has found it for them. Errors are "
                       + "paged, because the wrong template produces one per row.")
    public ResponseEntity<VendorProductResponses.Job> job(
            Authentication authentication, @PathVariable String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable errors = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(jobs.get(caller.userId(authentication), reference, errors));
    }

    @GetMapping("/products/export")
    @Operation(summary = "Export the catalogue",
               description = "Queued like an import, and for the same reason. Poll the returned "
                       + "reference; when it finishes it carries a link that expires.")
    public ResponseEntity<VendorProductResponses.Job> export(
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean includeArchived) {

        return ResponseEntity.accepted().body(
                jobs.startExport(caller.userId(authentication), includeArchived));
    }

    @GetMapping("/products/import-template")
    @Operation(summary = "Which columns an import understands")
    public ResponseEntity<VendorProductResponses.ImportTemplate> template() {
        return ResponseEntity.ok(jobs.template());
    }
}
