package com.sujula.repository.product;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.products.ProductTranslation;

@Repository
public interface ProductTranslationRepository extends JpaRepository<ProductTranslation, Long> {

    List<ProductTranslation> findByProductIdOrderByLocaleAsc(Long productId);

    Optional<ProductTranslation> findByProductIdAndLocaleIgnoreCase(Long productId, String locale);

    /**
     * Translations for several listings at once.
     *
     * <p>A catalogue page renders twenty products in the reader's language, and
     * asking per product is twenty queries for one screen.
     */
    @Query("SELECT t FROM ProductTranslation t WHERE t.product.id IN :productIds "
         + "AND LOWER(t.locale) = LOWER(CAST(:locale AS String))")
    List<ProductTranslation> findForProducts(@Param("productIds") java.util.Collection<Long> productIds,
                                             @Param("locale") String locale);

    void deleteByProductIdAndLocaleIgnoreCase(Long productId, String locale);
}
