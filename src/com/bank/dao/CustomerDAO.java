package com.bank.dao;

import com.bank.model.Customer;
import java.util.List;
import java.util.Optional;

/**
 * CustomerDAO — contract for all customer database operations.
 *
 * Folder: BankManagementSystem/src/com/bank/dao/CustomerDAO.java
 *
 * The interface defines WHAT operations exist.
 * CustomerDAOImpl defines HOW they are executed with SQL.
 */
public interface CustomerDAO {
    /**
     * Insert a new customer row.
     * @return the auto-generated customer_id assigned by MySQL, or -1 on failure.
     */
    long save(Customer customer);

    /**
     * Find one customer by their primary key.
     * @return Optional.empty() if no row exists with that ID.
     */
    Optional<Customer> findById(long customerId);

    /**
     * Find one customer by their unique national ID.
     * Used during account opening to avoid duplicate registrations.
     */
    Optional<Customer> findByNationalId(String nationalId);

    /**
     * Find one customer by email address.
     * Useful for login / password-reset flows.
     */
    Optional<Customer> findByEmail(String email);

    /**
     * Return every customer row — used to populate admin tables.
     */
    List<Customer> findAll();

    /**
     * Search customers whose first OR last name contains the given keyword.
     * Case-insensitive. Used for the customer search panel.
     */
    List<Customer> searchByName(String keyword);

    /**
     * Update mutable fields of an existing customer (phone, email).
     * national_id, birth_date, and created_at are intentionally excluded.
     * @return true if exactly one row was updated.
     */
    boolean update(Customer customer);

    /**
     * Hard-delete a customer row.
     * The DB enforces ON DELETE RESTRICT, so this will fail if the customer
     * still has accounts — the Service layer must check first.
     * @return true if exactly one row was deleted.
     */
    boolean delete(long customerId);

    // =========================================================================
    // v3.0 — FRAUD MODULE: admin ban capability
    // =========================================================================

    /**
     * Flip a customer between ACTIVE and BANNED.
     * banning  → banned_reason is stored and banned_at set to now.
     * unbanning → both fields are cleared back to NULL.
     * @return true if exactly one row was updated.
     */
    boolean updateBanStatus(long customerId,
                            Customer.CustomerStatus newStatus,
                            String reason);

    /**
     * Atomically ban a customer: in ONE database transaction, mark the customer BANNED
     * (reason + timestamp) and LOCK every account they own. Either both effects commit
     * or neither does, so a banned customer can never be left with an active account.
     * Row locks are taken customer-first then accounts by id, the same order as transfers.
     *
     * @return the number of accounts locked
     * @throws com.bank.exception.CustomerNotFoundException if the customer does not exist
     * @throws com.bank.exception.BankException if already banned or the reason is blank
     */
    int banCustomerAndLockAccounts(long customerId, String reason);

    /** Actor-aware overload used by administrator operations for audit attribution. */
    default int banCustomerAndLockAccounts(long customerId, String reason, String actor) {
        return banCustomerAndLockAccounts(customerId, reason);
    }

    /**
     * Atomically lift a ban: customer back to ACTIVE (reason/timestamp cleared). Accounts
     * locked by the ban remain locked and are reclassified for administrator review;
     * independently imposed locks are never silently removed.
     *
     * @return the number of accounts automatically unlocked (zero when locks remain for review)
     * @throws com.bank.exception.CustomerNotFoundException if the customer does not exist
     * @throws com.bank.exception.BankException if the customer is not banned
     */
    int unbanCustomerAndUnlockAccounts(long customerId);

    /** Actor-aware overload used by administrator operations for audit attribution. */
    default int unbanCustomerAndUnlockAccounts(long customerId, String actor) {
        return unbanCustomerAndUnlockAccounts(customerId);
    }

    /**
     * Return every BANNED customer, most recently banned first —
     * used by the Fraud Management panel's "Banned Customers" list.
     */
    List<Customer> findAllBanned();

    /** Atomically archives a customer and closes only ACTIVE/LOCKED accounts, preserving prior state. */
    default int archiveCustomerAndCloseAccounts(long customerId, String actor) {
        throw new UnsupportedOperationException("Customer archive is not implemented by this DAO.");
    }

    /** Restores the customer to the status it had immediately before archive; accounts remain CLOSED. */
    default void restoreArchivedCustomer(long customerId, String actor) {
        throw new UnsupportedOperationException("Customer restore is not implemented by this DAO.");
    }

    /** Restores one account closed by archive to its exact pre-archive status, after admin approval. */
    default boolean restoreArchivedAccount(long customerId, long accountId, String actor) {
        throw new UnsupportedOperationException("Archived account restore is not implemented by this DAO.");
    }

    /** Return archived customers for the administrator's lifecycle view. */
    default List<Customer> findAllArchived() {
        throw new UnsupportedOperationException("Archived customer lookup is not implemented by this DAO.");
    }
}
