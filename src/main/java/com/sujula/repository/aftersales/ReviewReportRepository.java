package com.sujula.repository.aftersales;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.aftersales.ReviewReport;

@Repository
public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    Optional<ReviewReport> findByReviewIdAndReportedById(Long reviewId, Long userId);

    /**
     * How many distinct people have reported a review.
     *
     * <p>What {@code Review.reportCount} is recounted from rather than nudged.
     * The table already refuses a second report from the same person, so this
     * counts people rather than complaints — and a seller with two accounts
     * therefore moves this by two rather than by twenty.
     */
    @Query("SELECT COUNT(r) FROM ReviewReport r WHERE r.review.id = :reviewId")
    long countForReview(@Param("reviewId") Long reviewId);
}
