package com.bank.dao;

import com.bank.model.Transaction;
import java.util.List;
import java.util.Optional;

/**
 * TransactionDAO — contract for ledger entry operations.
 *
 * Folder: BankManagementSystem/src/com/bank/dao/TransactionDAO.java
 *
 * NOTE: There is intentionally NO update() or delete() method here.
 * Transaction rows are an immutable audit ledger — they must never be
 * modified or removed after they are written.
 */
public interface TransactionDAO {

    /**
     * Insert a new ledger entry.
     * @return the auto-generated transaction_id, or -1 on failure.
     */
    long save(Transaction transaction);

    /** Find a single ledger entry by its primary key. */
    Optional<Transaction> findById(long transactionId);

    /**
     * Return all ledger entries for one account, newest first.
     * Used to build the account statement / history screen.
     */
    List<Transaction> findByAccountId(long accountId);

    /**
     * Return the N most-recent entries for one account.
     * Used for the "mini-statement" (last 5 / 10 transactions).
     */
    List<Transaction> findRecentByAccountId(long accountId, int limit);

    /** Return recent history no later than the supplied event timestamp. */
    default List<Transaction> findRecentByAccountIdBefore(long accountId, int limit, java.time.LocalDateTime occurredAt) {
        return findRecentByAccountId(accountId, limit);
    }

    /** Event-time history excluding the current and later ledger rows before applying the limit. */
    default List<Transaction> findRecentBeforeEvent(long accountId, int limit, java.time.LocalDateTime time, long eventId) {
        return findRecentByAccountIdBefore(accountId, limit, time).stream()
                .filter(t -> t.getTransactionId() < eventId).toList();
    }

    /** Return every transaction row — admin reporting only. */
    List<Transaction> findAll();
}
