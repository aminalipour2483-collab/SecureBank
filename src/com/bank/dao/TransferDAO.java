package com.bank.dao;

import com.bank.model.Transfer;
import com.bank.model.TransferExecutionResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TransferDAO — contract for inter-account transfer operations.
 *
 * Folder: BankManagementSystem/src/com/bank/dao/TransferDAO.java
 *
 * The most important method here is executeTransfer(), which wraps the
 * entire money-movement sequence in a single DB transaction so it is atomic:
 * either ALL steps succeed, or ALL are rolled back.
 */
public interface TransferDAO {

    /**
     * Execute a full atomic transfer between two accounts.
     *
     * Internally this method performs these steps inside ONE DB transaction:
     *   1. Debit  sender   balance  (UPDATE accounts)
     *   2. Credit receiver balance  (UPDATE accounts)
     *   3. Insert TRANSFER_OUT ledger entry (INSERT transactions)
     *   4. Insert TRANSFER_IN  ledger entry (INSERT transactions)
     *   5. Insert the transfer record itself (INSERT transfers)
     *
     * If any step fails, the entire sequence is rolled back.
     *
     * @return the committed transfer ID and the sender balance snapshot captured
     *         while both account rows were locked. Business/SQL failures are thrown.
     */
    TransferExecutionResult executeTransfer(Transfer transfer, String idempotencyKey);

    /** Convenience for one-shot callers; retryable clients should reuse an explicit key. */
    default TransferExecutionResult executeTransfer(Transfer transfer) {
        return executeTransfer(transfer, UUID.randomUUID().toString());
    }

    /** Find a transfer record by its primary key. */
    Optional<Transfer> findById(long transferId);

    /** Find by the unique reference number printed on receipts. */
    Optional<Transfer> findByReferenceNumber(String referenceNumber);

    /** All transfers where this account was the sender. */
    List<Transfer> findBySenderAccountId(long accountId);

    /** All transfers where this account was the receiver. */
    List<Transfer> findByReceiverAccountId(long accountId);

    /**
     * All transfers involving this account (either direction), newest first.
     * Used to build the transfer history screen for a customer.
     */
    List<Transfer> findAllByAccountId(long accountId);

    /** Return every transfer row — admin reporting only. */
    List<Transfer> findAll();

    /**
     * Update the status of an existing transfer record.
     * Used to mark a PENDING transfer as COMPLETED, FAILED, or REVERSED.
     * @return true if exactly one row was updated.
     */
    boolean updateStatus(long transferId, Transfer.TransferStatus newStatus);
}
