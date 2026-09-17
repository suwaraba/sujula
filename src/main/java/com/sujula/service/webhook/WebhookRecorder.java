package com.sujula.service.webhook;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.webhook.WebhookEvent;
import com.sujula.repository.webhook.WebhookEventRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Writes a refused delivery down, on its own transaction, without ever failing
 * the caller.
 *
 * <p>Its own bean rather than a method on {@link WebhookIntake} because
 * {@code REQUIRES_NEW} only applies through the proxy — a self-call would share
 * the caller's transaction and defeat the whole point.
 *
 * <p>The point being: a provider retries a delivery we refused just as readily
 * as one we accepted, and the second attempt has the same body and so the same
 * derived id. Letting that collision reach the caller turned a refusal into a
 * 500 — which tells a provider to retry harder, at the exact moment the platform
 * is trying to tell them no.
 */
@Slf4j
@Component
public class WebhookRecorder {

    private final WebhookEventRepository events;

    public WebhookRecorder(WebhookEventRepository events) {
        this.events = events;
    }

    /**
     * @return the stored row, or null when it could not be written — which is
     *         never a reason to fail the request. The refusal stands either way.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookEvent recordRejection(WebhookEvent event) {
        if (events.existsByProviderAndEventId(event.getProvider(), event.getEventId())) {
            // Already refused once with this exact body. Nothing to add.
            return null;
        }
        try {
            events.save(event);
            events.flush();
            return event;
        } catch (DataIntegrityViolationException race) {
            // Two identical refusals at once. The constraint decided; the
            // refusal is unaffected.
            log.debug("[Webhook] A refused delivery from {} was already recorded",
                    event.getProvider());
            return null;
        } catch (RuntimeException e) {
            log.error("[Webhook] Could not record a refused delivery from {}: {}",
                    event.getProvider(), e.getMessage());
            return null;
        }
    }
}
