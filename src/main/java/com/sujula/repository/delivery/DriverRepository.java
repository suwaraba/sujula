package com.sujula.repository.delivery;

import com.sujula.model.Driver;
import com.sujula.model.enums.DriverStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByUserId(Long userId);
    boolean existsByUserId(Long userId);

    Page<Driver> findByStatus(DriverStatus status, Pageable pageable);

    /** All available approved drivers (for admin assignment). */
    List<Driver> findByStatusAndAvailableTrue(DriverStatus status);

    /** Available approved drivers filtered by country (locality-aware assignment). */
    List<Driver> findByCountryCodeAndStatusAndAvailableTrue(String countryCode, DriverStatus status);

    /** All APPROVED drivers that have reported GPS data (admin live-tracking map). */
    List<Driver> findByStatusAndCurrentLatitudeIsNotNull(DriverStatus status);
}
