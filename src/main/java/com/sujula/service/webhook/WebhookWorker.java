package com.sujula.service.webhook;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sujula.model.webhook.WebhookEvent;
import com.sujula.repository.webhook.WebhookEventRepository;
import com.sujula.service.platform.JobRegistry;
import com.sujula.service.platform.ManagedJob;

import lombok.extern.slf4j.Slf4j;

/**
 * Acts on webhooks that have been verified and stored.
 *
 * <p>Separate from the request that received them, which is the point: the
 * provider gets a fast 200 and stops retrying, and the work happens where a
 * failure can be retried on our own schedule rather than theirs.
 */
@Slf4j
@Component
public class WebhookWorker implements ManagedJob {

    /** Enough to keep up without letting a backlog monopolise the scheduler. */
    private static final int BATCH = 20;

    private final WebhookEventRepository events;
    private final WebhookProcessor processor;
    private final WebhookProperties properties;
    private final JobRegistry registry;

    @Value("${sujula.webhooks.enabled:true}")
    private boolean enabled;

    public WebhookWorker(WebhookEventRepository events, WebhookProcessor processor,
                         WebhookProperties properties, JobRegistry registry) {
        this.events = events;
        this.processor = processor;
        this.properties = properties;
        this.registry = registry;
    }

    @Override public String jobName() { return "webhook-events"; }

    @Override
    public String description() {
        return "Acts on webhooks that have been verified and stored. A payment marked paid by a "
                + "provider becomes a paid order here, which is what lets sellers start packing.";
    }

    @Override public long intervalMs() { return java.time.Duration.ofSeconds(10).toMillis(); }

    @Override public boolean isEnabled() { return enabled; }

    @Scheduled(fixedDelayString = "${sujula.webhooks.interval-ms:10000}")
    public void process() {
        if (!enabled) {
            return;
        }
        try {
            registry.run(this, null);
        } catch (Exception e) {
            // Never take the scheduler thread down; the registry already has it.
            log.error("[Webhook] Pass failed", e);
        }
    }

    @Override
    public int runOnce() {
        int handled = 0;
        List<WebhookEvent> pending = events.findPending(BATCH);
        for (WebhookEvent event : pending) {
            try {
                processor.runOne(event.getId());
                handled++;
            } catch (Exception e) {
                // One bad event must not stop the queue, and the reason has to
                // survive — a payment nobody credited is a buyer who paid and
                // got nothing.
                log.error("[Webhook] Event {} from {} failed",
                        event.getEventId(), event.getProvider(), e);
                try {
                    processor.markFailed(event.getId(),
                            e.getMessage() == null ? e.toString() : e.getMessage(),
                            properties.getMaxAttempts());
                } catch (Exception unrecordable) {
                    log.error("[Webhook] Could not record the failure of {}", event.getEventId());
                }
            }
        }
        return handled;
    }
}
