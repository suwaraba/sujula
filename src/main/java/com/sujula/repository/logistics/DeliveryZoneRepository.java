package com.sujula.repository.logistics;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.logistics.DeliveryZone;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, Long> {

    Optional<DeliveryZone> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    /**
     * Every live zone, ready for the registry to hold in memory.
     *
     * <p>Ordered by priority so the first containing zone found is the winning
     * one, and the containment loop does not have to sort afterwards. Loaded
     * whole because a polygon test cannot be pushed into a query on a database
     * without the spatial extensions, and because a country's zone list is
     * counted in dozens.
     */
    @Query("SELECT z FROM DeliveryZone z WHERE z.active = TRUE "
         + "ORDER BY z.priority DESC, z.id ASC")
    List<DeliveryZone> findLive();

    @Query("SELECT z FROM DeliveryZone z "
         + "WHERE (:countryCode IS NULL OR UPPER(z.countryCode) = UPPER(CAST(:countryCode AS String))) "
         + "AND (:active IS NULL OR z.active = :active) "
         + "AND (:q IS NULL OR LOWER(z.name) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')) "
         + "     OR LOWER(z.code) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%'))) "
         + "ORDER BY z.countryCode ASC, z.priority DESC, z.name ASC")
    Page<DeliveryZone> search(@Param("q") String q,
                             @Param("countryCode") String countryCode,
                             @Param("active") Boolean active,
                             Pageable pageable);

    long countByActiveTrue();
}
