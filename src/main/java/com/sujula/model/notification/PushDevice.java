package com.sujula.model.notification;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A phone that has asked to be told things.
 *
 * <p>The token is the address, and it is not a secret in the sense a password
 * is — but it is not public either: anybody holding one can send that handset a
 * notification that looks like it came from us. So it is never returned by any
 * endpoint. What the user sees when they list their devices is the label and
 * when it was last used, which is what they need to recognise the old phone they
 * want to remove.
 *
 * <p>Tokens are reassigned by the operating system. The same token turning up
 * for a different user means the handset changed hands or the app was
 * reinstalled by somebody else, and the old registration is revoked rather than
 * left pointing at a person who no longer has that phone — otherwise the next
 * owner gets somebody else's delivery codes.
 */
@Entity
@Table(name = "push_devices",
       indexes = {
           @Index(name = "idx_push_device_user",  columnList = "user_id"),
           @Index(name = "idx_push_device_token", columnList = "token")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The provider's address for this handset.
     *
     * <p>{@code JsonIgnore} rather than merely unused by the mapper: the
     * annotation is what stops a future endpoint that serialises the entity
     * directly from publishing it.
     */
    @JsonIgnore
    @Column(nullable = false, length = 512)
    private String token;

    /** ANDROID, IOS or WEB. What decides how a payload is shaped. */
    @Column(nullable = false, length = 12)
    private String platform;

    /** What the user sees in the list: "Ebrima's Infinix", "Chrome on Windows". */
    @Column(length = 120)
    private String label;

    /**
     * When this device last proved it still exists.
     *
     * <p>Refreshed every time the app re-registers, which it does on each start.
     * A token that has not been seen for months is a phone that was sold, and
     * sending to it is how a stranger ends up holding somebody's parcel code.
     */
    private LocalDateTime lastSeenAt;

    /**
     * Turned off, without the row going.
     *
     * <p>Soft, so "this phone was removed on the 4th" survives. A stolen handset
     * is the case that makes it worth keeping.
     */
    private LocalDateTime revokedAt;

    @Column(length = 120)
    private String revokedReason;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Whether anything would actually be sent to this device. */
    public boolean isLive() {
        return revokedAt == null;
    }
}
