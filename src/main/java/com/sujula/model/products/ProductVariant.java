package com.sujula.model.products;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


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

    /**
     * Optimistic lock, for the one write that genuinely needs one.
     *
     * <p>Setting stock to an absolute figure is not safe under concurrency: two
     * people counting the same shelf and saving 10 and 12 leaves whichever
     * committed last, and one of them is simply wrong with nothing to say so.
     * A caller sending an absolute value therefore has to send the version they
     * read, and a stale one is refused rather than applied.
     *
     * <p>Adjusting by a delta needs none of this - {@code +5} is {@code +5}
     * whoever else is writing - which is why the inventory endpoint offers both
     * and asks for the version on only one of them.
     *
     * <p>Separate from the pessimistic lock checkout takes. That one stops two
     * buyers reserving the last handset; this one stops two staff overwriting
     * each other's count.
     */
    @jakarta.persistence.Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

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

    /**
     * Human-readable summary of what this variant is: "Large, Red".
     *
     * <p>Built from the option values alone — deliberately not from
     * {@code value.getOption().getName()}, which would lazy-load one extra row
     * per option everywhere a variant is listed. Values are ordered by their
     * option value's own sort order so the same variant always reads the same
     * way, in the cart, on the order and in the catalogue.
     *
     * @return null for a product that has no variants worth naming
     */
    @Transient
    public String getVariantLabel() {
        if (selectedValues == null || selectedValues.isEmpty()) {
            return null;
        }
        return selectedValues.stream()
                .sorted(Comparator.comparing(ProductOptionValue::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ProductOptionValue::getDisplayValue)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    /** Base + surcharges, unless overridden. */
    @Transient
    public BigDecimal getEffectivePrice() {
        if (priceOverride != null) return priceOverride;
        BigDecimal total = product.getPrice();
        for (ProductOptionValue v : selectedValues) {
            if (v.getExtraPrice() != null) {
                total = total.add(v.getExtraPrice());
            }
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

    public Long getVersion() { return version; }

    /**
     * Hibernate owns this. It is here because the class writes its accessors by
     * hand rather than generating them, and there is no setter that callers
     * should use: a client that could choose its own version could defeat the
     * lock by echoing back whatever it last saw.
     */
    void setVersion(Long version) { this.version = version; }
}
