package com.sujula.repository.shipment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.shipment.Shipment;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByReference(String reference);

    boolean existsByReference(String reference);

    Optional<Shipment> findByVendorOrderId(Long vendorOrderId);

    /**
     * A shipment this driver is actually on.
     *
     * <p>The driver goes into the query through the legs rather than being
     * compared afterwards, so a parcel somebody else is carrying is not found
     * rather than fetched and refused. Not-found matters more here than
     * elsewhere: this row carries a recipient's home address and phone number,
     * and "forbidden" would confirm both that the shipment is real and that the
     * prober guessed a live id.
     */
    @Query("SELECT s FROM Shipment s WHERE s.id = :id "
         + "AND EXISTS (SELECT 1 FROM ShipmentLeg l WHERE l.shipment = s AND l.driver.id = :driverId)")
    Optional<Shipment> findByIdAndDriverId(@Param("id") Long id, @Param("driverId") Long driverId);

    /** Shipments a driver has finished, newest first. */
    @Query(value = "SELECT DISTINCT s FROM Shipment s JOIN s.legs l "
                 + "WHERE l.driver.id = :driverId "
                 + "AND l.assignmentStatus = com.sujula.model.constant.LegAssignmentStatus.COMPLETED "
                 + "ORDER BY s.updatedAt DESC",
           countQuery = "SELECT COUNT(DISTINCT s) FROM Shipment s JOIN s.legs l "
                 + "WHERE l.driver.id = :driverId "
                 + "AND l.assignmentStatus = com.sujula.model.constant.LegAssignmentStatus.COMPLETED")
    Page<Shipment> findHistoryForDriver(@Param("driverId") Long driverId, Pageable pageable);

    List<Shipment> findByStatus(ShipmentStatus status);
}
