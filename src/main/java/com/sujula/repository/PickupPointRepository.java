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
     *
     * <p>A counter closed for the week is excluded, for the same reason
     * {@link #findNear} excludes it: this list is what a shopper picks a
     * collection point FROM, and offering one that is shut is worse than
     * offering nothing. That condition was missing here while
     * {@code findNear} had it, so the public search hid Latrikunda while
     * POST /delivery/serviceability went on offering it — two code paths
     * answering the same question two ways.
     */
    @Query("SELECT p FROM PickupPoint p WHERE p.active = true AND p.countryCode = :countryCode "
         + "AND p.status IN (com.sujula.model.constant.PartnerStatus.APPROVED, "
         + "                 com.sujula.model.constant.PartnerStatus.ACTIVE) "
         + "AND (p.closedUntil IS NULL OR p.closedUntil < CURRENT_TIMESTAMP) "
         + "AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL")
    List<PickupPoint> findCollectableIn(@Param("countryCode") String countryCode);

    // ── The operator's own points ────────────────────────────────────────────

    /**
     * Every point this operator runs.
     *
     * <p>A list rather than one row: somebody who runs a counter well is
     * frequently asked to run a second, and a model that allowed only one would
     * have them opening a second account to do it.
     */
    List<PickupPoint> findByOperatorUserIdOrderByNameAsc(Long operatorUserId);

    /** One of theirs, by id. Another operator's point is not found. */
    @Query("SELECT p FROM PickupPoint p WHERE p.id = :id AND p.operatorUser.id = :operatorUserId")
    Optional<PickupPoint> findByIdAndOperatorUserId(@Param("id") Long id,
                                                    @Param("operatorUserId") Long operatorUserId);

    // ── Public search ────────────────────────────────────────────────────────

    /**
     * Points near a place, nearest first.
     *
     * <p>The distance is computed in the database so the sort and the limit both
     * happen there. Fetching every point in the country and sorting in Java
     * would work today and stop working the first month this platform is busy.
     *
     * <p>A bounding box narrows the candidates before the trigonometry runs -
     * the box is cheap and wrong at the edges, the haversine is exact and
     * expensive, and doing the cheap one first is what keeps this query usable
     * without a spatial index.
     *
     * <p>Only approved, active points, and only ones that are not closed for the
     * week: a shopper sent to a shuttered counter has been actively misdirected,
     * which is worse than being shown nothing.
     *
     * <p>Two things here are deliberate and were both bugs first.
     *
     * <p>The id is selected explicitly rather than {@code p.*}: a database is
     * free to return columns in whatever order it likes - H2 and MySQL disagree
     * - so reading a wide row by position is a cast exception waiting for the
     * first schema change.
     *
     * <p>The distance is filtered in a derived table rather than with HAVING.
     * HAVING with no GROUP BY makes the whole statement an implicit aggregate,
     * which returns ONE row whatever the WHERE matched - so a search with no
     * counters nearby came back with a phantom one. A subquery keeps the alias
     * usable and the row count honest.
     */
    @Query(value = """
            SELECT near.point_id, near.distance_km FROM (
                SELECT p.id AS point_id, (6371 * ACOS(LEAST(1.0, GREATEST(-1.0,
                         COS(RADIANS(:lat)) * COS(RADIANS(p.latitude))
                       * COS(RADIANS(p.longitude) - RADIANS(:lng))
                       + SIN(RADIANS(:lat)) * SIN(RADIANS(p.latitude)))))) AS distance_km
                FROM pickup_points p
                WHERE p.active = TRUE
                  AND p.status = 'APPROVED'
                  AND (p.closed_until IS NULL OR p.closed_until < CURRENT_TIMESTAMP)
                  AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL
                  AND p.latitude BETWEEN :lat - (:radiusKm / 111.0)
                                     AND :lat + (:radiusKm / 111.0)
                  AND p.longitude BETWEEN :lng - (:radiusKm / 111.0)
                                      AND :lng + (:radiusKm / 111.0)
            ) near
            WHERE near.distance_km <= :radiusKm
            ORDER BY near.distance_km ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findNear(@Param("lat") double lat, @Param("lng") double lng,
                            @Param("radiusKm") double radiusKm, @Param("limit") int limit);

    /** One point, visible to the public only if it is open for business. */
    @Query("SELECT p FROM PickupPoint p WHERE p.id = :id AND p.active = TRUE "
         + "AND p.status = com.sujula.model.constant.PartnerStatus.APPROVED")
    Optional<PickupPoint> findPublicById(@Param("id") Long id);

    /**
     * Points in a town, for a shopper who gave a place rather than a position.
     *
     * <p>Same closure condition as the two searches above. A shopper who typed
     * "Latrikunda" instead of dropping a pin is the same shopper and must not
     * get a different answer.
     */
    @Query("SELECT p FROM PickupPoint p WHERE p.active = TRUE "
         + "AND p.status = com.sujula.model.constant.PartnerStatus.APPROVED "
         + "AND (p.closedUntil IS NULL OR p.closedUntil < CURRENT_TIMESTAMP) "
         + "AND LOWER(p.city) = LOWER(:city) ORDER BY p.name ASC")
    List<PickupPoint> findPublicInCity(@Param("city") String city);

    /**
     * The back office's counter list.
     *
     * <p>Capacity pressure is filtered in the query rather than in Java, because
     * "show me the counters that are full" is the question an administrator asks
     * when something is going wrong and paging through every point in the country
     * to find three of them is not an answer.
     */
    @Query("SELECT p FROM PickupPoint p "
         + "WHERE (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')) "
         + "   OR LOWER(p.city) LIKE LOWER(CONCAT('%', :q, '%')) "
         + "   OR LOWER(p.contactPhone) LIKE LOWER(CONCAT('%', :q, '%'))) "
         + "AND (:status IS NULL OR p.status = :status) "
         + "AND (:countryCode IS NULL OR UPPER(p.countryCode) = UPPER(:countryCode)) "
         + "AND (:fullOnly = FALSE OR p.storedParcels >= p.capacity) "
         + "ORDER BY p.countryCode ASC, p.city ASC, p.name ASC")
    Page<PickupPoint> adminSearch(@Param("q") String q,
                                  @Param("status") PartnerStatus status,
                                  @Param("countryCode") String countryCode,
                                  @Param("fullOnly") boolean fullOnly,
                                  Pageable pageable);

    /** True when this name is already taken in that city, ignoring one point. */
    @Query("SELECT COUNT(p) > 0 FROM PickupPoint p WHERE LOWER(p.name) = LOWER(:name) "
         + "AND LOWER(p.city) = LOWER(:city) AND UPPER(p.countryCode) = UPPER(:countryCode) "
         + "AND (:excludeId IS NULL OR p.id <> :excludeId)")
    boolean nameTakenInCity(@Param("name") String name, @Param("city") String city,
                            @Param("countryCode") String countryCode,
                            @Param("excludeId") Long excludeId);
}