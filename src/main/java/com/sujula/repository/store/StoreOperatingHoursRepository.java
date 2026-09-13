package com.sujula.repository.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sujula.model.store.StoreOperatingHours;

@Repository
public interface StoreOperatingHoursRepository extends JpaRepository<StoreOperatingHours, Long> {

    List<StoreOperatingHours> findByVendorIdOrderByDayOfWeekAsc(Long vendorId);

    void deleteByVendorId(Long vendorId);
}
