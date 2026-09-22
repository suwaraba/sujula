package com.sujula.service.vendorcatalogue.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.vendorcatalogue.VendorProductRequests;
import com.sujula.dto.response.vendorcatalogue.VendorProductResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.catalogue.CatalogueJob;
import com.sujula.model.catalogue.CatalogueJobError;
import com.sujula.model.constant.CatalogueJobStatus;
import com.sujula.model.constant.CatalogueJobType;
import com.sujula.model.user.Vendor;
import com.sujula.repository.catalogue.CatalogueJobErrorRepository;
import com.sujula.repository.catalogue.CatalogueJobRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.vendorcatalogue.CatalogueJobService;

import lombok.extern.slf4j.Slf4j;

/**
 * {@inheritDoc}
 *
 * <p>This class only queues and reports. The work is in
 * {@link CatalogueJobProcessor}, reached through {@link CatalogueJobWorker},
 * because a request thread is the wrong place to read four hundred rows and
 * because the seller's connection will be gone before it finishes.
 */
@Slf4j
@Service
public class CatalogueJobServiceImpl implements CatalogueJobService {

    /** The columns an import understands. */
    static final List<String> REQUIRED_COLUMNS = List.of("name", "price");
    static final List<String> OPTIONAL_COLUMNS = List.of(
            "sku", "short_description", "description", "stock", "low_stock_threshold",
            "category", "brand", "condition", "weight_kg", "dimensions", "country",
            "compare_at_price", "delivery_scope", "allow_backorder");

    private final CatalogueJobRepository jobs;
    private final CatalogueJobErrorRepository jobErrors;
    private final VendorRepository vendors;
    private final UserRepository users;

    /**
     * How many jobs one seller may have outstanding.
     *
     * <p>Low on purpose. A seller queueing twenty imports has made a mistake,
     * and the second one is almost always the same file again after the first
     * appeared not to do anything.
     */
    @Value("${sujula.catalogue.max-queued-jobs-per-vendor:3}")
    private int maxQueued;

    public CatalogueJobServiceImpl(CatalogueJobRepository jobs, CatalogueJobErrorRepository jobErrors,
                                   VendorRepository vendors, UserRepository users) {
        this.jobs = jobs;
        this.jobErrors = jobErrors;
        this.vendors = vendors;
        this.users = users;
    }

    @Override
    @Transactional
    public VendorProductResponses.Job startImport(Long userId,
                                                  VendorProductRequests.BulkImport request) {
        Vendor vendor = requireVendor(userId);
        requireQueueRoom(vendor);

        CatalogueJob job = jobs.save(CatalogueJob.builder()
                .reference(newReference("IMP"))
                .vendor(vendor)
                .requestedBy(users.findById(userId).orElse(null))
                .type(CatalogueJobType.IMPORT)
                .status(CatalogueJobStatus.QUEUED)
                .sourceUrl(request.fileUrl().trim())
                .originalFilename(trimToNull(request.originalFilename()))
                // Recorded as claimed; the worker checks the file's own bytes
                // and overwrites this. A filename is a claim, not a fact.
                .format(trimToNull(request.format()))
                .build());

        log.info("[Catalogue] Import {} queued for vendor {}", job.getReference(), vendor.getId());
        return toJob(job, List.of(), 0);
    }

    @Override
    @Transactional
    public VendorProductResponses.Job startExport(Long userId, boolean includeArchived) {
        Vendor vendor = requireVendor(userId);
        requireQueueRoom(vendor);

        CatalogueJob job = jobs.save(CatalogueJob.builder()
                .reference(newReference("EXP"))
                .vendor(vendor)
                .requestedBy(users.findById(userId).orElse(null))
                .type(CatalogueJobType.EXPORT)
                .status(CatalogueJobStatus.QUEUED)
                .format("csv")
                // Carried on the job rather than as a separate flag, so a
                // re-run of the same reference produces the same file.
                .originalFilename(includeArchived ? "all" : "current")
                .build());

        log.info("[Catalogue] Export {} queued for vendor {}", job.getReference(), vendor.getId());
        return toJob(job, List.of(), 0);
    }

    @Override
    @Transactional(readOnly = true)
    public VendorProductResponses.Job get(Long userId, String reference, Pageable errorPage) {
        Vendor vendor = requireVendor(userId);

        CatalogueJob job = jobs.findByReferenceAndVendorId(reference, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Job", reference));

        Page<CatalogueJobError> errors =
                jobErrors.findByJobIdOrderByRowNumberAsc(job.getId(), errorPage);

        return toJob(job, errors.getContent(), errors.getTotalElements());
    }

    @Override
    public VendorProductResponses.ImportTemplate template() {
        return new VendorProductResponses.ImportTemplate(REQUIRED_COLUMNS, OPTIONAL_COLUMNS);
    }

    // -- Helpers -------------------------------------------------------------

    private Vendor requireVendor(Long userId) {
        return vendors.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Store", "you do not have a store yet"));
    }

    private void requireQueueRoom(Vendor vendor) {
        long outstanding = jobs.countByVendorIdAndStatusIn(vendor.getId(),
                List.of(CatalogueJobStatus.QUEUED, CatalogueJobStatus.RUNNING));
        if (outstanding >= maxQueued) {
            throw new BadRequestException(
                    "You already have " + outstanding + " jobs waiting. Let those finish first - "
                    + "uploading the same file again will not make it go faster.");
        }
    }

    /**
     * The handle a seller polls with.
     *
     * <p>Random rather than sequential. A reference anyone can count through
     * would hand out other sellers' import errors, and those name their
     * products, their prices and their SKUs. Ownership is checked in the query
     * as well - this is the second lock, not the only one.
     */
    private String newReference(String prefix) {
        String reference;
        do {
            reference = prefix + "-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 16).toUpperCase(java.util.Locale.ROOT);
        } while (jobs.existsByReference(reference));
        return reference;
    }

    static VendorProductResponses.Job toJob(CatalogueJob job, List<CatalogueJobError> errors,
                                            long errorsTotal) {
        return new VendorProductResponses.Job(
                job.getReference(), job.getType(), job.getStatus(),
                job.getOriginalFilename(), job.getFormat(),
                job.getTotalRows() == null ? 0 : job.getTotalRows(),
                job.getSucceededRows() == null ? 0 : job.getSucceededRows(),
                job.getFailedRows() == null ? 0 : job.getFailedRows(),
                job.getFailureReason(),
                errors.stream()
                        .map(error -> new VendorProductResponses.RowError(
                                error.getRowNumber(), error.getField(),
                                error.getMessage(), error.getValue()))
                        .toList(),
                errors.size(), errorsTotal,
                job.getResultUrl(), job.getResultExpiresAt(),
                job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Exposed for the processor, which stamps the same clock. */
    static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
