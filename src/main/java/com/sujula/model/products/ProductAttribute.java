package com.sujula.model.products;

import jakarta.persistence.*;
import lombok.*;

/**
 * Free-form specification key-value pair for a product
 * (e.g. "Battery Capacity" → "5000 mAh", "Material" → "100% Cotton").
 * Shopizer equivalent: ProductAttribute.
 */
@Entity
@Table(name = "product_attributes")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String name;    // "Battery Capacity"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;   // "5000 mAh"

    @Builder.Default
    private Integer sortOrder = 0;
}
