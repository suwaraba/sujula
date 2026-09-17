package com.sujula.service.cart;

import com.sujula.service.CartService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes guest carts past their TTL.
 *
 * <p>{@code CartRepository.deleteExpiredGuestCarts} existed before this class
 * but nothing ever called it, so abandoned guest carts accumulated forever.
 *
 * <p>Disable in an environment with {@code sujula.cart.cleanup.enabled=false} —
 * useful when several instances run and only one should sweep.
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sujula.cart.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class GuestCartCleanupJob implements com.sujula.service.platform.ManagedJob {

    private static final Logger log = LoggerFactory.getLogger(GuestCartCleanupJob.class);

    private final CartService cartService;

    /** Records each pass; see {@code JobRegistry} for why this is not a cycle. */
    private final com.sujula.service.platform.JobRegistry registry;

    @Override public String jobName() { return "guest-cart-cleanup"; }

    @Override
    public String description() {
        return "Deletes guest carts past their time to live. A cart nobody came back to is not a "
                + "cart anybody is going to buy from, and they accumulate forever otherwise.";
    }

    @Override public int runOnce() { return cartService.purgeExpiredGuestCarts(); }

    @Override public long intervalMs() { return java.time.Duration.ofHours(1).toMillis(); }

    /** Hourly by default, offset off the hour so it does not pile onto other jobs. */
    @Scheduled(cron = "${sujula.cart.cleanup.cron:0 17 * * * *}")
    public void purgeExpiredGuestCarts() {
        try {
            // Through the registry, so the pass leaves a row an operator can
            // query. A job that logged its own failure and nothing else is one
            // nobody notices has stopped.
            registry.run(this, null);
        } catch (Exception e) {
            // A sweep failure must never take down the scheduler thread. The
            // registry has already recorded it.
            log.error("Guest cart cleanup failed", e);
        }
    }
}
