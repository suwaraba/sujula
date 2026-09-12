package com.sujula.model.constant;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A single thing a caller is allowed to do, resolved per user and handed to the
 * client by {@code GET /me/permissions}.
 *
 * <p>This exists so a client can decide what to render without re-deriving the
 * server's rules from the user's role — which is how a UI ends up offering a
 * button the API then refuses. It is <strong>not</strong> the authorisation
 * mechanism: every endpoint still enforces its own {@code @PreAuthorize} and its
 * own ownership check. This is a description of what the server will allow, not
 * the thing that allows it.
 *
 * <p>Permissions depend on more than the role. A vendor whose application is
 * still pending cannot list products, so {@code CATALOGUE_WRITE} is granted on
 * approval rather than on the role alone — see {@code PermissionResolver}.
 */
public enum Permission {

    // ── Everyone signed in ───────────────────────────────────────────────────
    PROFILE_READ,
    PROFILE_WRITE,
    SESSION_MANAGE,
    DATA_EXPORT,
    DATA_ERASURE,

    // ── Buying ───────────────────────────────────────────────────────────────
    CART_MANAGE,
    ORDER_PLACE,
    ORDER_READ_OWN,
    ORDER_CANCEL_OWN,
    ADDRESS_MANAGE,
    REVIEW_WRITE,

    // ── Selling ──────────────────────────────────────────────────────────────
    VENDOR_PROFILE_READ,
    VENDOR_PROFILE_WRITE,
    CATALOGUE_WRITE,
    VENDOR_ORDER_READ,
    VENDOR_ORDER_FULFIL,
    VENDOR_PAYOUT_READ,

    // ── Fulfilment staff ─────────────────────────────────────────────────────
    DELIVERY_READ,
    DELIVERY_UPDATE,
    PAYMENT_COLLECT,

    // ── Platform ─────────────────────────────────────────────────────────────
    USER_ADMIN,
    VENDOR_ADMIN,
    CATALOGUE_ADMIN,
    ORDER_ADMIN,
    PAYMENT_ADMIN,
    EXCHANGE_RATE_ADMIN,
    AUDIT_READ;

    /** Granted to every authenticated account whatever its role. */
    public static Set<Permission> baseline() {
        return Collections.unmodifiableSet(EnumSet.of(
                PROFILE_READ, PROFILE_WRITE, SESSION_MANAGE, DATA_EXPORT, DATA_ERASURE));
    }
}
