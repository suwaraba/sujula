package com.sujula.model.catalogue;

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
 * One row of an import that did not go in, and why.
 *
 * <p>The row number is the number in the seller's file, counting the header as
 * row 1, because that is the number their spreadsheet shows them. Any other
 * convention makes them count.
 */
@Entity
@Table(name = "catalogue_job_errors",
       indexes = @Index(name = "idx_job_error_job", columnList = "job_id"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogueJobError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private CatalogueJob job;

    /** As numbered in the seller's own file, header included. */
    @Column(nullable = false)
    private Integer rowNumber;

    /** Which column, where one is to blame. Null for a whole-row problem. */
    @Column(length = 60)
    private String field;

    @Column(nullable = false, length = 400)
    private String message;

    /**
     * What was in the cell.
     *
     * <p>Echoed back because "category does not exist" is half an answer and
     * "category 'Phonez' does not exist" is the whole one. Truncated, since a
     * pasted description can be a page long.
     */
    @Column(length = 200)
    private String value;
}
