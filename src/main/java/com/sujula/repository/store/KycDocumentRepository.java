package com.sujula.repository.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.KycDocumentType;
import com.sujula.model.store.KycDocument;

@Repository
public interface KycDocumentRepository extends JpaRepository<KycDocument, Long> {

    /**
     * The documents that currently count for a store — newest first, superseded
     * uploads left out.
     *
     * <p>Re-uploading after a rejection supersedes rather than replaces, so the
     * table holds every attempt. Anything deriving the store's standing must read
     * only the live ones, or a rejection from three weeks ago keeps the store out.
     */
    @Query("SELECT d FROM KycDocument d WHERE d.vendor.id = :vendorId "
         + "AND d.supersededAt IS NULL ORDER BY d.submittedAt DESC, d.id DESC")
    List<KycDocument> findLiveForVendor(@Param("vendorId") Long vendorId);

    /** Every attempt, including superseded ones — the onboarding history. */
    List<KycDocument> findByVendorIdOrderBySubmittedAtDesc(Long vendorId);

    /**
     * The live document of a given type, if there is one.
     *
     * <p>Used to supersede the previous upload when a new one of the same type
     * arrives, so a store never has two live passports disagreeing.
     */
    @Query("SELECT d FROM KycDocument d WHERE d.vendor.id = :vendorId AND d.type = :type "
         + "AND d.supersededAt IS NULL ORDER BY d.submittedAt DESC, d.id DESC LIMIT 1")
    Optional<KycDocument> findLiveOfType(@Param("vendorId") Long vendorId,
                                         @Param("type") KycDocumentType type);

    /**
     * One document, but only if it belongs to this store.
     *
     * <p>Ownership is the query. A scan of somebody's passport is not a row to
     * fetch by id and check afterwards.
     */
    @Query("SELECT d FROM KycDocument d WHERE d.id = :id AND d.vendor.id = :vendorId")
    Optional<KycDocument> findByIdAndVendorId(@Param("id") Long id,
                                              @Param("vendorId") Long vendorId);
}
