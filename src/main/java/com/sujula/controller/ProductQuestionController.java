package com.sujula.controller;

import com.sujula.dto.response.catalogue.CatalogueResponses;
import com.sujula.service.catalogue.ProductQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Questions on a listing.
 *
 * <p>Reading is public — the answers are most of what a diaspora buyer has to go
 * on, since they cannot pick the item up and the person who will use it is not
 * in the conversation.
 *
 * <p>Asking requires an account, and the question is held for review. Both
 * follow from the same fact: this endpoint writes public text onto someone
 * else's shopfront, which without a cost to the sender is a spam channel.
 */
@RestController
@RequestMapping("/products/{productId}/questions")
@Tag(name = "catalogue", description = "Product questions and answers")
public class ProductQuestionController {

    private static final int MAX_PAGE_SIZE = 50;

    private final ProductQuestionService questions;
    private final AuthenticatedCaller caller;

    public ProductQuestionController(ProductQuestionService questions, AuthenticatedCaller caller) {
        this.questions = questions;
        this.caller = caller;
    }

    @GetMapping
    @Operation(summary = "Questions and answers on a listing",
               description = "Approved questions only, answered ones first — an answered question "
                       + "is what a shopper came for.")
    public ResponseEntity<CatalogueResponses.QuestionPage> questions(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return ResponseEntity.ok(questions.published(productId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Ask a question about a product",
               description = "Held for moderation: the response acknowledges receipt and the "
                       + "question does not appear until it has been reviewed. Returning it as "
                       + "though it were live would have the asker refresh and think it was lost.")
    public ResponseEntity<CatalogueResponses.QuestionSubmitted> ask(
            Authentication authentication,
            @PathVariable Long productId,
            @Valid @RequestBody AskQuestion request) {

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(questions.ask(productId, caller.userId(authentication), request.question()));
    }

    /** What a shopper wants to know. */
    public record AskQuestion(
            @NotBlank @Size(min = 10, max = 500) String question) {}
}
