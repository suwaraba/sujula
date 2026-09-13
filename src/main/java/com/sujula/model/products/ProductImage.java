package com.sujula.model.products;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_images",
       indexes = {
           @Index(name = "idx_pi_product_id",         columnList = "product_id"),
           @Index(name = "idx_pi_product_default",    columnList = "product_id, isDefault")
       })

@JsonIgnoreProperties({"product", "hibernateLazyInitializer", "handler"})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String imageUrl;

    private String altText;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    /**
     * Whether the bytes are actually in storage.
     *
     * <p>The row is written when a storage key is claimed, before anything is
     * uploaded, so that ordering and the per-listing limit are settled while it
     * is still cheap to refuse. An upload that never completes then leaves a
     * PENDING row to clean up rather than a gap nobody knows about — and, more
     * to the point, a listing never shows an image that is not there.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private com.sujula.model.constant.MediaStatus status =
            com.sujula.model.constant.MediaStatus.READY;

    /** What the seller called the file, so two uploads can be told apart. */
    @Column(length = 255)
    private String originalFilename;

    @Column(length = 100)
    private String contentType;

    private Long sizeBytes;

    private LocalDateTime confirmedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
