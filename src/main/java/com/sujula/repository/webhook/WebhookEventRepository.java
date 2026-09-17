package com.sujula.repository.webhook;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.constant.WebhookKind;
import com.sujula.model.constant.WebhookStatus;
import com.sujula.model.webhook.WebhookEvent;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByProviderAndEventId(String provider, String eventId);

    boolean existsByProviderAndEventId(String provider, String eventId);

    @Query("SELECT e FROM WebhookEvent e WHERE e.status IN ("
         + "  com.sujula.model.constant.WebhookStatus.RECEIVED, "
         + "  com.sujula.model.constant.WebhookStatus.RETRYING) "
         + "ORDER BY e.receivedAt ASC LIMIT :limit")
    List<WebhookEvent> findPending(@Param("limit") int limit);

    @Query("SELECT e FROM WebhookEvent e "
         + "WHERE (:kind IS NULL OR e.kind = :kind) "
         + "AND (:provider IS NULL OR e.provider = :provider) "
         + "AND (:status IS NULL OR e.status = :status) "
         + "ORDER BY e.receivedAt DESC, e.id DESC")
    Page<WebhookEvent> search(@Param("kind") WebhookKind kind,
                              @Param("provider") String provider,
                              @Param("status") WebhookStatus status,
                              Pageable pageable);

    /**
     * Refusals in a window, per provider.
     *
     * <p>The number worth watching. One rejection is a clock drifting; fifty in
     * a minute is somebody trying signatures, and a platform that discarded them
     * would have nothing to count.
     */
    @Query("SELECT e.provider, COUNT(e) FROM WebhookEvent e "
         + "WHERE e.status = com.sujula.model.constant.WebhookStatus.REJECTED "
         + "AND e.receivedAt >= :since GROUP BY e.provider")
    List<Object[]> rejectionsSince(@Param("since") LocalDateTime since);

    long countByStatus(WebhookStatus status);
}
