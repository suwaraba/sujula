package com.sujula.service.notification;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.notification.NotificationPreference;
import com.sujula.repository.notification.NotificationPreferenceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Whether to tell somebody about something, on a given channel.
 *
 * <p>Three states, not two. A row saying yes, a row saying no, and — the
 * commonest by far — no row at all, which means "no opinion" and falls through
 * to the event's own default. Storing the whole matrix per user would be a
 * hundred rows each and would freeze today's defaults into every account ever
 * created, so a default nobody had an opinion about could never be improved.
 *
 * <p>Two things override a stored preference, in this order:
 *
 * <ol>
 *   <li>A <b>mandatory event</b> is sent whatever anybody said. The five of them
 *       are each a case where silence costs somebody something they cannot get
 *       back — a parcel they cannot collect, a freeze they did not know about, a
 *       payout that bounced, or somebody else in their account.</li>
 *   <li>The <b>inbox</b> cannot be switched off for a mandatory event, because
 *       it is the record rather than a message: somebody who turned everything
 *       off still has to be able to find out what happened.</li>
 * </ol>
 *
 * <p>Both live on the enums rather than here. A rule in this class is one a
 * second dispatcher would not have.
 */
@Slf4j
@Component
public class NotificationPreferences {

    private final NotificationPreferenceRepository stored;

    public NotificationPreferences(NotificationPreferenceRepository stored) {
        this.stored = stored;
    }

    /** Whether this user gets told about this, here. */
    @Transactional(readOnly = true)
    public boolean isEnabled(Long userId, NotificationEvent event, NotificationChannel channel) {
        return resolve(matrixOf(userId), event, channel);
    }

    /**
     * The whole matrix, resolved — every event on every channel, with what the
     * user would actually get.
     *
     * <p>Returned in full rather than as the sparse rows, because a settings page
     * that only showed the overrides would show almost nothing to almost
     * everybody.
     */
    @Transactional(readOnly = true)
    public Map<NotificationEvent, Map<NotificationChannel, Boolean>> resolveAll(Long userId) {
        Map<NotificationEvent, Map<NotificationChannel, Boolean>> overrides = matrixOf(userId);
        Map<NotificationEvent, Map<NotificationChannel, Boolean>> out =
                new EnumMap<>(NotificationEvent.class);
        for (NotificationEvent event : NotificationEvent.values()) {
            Map<NotificationChannel, Boolean> row = new EnumMap<>(NotificationChannel.class);
            for (NotificationChannel channel : NotificationChannel.values()) {
                row.put(channel, resolve(overrides, event, channel));
            }
            out.put(event, row);
        }
        return out;
    }

    /**
     * Whether the user is even allowed to change this one.
     *
     * <p>Part of the read rather than something the client works out, so a
     * settings page renders a locked switch with a reason instead of one that
     * silently does nothing.
     */
    public boolean isLocked(NotificationEvent event, NotificationChannel channel) {
        return event.isMandatory() && !channel.isOptionalForImportantEvents()
                || event.isMandatory() && channel != NotificationChannel.PUSH
                   && channel != NotificationChannel.EMAIL;
    }

    /**
     * Records one answer, or removes it where it agrees with the default.
     *
     * <p>Removing rather than storing "yes" when yes is already the default keeps
     * the table sparse and — more usefully — keeps "no opinion" expressible. A
     * user who sets something back to how it came should stop having an opinion
     * about it rather than pin the current default forever.
     *
     * @return whether anything changed
     */
    @Transactional
    public boolean set(com.sujula.model.user.User user, NotificationEvent event,
                       NotificationChannel channel, boolean enabled) {
        if (event.isMandatory() && !enabled) {
            // Refused quietly rather than loudly: a client that sends the whole
            // matrix back should not fail because one switch in it is locked.
            log.debug("[Notifications] Ignoring an attempt to switch off {} on {} for user {}",
                    event, channel, user.getId());
            return false;
        }

        var existing = stored.findByUserIdAndEventAndChannel(user.getId(), event, channel);
        boolean isDefault = enabled == event.isOnByDefault(channel);

        if (isDefault) {
            if (existing.isPresent()) {
                stored.delete(existing.get());
                return true;
            }
            return false;
        }

        if (existing.isPresent()) {
            if (existing.get().isEnabled() == enabled) {
                return false;
            }
            existing.get().setEnabled(enabled);
            stored.save(existing.get());
            return true;
        }

        stored.save(NotificationPreference.builder()
                .user(user).event(event).channel(channel).enabled(enabled).build());
        return true;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Map<NotificationEvent, Map<NotificationChannel, Boolean>> matrixOf(Long userId) {
        Map<NotificationEvent, Map<NotificationChannel, Boolean>> overrides =
                new EnumMap<>(NotificationEvent.class);
        List<NotificationPreference> rows = stored.findByUserId(userId);
        for (NotificationPreference row : rows) {
            overrides.computeIfAbsent(row.getEvent(), key -> new EnumMap<>(NotificationChannel.class))
                    .put(row.getChannel(), row.isEnabled());
        }
        return overrides;
    }

    private static boolean resolve(Map<NotificationEvent, Map<NotificationChannel, Boolean>> overrides,
                                   NotificationEvent event, NotificationChannel channel) {
        if (event.isMandatory() && !channel.isOptionalForImportantEvents()) {
            // The inbox, for something that matters. Not negotiable, and checked
            // before the stored row so an old override cannot suppress it.
            return true;
        }
        Boolean said = overrides.getOrDefault(event, Map.of()).get(channel);
        if (said != null) {
            return event.isMandatory() || said;
        }
        return event.isOnByDefault(channel);
    }
}
