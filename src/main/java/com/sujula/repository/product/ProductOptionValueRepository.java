package com.sujula.repository.product;

import com.sujula.model.products.ProductOptionValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValue, Long> {
    List<ProductOptionValue> findByOptionIdOrderBySortOrderAsc(Long optionId);
}
