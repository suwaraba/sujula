package com.sujula.service.platform;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.platform.FeatureFlag;
import com.sujula.repository.platform.FeatureFlagRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The switches this platform actually has, and what they are set to.
 *
 * <p>Declared here rather than created through the API, because a flag nothing
 * reads is a switch that does nothing — and a back office full of those is one
 * where nobody trusts any of them. Every key below has a reader somewhere in
 * this codebase; adding a key here without one is the mistake this class exists
 * to make visible.
 *
 * <p>Held in memory for a few seconds at a time. A flag is read on paths that
 * run on every delivery, and a database round trip for each would be a query
 * per parcel; a few seconds' staleness is the right trade, and an administrator
 * who has just moved one sees it take effect before they have finished reading
 * the response.
 */
@Slf4j
@Component
public class FeatureFlags {

    /** How long a read may be stale. Short enough that nobody notices. */
    private static final Duration CACHE_FOR = Duration.ofSeconds(10);

    // ── The flags themselves ─────────────────────────────────────────────────

    /**
     * Whether a driver may leave a parcel without a code when the recipient has
     * authorised it.
     *
     * <p>Off turns every delivery back into a code presented at the door. Worth
     * having as a switch because it is the one instruction that replaces
     * somebody standing at their door with a photograph, and a platform that
     * discovered it was being abused would want it stopped in seconds rather
     * than in a release.
     */
    public static final String SAFE_DROP = "delivery.safe-drop";

    /** Whether somebody may check out without an account at all. */
    public static final String GUEST_CHECKOUT = "checkout.guest";

    /** Whether the platform is accepting new seller applications. */
    public static final String VENDOR_SIGNUP = "vendor.applications";

    /** Whether administrators may open a session as somebody else. */
    public static final String IMPERSONATION = "admin.impersonation";

    private record Declared(String key, String label, String description,
                            boolean defaultOn, boolean clientVisible) {}

    private static final List<Declared> DECLARED = List.of(
            new Declared(SAFE_DROP, "Safe drop",
                    "Lets a driver leave a parcel without a code where the recipient has "
                            + "authorised it in advance. Off, every delivery needs a code at the "
                            + "door — slower, and impossible for somebody who is out at work.",
                    true, true),
            new Declared(GUEST_CHECKOUT, "Guest checkout",
                    "Lets somebody buy without an account. Off, every buyer must register "
                            + "first, which costs orders from people sending goods home once.",
                    true, true),
            new Declared(VENDOR_SIGNUP, "Seller applications",
                    "Whether new sellers may apply. Off while a backlog is cleared — existing "
                            + "sellers are unaffected and keep trading.",
                    true, true),
            new Declared(IMPERSONATION, "Administrator impersonation",
                    "Lets an administrator open a session as another user for support work. "
                            + "Every use is audited. Off, support must work from what the person "
                            + "tells them.",
                    // Not client-visible: telling a browser whether impersonation
                    // is available tells anybody looking how the platform is
                    // defended.
                    true, false));

    private final FeatureFlagRepository flags;

    private volatile Map<String, Boolean> cached = Map.of();
    private final AtomicLong readAt = new AtomicLong(0);

    public FeatureFlags(FeatureFlagRepository flags) {
        this.flags = flags;
    }

    /**
     * Writes any declared flag that has no row yet.
     *
     * <p>On startup rather than by migration, so a flag added in code exists the
     * first time the application runs with it. Existing rows are left alone —
     * an administrator's decision outranks the default it was born with.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureDeclared() {
        int created = 0;
        for (Declared declared : DECLARED) {
            if (flags.existsByFlagKey(declared.key())) {
                continue;
            }
            flags.save(FeatureFlag.builder()
                    .flagKey(declared.key())
                    .label(declared.label())
                    .description(declared.description())
                    .enabled(declared.defaultOn())
                    .clientVisible(declared.clientVisible())
                    .lastChangeReason("Created with its default when the flag was introduced.")
                    .build());
            created++;
        }
        if (created > 0) {
            log.info("[Flags] {} flag(s) created at their defaults", created);
        }
        invalidate();
    }

    /**
     * Whether a feature is on.
     *
     * <p>An unknown key is <em>on</em>. This is read on the delivery path, and a
     * typo that silently switched safe drop off for every parcel would be worse
     * than one that silently left it on — the first breaks deliveries for people
     * who are out at work, the second changes nothing.
     */
    public boolean isOn(String key) {
        return snapshot().getOrDefault(key, Boolean.TRUE);
    }

    public boolean isOff(String key) {
        return !isOn(key);
    }

    /** Drops the held values, so the next read goes to the database. */
    public void invalidate() {
        readAt.set(0);
    }

    private Map<String, Boolean> snapshot() {
        long last = readAt.get();
        long now = System.currentTimeMillis();
        if (last != 0 && now - last < CACHE_FOR.toMillis()) {
            return cached;
        }
        Map<String, Boolean> built = new HashMap<>();
        for (FeatureFlag flag : flags.findAll()) {
            built.put(flag.getFlagKey(), flag.isEnabled());
        }
        cached = Map.copyOf(built);
        readAt.set(now);
        return cached;
    }
}
