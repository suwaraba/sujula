package com.sujula.repository.finance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.finance.PayoutBatch;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, Long> {

    Optional<PayoutBatch> findByReference(String reference);

    boolean existsByReference(String reference);

    @Query("SELECT b FROM PayoutBatch b "
         + "WHERE (:status IS NULL OR b.status = :status) "
         + "AND (:currency IS NULL OR UPPER(b.currency) = UPPER(CAST(:currency AS String))) "
         + "ORDER BY b.preparedAt DESC, b.id DESC")
    Page<PayoutBatch> search(@Param("status") PayoutBatchStatus status,
                             @Param("currency") String currency,
                             Pageable pageable);

    /**
     * Runs sitting in front of an approver, oldest first.
     *
     * <p>Oldest first because a batch waiting three days is sellers waiting three
     * days, and a queue sorted the other way makes the stalest one the hardest
     * to find.
     */
    List<PayoutBatch> findByStatusOrderByPreparedAtAsc(PayoutBatchStatus status);

    /**
     * Whether a run for this currency is already open.
     *
     * <p>Two open batches in one currency is how the same vendor's balance is
     * committed twice. The assembler checks this before it starts rather than
     * discovering it when the second one is approved.
     */
    @Query("SELECT COUNT(b) > 0 FROM PayoutBatch b WHERE UPPER(b.currency) = UPPER(CAST(:currency AS String)) "
         + "AND b.status IN (com.sujula.model.constant.PayoutBatchStatus.DRAFT, "
         + "                 com.sujula.model.constant.PayoutBatchStatus.AWAITING_APPROVAL)")
    boolean hasOpenBatchFor(@Param("currency") String currency);

    @Query("SELECT b FROM PayoutBatch b WHERE b.status = "
         + "com.sujula.model.constant.PayoutBatchStatus.APPROVED AND b.approvedAt < :before")
    List<PayoutBatch> findReleasedBefore(@Param("before") LocalDateTime before);
}
