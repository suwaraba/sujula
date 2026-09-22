package com.sujula.repository;

import com.sujula.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Page<Notification> findByUserIdAndReadFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);
    long countByUserIdAndReadFalse(Long userId);

    /**
     * Bulk update, so it bypasses the persistence context — clear it afterwards
     * or a read later in the same transaction still reports the old flags.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.user.id = :userId AND n.read = false")
    void markAllReadByUserId(@Param("userId") Long userId);
}
