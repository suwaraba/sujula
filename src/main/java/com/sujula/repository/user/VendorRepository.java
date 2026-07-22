package com.sujula.repository.user;


import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.user.Vendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VendorRepository extends JpaRepository<Vendor, Long> {
    Optional<Vendor> findByUserId(Long userId);
    Optional<Vendor> findByStoreSlug(String storeSlug);
    boolean existsByUserId(Long userId);
    boolean existsByStoreSlug(String storeSlug);
    @Query("""
            SELECT COUNT(v) > 0 FROM Vendor v
            WHERE LOWER(v.storeEmail) = LOWER(:email)
               OR LOWER(v.user.email) = LOWER(:email)
            """)
    boolean existsByAssociatedEmail(@Param("email") String email);
    Page<Vendor> findByStatus(PartnerStatus status, Pageable pageable);
    long countByStatus(PartnerStatus status);

//    @Query("""
//        SELECT v FROM Vendor v WHERE v.status = 'APPROVED' AND (
//            LOWER(v.storeName)    LIKE LOWER(CONCAT('%', :q, '%')) OR
//            LOWER(v.description)  LIKE LOWER(CONCAT('%', :q, '%'))
//        )
//    """)
//    Page<Vendor> searchApproved(@Param("q") String query, Pageable pageable);
//
//    // ── Admin fetch queries (JOIN FETCH user to avoid LazyInitializationException) ──
//
//    /**
//     * All vendors — user association eagerly fetched in a single JOIN.
//     * The countQuery must NOT use JOIN FETCH (it would fail / be redundant).
//     */
//    @Query(value      = "SELECT v FROM Vendor v LEFT JOIN FETCH v.user",
//           countQuery = "SELECT COUNT(v) FROM Vendor v")
//    Page<Vendor> findAllFetchUser(Pageable pageable);
//
//    /** Status-filtered vendor list with user eagerly fetched. */
//    @Query(value      = "SELECT v FROM Vendor v LEFT JOIN FETCH v.user WHERE v.status = :status",
//           countQuery = "SELECT COUNT(v) FROM Vendor v WHERE v.status = :status")
//    Page<Vendor> findByStatusFetchUser(@Param("status") VendorStatus status, Pageable pageable);
//
//    /** Single vendor by PK with user eagerly fetched. */
//    @Query("SELECT v FROM Vendor v LEFT JOIN FETCH v.user WHERE v.id = :id")
//    Optional<Vendor> findByIdFetchUser(@Param("id") Long id);
//
//    /** Single vendor by owner user-ID with user eagerly fetched. */
//    @Query("SELECT v FROM Vendor v LEFT JOIN FETCH v.user u WHERE u.id = :userId")
//    Optional<Vendor> findByUserIdFetchUser(@Param("userId") Long userId);
//
//    /**
//     * Admin full-text search across all statuses.
//     * Uses JOIN FETCH so the User proxy is already resolved when VendorResponse.from() runs.
//     * countQuery uses a plain LEFT JOIN (no FETCH) because Hibernate ignores it for counts anyway.
//     */
//    @Query(value      = "SELECT v FROM Vendor v LEFT JOIN FETCH v.user u WHERE " +
//                        "LOWER(v.storeName)  LIKE LOWER(CONCAT('%', :q, '%')) OR " +
//                        "LOWER(v.storeEmail) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
//                        "LOWER(u.email)      LIKE LOWER(CONCAT('%', :q, '%'))",
//           countQuery = "SELECT COUNT(v) FROM Vendor v LEFT JOIN v.user u WHERE " +
//                        "LOWER(v.storeName)  LIKE LOWER(CONCAT('%', :q, '%')) OR " +
//                        "LOWER(v.storeEmail) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
//                        "LOWER(u.email)      LIKE LOWER(CONCAT('%', :q, '%'))")
    @Query("""
            SELECT v FROM Vendor v
            WHERE LOWER(v.storeName) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(v.description) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<Vendor> searchByName(@Param("q") String query, Pageable pageable);
}
