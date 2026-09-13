package com.sujula.model.products;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
 * One listing, written again in another language.
 *
 * <p>Not a nicety on this marketplace. A vendor in Ziguinchor writes in French;
 * the buyer paying for the goods is in Madrid and reads Spanish; the sister
 * receiving them in Serrekunda speaks Wolof and reads English. The listing text
 * is the only description anyone gets — nobody in that chain can pick the item
 * up and look at it — so a translation is the difference between a sale and a
 * dispute about what was bought.
 *
 * <p>A row per locale rather than columns per language: a column layout has to
 * be migrated every time a language is added, and adding languages is the whole
 * point.
 *
 * <p>Fields left blank fall back to the product's own. A partial translation is
 * useful — a translated name with the original description beats neither — so
 * nothing here is required beyond the locale itself.
 */
@Entity
@Table(name = "product_translations",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_product_translation_locale", columnNames = {"product_id", "locale"}),
       indexes = @Index(name = "idx_translation_product", columnList = "product_id"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** BCP 47, as the reference locale list carries them: en-GM, fr-SN, es-ES. */
    @Column(nullable = false, length = 10)
    private String locale;

    @Column(length = 255)
    private String name;

    @Column(length = 500)
    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Marks text a machine produced.
     *
     * <p>Worth knowing, and worth showing a buyer. A machine translation of "six
     * yards of wax print, cut to order" is usually fine and occasionally
     * nonsense, and a buyer deciding whether to spend a month's remittance
     * deserves to know which kind of text they are reading.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean machineTranslated = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
