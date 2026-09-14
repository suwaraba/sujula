package com.sujula.repository.aftersales;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.aftersales.DisputeMessage;

@Repository
public interface DisputeMessageRepository extends JpaRepository<DisputeMessage, Long> {

    List<DisputeMessage> findByDisputeIdOrderByCreatedAtAscIdAsc(Long disputeId);

    /**
     * The case file as a party sees it — moderators' notes left out.
     *
     * <p>Filtered in the query rather than after it. A moderator writing "the
     * custody chain shows this was collected from a counter, so the buyer is
     * probably mistaken" must not have that read back to the buyer, and a filter
     * applied in a mapper is one somebody later forgets to apply.
     */
    @Query("SELECT m FROM DisputeMessage m WHERE m.dispute.id = :disputeId "
         + "AND m.internal = FALSE ORDER BY m.createdAt ASC, m.id ASC")
    List<DisputeMessage> findVisibleToParties(@Param("disputeId") Long disputeId);

    long countByDisputeId(Long disputeId);
}
