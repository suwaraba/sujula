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
 * Something one side put in, as a file rather than a sentence.
 *
 * <p>Separate from a message because the two are read differently. A moderator
 * reads the messages to see what happened and looks at the evidence to check it,
 * and a photograph buried in the middle of an argument is one nobody finds.
 *
 * <p>What is <em>not</em> here is as deliberate: the custody chain is already
 * evidence and nobody uploads it. A parcel's collection code, position,
 * photograph and the counter it passed through are recorded as they happen, and
 * a dispute about whether something arrived is answered by reading them rather
 * than by asking the driver to prove himself after the fact.
 */
@Entity
@Table(name = "dispute_evidence",
       indexes = @Index(name = "idx_dispute_evidence_dispute", columnList = "dispute_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisputeEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispute_id", nullable = false)
    private Dispute dispute;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id")
    private User uploadedBy;

    /** Which side put it in, frozen at the time for the same reason a message's is. */
    @Column(nullable = false, length = 12)
    private String uploadedBySide;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(length = 100)
    private String contentType;

    /** What it is meant to show. Without this a moderator has a picture of a box. */
    @Column(length = 300)
    private String caption;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
