package com.sujula.model.analytics;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How many times a listing was looked at on one day.
 *
 * <p>One row per product per day, not one per view. A marketplace where most
 * traffic is a phone on a slow connection generates far more views than orders,
 * and a table with a row each would be the largest thing in the database inside
 * a month — for a number that is only ever read as a daily total.
 *
 * <p><strong>What this counts, exactly.</strong> Page loads of a product's
 * public detail, including reloads and including the same person twice. It is
 * not unique visitors and the funnel built on it says so, because a conversion
 * rate that quietly means something other than what a seller assumes is worse
 * than no conversion rate. Making it unique would need a session identity kept
 * per viewer, which is a different and much heavier thing than a counter.
 *
 * <p>The vendor is denormalised onto the row so a seller's whole funnel is one
 * indexed read rather than a join through every product they have ever listed.
 */
@Entity
@Table(name = "product_view_stats",
       uniqueConstraints = @UniqueConstraint(name = "uq_view_product_day",
                                             columnNames = {"product_id", "viewedOn"}),
       indexes = {
           @Index(name = "idx_view_vendor_day",  columnList = "vendor_id, viewedOn"),
           @Index(name = "idx_view_product_day", columnList = "product_id, viewedOn")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductViewStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Whose listing it is, kept here so a seller's funnel is one indexed read. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    /**
     * The day, in UTC.
     *
     * <p>Fixed to UTC rather than a shop's local day so that a series does not
     * change shape when a seller travels, and so two shops in different
     * countries can be put on the same axis. A dashboard may relabel; the stored
     * bucket does not move.
     */
    @Column(nullable = false)
    private LocalDate viewedOn;

    @Column(nullable = false)
    @Builder.Default
    private Long views = 0L;
}
