package com.sujula.model.catalogue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;

import com.sujula.model.constant.CatalogueJobStatus;
import com.sujula.model.constant.CatalogueJobType;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A bulk move of listings, in or out, run away from the request that asked for
 * it.
 *
 * <p>Asynchronous for a reason beyond duration. A seller uploading four hundred
 * rows over a mobile connection will lose the response long before the work
 * finishes, and a synchronous import would then have half-written their
 * catalogue with nobody able to say which half. A job has an id they can come
 * back to, which is the only design that survives the network these sellers
 * actually have.
 *
 * <p>Import and export share this table because they share everything that
 * matters about them — an owner, a state, a file, and a reason it failed. Two
 * tables would be two copies of the same polling endpoint.
 */
@Entity
@Table(name = "catalogue_jobs",
       indexes = {
           @Index(name = "idx_job_vendor",        columnList = "vendor_id"),
           @Index(name = "idx_job_status",        columnList = "status"),
           @Index(name = "idx_job_vendor_status", columnList = "vendor_id, status")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogueJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * What the seller is given to poll with.
     *
     * <p>Not the primary key. A job id in a URL that anyone can count through is
     * an invitation to read other sellers' import errors, and those name
     * products, prices and SKUs that are nobody else's business. Ownership is
     * still checked in the query — this is the second lock, not the only one.
     */
    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CatalogueJobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    @Builder.Default
    private CatalogueJobStatus status = CatalogueJobStatus.QUEUED;

    /** Where the uploaded file is, for an import. A storage key, never bytes. */
    @Column(length = 500)
    private String sourceUrl;

    @Column(length = 255)
    private String originalFilename;

    /** csv or xlsx, as detected rather than as claimed by the filename. */
    @Column(length = 10)
    private String format;

    /** Where the finished export landed, behind a link that expires. */
    @Column(length = 500)
    private String resultUrl;

    private LocalDateTime resultExpiresAt;

    @Builder.Default
    private Integer totalRows = 0;

    @Builder.Default
    private Integer succeededRows = 0;

    @Builder.Default
    private Integer failedRows = 0;

    /**
     * Why the whole job died, when no row was even attempted.
     *
     * <p>Distinct from the row errors below: "this is not a spreadsheet" and
     * "row 14 has no price" are different problems and need different words.
     */
    @Column(length = 500)
    private String failureReason;

    /**
     * What went wrong, row by row.
     *
     * <p>The reason this feature is worth building rather than telling sellers
     * to use the form. An import that reports "failed" has wasted their evening;
     * one that says "row 37: category 'Phonez' does not exist" has done the work
     * of finding it for them.
     */
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @Builder.Default
    private List<CatalogueJobError> errors = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public boolean isFinished() {
        return status == CatalogueJobStatus.COMPLETED
                || status == CatalogueJobStatus.COMPLETED_WITH_ERRORS
                || status == CatalogueJobStatus.FAILED;
    }
}
