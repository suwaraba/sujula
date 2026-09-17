package com.sujula.service.platform;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sujula.service.admin.SanctionRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * The things that lapse on their own, swept up.
 *
 * <p>Two of them, and the first is the one that matters to a person. A
 * suspension with an end date does not end by itself: the account stays locked
 * until something re-derives it, and without this job "thirty days" means
 * "until somebody remembers". A punishment that outlives its own sentence
 * because nobody ran a query is worse than no punishment.
 *
 * <p>The second is housekeeping on the job history itself — rows still claiming
 * to be RUNNING because a process died mid-pass, and history old enough that
 * nobody will read it.
 */
@Slf4j
@Component
public class PlatformSweeper implements ManagedJob {

    private final SanctionRegistry sanctions;
    private final JobRegistry registry;

    @Value("${sujula.platform.sweeper.enabled:true}")
    private boolean enabled;

    public PlatformSweeper(SanctionRegistry sanctions, JobRegistry registry) {
        this.sanctions = sanctions;
        this.registry = registry;
    }

    @Override public String jobName() { return "platform-sweeper"; }

    @Override
    public String description() {
        return "Lifts suspensions whose end date has passed, and tidies the job history. Without "
                + "the first, a thirty-day suspension lasts until somebody remembers.";
    }

    @Override public long intervalMs() { return Duration.ofMinutes(15).toMillis(); }

    @Override public boolean isEnabled() { return enabled; }

    @Override
    public int runOnce() {
        int lifted = sanctions.sweepLapsedLocks();
        int abandoned = registry.sweep();
        if (lifted > 0) {
            log.info("[Sweeper] {} account(s) came back by themselves", lifted);
        }
        return lifted + abandoned;
    }

    @Scheduled(fixedDelayString = "${sujula.platform.sweeper.interval-ms:900000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            registry.run(this, null);
        } catch (Exception e) {
            // Never take the scheduler thread down. The registry already has
            // the failure on a row.
            log.error("[Sweeper] Pass failed", e);
        }
    }
}
