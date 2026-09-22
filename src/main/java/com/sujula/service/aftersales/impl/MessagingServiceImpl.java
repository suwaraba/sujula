package com.sujula.service.aftersales.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.aftersales.MessageThread;
import com.sujula.model.aftersales.ThreadMessage;
import com.sujula.model.constant.ThreadSubject;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.aftersales.MessageThreadRepository;
import com.sujula.repository.aftersales.ThreadMessageRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.aftersales.ContactDetailFilter;
import com.sujula.service.aftersales.MessagingService;

import lombok.extern.slf4j.Slf4j;

/**
 * Threads between a buyer and a seller.
 *
 * <p>Participation is in the query everywhere. Every thread looks the same from
 * outside and the ids are sequential, so a read that fetched first and checked
 * afterwards would be one missing check away from handing somebody another
 * buyer's conversation.
 */
@Slf4j
@Service
public class MessagingServiceImpl implements MessagingService {

    /**
     * How many messages one person may send into a thread in an hour.
     *
     * <p>A seller who can send forty messages to somebody who once asked about a
     * charger has a broadcast channel, which is not what this is.
     */
    private static final int MAX_MESSAGES_PER_HOUR = 30;

    private static final int PREVIEW_LENGTH = 120;

    private final MessageThreadRepository threads;
    private final ThreadMessageRepository messages;
    private final OrderRepository orders;
    private final ProductRepository products;
    private final VendorRepository vendors;
    private final UserRepository users;
    private final ContactDetailFilter filter;

