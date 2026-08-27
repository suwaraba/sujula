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
public class GuestCartCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(GuestCartCleanupJob.class);

    private final CartService cartService;

    /** Hourly by default, offset off the hour so it does not pile onto other jobs. */
    @Scheduled(cron = "${sujula.cart.cleanup.cron:0 17 * * * *}")
    public void purgeExpiredGuestCarts() {
        try {
            cartService.purgeExpiredGuestCarts();
        } catch (Exception e) {
            // A sweep failure must never take down the scheduler thread
            log.error("Guest cart cleanup failed", e);
        }
    }
}
