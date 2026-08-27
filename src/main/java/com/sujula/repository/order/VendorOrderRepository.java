package com.sujula.repository.order;

import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.VendorOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VendorOrderRepository extends JpaRepository<VendorOrder, Long> {

    List<VendorOrder> findByOrderId(Long orderId);

    Page<VendorOrder> findByVendorId(Long vendorId, Pageable pageable);

    Page<VendorOrder> findByVendorIdAndStatus(Long vendorId, VendorOrderStatus status, Pageable pageable);

    @Query(value = "SELECT vo FROM VendorOrder vo LEFT JOIN FETCH vo.order o WHERE vo.vendor.id = :vendorId",
           countQuery = "SELECT COUNT(vo) FROM VendorOrder vo WHERE vo.vendor.id = :vendorId")
    Page<VendorOrder> findByVendorIdFetchOrder(@Param("vendorId") Long vendorId, Pageable pageable);

    long countByVendorIdAndStatus(Long vendorId, VendorOrderStatus status);
}
