package com.bank.dao;

import com.bank.model.Account;
import com.bank.model.BalanceChangeResult;
import com.bank.model.Transaction.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AccountDAO — contract for all account database operations.
 *
 * Folder: BankManagementSystem/src/com/bank/dao/AccountDAO.java
 */
public interface AccountDAO {

    /** Maximum number of accounts per customer, including locked and closed accounts. */
    int MAX_ACCOUNTS_PER_CUSTOMER = 4;

    /**
     * Insert a new account row.
     * @return the auto-generated account_id, or -1 on failure.
     */
    long save(Account account);

    /**
     * Create an account and, when its opening balance is positive, write the
     * matching DEPOSIT ledger entry atomically in one database transaction.
     */
    long saveWithOpeningDeposit(Account account);

    /** Find by primary key. */
    Optional<Account> findById(long accountId);

    /** Find by the 12-digit account number printed on statements. */
    Optional<Account> findByAccountNumber(String accountNumber);

    /** Find by the 12-digit card number. Used during customer login. */
    Optional<Account> findByCardNumber(String cardNumber);

    /** Return all accounts belonging to one customer. */
    List<Account> findByCustomerId(long customerId);

    /** Return every account row — used by the admin overview screen. */
    List<Account> findAll();

    /**
     * Atomically apply a deposit or withdrawal and append its immutable ledger
     * entry. Account/customer status, balance, balance update, and ledger insert
     * are checked or changed within one database transaction.
     */
    BalanceChangeResult executeBalanceChange(long accountId,
                                             TransactionType type,
                                             BigDecimal amount,
                                             String idempotencyKey);

    /** Compatibility entry point. Callers that may retry must retain and pass an explicit key. */
    default BalanceChangeResult executeBalanceChange(long accountId,
                                                      TransactionType type,
                                                      BigDecimal amount) {
        return executeBalanceChange(accountId, type, amount, UUID.randomUUID().toString());
    }

    /**
     * Administrator status change with row-locked lifecycle validation.
     * CLOSED accounts cannot be reopened through this generic operation; closure requires zero balance.
     * @return true if the status is already correct or exactly one row was updated.
     */
    boolean updateStatus(long accountId, Account.AccountStatus newStatus);

    /** Actor-aware status update for auditable administrator operations. */
    default boolean updateStatus(long accountId, Account.AccountStatus newStatus, String actor) {
        return updateStatus(accountId, newStatus);
    }

    /** Mark an account as locked specifically because the third consecutive PIN attempt failed. */
    boolean lockAfterFailedPinAttempts(long accountId);

    /** Atomically replace a PIN and unlock only a FAILED_PIN-locked account. */
    boolean resetPinAndUnlockAfterRecovery(long accountId, String newPinHash);

    /**
     * Update the PIN hash (after a customer changes their PIN).
     * @return true if exactly one row was updated.
     */
    boolean updatePinHash(long accountId, String newPinHash);

    /**
     * Increment failed_pin_attempts by 1 for the given account.
     * @return the NEW attempt count after incrementing.
     */
    int incrementFailedAttempts(long accountId);

    /**
     * Reset failed_pin_attempts back to 0 on a successful login.
     * @return true if exactly one row was updated.
     */
    boolean resetFailedAttempts(long accountId);

    /**
     * Hard-delete an account row.
     * The DB enforces ON DELETE RESTRICT on transactions/transfers, so the
     * Service layer must verify the account has no history first.
     */
    boolean delete(long accountId);
}
