package com.bank.dao;

/** Persistent throttling for customer PIN-recovery attempts. The key is a SHA-256
 * fingerprint of the card number; the raw card number is never stored here. */
public interface PinRecoveryDAO {
    boolean isTemporarilyLocked(String cardFingerprint);
    /** Returns the new failure count, or -1 when the key is already throttled. */
    int recordFailure(String cardFingerprint);
    void clear(String cardFingerprint);
}
