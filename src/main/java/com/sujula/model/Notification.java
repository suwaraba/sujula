package com.sujula.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notifications")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String message;

    /**
     * What happened, as a value rather than a string somebody typed.
     *
     * <p>Was free text, and the preference matrix is what forced the change: a
     * user cannot hold an opinion about "ORDER" if half the code writes "ORDER"
     * and the other half writes "ORDER_UPDATE". The column keeps its name so the
     * rows that exist keep working.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    @Builder.Default
    private NotificationEvent event = NotificationEvent.GENERAL;

    /** The order number, parcel reference or dispute reference this is about. */
    private String referenceId;

    /**
     * Which channels this actually went out on, comma-separated.
     *
     * <p>Kept because "did he get the email" is the first question support asks,
     * and because a user who turned email off and then wonders why they were not
     * told deserves an answer that is on the row rather than reconstructed from
     * what their preferences happen to say today.
     */
    @Column(length = 60)
    private String sentOn;

    /**
     * Column is {@code is_read}, not {@code read}.
     *
     * <p>{@code read} is a reserved word in MySQL, so the generated
     * {@code create table notifications (... read bit not null ...)} was a syntax
     * error — the table was never created, the application started anyway because
     * Hibernate logs DDL failures and continues, and every notification write
     * would then have failed against a table that did not exist.
     */
    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
