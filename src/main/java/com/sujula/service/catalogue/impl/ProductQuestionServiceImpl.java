package com.sujula.service.catalogue.impl;

import com.sujula.dto.response.catalogue.CatalogueResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductQuestion;
import com.sujula.model.user.User;
import com.sujula.repository.product.ProductQuestionRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.catalogue.ProductQuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Product questions, held for moderation.
 *
 * <p>Two limits, and both exist because this endpoint writes public text onto
 * somebody else's shopfront. One account should not be able to fill a seller's
 * page faster than a moderator can read it, and the same question asked four
 * times is three wasted moderator decisions.
 */
@Slf4j
@Service
public class ProductQuestionServiceImpl implements ProductQuestionService {

    /**
     * How many unreviewed questions one person may have outstanding.
     *
     * <p>Not a rate limit on asking — a limit on the queue. Somebody researching
     * a purchase might genuinely ask five things across five listings; somebody
     * posting a phone number on every product in a category will hit this on the
     * sixth and stop being a moderator's problem.
     */
    private static final int MAX_PENDING_PER_ASKER = 5;

    private static final int MIN_LENGTH = 10;
    private static final int MAX_LENGTH = 500;

    private final ProductQuestionRepository questions;
    private final ProductRepository products;
    private final UserRepository users;

    public ProductQuestionServiceImpl(ProductQuestionRepository questions,
                                      ProductRepository products, UserRepository users) {
        this.questions = questions;
        this.products = products;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogueResponses.QuestionPage published(Long productId, Pageable pageable) {
        if (!products.existsById(productId)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        Page<ProductQuestion> page = questions.findPublished(productId, pageable);

        return new CatalogueResponses.QuestionPage(
                page.getContent().stream().map(ProductQuestionServiceImpl::toResponse).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    public CatalogueResponses.QuestionSubmitted ask(Long productId, Long userId, String question) {
        String text = question == null ? "" : question.trim();
        if (text.length() < MIN_LENGTH) {
            throw new BadRequestException(
                    "A question needs at least " + MIN_LENGTH + " characters — enough for a seller "
                            + "to know what is being asked.");
        }
        if (text.length() > MAX_LENGTH) {
            throw new BadRequestException(
                    "A question can be at most " + MAX_LENGTH + " characters.");
        }

        Product product = products.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        if (questions.existsSameQuestion(productId, userId, text)) {
            throw new BadRequestException(
                    "You have already asked this about this product. It will appear once a "
                            + "moderator has reviewed it.");
        }
        if (questions.countPendingByAsker(userId) >= MAX_PENDING_PER_ASKER) {
            throw new BadRequestException(
                    "You have " + MAX_PENDING_PER_ASKER + " questions waiting for review. Please "
                            + "wait for those before asking more.");
        }

        User asker = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // approvedAt stays null. Visibility is a consequence of somebody
        // approving it, never of the row existing.
        ProductQuestion saved = questions.save(ProductQuestion.builder()
                .product(product)
                .askedBy(asker)
                .question(text)
                .createdAt(LocalDateTime.now())
                .build());

        log.info("[Catalogue] Question {} asked by user {} on product {}, awaiting moderation",
                saved.getId(), userId, productId);

        return new CatalogueResponses.QuestionSubmitted(saved.getId(), "PENDING_REVIEW",
                "Your question has been sent to the seller. It will appear on the listing once it "
                        + "has been reviewed.");
    }

    private static CatalogueResponses.Question toResponse(ProductQuestion question) {
        return new CatalogueResponses.Question(
                question.getId(),
                // First names only. A question is public text and the asker did
                // not consent to having their full name on a seller's page.
                firstNameOf(question.getAskedBy()),
                question.getQuestion(),
                question.getAnswer(),
                question.getAnsweredBy() == null ? null : firstNameOf(question.getAnsweredBy()),
                question.getAnsweredAt(),
                question.getCreatedAt());
    }

    private static String firstNameOf(User user) {
        if (user == null || user.getFirstName() == null || user.getFirstName().isBlank()) {
            return "A shopper";
        }
        return user.getFirstName();
    }
}
