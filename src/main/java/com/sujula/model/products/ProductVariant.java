package com.sujula.model.products;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Entity
@Table(name = "product_variants")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String sku;

    @Column(nullable = false)
    private Integer stock;

    @Column(name = "price_override", precision = 12, scale = 2)
    private BigDecimal priceOverride;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToMany
    @JoinTable(name = "variant_option_values",
            joinColumns = @JoinColumn(name = "variant_id"),
            inverseJoinColumns = @JoinColumn(name = "option_value_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_variant_value_once",
                    columnNames = {"variant_id", "option_value_id"}))
    private List<ProductOptionValue> selectedValues = new ArrayList<>();

    /** Base + surcharges, unless overridden. */
    @Transient
    public BigDecimal getEffectivePrice() {
        if (priceOverride != null) return priceOverride;
        BigDecimal total = product.getPrice();
        for (ProductOptionValue v : selectedValues) {
            total = total.add(BigDecimal.valueOf(v.getExtraPrice()));
        }
        return total;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public BigDecimal getPriceOverride() { return priceOverride; }
    public void setPriceOverride(BigDecimal priceOverride) { this.priceOverride = priceOverride; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public List<ProductOptionValue> getSelectedValues() { return selectedValues; }
    public void setSelectedValues(List<ProductOptionValue> selectedValues) { this.selectedValues = selectedValues; }
}
