package com.sujula.repository.shipment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.RecipientInstructionType;
import com.sujula.model.shipment.RecipientInstruction;

@Repository
public interface RecipientInstructionRepository extends JpaRepository<RecipientInstruction, Long> {

    /** The whole record for one parcel, oldest first. What the summary is derived from. */
    List<RecipientInstruction> findByShipmentIdOrderByCreatedAtAscIdAsc(Long shipmentId);

    /** The ones currently in force, of every kind. */
    @Query("SELECT i FROM RecipientInstruction i WHERE i.shipment.id = :shipmentId "
         + "AND i.supersededAt IS NULL ORDER BY i.createdAt ASC, i.id ASC")
    List<RecipientInstruction> findInForce(@Param("shipmentId") Long shipmentId);

    /** The one of a kind currently in force, if there is one. */
    @Query("SELECT i FROM RecipientInstruction i WHERE i.shipment.id = :shipmentId "
         + "AND i.type = :type AND i.supersededAt IS NULL "
         + "ORDER BY i.createdAt DESC, i.id DESC LIMIT 1")
    Optional<RecipientInstruction> findInForceOfType(@Param("shipmentId") Long shipmentId,
                                                     @Param("type") RecipientInstructionType type);
}
