package com.sujula.repository.catalogue;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.catalogue.CatalogueJob;
import com.sujula.model.constant.CatalogueJobStatus;

@Repository
public interface CatalogueJobRepository extends JpaRepository<CatalogueJob, Long> {

    /**
     * One job, by the reference the seller holds, and only if it is theirs.
     *
     * <p>Both halves matter. The reference is unguessable so a job id cannot be
     * walked, and the ownership is in the query so a leaked reference still does
     * not open somebody else's import errors — which name their products, their
     * prices and their SKUs.
     */
    @Query("SELECT j FROM CatalogueJob j WHERE j.reference = :reference AND j.vendor.id = :vendorId")
    Optional<CatalogueJob> findByReferenceAndVendorId(@Param("reference") String reference,
                                                      @Param("vendorId") Long vendorId);

    @Query(value      = "SELECT j FROM CatalogueJob j WHERE j.vendor.id = :vendorId "
                      + "ORDER BY j.createdAt DESC",
           countQuery = "SELECT COUNT(j) FROM CatalogueJob j WHERE j.vendor.id = :vendorId")
    Page<CatalogueJob> findForVendor(@Param("vendorId") Long vendorId, Pageable pageable);

    /**
     * The queue the worker drains, oldest first.
     *
     * <p>Oldest first so a seller who uploaded an hour ago is not overtaken
     * indefinitely by one uploading now.
     */
    @Query("SELECT j FROM CatalogueJob j WHERE j.status = :status ORDER BY j.createdAt ASC LIMIT :batch")
    List<CatalogueJob> findQueued(@Param("status") CatalogueJobStatus status,
                                  @Param("batch") int batch);

    /** How many of this seller's jobs are still outstanding, to cap the queue per vendor. */
    long countByVendorIdAndStatusIn(Long vendorId, List<CatalogueJobStatus> statuses);

    boolean existsByReference(String reference);
}
