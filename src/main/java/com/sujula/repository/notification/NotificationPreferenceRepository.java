package com.sujula.repository.notification;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.notification.NotificationPreference;

@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    /**
     * Everything this user has an opinion about.
     *
     * <p>Read as a set and resolved in memory rather than queried per lookup: a
     * single notification would otherwise be three round trips, and the whole
     * matrix is at most a few dozen rows.
     */
    List<NotificationPreference> findByUserId(Long userId);

    Optional<NotificationPreference> findByUserIdAndEventAndChannel(
            Long userId, NotificationEvent event, NotificationChannel channel);
}
