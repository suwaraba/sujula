package com.sujula.repository.notification;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.notification.PushDevice;

@Repository
public interface PushDeviceRepository extends JpaRepository<PushDevice, Long> {

    /** A device this user actually registered. Anybody else's is not found. */
    @Query("SELECT d FROM PushDevice d WHERE d.id = :id AND d.user.id = :userId")
    Optional<PushDevice> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT d FROM PushDevice d WHERE d.user.id = :userId AND d.revokedAt IS NULL "
         + "ORDER BY d.lastSeenAt DESC NULLS LAST, d.createdAt DESC")
    List<PushDevice> findLiveForUser(@Param("userId") Long userId);

    /**
     * Whoever currently holds this token, whichever account it is under.
     *
     * <p>Not scoped to a user on purpose, and it is the only query here that is
     * not. Operating systems reassign push tokens: the same token appearing
     * under a second account means the handset changed hands or the app was
     * reinstalled by somebody else, and the previous registration has to be
     * revoked rather than left pointing at a person who no longer has that
     * phone — otherwise the next owner is sent their parcel codes.
     */
    @Query("SELECT d FROM PushDevice d WHERE d.token = :token AND d.revokedAt IS NULL")
    List<PushDevice> findLiveByToken(@Param("token") String token);
}
