package com.sujula.repository.platform;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sujula.model.platform.FeatureFlag;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    Optional<FeatureFlag> findByFlagKey(String flagKey);

    boolean existsByFlagKey(String flagKey);

    List<FeatureFlag> findAllByOrderByFlagKeyAsc();

    /** The subset a client may be told about. Most are not. */
    List<FeatureFlag> findByClientVisibleTrueOrderByFlagKeyAsc();
}
