package com.bank.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction — mirrors the 'transactions' table.
 *
 * Folder in NetBeans:
 *   BankManagementSystem/src/com/bank/model/Transaction.java
 *
 * IMPORTANT — this is a ledger / audit record.
 * Once written to the database it is NEVER updated or deleted.
 * That is why there is no full setter for transactionId or createdAt
 * (the DB generates both).
 */
public class Transaction {

    // -------------------------------------------------------------------------
    // ENUM  (mirrors MySQL ENUM column exactly)
    // -------------------------------------------------------------------------

    public enum TransactionType {
        DEPOSIT,
        WITHDRAW,
        TRANSFER_IN,
        TRANSFER_OUT;

        public static TransactionType from(String value) {
            return TransactionType.valueOf(value.toUpperCase());
        }
    }

    // -------------------------------------------------------------------------
    // FIELDS
    // -------------------------------------------------------------------------

    private long            transactionId;

    /** FK to accounts.account_id */
    private long            accountId;

    private TransactionType transactionType;

    /** Always positive — the direction is conveyed by transactionType. */
    private BigDecimal      amount;

    /**
     * The account balance immediately after this entry was posted.
     * Stored for O(1) statement generation without re-summing the ledger.
     */
    private BigDecimal      balanceAfterTransaction;

    private LocalDateTime   createdAt;

    // -------------------------------------------------------------------------
    // CONSTRUCTORS
    // -------------------------------------------------------------------------

    /** No-arg constructor — required by DAO result mappers. */
    public Transaction() {}

    /**
     * Full constructor — use when loading an existing ledger row from the DB.
     */
    public Transaction(long transactionId,
                       long accountId,
                       TransactionType transactionType,
                       BigDecimal amount,
                       BigDecimal balanceAfterTransaction,
                       LocalDateTime createdAt) {
        this.transactionId           = transactionId;
        this.accountId               = accountId;
        this.transactionType         = transactionType;
        this.amount                  = amount;
        this.balanceAfterTransaction = balanceAfterTransaction;
        this.createdAt               = createdAt;
    }

    /**
     * Insert constructor — use when recording a NEW transaction.
     * transaction_id and created_at are omitted (MySQL generates them).
     */
    public Transaction(long accountId,
                       TransactionType transactionType,
                       BigDecimal amount,
                       BigDecimal balanceAfterTransaction) {
        this.accountId               = accountId;
        this.transactionType         = transactionType;
        this.amount                  = amount;
        this.balanceAfterTransaction = balanceAfterTransaction;
    }

    // -------------------------------------------------------------------------
    // GETTERS & SETTERS
    // -------------------------------------------------------------------------

    public long getTransactionId()                  { return transactionId; }
    public void setTransactionId(long id)           { this.transactionId = id; }

    public long getAccountId()                      { return accountId; }
    public void setAccountId(long accountId)        { this.accountId = accountId; }

    public TransactionType getTransactionType()                    { return transactionType; }
    public void setTransactionType(TransactionType transactionType){ this.transactionType = transactionType; }

    public BigDecimal getAmount()                   { return amount; }
    public void setAmount(BigDecimal amount)        { this.amount = amount; }

    public BigDecimal getBalanceAfterTransaction()              { return balanceAfterTransaction; }
    public void setBalanceAfterTransaction(BigDecimal balance)  { this.balanceAfterTransaction = balance; }

    public LocalDateTime getCreatedAt()                    { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)      { this.createdAt = createdAt; }

    // -------------------------------------------------------------------------
    // UTILITY
    // -------------------------------------------------------------------------

    /**
     * Returns true if this entry increased the account balance.
     * Useful for color-coding rows in the Swing transaction table
     * (green for credit, red for debit).
     */
    public boolean isCredit() {
        return transactionType == TransactionType.DEPOSIT
            || transactionType == TransactionType.TRANSFER_IN;
    }

    public boolean isDebit() {
        return !isCredit();
    }

    @Override
    public String toString() {
        return "Transaction{"
                + "transactionId=" + transactionId
                + ", accountId=" + accountId
                + ", type=" + transactionType
                + ", amount=" + amount
                + ", balanceAfter=" + balanceAfterTransaction
                + ", createdAt=" + createdAt
                + '}';
    }
}