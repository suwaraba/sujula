package com.sujula.model.finance;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.constant.ReportType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Somebody asked for a file of the platform's money.
 *
 * <p>A job rather than a response, for two reasons. A year of ledger rows is not
 * something to build while a client waits on a socket — but more importantly,
 * this is the single most sensitive read on the platform: every vendor's
 * earnings, every buyer's spend, in one file that leaves the building. A request
 * that returned it inline would leave nothing behind but a line in an access
 * log. This leaves a row with a name on it.
 *
 * <p>Which is also why the result expires. A signed URL that lives forever is an
 * export somebody forwards in eighteen months, and the platform has no way to
 * know it happened.
 */
@Entity
@Table(name = "report_exports",
       indexes = {
           @Index(name = "idx_export_reference", columnList = "reference", unique = true),
           @Index(name = "idx_export_status",    columnList = "status"),
           @Index(name = "idx_export_requester", columnList = "requestedByUserId")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReportExportStatus status = ReportExportStatus.QUEUED;

    @Column(nullable = false)
    private Long requestedByUserId;

    @Column(nullable = false, length = 150)
    private String requestedByEmail;

    private LocalDate fromDate;
    private LocalDate toDate;

    /**
     * The currency the report is denominated in, when it is denominated at all.
     *
     * <p>Null means "one section per currency", which is the honest answer for a
     * platform whose vendors settle in three. A report that added dalasi to CFA
     * to produce a single revenue figure would be a report whose headline number
     * means nothing (C2).
     */
    @Column(length = 3)
    private String currency;

    /** Narrowing to one seller, when somebody is looking at one seller. */
    private Long vendorId;

    @Column(length = 10)
    @Builder.Default
    private String format = "CSV";

    @Column(length = 500)
    private String resultUrl;

    /** How many rows it turned out to be, once it has been built. */
    private Integer rowCount;

    /**
     * When the download stops working.
     *
     * <p>Short on purpose. See the class note: this file is every vendor's
     * earnings, and a link with no expiry is a file the platform has lost track
     * of.
     */
    private LocalDateTime resultExpiresAt;

    @Column(length = 1000)
    private String failureReason;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Whether the file is still there to be fetched. */
    public boolean isDownloadable() {
        return status == ReportExportStatus.READY
                && resultUrl != null
                && (resultExpiresAt == null || resultExpiresAt.isAfter(LocalDateTime.now()));
    }
}
