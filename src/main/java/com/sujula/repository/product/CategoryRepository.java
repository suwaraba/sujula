package com.sujula.repository.product;

import com.sujula.model.products.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<com.sujula.model.products.Category> findBySlug(String slug);
    Optional<Category> findByNameIgnoreCase(String name);
    List<Category> findByParentIsNullAndActiveTrue();
    List<Category> findByParentIdAndActiveTrue(Long parentId);
    boolean existsBySlug(String slug);

    @Query("""
        SELECT c FROM Category c WHERE c.active = true AND
        LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%'))
    """)
    List<Category> searchActive(@Param("q") String query);

    // ── Admin queries (parent eagerly fetched to avoid N+1 and lazy-load outside tx) ──

    @Query(value = "SELECT c FROM Category c LEFT JOIN FETCH c.parent ORDER BY c.sortOrder ASC, c.name ASC",
           countQuery = "SELECT COUNT(c) FROM Category c")
    Page<Category> findAllFetchParent(Pageable pageable);

    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.parent ORDER BY c.sortOrder ASC, c.name ASC")
    List<Category> findAllFetchParent();

    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.parent WHERE c.id = :id")
    Optional<Category> findByIdFetchParent(@Param("id") Long id);
}
