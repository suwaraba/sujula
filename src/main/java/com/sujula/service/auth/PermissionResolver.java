package com.sujula.service.auth;

import com.sujula.model.constant.Permission;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.user.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Works out what an account is actually allowed to do, for
 * {@code GET /me/permissions}.
 *
 * <p>A role on its own is not the answer, which is the whole reason this class
 * exists. A vendor whose application is still pending has the VENDOR role and
 * cannot list a single product; a vendor who has been suspended keeps the role
 * and loses the ability to trade. Deriving the client's capabilities from the
 * role alone produces a back office full of buttons the API then refuses.
 *
 * <p>This is advisory. It describes what the server will allow so a client can
 * render honestly — it does not grant anything. Every endpoint still enforces
 * its own {@code @PreAuthorize} and its own ownership check, and would continue
 * to do so if this class returned nonsense.
 */
@Service
public class PermissionResolver {

    private final VendorRepository vendorRepository;

    public PermissionResolver(VendorRepository vendorRepository) {
        this.vendorRepository = vendorRepository;
    }

    @Transactional(readOnly = true)
    public Set<Permission> resolve(User user) {
        return resolve(user, null);
    }

    /**
     * The same resolution, for a caller that has already loaded the vendor record.
     *
     * <p>{@code /me} needs the vendor for its own fields — the vendor id and
     * standing it reports — and would otherwise pay for the same row twice in one
     * response. Pass {@code null} to have it looked up here.
     */
    @Transactional(readOnly = true)
    public Set<Permission> resolve(User user, Optional<Vendor> vendor) {
        if (user == null || user.getRole() == null) {
            return Set.of();
        }

        Set<Permission> granted = EnumSet.copyOf(Permission.baseline());

        // A blocked account keeps nothing but the ability to see and export
        // itself: data-protection rights do not depend on being in good standing.
        if (user.isBlocked() || !user.isEnabled()) {
            return Collections.unmodifiableSet(
                    EnumSet.of(Permission.PROFILE_READ, Permission.DATA_EXPORT, Permission.SESSION_MANAGE));
        }

        granted.addAll(buyerPermissions());

        switch (user.getRole()) {
            case VENDOR -> granted.addAll(vendorPermissions(user, vendor));
            case DELIVERY -> granted.addAll(EnumSet.of(
                    Permission.DELIVERY_READ, Permission.DELIVERY_UPDATE, Permission.PAYMENT_COLLECT));
            case PICKUP_OPERATOR -> granted.addAll(EnumSet.of(
                    Permission.DELIVERY_READ, Permission.DELIVERY_UPDATE, Permission.PAYMENT_COLLECT));
            case ADMIN -> granted.addAll(EnumSet.of(
                    Permission.USER_ADMIN, Permission.VENDOR_ADMIN, Permission.CATALOGUE_ADMIN,
                    Permission.ORDER_ADMIN, Permission.PAYMENT_ADMIN, Permission.EXCHANGE_RATE_ADMIN,
                    Permission.AUDIT_READ));
            case CUSTOMER -> { /* the buyer set above is the whole grant */ }
        }

        return Collections.unmodifiableSet(granted);
    }

    /**
     * Everyone who can hold an account can buy — including staff and admins, who
     * are customers of the platform in their own right and whose orders go
     * through exactly the same checkout.
     */
    private static Set<Permission> buyerPermissions() {
        return EnumSet.of(
                Permission.CART_MANAGE, Permission.ORDER_PLACE, Permission.ORDER_READ_OWN,
                Permission.ORDER_CANCEL_OWN, Permission.ADDRESS_MANAGE, Permission.REVIEW_WRITE);
    }

    /**
     * Selling rights depend on the vendor record's state, not on the role.
     *
     * <p>Reading the profile is always allowed — a pending applicant needs to see
     * why they are pending. Listing products and fulfilling orders arrive only
     * once the vendor may actually trade, which is the same predicate checkout
     * uses, so the two cannot drift apart.
     */
    private Set<Permission> vendorPermissions(User user, Optional<Vendor> known) {
        Set<Permission> granted = EnumSet.of(Permission.VENDOR_PROFILE_READ);

        Optional<Vendor> vendor = known != null ? known : vendorRepository.findByUserId(user.getId());
        if (vendor.isEmpty()) {
            return granted;   // the role was granted but no vendor record exists yet
        }

        granted.add(Permission.VENDOR_PROFILE_WRITE);
        if (vendor.get().getStatus() != null && vendor.get().getStatus().canTrade()) {
            granted.addAll(EnumSet.of(
                    Permission.CATALOGUE_WRITE, Permission.VENDOR_ORDER_READ,
                    Permission.VENDOR_ORDER_FULFIL, Permission.VENDOR_PAYOUT_READ));
        }
        return granted;
    }

    /** True when this account sells and may trade right now. */
    @Transactional(readOnly = true)
    public boolean canTrade(User user) {
        return user != null
                && user.getRole() == UserRole.VENDOR
                && vendorRepository.findByUserId(user.getId())
                        .map(v -> v.getStatus() != null && v.getStatus().canTrade())
                        .orElse(false);
    }
}
