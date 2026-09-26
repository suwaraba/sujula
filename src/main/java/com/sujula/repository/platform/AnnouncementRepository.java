package com.sujula.repository.platform;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.constant.UserRole;
import com.sujula.model.platform.Announcement;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    boolean existsByReference(String reference);

    @Query("SELECT a FROM Announcement a "
         + "WHERE (:role IS NULL OR a.audienceRole = :role) "
         + "AND (:countryCode IS NULL OR UPPER(a.countryCode) = UPPER(CAST(:countryCode AS String))) "
         + "ORDER BY a.sentAt DESC NULLS FIRST, a.id DESC")
    Page<Announcement> search(@Param("role") UserRole role,
                              @Param("countryCode") String countryCode,
                              Pageable pageable);
}
