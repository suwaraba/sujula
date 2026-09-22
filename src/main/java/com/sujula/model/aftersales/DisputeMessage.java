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
 * Something one side said about the case.
 *
 * <p>Append-only, and nothing here is edited. A dispute is decided by reading
 * what both sides said in the order they said it, and a message somebody could
 * rewrite afterwards is not evidence of anything.
 */
@Entity
@Table(name = "dispute_messages",
       indexes = @Index(name = "idx_dispute_message_dispute", columnList = "dispute_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisputeMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispute_id", nullable = false)
    private Dispute dispute;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id")
    private User author;

    /**
     * Which side wrote it, frozen at the time.
     *
     * <p>Derived from the author when the row is written rather than worked out
     * on every read. A user's role can change — a buyer who later opens a shop
     * is not a seller retroactively — and a case file that re-labelled old
     * messages would misrepresent who was arguing what.
     */
    @Column(nullable = false, length = 12)
    private String authorSide;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    /**
     * Whether this is a note between moderators rather than to the parties.
     *
     * <p>Kept on the same thread rather than in a separate table so the order of
     * events survives, and filtered out of what either side reads. A moderator
     * writing "the custody chain shows this was collected at a counter" needs
     * somewhere to put it that is not a public reply.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean internal = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
