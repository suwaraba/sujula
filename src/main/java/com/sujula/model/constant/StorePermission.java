package com.sujula.model.constant;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a member of a store's staff may do, inside that store.
 *
 * <p>Deliberately its own enum rather than a subset of {@link Permission}. A
 * store owner chooses these values, and if the field held platform permissions
 * then "grant my assistant PAYMENT_ADMIN" would be a well-formed request that
 * only validation stands between and privilege escalation. Here there is no such
 * value to send: the dangerous grants are not representable rather than
 * rejected, which is the difference between a check that can be forgotten and
 * one that cannot.
 *
 * <p>Every one of these is scoped to the store the staff row belongs to. There
 * is no CATALOGUE_WRITE here that reaches another vendor's products.
 */
public enum StorePermission {

    /** See the store's orders, but not act on them. */
    ORDERS_VIEW,

    /** Accept, prepare and hand over the store's orders. */
    ORDERS_FULFIL,

    /** Create, edit and unpublish the store's own listings. */
    CATALOGUE_MANAGE,

    /** Read the store's settlement figures and payout history. */
    FINANCE_VIEW,

    /** Edit the storefront: name, description, policies, opening hours. */
    STORE_PROFILE_MANAGE,

    /** Invite and remove other staff. Never includes the owner. */
    STAFF_MANAGE;

    /**
     * What an invite gets when the owner names nothing.
     *
     * <p>Reading orders and nothing else. A default that could change anything
     * would make "I just added them quickly" a way to lose a catalogue.
     */
    public static Set<StorePermission> leastPrivilege() {
        return Collections.unmodifiableSet(EnumSet.of(ORDERS_VIEW));
    }

    /**
     * Grants a staff member may never hold, whatever the owner asks for.
     *
     * <p>Empty today, and present because the interesting question about this
     * enum is what happens when somebody adds a value to it. A future
     * PAYOUT_DESTINATION_MANAGE belongs here rather than in the list above.
     */
    public static Set<StorePermission> ownerOnly() {
        return Collections.unmodifiableSet(EnumSet.noneOf(StorePermission.class));
    }
}
