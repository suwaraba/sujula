package com.sujula.service.money.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.finance.ReportExport;
import com.sujula.repository.finance.ReportExportRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Drains the finance export queue, and retires links that have lapsed.
 *
 * <p>Two per pass, not five. These are the heaviest reads on the platform — a
 * year of ledger rows across every seller — and a backlog of them monopolising
 * the scheduler would starve everything else that runs on it.
 */
@Slf4j
@Component
public class ReportExportWorker implements com.sujula.service.platform.ManagedJob {

    private static final int BATCH = 2;

    private final ReportExportRepository exports;
    private final ReportExportProcessor processor;

    @Value("${sujula.money.exports.enabled:true}")
    private boolean enabled;

    /** Records each pass; see {@code JobRegistry} for why this is not a cycle. */
    private final com.sujula.service.platform.JobRegistry registry;

    public ReportExportWorker(ReportExportRepository exports, ReportExportProcessor processor,
                                                            com.sujula.service.platform.JobRegistry registry) {
        this.exports = exports;
        this.processor = processor;
        this.registry = registry;
    }

    @Override public String jobName() { return "finance-exports"; }

    @Override
    public String description() {
        return "Builds the finance files administrators ask for, and retires links that have "
                + "lapsed. The heaviest reads on the platform, which is why it takes two at a "
                + "time.";
    }

    @Override public long intervalMs() { return java.time.Duration.ofSeconds(45).toMillis(); }

    @Override public boolean isEnabled() { return enabled; }

    @Scheduled(fixedDelayString = "${sujula.money.exports.interval-ms:45000}")
    public void process() {
        if (!enabled) {
            return;
        }
        registry.run(this, null);
    }

    @Override
    public int runOnce() {
        int handled = 0;
        List<ReportExport> queued = exports.findQueued(ReportExportStatus.QUEUED, BATCH);
        for (ReportExport export : queued) {
            try {
                processor.runOne(export.getId());
                handled++;
            } catch (Exception e) {
                // One bad export must not stop the queue, and the reason has to
                // survive for the person who asked — they are waiting on a file.
                log.error("[Money] Export {} failed", export.getReference(), e);
                try {
                    processor.markFailed(export.getId(), e.getMessage());
                } catch (Exception ignored) {
                    log.error("[Money] Could not record the failure of export {}",
                            export.getReference());
                }
            }
        }

        // Retiring a lapsed link is not housekeeping: the row is what answers
        // "who exported every seller's earnings in March", and a row still
        // saying READY about a file that has gone is a row telling a lie.
        try {
            int expired = processor.expireLapsed();
            if (expired > 0) {
                log.info("[Money] {} export link(s) have lapsed", expired);
            }
        } catch (Exception e) {
            log.warn("[Money] Could not retire lapsed export links: {}", e.getMessage());
        }
        return handled;
    }
}