    public MessagingServiceImpl(MessageThreadRepository threads, ThreadMessageRepository messages,
                                OrderRepository orders, ProductRepository products,
                                VendorRepository vendors, UserRepository users,
                                ContactDetailFilter filter) {
        this.threads = threads;
        this.messages = messages;
        this.orders = orders;
        this.products = products;
        this.vendors = vendors;
        this.users = users;
        this.filter = filter;
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public PagedResponse<AfterSalesResponses.ThreadSummary> threads(Long userId,
                                                                    Pageable pageable) {
        Page<MessageThread> page = threads.findForParticipant(userId, pageable);
        return PagedResponse.of(page.map(thread -> summaryOf(thread, userId)));
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ThreadDetail thread(Long userId, Long threadId, Pageable pageable) {
        MessageThread thread = requireParticipant(threadId, userId);

        // Reading is a write. The alternative is a separate mark-read call that
        // clients forget to make and a badge that never clears.
        int marked = messages.markRead(threadId, userId, LocalDateTime.now());
        if (marked > 0) {
            applyUnread(thread, userId, 0);
            threads.save(thread);
        }

        Page<ThreadMessage> page = messages.findByThreadIdOrderByCreatedAtAscIdAsc(
                threadId, pageable == null ? PageRequest.of(0, 50) : pageable);
        return detailOf(thread, userId, page);
    }

    // ── Opening one ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.ThreadDetail openThread(Long userId,
                                                       AfterSalesRequests.OpenThread request) {
        requireExactlyOneSubject(request);
        User buyer = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Order order = null;
        Product product = null;
        Vendor vendor;

        if (request.subject() == ThreadSubject.ORDER) {
            order = orders.findById(request.orderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order", request.orderId()));
            requireBuyerOf(order, userId);
            vendor = sellerOn(order);
        } else {
            product = products.findById(request.productId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product", request.productId()));
            vendor = product.getVendor();
            if (vendor == null) {
                throw new BadRequestException("That product has no seller to write to.");
            }
        }

        if (vendor.getUser() != null && userId.equals(vendor.getUser().getId())) {
            throw new BadRequestException("You cannot open a conversation with yourself.");
        }

        Optional<MessageThread> existing = threads.findExisting(
                userId, vendor.getId(),
                order == null ? null : order.getId(),
                product == null ? null : product.getId());

        MessageThread thread = existing.orElseGet(() -> threads.save(MessageThread.builder()
                .subject(request.subject())
                .order(null)
                .product(null)
                .buyer(buyer)
                .vendor(vendor)
                .title(request.title())
                .lastMessageAt(LocalDateTime.now())
                .build()));

        if (existing.isEmpty()) {
            thread.setOrder(order);
            thread.setProduct(product);
            threads.save(thread);
        }

        appendMessage(thread, buyer, request.body());

        Page<ThreadMessage> page = messages.findByThreadIdOrderByCreatedAtAscIdAsc(
                thread.getId(), PageRequest.of(0, 50));
        log.info("[Messages] thread {} {} by user {} about {} {}", thread.getId(),
                existing.isPresent() ? "reused" : "opened", userId, request.subject(),
                order != null ? order.getOrderNumber() : request.productId());
        return detailOf(thread, userId, page);
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public AfterSalesResponses.MessageSent send(Long userId, Long threadId,
                                                AfterSalesRequests.SendMessage request) {
        MessageThread thread = requireParticipant(threadId, userId);
        if (thread.getClosedAt() != null) {
            throw new BadRequestException(
                    "This conversation is closed. If something has come up about the order, open "
                            + "a return or a dispute — those go somewhere that can act on them.");
        }

        long recent = messages.countRecentFrom(threadId, userId,
                LocalDateTime.now().minusHours(1));
        if (recent >= MAX_MESSAGES_PER_HOUR) {
            throw new BadRequestException(
                    "You have sent " + recent + " messages here in the last hour. Give them a "
                            + "chance to read them.");
        }

        User sender = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        ThreadMessage saved = appendMessage(thread, sender, request.body());

        return new AfterSalesResponses.MessageSent(threadId, messageView(saved),
                // Told to the sender rather than discovered by reading their own
                // words back. A silent edit is a lie, and one that teaches people
                // to write in code.
                saved.isFiltered() ? noticeFor(saved) : null);
    }

    /**
     * Writes one message and moves everything that follows from it.
     *
     * <p>The single place a message is created, so the filter, the two unread
     * counters and the thread's last-message time cannot come apart. The unread
     * count is recomputed from the messages rather than incremented — a nudged
     * counter drifts, and what this one drifts into is a badge nobody can clear.
     */
    private ThreadMessage appendMessage(MessageThread thread, User sender, String body) {
        ContactDetailFilter.Result cleaned = filter.clean(body);
        boolean fromBuyer = thread.getBuyer() != null
                && sender.getId().equals(thread.getBuyer().getId());

        ThreadMessage message = messages.save(ThreadMessage.builder()
                .thread(thread)
                .sender(sender)
                .senderSide(fromBuyer ? "BUYER" : "VENDOR")
                .body(cleaned.cleaned())
                // Kept, and only a moderator ever reads it: a buyer whose
                // innocent sentence was mangled needs somebody to be able to
                // look, and a seller who tries this weekly leaves a pattern that
                // only exists if the attempts were kept.
                .originalBody(cleaned.changed() ? body : null)
                .filtered(cleaned.changed())
                .filteredKinds(cleaned.kindsAsText())
                .build());

        thread.setLastMessageAt(LocalDateTime.now());
        Long otherUserId = fromBuyer
                ? (thread.getVendor() != null && thread.getVendor().getUser() != null
                   ? thread.getVendor().getUser().getId() : null)
                : (thread.getBuyer() != null ? thread.getBuyer().getId() : null);
        if (otherUserId != null) {
            applyUnread(thread, otherUserId, messages.countUnreadFor(thread.getId(), otherUserId));
        }
        threads.save(thread);

        if (cleaned.changed()) {
            log.info("[Messages] thread {} — {} removed from a message by user {}",
                    thread.getId(), cleaned.kindsAsText(), sender.getId());
        }
        return message;
    }

    /** Sets whichever side's counter belongs to this user. */
    private static void applyUnread(MessageThread thread, Long userId, int count) {
        if (thread.getBuyer() != null && userId.equals(thread.getBuyer().getId())) {
            thread.setUnreadForBuyer(count);
        } else if (thread.getVendor() != null && thread.getVendor().getUser() != null
                && userId.equals(thread.getVendor().getUser().getId())) {
            thread.setUnreadForVendor(count);
        }
    }

    private String noticeFor(ThreadMessage message) {
        return filter.clean(message.getOriginalBody() == null
                ? message.getBody() : message.getOriginalBody()).notice();
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private MessageThread requireParticipant(Long threadId, Long userId) {
        return threads.findByIdForParticipant(threadId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", threadId));
    }

    /**
     * Refuses a thread about nothing, or about two things.
     *
     * <p>The rule that keeps this from being a messaging service. Checked here
     * rather than by annotations because "exactly one of these two" is not
     * something a field constraint can say.
     */
    private static void requireExactlyOneSubject(AfterSalesRequests.OpenThread request) {
        boolean hasOrder = request.orderId() != null;
        boolean hasProduct = request.productId() != null;
        if (hasOrder == hasProduct) {
            throw new BadRequestException(
                    "A conversation has to be about one order or one product. Say which.");
        }
        if (request.subject() == ThreadSubject.ORDER && !hasOrder) {
            throw new BadRequestException("Say which order this is about.");
        }
        if (request.subject() == ThreadSubject.PRODUCT && !hasProduct) {
            throw new BadRequestException("Say which product this is about.");
        }
    }

    private static void requireBuyerOf(Order order, Long userId) {
        boolean theirs = order.getCustomer() != null && userId.equals(order.getCustomer().getId());
        if (!theirs) {
            throw new ResourceNotFoundException("Order", order.getId());
        }
    }

    /**
     * The seller on an order, where there is exactly one.
     *
     * <p>An order can hold several sellers (C3), and a thread holds two people —
     * so a multi-vendor order has to say which seller is being written to. Rather
     * than guessing, this refuses and names the alternative.
     */
    private static Vendor sellerOn(Order order) {
        List<Vendor> distinct = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            Vendor vendor = item.getVendor();
            if (vendor != null && distinct.stream().noneMatch(v -> v.getId().equals(vendor.getId()))) {
                distinct.add(vendor);
            }
        }
        if (distinct.isEmpty()) {
            throw new BadRequestException("That order has no seller to write to.");
        }
        if (distinct.size() > 1) {
            throw new BadRequestException(
                    "That order is from " + distinct.size() + " sellers, who each handle their own "
                            + "part of it. Open the conversation from the part you want to ask "
                            + "about.");
        }
        return distinct.get(0);
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private AfterSalesResponses.ThreadSummary summaryOf(MessageThread thread, Long userId) {
        boolean buyer = thread.getBuyer() != null && userId.equals(thread.getBuyer().getId());
        List<ThreadMessage> all = messages.findByThreadIdOrderByCreatedAtAscIdAsc(thread.getId());
        ThreadMessage last = all.isEmpty() ? null : all.get(all.size() - 1);

        return new AfterSalesResponses.ThreadSummary(
                thread.getId(), thread.getSubject(), thread.getTitle(),
                thread.getOrder() == null ? null : thread.getOrder().getOrderNumber(),
                thread.getProduct() == null ? null : thread.getProduct().getId(),
                thread.getProduct() == null ? null : thread.getProduct().getName(),
                // Who the other person is, named as a business or a first name.
                // A buyer's surname is not the seller's to have.
                buyer ? storeName(thread) : buyerName(thread),
                preview(last), thread.getLastMessageAt(),
                buyer ? nullToZero(thread.getUnreadForBuyer())
                      : nullToZero(thread.getUnreadForVendor()),
                thread.getClosedAt() != null);
    }

    private AfterSalesResponses.ThreadDetail detailOf(MessageThread thread, Long userId,
                                                      Page<ThreadMessage> page) {
        boolean buyer = thread.getBuyer() != null && userId.equals(thread.getBuyer().getId());
        List<AfterSalesResponses.MessageView> views = new ArrayList<>();
        for (ThreadMessage message : page.getContent()) {
            views.add(messageView(message));
        }
        return new AfterSalesResponses.ThreadDetail(
                thread.getId(), thread.getSubject(), thread.getTitle(),
                thread.getOrder() == null ? null : thread.getOrder().getOrderNumber(),
                thread.getProduct() == null ? null : thread.getProduct().getId(),
                thread.getProduct() == null ? null : thread.getProduct().getName(),
                buyer ? storeName(thread) : buyerName(thread),
                thread.getClosedAt() != null,
                views, (int) page.getTotalElements(), page.getNumber(), page.getSize());
    }

    private static AfterSalesResponses.MessageView messageView(ThreadMessage message) {
        return new AfterSalesResponses.MessageView(
                message.getId(), message.getSenderSide(),
                senderName(message),
                // Never originalBody. That exists for moderation and nothing
                // else; serving it here would make the filter decorative.
                message.getBody(),
                message.isFiltered(), message.getFilteredKinds(),
                message.getCreatedAt(), message.getReadAt());
    }

    private static String senderName(ThreadMessage message) {
        User sender = message.getSender();
        if (sender == null) {
            return "VENDOR".equals(message.getSenderSide()) ? "The seller" : "The buyer";
        }
        return sender.getFirstName() != null ? sender.getFirstName() : "They";
    }

    private static String storeName(MessageThread thread) {
        return thread.getVendor() == null ? null : thread.getVendor().getStoreName();
    }

    private static String buyerName(MessageThread thread) {
        User buyer = thread.getBuyer();
        return buyer == null ? null
                : (buyer.getFirstName() != null ? buyer.getFirstName() : "A buyer");
    }

    private static String preview(ThreadMessage message) {
        if (message == null || message.getBody() == null) {
            return null;
        }
        String body = message.getBody().replace('\n', ' ').trim();
        return body.length() <= PREVIEW_LENGTH ? body
                : body.substring(0, PREVIEW_LENGTH - 1) + "…";
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
