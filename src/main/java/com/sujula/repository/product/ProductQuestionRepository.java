package com.sujula.repository.product;

import com.sujula.model.products.ProductQuestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductQuestionRepository extends JpaRepository<ProductQuestion, Long> {

    /**
     * The questions a shopper may see on a listing.
     *
     * <p>Moderation is in the query, not applied afterwards. A filter that lives
     * in a service is one an endpoint can be written without; a question awaiting
     * review is not merely hidden here, it is not returned.
     *
     * <p>Answered first, because an answered question is what a shopper came for.
     */
    @Query("SELECT q FROM ProductQuestion q JOIN FETCH q.askedBy "
         + "WHERE q.product.id = :productId AND q.approvedAt IS NOT NULL AND q.rejectedAt IS NULL "
         + "ORDER BY CASE WHEN q.answer IS NULL THEN 1 ELSE 0 END ASC, q.createdAt DESC")
    Page<ProductQuestion> findPublished(@Param("productId") Long productId, Pageable pageable);

    /** One question, with everything a response needs, and only if it is public. */
    @Query("SELECT q FROM ProductQuestion q JOIN FETCH q.askedBy "
         + "WHERE q.id = :id AND q.approvedAt IS NOT NULL AND q.rejectedAt IS NULL")
    Optional<ProductQuestion> findPublishedById(@Param("id") Long id);

    /** The moderation queue. */
    @Query("SELECT q FROM ProductQuestion q JOIN FETCH q.askedBy JOIN FETCH q.product "
         + "WHERE q.approvedAt IS NULL AND q.rejectedAt IS NULL ORDER BY q.createdAt ASC")
    Page<ProductQuestion> findPending(Pageable pageable);

    /**
     * How many questions one person has in the queue.
     *
     * <p>What makes the per-asker limit enforceable. Without it, one account can
     * fill a seller's page faster than a moderator can read it, and the cost of
     * doing so is a few seconds.
     */
    @Query("SELECT COUNT(q) FROM ProductQuestion q WHERE q.askedBy.id = :userId "
         + "AND q.approvedAt IS NULL AND q.rejectedAt IS NULL")
    long countPendingByAsker(@Param("userId") Long userId);

    /** Whether this person already asked this exact thing here. */
    @Query("SELECT COUNT(q) > 0 FROM ProductQuestion q WHERE q.product.id = :productId "
         + "AND q.askedBy.id = :userId AND LOWER(TRIM(q.question)) = LOWER(TRIM(:question))")
    boolean existsSameQuestion(@Param("productId") Long productId,
                               @Param("userId") Long userId,
                               @Param("question") String question);
}
