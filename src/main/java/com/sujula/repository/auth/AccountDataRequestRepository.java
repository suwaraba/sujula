package com.sujula.repository.auth;

import com.sujula.model.auth.AccountDataRequest;
import com.sujula.model.constant.DataRequestStatus;
import com.sujula.model.constant.DataRequestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountDataRequestRepository extends JpaRepository<AccountDataRequest, Long> {

    /**
     * The open request of this kind, if there is one.
     *
     * <p>This is what makes the endpoints idempotent: asking again while one is
     * pending returns that one rather than queueing another, so a retried request
     * on a bad connection cannot start two erasures.
     */
    @Query("SELECT r FROM AccountDataRequest r WHERE r.user.id = :userId AND r.type = :type "
         + "AND r.status IN :open ORDER BY r.requestedAt DESC LIMIT 1")
    Optional<AccountDataRequest> findOpen(@Param("userId") Long userId,
                                          @Param("type") DataRequestType type,
                                          @Param("open") List<DataRequestStatus> open);

    /** The latest of a kind, open or not — what {@code GET /me/export} reports on. */
    @Query("SELECT r FROM AccountDataRequest r WHERE r.user.id = :userId AND r.type = :type "
         + "ORDER BY r.requestedAt DESC LIMIT 1")
    Optional<AccountDataRequest> findLatest(@Param("userId") Long userId,
                                            @Param("type") DataRequestType type);

    Optional<AccountDataRequest> findByIdAndUserId(Long id, Long userId);

    /** The worker's queue. Fetches the user because every job needs it. */
    @Query("SELECT r FROM AccountDataRequest r JOIN FETCH r.user WHERE r.status = :status "
         + "ORDER BY r.requestedAt ASC LIMIT :limit")
    List<AccountDataRequest> findQueued(@Param("status") DataRequestStatus status, @Param("limit") int limit);
}
