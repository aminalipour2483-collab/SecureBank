package com.bank.service;

import com.bank.security.AdminPrincipal;
import com.bank.security.CustomerPrincipal;
import com.bank.model.Transfer;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * TransferService — business operations for inter-account transfers.
 *
 * Folder: BankManagementSystem/src/com/bank/service/TransferService.java
 */
public interface TransferService {

    /**
     * Process a full money transfer between two accounts exactly once per idempotency key.
     * Reuse the same key for all retries of the same logical request.
     *
     * Business rules enforced BEFORE calling the DAO:
     *   1. Sender account must exist and be ACTIVE
     *   2. Receiver account must exist and be ACTIVE
     *   3. Sender and receiver must be different accounts
     *   4. Amount must be greater than zero
     *   5. Sender must have sufficient balance
     *
     * The DAO layer then executes all DB writes atomically in one transaction.
     *
     * @param senderAccountId    account the money leaves
     * @param receiverAccountId  account the money arrives in
     * @param amount             must be > 0
     * @return the completed Transfer record (with its generated ID and reference)
     *
     * @throws com.bank.exception.AccountNotFoundException   if either account not found
     * @throws com.bank.exception.AccountLockedException     if either account is locked
     * @throws com.bank.exception.InsufficientFundsException if sender balance < amount
     * @throws com.bank.exception.BankException              for any other rule violation
     */
    Transfer processTransfer(CustomerPrincipal principal,
                             long senderAccountId,
                             long receiverAccountId,
                             BigDecimal amount,
                             String idempotencyKey);

    /** @deprecated Internal compatibility entry point; customer UI must use the principal overload. */
    @Deprecated
    Transfer processTransfer(long senderAccountId,
                             long receiverAccountId,
                             BigDecimal amount,
                             String idempotencyKey);

    /** Convenience for one-shot callers; retryable clients should reuse an explicit key. */
    @Deprecated
    default Transfer processTransfer(long senderAccountId,
                                     long receiverAccountId,
                                     BigDecimal amount) {
        return processTransfer(senderAccountId, receiverAccountId, amount,
                UUID.randomUUID().toString());
    }

    /**
     * Retrieve the full transfer history for an account (both directions).
     *
     * @throws com.bank.exception.AccountNotFoundException if account not found
     */
    /** Resolve a transfer recipient through the service boundary without exposing DAO objects to Swing. */
    long resolveRecipientAccountIdByCardNumber(CustomerPrincipal principal, String cardNumber);

    /** Resolve a transfer recipient through the service boundary without exposing DAO objects to Swing. */
    long resolveRecipientAccountIdByAccountNumber(CustomerPrincipal principal, String accountNumber);

    List<Transfer> getTransferHistory(CustomerPrincipal principal, long accountId);

    /** @deprecated Internal compatibility entry point; use a principal overload. */
    @Deprecated
    List<Transfer> getTransferHistory(long accountId);

    /**
     * Find one transfer by its unique reference number.
     * Used to print a receipt or handle a support inquiry.
     *
     * @throws com.bank.exception.BankException if reference not found
     */
    Transfer findByReferenceNumber(CustomerPrincipal principal, String referenceNumber);

    /** @deprecated Internal compatibility entry point; use a principal overload. */
    @Deprecated
    Transfer findByReferenceNumber(String referenceNumber);

    /**
     * Return every transfer record — used by the admin panel overview table.
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    List<Transfer> findAll(AdminPrincipal admin);

    /**
     * Admin override: update the status of an existing transfer record.
     * e.g. PENDING → FAILED, or COMPLETED → REVERSED.
     *
     * @throws com.bank.exception.BankException if transfer not found
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    void updateTransferStatus(AdminPrincipal admin, long transferId, Transfer.TransferStatus newStatus);
}
