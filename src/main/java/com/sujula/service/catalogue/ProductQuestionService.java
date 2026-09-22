package com.sujula.service.catalogue;

import com.sujula.dto.response.catalogue.CatalogueResponses;
import org.springframework.data.domain.Pageable;

/**
 * Questions shoppers ask about a listing.
 *
 * <p>Worth more on this marketplace than most. A buyer in Madrid cannot pick the
 * phone up, and the person who will actually use it is not in the conversation —
 * so "does this charger have a UK plug" is not idle curiosity, it is the only
 * way to find out.
 *
 * <p>Reading is public. Asking is not: public text on someone else's shopfront
 * is a spam channel with no cost to the sender unless there is an account behind
 * it, and it stays invisible until a moderator approves it.
 */
public interface ProductQuestionService {

    /** What a shopper sees: approved questions, answered ones first. */
    CatalogueResponses.QuestionPage published(Long productId, Pageable pageable);

    /**
     * Records a question for moderation.
     *
     * @param userId the asker. Required — an anonymous question is a free channel
     *               for anything anyone wants on a seller's page
     * @return an acknowledgement, deliberately not the question. Returning it as
     *         though it were live would have the asker refresh and think it was
     *         lost
     */
    CatalogueResponses.QuestionSubmitted ask(Long productId, Long userId, String question);
}
