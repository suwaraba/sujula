package com.sujula.model.constant;

/**
 * Why a session stopped being usable.
 *
 * <p>Recorded rather than inferred: "the user signed out here" and "we detected
 * a stolen refresh token" look identical once a row is simply deleted, and the
 * second is the one worth being able to find afterwards.
 */
public enum SessionRevocationReason {

    /** The user signed out on this device. */
    LOGOUT,

    /** The user signed out everywhere, from any device. */
    LOGOUT_ALL,

    /** Password changed or reset — every other session is dropped. */
    CREDENTIALS_CHANGED,

    /** Multi-factor authentication was enabled or disabled. */
    MFA_CHANGED,

    /**
     * A refresh token was presented that had already been rotated away.
     *
     * <p>Either the token was stolen and replayed, or a legitimate client raced
     * itself. Both are treated as theft: the whole session is revoked, because
     * the alternative is leaving a thief holding a valid chain.
     */
    TOKEN_REPLAY,

    /** An administrator ended it. */
    ADMIN,

    /** The account was erased under a data-deletion request. */
    ACCOUNT_ERASED
}
