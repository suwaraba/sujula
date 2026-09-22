package com.sujula.model.products;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;


@Entity
@Table(name = "product_option_values",
       uniqueConstraints = @UniqueConstraint(columnNames = {"option_id", "value"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductOptionValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id", nullable = false)
    private ProductOption option;

    @Column(nullable = false)
    private String value;          // stored value: "L", "red"

    @Column(nullable = false)
    private String displayValue;   // shown to user: "Large", "Red"

    /** Surcharge this value adds to the product's base price, in the product's listing currency. */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal extraPrice = BigDecimal.ZERO;

    private String colorHex;       // only for COLOR_SWATCH type: "#FF0000"
    private String imageUrl;       // only for IMAGE_SWATCH type

    @Builder.Default
    private Integer sortOrder = 0;


}
