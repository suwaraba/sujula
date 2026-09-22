package com.sujula.repository.product;

import com.sujula.model.products.Brand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandRepository extends JpaRepository<Brand, Long> {

    /**
     * The brands a shopper may see, in the order a list should show them.
     *
     * <p>Ordered in the query: sortOrder is an editorial decision somebody made,
     * and re-sorting it in Java would be work done twice for an answer the
     * database already has.
     */
    @Query("SELECT b FROM Brand b WHERE b.active = true ORDER BY b.sortOrder ASC, b.name ASC")
    List<Brand> findActive();

    Optional<Brand> findBySlugIgnoreCase(String slug);

    /**
     * Only the brands that actually have something to buy.
     *
     * <p>A brand filter listing names with no stock behind them sends shoppers
     * into empty result pages, which reads as a broken search rather than an
     * empty catalogue.
     */
    @Query("SELECT DISTINCT b FROM Brand b JOIN Product p ON p.brand.id = b.id "
         + "WHERE b.active = true AND p.active = true ORDER BY b.sortOrder ASC, b.name ASC")
    List<Brand> findActiveWithProducts();
}
