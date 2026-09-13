package com.sujula.repository;

import com.sujula.model.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AddressRepository extends JpaRepository<Address, Long> {

    /**
     * Every address on this account, deleted ones included.
     *
     * <p>Kept as it was, because the callers that predate soft deletion — the
     * data export and the erasure worker — want exactly this. A tombstone still
     * carries a name, a phone number and a location, so an export that skipped
     * them would be incomplete and an erasure that skipped them would leave
     * personal data behind.
     */
    List<Address> findByUserId(Long userId);

    /**
     * The address book as its owner sees it: live rows, default first, then
     * newest.
     *
     * <p>Ordered in the query rather than in Java because this is the order a
     * chooser has to offer them in, and sorting a list the database could have
     * returned sorted is work done twice.
     */
    @Query("SELECT a FROM Address a WHERE a.user.id = :userId AND a.deletedAt IS NULL "
         + "ORDER BY a.isDefault DESC, a.id DESC")
    List<Address> findLiveByUserId(@Param("userId") Long userId);

    /**
     * One address, but only if it is this user's and still live.
     *
     * <p>Ownership is the query. Loading by id and comparing the owner afterwards
     * is the version of this that eventually ships with the comparison missing;
     * asking the database for "this id, belonging to this person" cannot. A
     * stranger's address comes back empty, so it reads as not-found rather than
     * forbidden — confirming that address 41 exists tells someone something about
     * a person they have no business knowing.
     */
    @Query("SELECT a FROM Address a WHERE a.id = :id AND a.user.id = :userId AND a.deletedAt IS NULL")
    Optional<Address> findLiveByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** The one checkout preselects. */
    @Query("SELECT a FROM Address a WHERE a.user.id = :userId AND a.deletedAt IS NULL "
         + "AND a.isDefault = true")
    Optional<Address> findLiveDefault(@Param("userId") Long userId);

    @Query("SELECT COUNT(a) FROM Address a WHERE a.user.id = :userId AND a.deletedAt IS NULL")
    long countLiveByUserId(@Param("userId") Long userId);

    /**
     * The next address to promote when the default is removed.
     *
     * <p>Oldest first: the one they have had longest is the likeliest to be
     * where they actually live.
     */
    @Query("SELECT a FROM Address a WHERE a.user.id = :userId AND a.deletedAt IS NULL "
         + "AND a.id <> :excludeId ORDER BY a.id ASC LIMIT 1")
    Optional<Address> findNextDefaultCandidate(@Param("userId") Long userId,
                                               @Param("excludeId") Long excludeId);

    /**
     * Whether any order was placed against this address.
     *
     * <p>What decides between a soft delete and a real one. An order keeps its
     * own snapshot of where it was going, so history never depends on this row —
     * but an order that named it should still resolve to something, so a
     * referenced address is kept as a tombstone and an unreferenced one is
     * removed outright rather than accumulating.
     */
    @Query("SELECT COUNT(o) > 0 FROM Order o WHERE o.shippingAddress.id = :addressId")
    boolean isReferencedByAnyOrder(@Param("addressId") Long addressId);

    @Modifying
    @Query("UPDATE Address a SET a.isDefault = false WHERE a.user.id = :userId")
    void clearDefaultsByUserId(@Param("userId") Long userId);

    long countByUserId(Long userId);
}
