package com.bank.service;

import com.bank.security.AdminPrincipal;
import com.bank.security.CustomerPrincipal;
import com.bank.model.Account;
import com.bank.model.Account.AccountStatus;
import com.bank.model.Transaction;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * AccountService -- business operations for account management.
 *
 * Folder: BankManagementSystem/src/com/bank/service/AccountService.java
 */
public interface AccountService {

    /**
     * Open a new bank account for an existing customer.
     * Generates a unique 12-digit card number and account number automatically.
     * Hashes the plain-text PIN before persisting.
     *
     * @return the fully populated Account object (includes card number and account number)
     * @throws com.bank.exception.CustomerNotFoundException if customerId not found
     * @throws com.bank.exception.BankException             if initialDeposit is negative
     */
    /** Customer-authenticated account opening for an existing customer. */
    Account openAccount(CustomerPrincipal principal,
                        Account.AccountType accountType,
                        String plainTextPin,
                        BigDecimal initialDeposit);

    /** @deprecated Internal compatibility entry point; customer UI must use the principal overload. */
    @Deprecated
    Account openAccount(long customerId,
                        Account.AccountType accountType,
                        String plainTextPin,
                        BigDecimal initialDeposit);

    /**
     * Normal login: card number + PIN.
     * Tracks wrong attempts; auto-locks the account after 3 failures.
     *
     * @return the authenticated Account
     * @throws com.bank.exception.AccountNotFoundException        if card not found
     * @throws com.bank.exception.InvalidPinWithAttemptsException if PIN is wrong (attempts 1-2)
     * @throws com.bank.exception.AccountAutoLockedException      if this was the 3rd wrong attempt
     * @throws com.bank.exception.AccountLockedException          if already locked
     */
    Account login(String cardNumber, String plainTextPin);

    /**
     * Recover a forgotten PIN after the account was automatically locked by three
     * consecutive failed PIN attempts. All three registered identity values must match.
     * Recovery is throttled after repeated failed identity checks.
     */
    void recoverPin(String cardNumber, String nationalId, String registeredPhone, String newPin);

    /**
     * Return the current balance of an account.
     *
     * @throws com.bank.exception.AccountNotFoundException if account not found
     */
    BigDecimal getAccountBalance(CustomerPrincipal principal, long accountId);

    /** @deprecated Internal compatibility entry point; customer UI must use the principal overload. */
    @Deprecated
    BigDecimal getAccountBalance(long accountId);

    /**
     * Return all accounts owned by one customer.
     *
     * @throws com.bank.exception.CustomerNotFoundException if customer not found
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    List<Account> getAccountsByCustomer(AdminPrincipal admin, long customerId);

    /** Every account in the system (admin tables and reports). */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    List<Account> getAllAccounts(AdminPrincipal admin);

    /**
     * Deposit money and record a DEPOSIT ledger entry exactly once per idempotency key.
     * Reuse the same key for all retries of the same logical request.
     *
     * @throws com.bank.exception.AccountNotFoundException if account not found
     * @throws com.bank.exception.AccountLockedException   if account is locked
     * @throws com.bank.exception.BankException            if amount <= 0
     */
    void deposit(CustomerPrincipal principal, long accountId, BigDecimal amount, String idempotencyKey);

    /** @deprecated Internal compatibility entry point; customer UI must use the principal overload. */
    @Deprecated
    void deposit(long accountId, BigDecimal amount, String idempotencyKey);

    /** Convenience for one-shot callers; retryable clients should reuse an explicit key. */
    @Deprecated
    default void deposit(long accountId, BigDecimal amount) {
        deposit(accountId, amount, UUID.randomUUID().toString());
    }

    /**
     * Withdraw money and record a WITHDRAW ledger entry exactly once per idempotency key.
     * Reuse the same key for all retries of the same logical request.
     *
     * @throws com.bank.exception.AccountNotFoundException    if account not found
     * @throws com.bank.exception.AccountLockedException      if account is locked
     * @throws com.bank.exception.InsufficientFundsException  if balance < amount
     * @throws com.bank.exception.BankException               if amount <= 0
     */
    void withdraw(CustomerPrincipal principal, long accountId, BigDecimal amount, String idempotencyKey);

    /** @deprecated Internal compatibility entry point; customer UI must use the principal overload. */
    @Deprecated
    void withdraw(long accountId, BigDecimal amount, String idempotencyKey);

    /** Convenience for one-shot callers; retryable clients should reuse an explicit key. */
    @Deprecated
    default void withdraw(long accountId, BigDecimal amount) {
        withdraw(accountId, amount, UUID.randomUUID().toString());
    }

    /**
     * Change the PIN after verifying the current PIN.
     *
     * @throws com.bank.exception.AccountNotFoundException if account not found
     * @throws com.bank.exception.InvalidPinException      if currentPin is wrong
     */
    void changePin(CustomerPrincipal principal, long accountId, String currentPin, String newPin);

    /** @deprecated Internal compatibility entry point; customer UI must use the principal overload. */
    @Deprecated
    void changePin(long accountId, String currentPin, String newPin);

    /**
     * Lock, unlock, or close an account (admin operation). Normal closure requires zero balance;
     * CLOSED accounts cannot be reopened through this generic operation.
     *
     * @throws com.bank.exception.AccountNotFoundException if account not found
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    void setAccountStatus(AdminPrincipal admin, long accountId, AccountStatus newStatus);

    /**
     * Return full transaction history for an account (newest first).
     *
     * @throws com.bank.exception.AccountNotFoundException if account not found
     */
    List<Transaction> getTransactionHistory(CustomerPrincipal principal, long accountId);

    /** Administrator view of an account history. */
    List<Transaction> getTransactionHistory(AdminPrincipal admin, long accountId);

    /** @deprecated Internal compatibility entry point; use a principal overload. */
    @Deprecated
    List<Transaction> getTransactionHistory(long accountId);

    /**
     * Return the N most recent transactions (mini-statement).
     *
     * @throws com.bank.exception.AccountNotFoundException if account not found
     */
    List<Transaction> getMiniStatement(CustomerPrincipal principal, long accountId, int limit);

    /** @deprecated Internal compatibility entry point; use a principal overload. */
    @Deprecated
    List<Transaction> getMiniStatement(long accountId, int limit);
}