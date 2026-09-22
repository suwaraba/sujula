package com.sujula.repository.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.user.BankAccount;

/**
 * Where a store's money goes.
 *
 * <p>Nothing here fetches by id alone. Every read is scoped to a vendor, because
 * the only thing an id on this table buys an attacker is somebody else's payout
 * destination.
 */
@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    @Query("SELECT b FROM BankAccount b WHERE b.vendor.id = :vendorId ORDER BY b.id ASC")
    List<BankAccount> findForVendor(@Param("vendorId") Long vendorId);

    /**
     * The account payouts actually go to.
     *
     * <p>Exactly one per vendor in practice: this surface replaces rather than
     * appends, so a store cannot accumulate a list of old destinations one of
     * which a payout run might pick.
     */
    @Query("SELECT b FROM BankAccount b WHERE b.vendor.id = :vendorId AND b.isDefault = TRUE "
         + "ORDER BY b.id ASC LIMIT 1")
    Optional<BankAccount> findDefaultForVendor(@Param("vendorId") Long vendorId);

    @Query("SELECT b FROM BankAccount b WHERE b.id = :id AND b.vendor.id = :vendorId")
    Optional<BankAccount> findByIdAndVendorId(@Param("id") Long id,
                                              @Param("vendorId") Long vendorId);
}
