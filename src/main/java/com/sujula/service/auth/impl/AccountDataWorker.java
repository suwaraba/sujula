package com.sujula.service.auth.impl;

import com.sujula.model.auth.AccountDataRequest;
import com.sujula.model.constant.DataRequestStatus;
import com.sujula.repository.auth.AccountDataRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drains the queue of data-protection requests {@code /me} records.
 *
 * <p>A job rather than part of the request, for a reason beyond duration: an
 * erasure executed inline would delete the account partway through the request
 * that account is making, and an export of a busy user's history is not
 * something to build while a client waits on a socket.
 *
 * <p>The work itself lives in {@link AccountDataProcessor}. This class only
 * decides what to run and keeps one failure from stopping the rest.
 */
@Slf4j
@Component
public class AccountDataWorker {

    /** How many requests one pass handles, so a backlog cannot monopolise a thread. */
    private static final int BATCH = 5;

    private final AccountDataRequestRepository requests;
    private final AccountDataProcessor processor;

    @Value("${sujula.auth.data-requests.enabled:true}")
    private boolean enabled;

    public AccountDataWorker(AccountDataRequestRepository requests, AccountDataProcessor processor) {
        this.requests = requests;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${sujula.auth.data-requests.interval-ms:60000}")
    public void process() {
        if (!enabled) {
            return;
        }
        List<AccountDataRequest> queued = requests.findQueued(DataRequestStatus.PENDING, BATCH);
        for (AccountDataRequest request : queued) {
            try {
                processor.runOne(request.getId());
            } catch (Exception e) {
                // One bad request must not stop the queue, and the reason has to
                // survive for the person who asked.
                log.error("[Account] {} {} failed", request.getType(), request.getReference(), e);
                try {
                    processor.markFailed(request.getId(), e.getMessage());
                } catch (Exception ignored) {
                    log.error("[Account] Could not record the failure of request {}", request.getId());
                }
            }
        }
    }
}
