package com.sujula.service.aftersales;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Review;
import com.sujula.model.aftersales.ThreadMessage;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ReviewReportReason;
import com.sujula.model.constant.ThreadSubject;
import com.sujula.model.constant.UserRole;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.aftersales.ThreadMessageRepository;
import com.sujula.repository.order.OrderItemRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ReviewRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.aftersales.impl.MessagingServiceImpl;
import com.sujula.service.aftersales.impl.ReviewModerationServiceImpl;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Talking to a seller, and what happens to a review after it is written.
 *
 * <p>The sharpest claims are that a thread cannot exist without an order or a
 * product behind it, that what was typed survives beside what was published, and
 * that reporting a review takes nothing down.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({MessagingServiceImpl.class, ReviewModerationServiceImpl.class, ContactDetailFilter.class})
class MessagingAndReviewServiceTest {

    @Autowired private MessagingServiceImpl messaging;
    @Autowired private ReviewModerationServiceImpl reviewModeration;
    @Autowired private ThreadMessageRepository threadMessages;
    @Autowired private ReviewRepository reviews;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private User buyer;
    private User stranger;
    private User sellerUser;
    private Vendor kombo;
    private Product galaxy;
    private Order order;

    @BeforeEach
    void setUp() {
        buyer = user("ousman@example.es", "Ousman", UserRole.CUSTOMER);
        stranger = user("nosy@example.es", "Nosy", UserRole.CUSTOMER);
        sellerUser = user("lamin@sujula.gm", "Lamin", UserRole.VENDOR);

        kombo = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-messages")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .build());

        galaxy = products.save(Product.builder()
                .name("Galaxy A15 128GB").slug("galaxy-a15-messages").vendor(kombo)
                .price(new BigDecimal("6075.00")).priceCurrency("GMD")
                .stock(4).active(true).country("GM")
                .build());

        order = new Order();
        order.setOrderNumber("SJL-MSG-0001");
        order.setSubtotal(new BigDecimal("90.00"));
        order.setTotal(new BigDecimal("90.00"));
        order.setCurrency("EUR");
        order.setCustomer(buyer);
        order = orders.save(order);

        orderItems.save(OrderItem.builder()
                .order(order).vendor(kombo).product(galaxy)
                .quantity(1)
                .unitPrice(new BigDecimal("6075.00")).totalPrice(new BigDecimal("6075.00"))
                .unitPriceConverted(new BigDecimal("90.00"))
                .totalPriceConverted(new BigDecimal("90.00"))
                .currency("GMD").productName("Galaxy A15 128GB")
                .build());

        entityManager.flush();
        entityManager.refresh(order);
    }

    private User user(String email, String firstName, UserRole role) {
        User person = new User();
        person.setEmail(email);
        person.setPassword("x");
        person.setFirstName(firstName);
        person.setLastName("Person");
        person.setRole(role);
        return users.save(person);
    }

    private AfterSalesResponses.ThreadDetail openAboutTheOrder(String body) {
        return messaging.openThread(buyer.getId(), new AfterSalesRequests.OpenThread(
                ThreadSubject.ORDER, order.getId(), null, "Charger missing", body));
    }

    // ── A thread is always about something ───────────────────────────────────

    @Test
    void aThreadAboutAnOrderCarriesTheOrderSoTheSellerKnowsWhichOne() {
        AfterSalesResponses.ThreadDetail thread = openAboutTheOrder("The charger was not in the box.");

        assertEquals(ThreadSubject.ORDER, thread.subject());
        assertEquals("SJL-MSG-0001", thread.orderNumber());
        assertEquals("Kombo Electronics", thread.withName());
        assertEquals(1, thread.messages().size());
    }

