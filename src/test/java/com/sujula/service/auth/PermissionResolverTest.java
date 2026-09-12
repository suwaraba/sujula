package com.sujula.service.auth;

import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.Permission;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.user.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an account may do, and — the part that matters — what a role alone does
 * not tell you.
 */
class PermissionResolverTest {

    private VendorRepository vendors;
    private PermissionResolver resolver;

    @BeforeEach
    void setUp() {
        vendors = mock(VendorRepository.class);
        resolver = new PermissionResolver(vendors);
    }

    private static User user(UserRole role) {
        User user = new User();
        user.setId(7L);
        user.setRole(role);
        user.setEnabled(true);
        user.setBlocked(false);
        return user;
    }

    private static Vendor vendor(PartnerStatus status) {
        Vendor vendor = new Vendor();
        vendor.setId(42L);
        vendor.setStatus(status);
        return vendor;
    }

    @Test
    void customerCanBuyButNotSell() {
        Set<Permission> granted = resolver.resolve(user(UserRole.CUSTOMER));

        assertTrue(granted.contains(Permission.ORDER_PLACE));
        assertTrue(granted.contains(Permission.CART_MANAGE));
        assertFalse(granted.contains(Permission.CATALOGUE_WRITE));
        assertFalse(granted.contains(Permission.USER_ADMIN));
    }

    /**
     * The case the whole class exists for. A pending applicant holds the VENDOR
     * role and cannot list a single product; deriving the client's capabilities
     * from the role would draw them a back office of buttons the API refuses.
     */
    @Test
    void pendingVendorHasTheRoleButNotTheRights() {
        when(vendors.findByUserId(7L)).thenReturn(Optional.of(vendor(PartnerStatus.PENDING)));

        Set<Permission> granted = resolver.resolve(user(UserRole.VENDOR));

        assertTrue(granted.contains(Permission.VENDOR_PROFILE_READ),
                "a pending applicant has to be able to see why they are pending");
        assertFalse(granted.contains(Permission.CATALOGUE_WRITE));
        assertFalse(granted.contains(Permission.VENDOR_ORDER_FULFIL));
    }

    @Test
    void approvedVendorMaySell() {
        when(vendors.findByUserId(7L)).thenReturn(Optional.of(vendor(PartnerStatus.APPROVED)));

        Set<Permission> granted = resolver.resolve(user(UserRole.VENDOR));

        assertTrue(granted.contains(Permission.CATALOGUE_WRITE));
        assertTrue(granted.contains(Permission.VENDOR_ORDER_FULFIL));
        assertTrue(granted.contains(Permission.VENDOR_PAYOUT_READ));
    }

    /** Suspension takes the trading rights away without touching the role. */
    @Test
    void suspendedVendorKeepsTheRoleAndLosesTheTrade() {
        when(vendors.findByUserId(7L)).thenReturn(Optional.of(vendor(PartnerStatus.SUSPENDED)));

        Set<Permission> granted = resolver.resolve(user(UserRole.VENDOR));

        assertTrue(granted.contains(Permission.VENDOR_PROFILE_READ));
        assertFalse(granted.contains(Permission.CATALOGUE_WRITE));
        assertFalse(resolver.canTrade(user(UserRole.VENDOR)));
    }

    @Test
    void vendorRoleWithNoVendorRecordGetsOnlyTheProfileRead() {
        when(vendors.findByUserId(7L)).thenReturn(Optional.empty());

        Set<Permission> granted = resolver.resolve(user(UserRole.VENDOR));

        assertTrue(granted.contains(Permission.VENDOR_PROFILE_READ));
        assertFalse(granted.contains(Permission.VENDOR_PROFILE_WRITE));
        assertFalse(granted.contains(Permission.CATALOGUE_WRITE));
    }

    /**
     * A blocked account keeps its data-protection rights and nothing else.
     * Being in bad standing is not grounds for withholding someone's own data.
     */
    @Test
    void blockedAccountKeepsOnlyItsDataRights() {
        User user = user(UserRole.VENDOR);
        user.setBlocked(true);

        Set<Permission> granted = resolver.resolve(user);

        assertEquals(Set.of(Permission.PROFILE_READ, Permission.DATA_EXPORT,
                            Permission.SESSION_MANAGE), granted);
        verify(vendors, never()).findByUserId(anyLong());
    }

    @Test
    void disabledAccountIsTreatedTheSameWay() {
        User user = user(UserRole.CUSTOMER);
        user.setEnabled(false);

        assertFalse(resolver.resolve(user).contains(Permission.ORDER_PLACE));
    }

    @Test
    void adminIsAlsoABuyer() {
        Set<Permission> granted = resolver.resolve(user(UserRole.ADMIN));

        assertTrue(granted.contains(Permission.USER_ADMIN));
        assertTrue(granted.contains(Permission.ORDER_PLACE),
                "staff buy on the platform too, through the same checkout");
    }

    /**
     * The overload {@code /me} uses. Handing the vendor in must not cause a
     * second lookup — that duplicate query is the reason the overload exists.
     */
    @Test
    void aPreLoadedVendorIsNotFetchedAgain() {
        Set<Permission> granted =
                resolver.resolve(user(UserRole.VENDOR), Optional.of(vendor(PartnerStatus.ACTIVE)));

        assertTrue(granted.contains(Permission.CATALOGUE_WRITE));
        verify(vendors, never()).findByUserId(anyLong());
    }
}
