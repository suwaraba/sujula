package com.sujula.service.vendorcatalogue;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.vendorcatalogue.VendorProductRequests;
import com.sujula.dto.response.vendorcatalogue.VendorProductResponses;

/**
 * Moving several hundred listings at once, away from the request that asked.
 *
 * <p>Asynchronous for a reason beyond duration. A seller uploading four hundred
 * rows over a mobile connection loses the response long before the work
 * finishes, and a synchronous import would have written half their catalogue
 * with nobody able to say which half. A job reference they can come back to is
 * the only design that survives the network these sellers actually have.
 */
public interface CatalogueJobService {

    /** Queues a spreadsheet. Every row it creates is a DRAFT; nothing is published. */
    VendorProductResponses.Job startImport(Long userId, VendorProductRequests.BulkImport request);

    /** Queues an export of everything this seller has. */
    VendorProductResponses.Job startExport(Long userId, boolean includeArchived);

    /**
     * One job, by the reference the seller holds and only if it is theirs.
     *
     * <p>Row errors are paged: a seller who uploaded the wrong template produces
     * one per row, and four hundred of them do not belong in a response.
     */
    VendorProductResponses.Job get(Long userId, String reference, Pageable errorPage);

    /** The columns an import understands, so a client can offer a template. */
    VendorProductResponses.ImportTemplate template();
}