    @Test
    void aThreadAboutNothingCannotBeExpressed() {
        // The rule that stops this being a messaging service: nobody can open a
        // channel to a stranger, because there is no request shape for one.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> messaging.openThread(buyer.getId(), new AfterSalesRequests.OpenThread(
                        ThreadSubject.ORDER, null, null, "hello", "hello")));
        assertTrue(refused.getMessage().contains("one order or one product"));
    }

    @Test
    void aThreadAboutTwoThingsIsAlsoRefused() {
        assertThrows(BadRequestException.class,
                () -> messaging.openThread(buyer.getId(), new AfterSalesRequests.OpenThread(
                        ThreadSubject.ORDER, order.getId(), galaxy.getId(), "hello", "hello")));
    }

    @Test
    void aProductThreadNeedsNoPurchase() {
        // Somebody deciding whether to buy is exactly who needs to ask.
        AfterSalesResponses.ThreadDetail thread = messaging.openThread(stranger.getId(),
                new AfterSalesRequests.OpenThread(ThreadSubject.PRODUCT, null, galaxy.getId(),
                        "Is it dual sim?", "Is this one dual sim?"));

        assertEquals(ThreadSubject.PRODUCT, thread.subject());
        assertEquals(galaxy.getId(), thread.productId());
        assertNull(thread.orderNumber());
    }

    @Test
    void somebodyElsesOrderIsNotFound() {
        assertThrows(ResourceNotFoundException.class,
                () -> messaging.openThread(stranger.getId(), new AfterSalesRequests.OpenThread(
                        ThreadSubject.ORDER, order.getId(), null, "hello", "hello")));
    }

    @Test
    void askingAboutTheSameOrderTwiceLandsBackInTheSameConversation() {
        Long first = openAboutTheOrder("The charger was missing.").id();
        Long second = openAboutTheOrder("Any news?").id();

        // A second thread the seller has to notice is how somebody gets ignored.
        assertEquals(first, second);
        assertEquals(2, threadMessages.findByThreadIdOrderByCreatedAtAscIdAsc(first).size());
    }

    // ── The filter, in place ─────────────────────────────────────────────────

    @Test
    void aTelephoneNumberIsTakenOutAndTheSenderIsToldWhy() {
        Long threadId = openAboutTheOrder("The charger was missing.").id();

        AfterSalesResponses.MessageSent sent = messaging.send(sellerUser.getId(), threadId,
                new AfterSalesRequests.SendMessage(
                        "Sorry! Call me on 3100077 and I will send one today."));

        assertTrue(sent.message().filtered());
        assertFalse(sent.message().body().contains("3100077"));
        assertTrue(sent.message().body().contains("Sorry!"));
        assertEquals("PHONE", sent.message().filteredKinds());
        // Told rather than discovered. A silent edit is a lie, and one that
        // teaches people to write in code.
        assertNotNull(sent.notice());
        assertTrue(sent.notice().contains("held until the parcel is proven delivered"));
    }

    @Test
    void whatWasTypedSurvivesButOnlyForAModerator() {
        Long threadId = openAboutTheOrder("hello").id();
        messaging.send(sellerUser.getId(), threadId,
                new AfterSalesRequests.SendMessage("whatsapp me on 3100077"));
        entityManager.flush();

        ThreadMessage stored = threadMessages.findByThreadIdOrderByCreatedAtAscIdAsc(threadId)
                .get(1);
        // Kept: a buyer whose innocent sentence was mangled needs somebody to be
        // able to look, and a seller who tries this weekly leaves a pattern that
        // only exists if the attempts were kept.
        assertNotNull(stored.getOriginalBody());
        assertTrue(stored.getOriginalBody().contains("3100077"));

        // And never served. Serving it would make the filter decorative.
        AfterSalesResponses.ThreadDetail read = messaging.thread(buyer.getId(), threadId,
                PageRequest.of(0, 50));
        assertFalse(read.toString().contains("3100077"));
    }

    @Test
    void anOrdinaryMessageKeepsItsOriginalUnstored() {
        Long threadId = openAboutTheOrder("The charger was missing from the box.").id();
        entityManager.flush();

        ThreadMessage stored = threadMessages.findByThreadIdOrderByCreatedAtAscIdAsc(threadId)
                .get(0);
        // Nothing was changed, so there is nothing to keep a second copy of.
        assertNull(stored.getOriginalBody());
        assertFalse(stored.isFiltered());
    }

    // ── Reading and unread ───────────────────────────────────────────────────

    @Test
    void openingAThreadMarksTheOtherSidesMessagesRead() {
        Long threadId = openAboutTheOrder("The charger was missing.").id();
        messaging.send(sellerUser.getId(), threadId,
                new AfterSalesRequests.SendMessage("I will post one today."));
        entityManager.flush();

        assertEquals(1, messaging.threads(buyer.getId(), PageRequest.of(0, 10))
                .getContent().get(0).unread());

        messaging.thread(buyer.getId(), threadId, PageRequest.of(0, 50));
        entityManager.flush();

        // A separate mark-read call is one clients forget to make, and a badge
        // that never clears.
        assertEquals(0, messaging.threads(buyer.getId(), PageRequest.of(0, 10))
                .getContent().get(0).unread());
    }

    @Test
    void aStrangerCannotReadAConversation() {
        Long threadId = openAboutTheOrder("hello").id();

        // Every thread looks the same from outside and the ids are sequential,
        // so the participation test is in the query.
        assertThrows(ResourceNotFoundException.class,
                () -> messaging.thread(stranger.getId(), threadId, PageRequest.of(0, 50)));
        assertThrows(ResourceNotFoundException.class,
                () -> messaging.send(stranger.getId(), threadId,
                        new AfterSalesRequests.SendMessage("who are you talking to")));
    }

    @Test
    void eachSideSeesWhoTheOtherIsInTheTermsThatSuitThem() {
        Long threadId = openAboutTheOrder("hello").id();
        entityManager.flush();

        AfterSalesResponses.ThreadSummary buyerRow = messaging
                .threads(buyer.getId(), PageRequest.of(0, 10)).getContent().get(0);
        AfterSalesResponses.ThreadSummary sellerRow = messaging
                .threads(sellerUser.getId(), PageRequest.of(0, 10)).getContent().get(0);

        assertEquals("Kombo Electronics", buyerRow.withName(), "a business, which is public");
        assertEquals("Ousman", sellerRow.withName(), "a first name — the surname is not theirs to have");
        assertEquals(threadId, sellerRow.id());
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    private Review postReview(int rating, LocalDateTime when) {
        Review review = Review.builder()
                .user(buyer).product(galaxy).rating(rating)
                .title("Good phone").comment("Arrived quickly, works well.")
                .verified(true)
                .build();
        review = reviews.save(review);
        entityManager.flush();
        if (when != null) {
            // createdAt is set by Hibernate; the window is counted from it, so
            // an old review has to be aged in the database rather than in Java.
            entityManager.createQuery(
                            "UPDATE Review r SET r.createdAt = :when WHERE r.id = :id")
                    .setParameter("when", when).setParameter("id", review.getId())
                    .executeUpdate();
            entityManager.clear();
            review = reviews.findById(review.getId()).orElseThrow();
        }
        return review;
    }

    @Test
    void theAuthorMayChangeTheirReviewInsideTheWindowAndTheChangeIsDated() {
        Review review = postReview(5, null);

        AfterSalesResponses.ReviewView edited = reviewModeration.edit(buyer.getId(), review.getId(),
                new AfterSalesRequests.EditReview(2, null,
                        "It stopped charging after a week."));

        assertEquals(2, edited.rating());
        // A rating that went from five to one after the seller stopped answering
        // is a different thing from one that was always one.
        assertNotNull(edited.editedAt());
        assertEquals(1, edited.editCount());
        assertTrue(edited.hoursLeftToEdit() > 0);
    }

    @Test
    void aReviewOutsideTheWindowStaysAsItWasWritten() {
        Review old = postReview(1, LocalDateTime.now().minusDays(5));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> reviewModeration.edit(buyer.getId(), old.getId(),
                        new AfterSalesRequests.EditReview(5, null, "Actually it is fine")));
        // The reason the window exists, said in the refusal: otherwise a seller
        // offers to settle in exchange for a rewrite.
        assertTrue(refused.getMessage().contains("nobody can talk you into rewriting"));
    }

    @Test
    void aReviewWithContactDetailsInItIsCleanedLikeAnyOtherWriting() {
        Review review = postReview(4, null);

        AfterSalesResponses.ReviewView edited = reviewModeration.edit(buyer.getId(), review.getId(),
                new AfterSalesRequests.EditReview(null, null,
                        "Good seller, ring him on 3100077"));
        // A review is a public page. A number in one is published rather than
        // sent to one person.
        assertFalse(edited.comment().contains("3100077"));
    }

    @Test
    void withdrawingAReviewIsSoftBecauseTheSellersAnswerHangsOffIt() {
        Review review = postReview(2, null);
        reviewModeration.reply(sellerUser.getId(), review.getId(),
                new AfterSalesRequests.ReplyToReview("Sorry — send it back and we will replace it."));

        reviewModeration.delete(buyer.getId(), review.getId());
        entityManager.flush();

        Review stored = reviews.findById(review.getId()).orElseThrow();
        assertNotNull(stored.getDeletedAt());
        assertFalse(stored.isVisible());
        // The row survives, so the seller's words and the rating arithmetic are
        // not left describing something that no longer exists.
        assertNotNull(stored.getVendorReply());
    }

    @Test
    void theSellerAnswersOnceAndOnlyOnce() {
        Review review = postReview(1, null);
        reviewModeration.reply(sellerUser.getId(), review.getId(),
                new AfterSalesRequests.ReplyToReview("Sorry to hear it."));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> reviewModeration.reply(sellerUser.getId(), review.getId(),
                        new AfterSalesRequests.ReplyToReview("And another thing.")));
        // Otherwise the argument under a bad review becomes longer than the
        // review, and the next buyer reads a quarrel.
        assertTrue(refused.getMessage().contains("one answer per review"));
    }

    @Test
    void theSellersAnswerIsFilteredBecauseEverybodyReadsIt() {
        Review review = postReview(1, null);

        AfterSalesResponses.ReviewView answered = reviewModeration.reply(
                sellerUser.getId(), review.getId(),
                new AfterSalesRequests.ReplyToReview("Call me on 3100077 and I will sort it."));

        assertFalse(answered.vendorReply().contains("3100077"));
    }

    @Test
    void aDifferentSellerCannotPutWordsUnderSomebodyElsesProduct() {
        Review review = postReview(3, null);
        User otherSeller = user("teranga@sujula.sn", "Modou", UserRole.VENDOR);
        vendors.save(Vendor.builder()
                .user(otherSeller).storeName("Teranga Mobile").storeSlug("teranga-messages")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("SN")
                .build());

        assertThrows(ResourceNotFoundException.class,
                () -> reviewModeration.reply(otherSeller.getId(), review.getId(),
                        new AfterSalesRequests.ReplyToReview("Buy from me instead")));
    }

    @Test
    void reportingAReviewTakesNothingDownAndSaysSo() {
        Review review = postReview(1, null);

        AfterSalesResponses.ReviewReported reported = reviewModeration.report(
                sellerUser.getId(), review.getId(),
                new AfterSalesRequests.ReportReview(ReviewReportReason.FALSE_CLAIM,
                        "The phone was working when it left."));
        entityManager.flush();

        assertEquals(1, reported.reportCount());
        // Said plainly: a seller who believes reporting works reports every
        // review below four stars.
        assertTrue(reported.message().contains("stays up"));
        assertTrue(reviews.findById(review.getId()).orElseThrow().isVisible());
    }

    @Test
    void reportingTwiceCountsOnce() {
        Review review = postReview(1, null);
        reviewModeration.report(sellerUser.getId(), review.getId(),
                new AfterSalesRequests.ReportReview(ReviewReportReason.ABUSIVE, null));
        entityManager.flush();

        AfterSalesResponses.ReviewReported again = reviewModeration.report(
                sellerUser.getId(), review.getId(),
                new AfterSalesRequests.ReportReview(ReviewReportReason.SPAM, null));

        assertEquals(1, again.reportCount());
        assertTrue(again.message().contains("already reported"));
    }

    @Test
    void youCannotReportYourOwnReview() {
        Review review = postReview(1, null);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> reviewModeration.report(buyer.getId(), review.getId(),
                        new AfterSalesRequests.ReportReview(ReviewReportReason.SPAM, null)));
        assertTrue(refused.getMessage().contains("your own review"));
    }
}
