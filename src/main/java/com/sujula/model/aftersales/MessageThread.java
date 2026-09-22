package com.sujula.model.aftersales;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.ThreadSubject;
import com.sujula.model.order.Order;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A conversation between one buyer and one seller, about one thing.
 *
 * <p><strong>It must hang off an order or a product, and there is no third
 * option.</strong> That is not a tidiness rule — it is what stops this being a
 * messaging service. Nobody can open a channel to a stranger here: the only
 * people who can write to a seller are somebody who has bought from them and
 * somebody looking at something they are selling, and the thread carries the
 * reason so the seller opening it already knows which order is being asked
 * about.
 *
 * <p>Two participants, named as columns rather than as a join table. A thread is
 * always exactly a buyer and a vendor; a list would suggest it could be three
 * people, and the first code to assume otherwise would be the code that leaks
 * one buyer's message to another.
 */
@Entity
@Table(name = "message_threads",
       indexes = {
           @Index(name = "idx_thread_buyer",  columnList = "buyer_user_id"),
           @Index(name = "idx_thread_vendor", columnList = "vendor_id"),
           @Index(name = "idx_thread_order",  columnList = "order_id"),
           @Index(name = "idx_thread_product", columnList = "product_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ThreadSubject subject;

    /** What it is about, for an ORDER thread. Exactly one of these two is set. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    /** What it is about, for a PRODUCT thread. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_user_id", nullable = false)
    private User buyer;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    /** What the buyer called it. The seller sees this in a list of thirty. */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * When the last message landed.
     *
     * <p>Denormalised because the list is sorted by it and a seller with three
     * hundred threads would otherwise join every message on every page load.
     * Written only where a message is written.
     */
    @Column(nullable = false)
    private LocalDateTime lastMessageAt;

    /** Unread counts, one per side. What draws the badge. */
    @Column(nullable = false)
    @Builder.Default
    private Integer unreadForBuyer = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer unreadForVendor = 0;

    /**
     * Closed to new messages.
     *
     * <p>Set when the thing it is about is finished and nobody has written for a
     * while. The thread stays readable — a buyer looking up what a seller
     * promised six months ago is the reason it exists.
     */
    private LocalDateTime closedAt;

    @JsonIgnore
    @OneToMany(mappedBy = "thread", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC, id ASC")
    @Builder.Default
    private List<ThreadMessage> messages = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Whether a user id is one of the two people in this thread. */
    public boolean involves(Long userId) {
        if (userId == null) {
            return false;
        }
        return (buyer != null && userId.equals(buyer.getId()))
                || (vendor != null && vendor.getUser() != null
                    && userId.equals(vendor.getUser().getId()));
    }
}
