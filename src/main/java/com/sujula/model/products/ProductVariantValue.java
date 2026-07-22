package com.sujula.model.products;

import jakarta.persistence.*;
import lombok.*;

/**
 * Links a ProductVariant to a specific ProductOptionValue.
 * A variant has one value per option (e.g. Size=Large AND Color=Red).
 */
@Entity
@Table(name = "product_variant_values",
       uniqueConstraints = @UniqueConstraint(columnNames = {"variant_id", "option_value_id"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariantValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_value_id", nullable = false)
    private ProductOptionValue optionValue;
}
