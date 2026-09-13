package com.sujula.repository;

import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.delivery.PickupPoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PickupPointRepository extends JpaRepository<PickupPoint, Long> {

    // Legacy queries (keep for existing admin service)
    Page<PickupPoint> findByActiveTrue(Pageable pageable);
    Page<PickupPoint> findByCityAndActiveTrue(String city, Pageable pageable);

    // Approval flow
    Page<PickupPoint> findByStatus(PartnerStatus status, Pageable pageable);

    // Operator self-service
    Optional<PickupPoint> findByOperatorUserId(Long operatorUserId);
    boolean existsByOperatorUserId(Long operatorUserId);

    // ── Duplication guards ────────────────────────────────────────────────────

    /** True if any pickup point already uses this contact email. */
    boolean existsByContactEmailIgnoreCase(String contactEmail);

    /**
     * True if any pickup point OTHER than {@code excludeId} uses this contact email.
     * Used on updates so a point can keep its own email.
     */
    boolean existsByContactEmailIgnoreCaseAndIdNot(String contactEmail, Long excludeId);

    /** True if any pickup point already uses this contact phone. */
    boolean existsByContactPhone(String contactPhone);

    /** True if any pickup point OTHER than {@code excludeId} uses this contact phone. */
    boolean existsByContactPhoneAndIdNot(String contactPhone, Long excludeId);

    /**
     * True if a pickup point with the same name already exists in the same city/country.
     * Prevents two identically-named points at the same location.
     */
    boolean existsByNameIgnoreCaseAndCityIgnoreCaseAndCountryCodeIgnoreCase(
            String name, String city, String countryCode);

    /**
     * True if a pickup point OTHER than {@code excludeId} has the same name in the same city/country.
     */
    boolean existsByNameIgnoreCaseAndCityIgnoreCaseAndCountryCodeIgnoreCaseAndIdNot(
            String name, String city, String countryCode, Long excludeId);

    // Public discovery: only active + approved
    Page<PickupPoint> findByStatusAndActiveTrue(PartnerStatus status, Pageable pageable);
    Page<PickupPoint> findByCityAndStatusAndActiveTrue(String city, PartnerStatus status, Pageable pageable);

    List<PickupPoint> findByCountryCodeAndStatusAndActiveTrue(String countryCode, PartnerStatus status);

    /**
     * Hubs that could actually take a parcel today, in one country, with a
     * location.
     *
     * <p>Both trading statuses in one query, because {@code PartnerStatus} has
     * two of them and asking for one is how half the network goes missing from a
     * "nearest pickup point" list. Points without coordinates are excluded here
     * rather than filtered afterwards: nothing can be ranked by distance from a
     * hub whose position is unknown.
     *
     * <p>Returns a country's points rather than a radius query — no spatial index
     * exists on this table, and for a country with tens of hubs the ranking is
     * cheaper in memory than a bounding-box scan would be. That stops being true
     * at a few thousand, which is the point to add one.
     */
    @Query("SELECT p FROM PickupPoint p WHERE p.active = true AND p.countryCode = :countryCode "
         + "AND p.status IN (com.sujula.model.constant.PartnerStatus.APPROVED, "
         + "                 com.sujula.model.constant.PartnerStatus.ACTIVE) "
         + "AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL")
    List<PickupPoint> findCollectableIn(@Param("countryCode") String countryCode);
}
