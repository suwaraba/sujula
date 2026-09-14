package com.sujula.repository.user;

import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);
    Page<User> findByRole(UserRole role, Pageable pageable);

    /**
     * The administrative search: one query with every filter optional.
     *
     * <p>Written as one query rather than a specification builder because the
     * filters are a closed set and will stay one — and because a query somebody
     * can read is a query somebody can see the missing index on. The text match
     * is deliberately narrow: name, email and phone, not a free-text sweep of
     * every column, so an agent looking for "Fatou" does not get every order
     * note that mentions her.
     *
     * <p>{@code lockedOut} reads the sanction rows rather than {@code enabled},
     * because the flag is a cache of them: an account whose suspension lapsed an
     * hour ago is not locked out even if nothing has swept it yet, and a filter
     * that disagreed with the gate would send agents to argue with people who
     * can already sign in.
     */
    @Query("""
            SELECT u FROM User u WHERE
              (:role IS NULL OR u.role = :role)
              AND (:country IS NULL OR u.detectedCountryCode = :country)
              AND (:blocked IS NULL OR u.blocked = :blocked)
              AND (:q IS NULL OR
                   LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) OR
                   LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :q, '%')) OR
                   u.phone LIKE CONCAT('%', :q, '%'))
              AND (:lockedOut IS NULL OR :lockedOut = (
                   CASE WHEN EXISTS (
                     SELECT 1 FROM Sanction s WHERE s.user = u AND s.liftedAt IS NULL
                       AND (s.expiresAt IS NULL OR s.expiresAt > :now)
                       AND s.type IN (com.sujula.model.constant.SanctionType.SUSPENSION,
                                      com.sujula.model.constant.SanctionType.BAN))
                   THEN TRUE ELSE FALSE END))
            ORDER BY u.createdAt DESC
            """)
    Page<User> search(@Param("q") String q,
                      @Param("role") UserRole role,
                      @Param("country") String country,
                      @Param("blocked") Boolean blocked,
                      @Param("lockedOut") Boolean lockedOut,
                      @Param("now") java.time.LocalDateTime now,
                      Pageable pageable);

    /** How many accounts of each role there are, for the dashboard. */
    @Query("SELECT u.role, COUNT(u) FROM User u GROUP BY u.role")
    java.util.List<Object[]> countByRole();

    /** Used at startup to tell an unadministered deployment from a healthy one. */
    long countByRole(UserRole role);
    Optional<User> findByEmailVerificationToken(String token);
    Optional<User> findByPasswordResetToken(String token);

}
