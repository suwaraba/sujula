package com.sujula.repository.product;

import com.sujula.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /**
     * A product's reviews, with the reviewer fetched.
     *
     * <p>Joined rather than lazily loaded because every row renders a name: a
     * page of twenty reviews would otherwise be twenty-one queries, which is the
     * classic way a listing page gets slow without anybody noticing in
     * development.
     */
    @Query(value = "SELECT r FROM Review r JOIN FETCH r.user WHERE r.product.id = :productId",
           countQuery = "SELECT COUNT(r) FROM Review r WHERE r.product.id = :productId")
    Page<Review> findForProduct(@Param("productId") Long productId, Pageable pageable);

    /**
     * How many reviews sit at each star rating.
     *
     * <p>One grouped query rather than five counts. What a client needs to draw
     * the distribution bars, and what tells a shopper the difference between
     * four stars from consensus and four stars from a fight.
     */
    @Query("SELECT r.rating, COUNT(r) FROM Review r WHERE r.product.id = :productId "
         + "GROUP BY r.rating ORDER BY r.rating DESC")
    List<Object[]> ratingHistogram(@Param("productId") Long productId);

    /**
     * Which products this reviewer has already reviewed.
     *
     * <p>Ids only, and only theirs. The buyer's order page needs to know which
     * lines still offer a "write a review" button, and answering that by
     * loading every review on the platform and filtering in Java is a table scan
     * per page view.
     */
    @Query("SELECT r.product.id FROM Review r WHERE r.user.id = :userId AND r.product IS NOT NULL")
    List<Long> findProductIdsReviewedBy(@Param("userId") Long userId);
}
