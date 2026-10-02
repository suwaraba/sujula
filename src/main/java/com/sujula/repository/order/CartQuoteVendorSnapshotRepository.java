package com.sujula.repository.order;

import com.sujula.model.order.CartQuoteVendorSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartQuoteVendorSnapshotRepository
        extends JpaRepository<CartQuoteVendorSnapshot, Long> {

    List<CartQuoteVendorSnapshot> findAllByQuoteIdOrderByVendorId(String quoteId);

    Optional<CartQuoteVendorSnapshot> findByQuoteIdAndVendorId(String quoteId, Long vendorId);
}
