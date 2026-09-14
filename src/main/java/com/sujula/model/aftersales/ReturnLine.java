package com.sujula.model.aftersales;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.order.OrderItem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing going back, and how many of it.
 *
 * <p>A line rather than a whole order, because a buyer who ordered three cases
 * and wants to return one is the ordinary case. Returning the slice wholesale
 * would take back two cases nobody complained about and refund money nobody
 * asked for.
 *
 * <p>The prices are snapshotted from the order item at the moment the return is
 * opened. Reading them off the item later would follow a price the seller has
 * since changed, and settle the return at a number the buyer never paid.
 */
@Entity
@Table(name = "return_lines",
       indexes = @Index(name = "idx_return_line_request", columnList = "return_request_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_request_id", nullable = false)
    private ReturnRequest returnRequest;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    /** How many are coming back. Never more than were bought, which the service checks. */
    @Column(nullable = false)
    private Integer quantity;

    /** What the seller charged for one, in their own currency. */
    @Column(precision = 12, scale = 2)
    private BigDecimal unitPriceNative;

    /** What the buyer was charged for one, in theirs. */
    @Column(precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /**
     * What it was, in words, as it was at the time.
     *
     * <p>Copied rather than joined. A seller who renames or delists a product
     * must not turn a buyer's open return into a row about nothing.
     */
    @Column(length = 300)
    private String productName;

    /**
     * The handset actually sent, where there was one.
     *
     * <p>A return of a phone is a return of <em>that</em> phone. Without the
     * IMEI on the line, a seller receiving a different handset back has no way
     * to say so and no record to say it against.
     */
    @Column(length = 200)
    private String assignedImeis;
}
