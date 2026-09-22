package com.sujula.model.store;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.KycDocumentType;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One identity or business document a store submitted to be verified.
 *
 * <p>Rows here are append-only in practice: a rejected document is superseded by
 * a new upload rather than edited, so the history shows what was sent, when, and
 * why it came back. An onboarding dispute is always about that sequence.
 *
 * <p>The file itself lives in object storage and only its URL is here. A scan of
 * somebody's passport in a database column is a row that gets copied into a
 * backup, a staging dump and an analyst's laptop; behind a storage key it is one
 * object with its own access rules.
 */
@Entity
@Table(name = "kyc_documents",
       indexes = {
           @Index(name = "idx_kyc_vendor",        columnList = "vendor_id"),
           @Index(name = "idx_kyc_vendor_status", columnList = "vendor_id, status")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private KycDocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private KycDocumentStatus status = KycDocumentStatus.SUBMITTED;

    /** Where the file is. Never the file. */
    @Column(nullable = false, length = 500)
    private String fileUrl;

    /** What the applicant called it, shown back to them so they can tell two apart. */
    @Column(length = 255)
    private String originalFilename;

    @Column(length = 100)
    private String contentType;

    private Long sizeBytes;

    /**
     * Expiry as printed on the document, where it has one.
     *
     * <p>An accepted passport that expired last year is not evidence of anything,
     * and the only moment anyone can read the date off it is while they are
     * looking at it.
     */
    private java.time.LocalDate expiresOn;

    /**
     * Why it was refused, in words the applicant can act on.
     *
     * <p>Set whenever the status is REJECTED, and the service enforces that.
     * "Rejected" on its own sends somebody back to upload the same blurry
     * photograph.
     */
    @Column(length = 400)
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;

    private LocalDateTime reviewedAt;

    /**
     * Superseded by a later upload of the same type.
     *
     * <p>Set rather than deleted: the rejected version and the reason it was
     * rejected are the record of why onboarding took three weeks.
     */
    private LocalDateTime supersededAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime submittedAt;

    public boolean isLive() {
        return supersededAt == null;
    }
}
