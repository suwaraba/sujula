package com.sujula.model.notification;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One answer to "tell me about this, on that".
 *
 * <p><strong>Only the answers that differ from the default are stored.</strong>
 * A user who has never opened the settings page has no rows here at all, and
 * gets whatever {@link NotificationEvent#isOnByDefault} says. Writing the whole
 * matrix on registration would be a hundred rows per person, and — worse — would
 * freeze today's defaults into every account ever created, so a default nobody
 * had an opinion about could never be improved.
 *
 * <p>Which makes the absence of a row meaningful rather than missing: it means
 * "no opinion", and that is a third state distinct from on and off.
 */
@Entity
@Table(name = "notification_preferences",
       uniqueConstraints = @UniqueConstraint(name = "uq_notification_preference",
               columnNames = {"user_id", "event", "channel"}),
       indexes = @Index(name = "idx_notification_preference_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 40)
    private NotificationEvent event;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 12)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
