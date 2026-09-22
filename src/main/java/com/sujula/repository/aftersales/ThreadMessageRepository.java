package com.sujula.repository.aftersales;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.aftersales.ThreadMessage;

@Repository
public interface ThreadMessageRepository extends JpaRepository<ThreadMessage, Long> {

    Page<ThreadMessage> findByThreadIdOrderByCreatedAtAscIdAsc(Long threadId, Pageable pageable);

    List<ThreadMessage> findByThreadIdOrderByCreatedAtAscIdAsc(Long threadId);

    /**
     * How many the other side has sent in a window.
     *
     * <p>What the rate limit reads. A seller who can send forty messages an hour
     * to somebody who once asked about a charger is a seller with a broadcast
     * channel, which is not what this is.
     */
    @Query("SELECT COUNT(m) FROM ThreadMessage m WHERE m.thread.id = :threadId "
         + "AND m.sender.id = :userId AND m.createdAt > :since")
    long countRecentFrom(@Param("threadId") Long threadId, @Param("userId") Long userId,
                         @Param("since") java.time.LocalDateTime since);

    /**
     * Marks the other side's messages read, in one statement.
     *
     * <p>A bulk update rather than a loop. Opening a thread with two hundred
     * messages in it must not be two hundred writes, and the unread counter on
     * the thread is recomputed from this rather than decremented.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("UPDATE ThreadMessage m SET m.readAt = :now WHERE m.thread.id = :threadId "
         + "AND m.sender.id <> :readerId AND m.readAt IS NULL")
    int markRead(@Param("threadId") Long threadId, @Param("readerId") Long readerId,
                 @Param("now") java.time.LocalDateTime now);

    @Query("SELECT COUNT(m) FROM ThreadMessage m WHERE m.thread.id = :threadId "
         + "AND m.sender.id <> :readerId AND m.readAt IS NULL")
    int countUnreadFor(@Param("threadId") Long threadId, @Param("readerId") Long readerId);
}
