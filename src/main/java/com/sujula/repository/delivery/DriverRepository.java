package com.sujula.repository.delivery;

import com.sujula.model.constant.DriverStatus;
import com.sujula.model.delivery.Driver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverRepository extends JpaRepository<Driver, Long> {

    /**
     * Drivers who work in a country.
     *
     * <p>Scoped by where the goods are going rather than by anything about the
     * buyer (C1). A parcel bought in Madrid and delivered in Serrekunda is
     * carried by somebody in The Gambia, and a query keyed on the payer would
     * return nobody.
     */
    java.util.List<com.sujula.model.delivery.Driver> findByCountryCode(String countryCode);


    Optional<Driver> findByUserId(Long userId);
    boolean existsByUserId(Long userId);

    Page<Driver> findByStatus(DriverStatus status, Pageable pageable);

    /** All available approved drivers (for admin assignment). */
    List<Driver> findByStatusAndAvailableTrue(DriverStatus status);

    /** Available approved drivers filtered by country (locality-aware assignment). */
    List<Driver> findByCountryCodeAndStatusAndAvailableTrue(String countryCode, DriverStatus status);

    /** All APPROVED drivers that have reported GPS data (admin live-tracking map). */
    List<Driver> findByStatusAndCurrentLatitudeIsNotNull(DriverStatus status);

    /**
     * The back office's driver list.
     *
     * <p>Joins the user for the name and email the list shows, because a
     * dispatcher scanning twenty rows should not cost twenty extra selects. The
     * zone filter goes through the coverage the platform assigned rather than
     * the free-text area the driver typed — the two disagree often, and the one
     * that decides who gets offered work is the assigned one.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT d FROM Driver d JOIN FETCH d.user u "
          + "LEFT JOIN d.coverage z "
          + "WHERE (:q IS NULL OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(u.lastName) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) "
          + "   OR LOWER(d.phone) LIKE LOWER(CONCAT('%', :q, '%'))) "
          + "AND (:status IS NULL OR d.status = :status) "
          + "AND (:countryCode IS NULL OR UPPER(d.countryCode) = UPPER(:countryCode)) "
          + "AND (:zoneCode IS NULL OR UPPER(z.code) = UPPER(:zoneCode)) "
          + "AND (:availableOnly = FALSE OR d.available = TRUE) "
          + "GROUP BY d, u "
          + "ORDER BY d.status ASC, d.acceptanceScore DESC, d.id ASC")
    Page<Driver> adminSearch(@org.springframework.data.repository.query.Param("q") String q,
                             @org.springframework.data.repository.query.Param("status") DriverStatus status,
                             @org.springframework.data.repository.query.Param("countryCode") String countryCode,
                             @org.springframework.data.repository.query.Param("zoneCode") String zoneCode,
                             @org.springframework.data.repository.query.Param("availableOnly") boolean availableOnly,
                             Pageable pageable);

    /** How many drivers an administrator has approved for a zone. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(d) FROM Driver d JOIN d.coverage z WHERE z.id = :zoneId")
    long countCovering(@org.springframework.data.repository.query.Param("zoneId") Long zoneId);
}
