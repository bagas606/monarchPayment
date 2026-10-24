package id.ppob2.admin.session;

public enum SessionState {
    /** Password accepted; a TOTP code is still owed. May only call MFA verify. */
    PENDING_MFA,
    /** The role requires MFA and none is enrolled. May only enrol. */
    PENDING_ENROLL,
    ACTIVE
}
