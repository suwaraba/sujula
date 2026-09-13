package com.sujula.service.vendorcatalogue.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sujula.model.catalogue.CatalogueJob;
import com.sujula.model.constant.CatalogueJobStatus;
import com.sujula.repository.catalogue.CatalogueJobRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Drains the queue of bulk catalogue jobs.
 *
 * <p>This class decides what to run and keeps one failure from stopping the
 * rest. The work is in {@link CatalogueJobProcessor}, and the split is
 * deliberate rather than tidy: Spring applies {@code @Transactional} through a
 * proxy, and a proxy is not involved when an object calls its own method. With
 * both in one class the processor's {@code REQUIRES_NEW} would be inert, a
 * failure in one job would roll back the batch's bookkeeping, and nothing about
 * the code would say so.
 */
@Slf4j
@Component
public class CatalogueJobWorker {

    /** One pass, so a seller with a backlog cannot monopolise the thread. */
    private static final int BATCH = 3;

    private final CatalogueJobRepository jobs;
    private final CatalogueJobProcessor processor;

    @Value("${sujula.catalogue.jobs.enabled:true}")
    private boolean enabled;

    public CatalogueJobWorker(CatalogueJobRepository jobs, CatalogueJobProcessor processor) {
        this.jobs = jobs;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${sujula.catalogue.jobs.interval-ms:30000}")
    public void process() {
        if (!enabled) {
            return;
        }
        List<CatalogueJob> queued = jobs.findQueued(CatalogueJobStatus.QUEUED, BATCH);
        for (CatalogueJob job : queued) {
            try {
                processor.runOne(job.getId());
            } catch (Exception failed) {
                // One bad job must not stop the queue, and the reason has to
                // survive for the seller who is polling it.
                log.error("[Catalogue] Job {} failed", job.getReference(), failed);
                try {
                    processor.markFailed(job.getId(), failed.getMessage());
                } catch (Exception unrecordable) {
                    log.error("[Catalogue] Could not record the failure of job {}",
                            job.getReference(), unrecordable);
                }
            }
        }
    }
}
