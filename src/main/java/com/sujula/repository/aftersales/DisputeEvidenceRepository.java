package com.sujula.repository.aftersales;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sujula.model.aftersales.DisputeEvidence;

@Repository
public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidence, Long> {

    List<DisputeEvidence> findByDisputeIdOrderByCreatedAtAscIdAsc(Long disputeId);

    long countByDisputeId(Long disputeId);
}
