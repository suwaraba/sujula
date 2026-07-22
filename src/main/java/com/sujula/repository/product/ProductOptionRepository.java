package com.sujula.repository.product;

import com.sujula.model.products.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {
    Optional<ProductOption> findByCode(String code);
    boolean existsByCode(String code);
}
