package com.sujula.repository.catalogue;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sujula.model.catalogue.CatalogueJobError;

@Repository
public interface CatalogueJobErrorRepository extends JpaRepository<CatalogueJobError, Long> {

    /**
     * A job's errors in file order.
     *
     * <p>Paged, because a seller who exported the wrong template produces one
     * error per row and four hundred of them do not belong in a single
     * response.
     */
    Page<CatalogueJobError> findByJobIdOrderByRowNumberAsc(Long jobId, Pageable pageable);

    List<CatalogueJobError> findByJobIdOrderByRowNumberAsc(Long jobId);

    long countByJobId(Long jobId);
}
