package com.sujula.model.platform;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.UserRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Something the platform told a group of people at once.
 *
 * <p>A record rather than a loop, and that distinction earns its keep the first
 * time somebody asks "did the sellers in Senegal get told about the holiday
 * schedule". A broadcast that existed only as a burst of inbox rows is one
 * nobody can audit, repeat, or explain.
 *
 * <p>The segment is a role and a country, both optional. Country is the
 * <em>actor's own</em> country here — where a seller trades, where a driver
 * carries — which is the one place on this platform where that is the right
 * question to ask, because an announcement is about the person rather than about
 * a parcel. Nothing here is keyed on a delivery destination.
 */
@Entity
@Table(name = "announcements",
       indexes = {
           @Index(name = "idx_announcement_sent",    columnList = "sentAt"),
           @Index(name = "idx_announcement_segment", columnList = "audienceRole, countryCode")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 4000)
    private String body;

    /** Null means every role. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserRole audienceRole;

    /** ISO 3166-1 alpha-2 of the recipients' own country. Null means everywhere. */
    @Column(length = 2)
    private String countryCode;

    /**
     * Which event this counts as, so people's own preferences are honoured.
     *
     * <p>An announcement is not a licence to reach somebody who switched
     * marketing off. It goes out through the same dispatch as everything else,
     * and the preference resolver decides which channels each person actually
     * gets it on.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    @Builder.Default
    private NotificationEvent event = NotificationEvent.PLATFORM_NOTICE;

    @Column(nullable = false)
    private Long sentByUserId;

    private LocalDateTime sentAt;

    /**
     * How many people it reached, counted after the fact.
     *
     * <p>Recipients is not the size of the segment: somebody who has switched
     * every channel off for this event is in the segment and receives nothing,
     * and reporting the segment size as the reach would be reporting a number
     * that is not true.
     */
    @Setter(AccessLevel.NONE)
    @Column(nullable = false)
    @Builder.Default
    private int recipients = 0;

    @Setter(AccessLevel.NONE)
    @Column(nullable = false)
    @Builder.Default
    private int segmentSize = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Set once, after the send. */
    public void applyReach(int segmentSize, int recipients) {
        this.segmentSize = segmentSize;
        this.recipients = recipients;
    }
}
