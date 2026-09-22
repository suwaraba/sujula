package com.sujula.repository.platform;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.platform.CallbackRequest;

public interface CallbackRequestRepository extends JpaRepository<CallbackRequest, Long> {

    List<CallbackRequest> findByDisputeIdOrderByRequestedAtDesc(Long disputeId);

    /**
     * Calls somebody still owes, oldest deadline first.
     *
     * <p>A call promised and not made is worse than one never promised: the
     * person is waiting by a phone. Ordering by the deadline rather than by when
     * it was asked for puts the ones about to be broken at the top.
     */
    @Query("SELECT c FROM CallbackRequest c "
         + "WHERE c.outcome IS NULL OR c.outcome IN ("
         + "  com.sujula.model.constant.CallbackOutcome.NO_ANSWER, "
         + "  com.sujula.model.constant.CallbackOutcome.RESCHEDULED) "
         + "ORDER BY c.callBy ASC NULLS LAST, c.requestedAt ASC")
    Page<CallbackRequest> findOutstanding(Pageable pageable);

    @Query("SELECT COUNT(c) FROM CallbackRequest c "
         + "WHERE (c.outcome IS NULL OR c.outcome IN ("
         + "  com.sujula.model.constant.CallbackOutcome.NO_ANSWER, "
         + "  com.sujula.model.constant.CallbackOutcome.RESCHEDULED)) "
         + "AND c.callBy IS NOT NULL AND c.callBy < :now")
    long countOverdue(@Param("now") LocalDateTime now);
}
