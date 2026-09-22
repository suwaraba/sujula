package com.sujula.repository.finance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.finance.ReportExport;

public interface ReportExportRepository extends JpaRepository<ReportExport, Long> {

    Optional<ReportExport> findByReference(String reference);

    @Query("SELECT e FROM ReportExport e WHERE e.status = :status ORDER BY e.createdAt ASC LIMIT :limit")
    List<ReportExport> findQueued(@Param("status") ReportExportStatus status,
                                  @Param("limit") int limit);

    @Query("SELECT e FROM ReportExport e "
         + "WHERE (:requestedBy IS NULL OR e.requestedByUserId = :requestedBy) "
         + "AND (:status IS NULL OR e.status = :status) "
         + "ORDER BY e.createdAt DESC")
    Page<ReportExport> search(@Param("requestedBy") Long requestedBy,
                              @Param("status") ReportExportStatus status,
                              Pageable pageable);

    /**
     * How many exports this person has asked for since a moment.
     *
     * <p>This is the rate limit, and it is counted per person rather than per
     * platform. The file is every vendor's earnings; ten of them in an hour from
     * one account is the shape of an account somebody else is using.
     */
    @Query("SELECT COUNT(e) FROM ReportExport e "
         + "WHERE e.requestedByUserId = :userId AND e.createdAt >= :since")
    long countSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Built files whose link has lapsed and whose row has not caught up. */
    @Query("SELECT e FROM ReportExport e WHERE e.status = "
         + "com.sujula.model.constant.ReportExportStatus.READY "
         + "AND e.resultExpiresAt IS NOT NULL AND e.resultExpiresAt < :now")
    List<ReportExport> findLapsed(@Param("now") LocalDateTime now);
}
