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
            WHERE LOWER(v.storeEmail) = LOWER(CAST(:email AS String))
               OR LOWER(v.user.email) = LOWER(CAST(:email AS String))
            """)
    boolean existsByAssociatedEmail(@Param("email") String email);
    Page<Vendor> findByStatus(PartnerStatus status, Pageable pageable);

    /**
     * The administrative store list, with every filter optional.
     *
     * <p>{@code payoutsHeld} is here because it is the question an administrator
     * asks after a suspension sweep — "whose money are we sitting on" — and it
     * is the one thing on this screen a seller will telephone about.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT v FROM Vendor v WHERE
              (:status IS NULL OR v.status = :status)
              AND (:country IS NULL OR v.pickupCountryCode = :country)
              AND (:payoutsHeld IS NULL OR
                   (:payoutsHeld = TRUE AND v.payoutsHeldAt IS NOT NULL) OR
                   (:payoutsHeld = FALSE AND v.payoutsHeldAt IS NULL))
              AND (:q IS NULL OR
                   LOWER(v.storeName) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')) OR
                   LOWER(v.storeSlug) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')) OR
                   LOWER(v.user.email) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')))
            ORDER BY v.createdAt DESC
            """)
    Page<Vendor> adminSearch(@org.springframework.data.repository.query.Param("q") String q,
                             @org.springframework.data.repository.query.Param("status") PartnerStatus status,
                             @org.springframework.data.repository.query.Param("country") String country,
                             @org.springframework.data.repository.query.Param("payoutsHeld") Boolean payoutsHeld,
                             Pageable pageable);
    long countByStatus(PartnerStatus status);

    /**
     * One store, but only if this user owns it.
     *
     * <p>Ownership is the query, not a comparison after it. Every endpoint on
     * the {@code /vendor/stores} surface resolves through this, so somebody
     * else's store is not found rather than found and refused — and a store id
     * is a small integer anyone can walk.
     */
    @Query("SELECT v FROM Vendor v WHERE v.id = :id AND v.user.id = :userId")
    Optional<Vendor> findByIdAndOwnerId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * The same store with its opening hours already loaded.
     *
     * <p>{@code spring.jpa.open-in-view} is off, so a response that reads the
     * hours must have fetched them inside the transaction — otherwise the first
     * client to ask for a store detail gets a lazy-initialisation failure rather
     * than a store.
     */
    @Query("SELECT v FROM Vendor v LEFT JOIN FETCH v.operatingHours "
         + "WHERE v.id = :id AND v.user.id = :userId")
    Optional<Vendor> findByIdAndOwnerIdWithHours(@Param("id") Long id,
                                                 @Param("userId") Long userId);

    /** Whether a slug is taken by a store other than this one. */
    @Query("SELECT COUNT(v) > 0 FROM Vendor v WHERE v.storeSlug = :slug AND v.id <> :exceptId")
    boolean existsByStoreSlugAndIdNot(@Param("slug") String slug, @Param("exceptId") Long exceptId);

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
            WHERE LOWER(v.storeName) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%'))
               OR LOWER(v.description) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%'))
            """)
    Page<Vendor> searchByName(@Param("q") String query, Pageable pageable);
}
