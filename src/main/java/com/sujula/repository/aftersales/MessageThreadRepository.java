package com.sujula.repository.aftersales;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.aftersales.MessageThread;

@Repository
public interface MessageThreadRepository extends JpaRepository<MessageThread, Long> {

    /**
     * A thread the caller is actually in.
     *
     * <p>Both participants in the query, so there is no path that fetches a
     * thread and then checks. Messages between a buyer and a seller are the
     * most ordinary thing on the platform and the easiest to leak: every thread
     * looks the same from the outside and the ids are sequential.
     */
    @Query("SELECT t FROM MessageThread t WHERE t.id = :id "
         + "AND (t.buyer.id = :userId OR t.vendor.user.id = :userId)")
    Optional<MessageThread> findByIdForParticipant(@Param("id") Long id,
                                                   @Param("userId") Long userId);

    @Query("SELECT t FROM MessageThread t WHERE t.buyer.id = :userId OR t.vendor.user.id = :userId "
         + "ORDER BY t.lastMessageAt DESC")
    Page<MessageThread> findForParticipant(@Param("userId") Long userId, Pageable pageable);

    /**
     * The thread that already exists for this pairing, if there is one.
     *
     * <p>Looked up before a new one is opened. A buyer who asks about the same
     * order twice should land back in the conversation they were already having,
     * rather than starting a second one the seller has to notice.
     */
    @Query("SELECT t FROM MessageThread t WHERE t.buyer.id = :buyerId AND t.vendor.id = :vendorId "
         + "AND ((:orderId IS NOT NULL AND t.order.id = :orderId) "
         + "  OR (:productId IS NOT NULL AND t.product.id = :productId))")
    Optional<MessageThread> findExisting(@Param("buyerId") Long buyerId,
                                         @Param("vendorId") Long vendorId,
                                         @Param("orderId") Long orderId,
                                         @Param("productId") Long productId);
}
