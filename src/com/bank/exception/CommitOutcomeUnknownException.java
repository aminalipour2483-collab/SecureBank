package com.bank.exception;

/** A commit was attempted but its acknowledgement was not received reliably.
 * Callers must reconcile persisted state before retrying; never report rollback as certain. */
public final class CommitOutcomeUnknownException extends BankException {
    public CommitOutcomeUnknownException(String operation, Throwable cause) {
        super(operation + " Commit outcome is unknown. Verify the recorded operation before retrying.", cause);
    }
}
