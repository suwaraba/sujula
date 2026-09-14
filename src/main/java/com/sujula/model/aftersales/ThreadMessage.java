package com.sujula.model.aftersales;

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
 * One message, and what it said before the filter got to it.
 *
 * <p>Both are kept. {@link #body} is what everybody reads and has contact
 * details taken out of it; {@link #originalBody} is what was typed, and only a
 * moderator with a reason ever sees it.
 *
 * <p>Keeping the original is the part worth arguing about, and it earns its
 * place twice. A buyer whose message was mangled by an over-eager filter needs
 * somebody to be able to see that it was innocent — an order number is a long
 * run of digits and so is a telephone number. And a seller repeatedly trying to
 * move buyers off the platform leaves a pattern that only exists if the attempts
 * were kept.
 */
@Entity
@Table(name = "thread_messages",
       indexes = {
           @Index(name = "idx_thread_message_thread", columnList = "thread_id"),
           @Index(name = "idx_thread_message_filtered", columnList = "filtered")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThreadMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id", nullable = false)
    private MessageThread thread;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User sender;

    /** BUYER or VENDOR, frozen at the time rather than derived on every read. */
    @Column(nullable = false, length = 12)
    private String senderSide;

    /** What everybody reads. Contact details are already out of this. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    /** What was typed. Null when the filter changed nothing. */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String originalBody;

    /** Whether anything was taken out. Drives the notice shown under the message. */
    @Column(nullable = false)
    @Builder.Default
    private boolean filtered = false;

    /**
     * What kinds of thing were removed, comma-separated.
     *
     * <p>Kinds rather than values: "PHONE,EMAIL" tells the sender what to stop
     * doing and tells a moderator what to look for, and storing the number
     * itself in a second column would be keeping exactly the thing that was
     * taken out.
     */
    @Column(length = 100)
    private String filteredKinds;

    private LocalDateTime readAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
