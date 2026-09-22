package com.sujula.repository.product;

import com.sujula.model.products.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {
    Optional<ProductOption> findByCode(String code);
    boolean existsByCode(String code);

    /**
     * One listing's options, with their values already loaded.
     *
     * <p>Fetched rather than lazy because building a variant reads every value
     * of every option at once, and the lazy version is a query per option on a
     * page that shows all of them.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT o FROM ProductOption o LEFT JOIN FETCH o.values "
          + "WHERE o.product.id = :productId ORDER BY o.sortOrder ASC")
    List<ProductOption> findByProductIdOrderBySortOrderAsc(
            @org.springframework.data.repository.query.Param("productId") Long productId);
}
