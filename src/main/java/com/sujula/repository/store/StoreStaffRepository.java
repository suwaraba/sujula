package com.sujula.repository.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.StoreStaffStatus;
import com.sujula.model.store.StoreStaff;

@Repository
public interface StoreStaffRepository extends JpaRepository<StoreStaff, Long> {

    /** Everyone attached to a store, revoked rows included — the access history. */
    @Query("SELECT s FROM StoreStaff s LEFT JOIN FETCH s.user "
         + "WHERE s.vendor.id = :vendorId ORDER BY s.createdAt ASC")
    List<StoreStaff> findForVendor(@Param("vendorId") Long vendorId);

    @Query("SELECT s FROM StoreStaff s WHERE s.vendor.id = :vendorId AND s.status <> :excluded")
    List<StoreStaff> findForVendorExcluding(@Param("vendorId") Long vendorId,
                                            @Param("excluded") StoreStaffStatus excluded);

    /**
     * One staff member of this store, addressed by their user id.
     *
     * <p>The endpoints are {@code /staff/{userId}} rather than {@code /staff/{id}}
     * because the owner knows who they invited, not which row it became. An
     * invitation not yet accepted has no user id, so it is addressed by email
     * through {@link #findByVendorIdAndEmailIgnoreCase} instead.
     */
    @Query("SELECT s FROM StoreStaff s LEFT JOIN FETCH s.user "
         + "WHERE s.vendor.id = :vendorId AND s.user.id = :userId")
    Optional<StoreStaff> findByVendorIdAndUserId(@Param("vendorId") Long vendorId,
                                                 @Param("userId") Long userId);

    Optional<StoreStaff> findByVendorIdAndEmailIgnoreCase(Long vendorId, String email);

    /** Every store this person works in, for resolving what they may do. */
    @Query("SELECT s FROM StoreStaff s WHERE s.user.id = :userId AND s.status = 'ACTIVE'")
    List<StoreStaff> findActiveForUser(@Param("userId") Long userId);

    Optional<StoreStaff> findByInviteTokenHash(String inviteTokenHash);

    long countByVendorIdAndStatus(Long vendorId, StoreStaffStatus status);

    /**
     * Everyone who currently has access or is about to — invited and active
     * together, revoked left out.
     *
     * <p>One query rather than two counts added up. It is read on every store
     * response, and a figure assembled from two round trips can also disagree
     * with itself if a row moves between them.
     */
    @Query("SELECT COUNT(s) FROM StoreStaff s WHERE s.vendor.id = :vendorId "
         + "AND s.status <> com.sujula.model.constant.StoreStaffStatus.REVOKED")
    long countLiveForVendor(@Param("vendorId") Long vendorId);
}
