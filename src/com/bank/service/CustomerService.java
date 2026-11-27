package com.bank.service;

import com.bank.security.AdminPrincipal;
import com.bank.security.CustomerPrincipal;
import com.bank.model.Customer;
import java.time.LocalDate;
import java.util.List;

/**
 * CustomerService — business operations for customer management.
 *
 * Folder: BankManagementSystem/src/com/bank/service/CustomerService.java
 *
 * This interface defines WHAT the business layer can do with customers.
 * The implementation adds validation rules on top of the raw DAO calls.
 */
public interface CustomerService {

    /**
     * Register a new customer after validating all business rules:
     *   - national_id must not already exist
     *   - email must not already exist
     *   - birth_date must be in the past (customer must be born)
     *   - customer must be at least 18 years old
     *   - address, postalCode, and occupation are mandatory
     *
     * @return the newly assigned customer_id
     * @throws com.bank.exception.BankException if any validation fails
     */
    long registerCustomer(String nationalId,
                          String firstName,
                          String lastName,
                          LocalDate birthDate,
                          String phone,
                          String email,
                          String address,
                          String postalCode,
                          String occupation);

    /** Return the profile belonging to the currently authenticated customer. */
    Customer findCustomerById(CustomerPrincipal principal);

    /** Administrator lookup by primary key. */
    Customer findCustomerById(AdminPrincipal admin, long customerId);

    /** Administrator lookup by national ID. */
    Customer findCustomerByNationalId(AdminPrincipal admin, String nationalId);

    /** Administrator lookup by email address. */
    Customer findCustomerByEmail(AdminPrincipal admin, String email);

    /** @deprecated Use a principal-bound overload; this legacy method fails closed in the production composition. */
    @Deprecated
    Customer findCustomerById(long customerId);

    /** @deprecated Use the administrator overload; this legacy method fails closed in the production composition. */
    @Deprecated
    Customer findCustomerByNationalId(String nationalId);

    /** @deprecated Use the administrator overload; this legacy method fails closed in the production composition. */
    @Deprecated
    Customer findCustomerByEmail(String email);

    /**
     * Search customers by partial name (first or last).
     * Returns an empty list — not an exception — when nothing matches.
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    List<Customer> searchCustomersByName(AdminPrincipal admin, String keyword);

    /**
     * Return every customer — used by the admin overview table.
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    List<Customer> getAllCustomers(AdminPrincipal admin);

    /**
     * Update a customer's contact details (phone and email).
     *
     * @throws com.bank.exception.CustomerNotFoundException if customer not found
     * @throws com.bank.exception.BankException if the new email is already used
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    void updateCustomerInfo(AdminPrincipal admin, long customerId, String phone, String email);

    // =========================================================================
    // v3.0 — FRAUD MODULE: admin ban capability
    // =========================================================================

    /**
     * Ban a customer after confirmed fraud:
     *   • customers.status → BANNED (+ reason + timestamp)
     *   • only ACTIVE accounts become LOCKED/BAN; existing LOCKED/CLOSED states are preserved
     *   • login and customer operations are rejected
     *
     * @throws com.bank.exception.BankException if the customer is unknown,
     *         already banned, or the reason is blank
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    void banCustomer(AdminPrincipal admin, long customerId, String reason);

    /**
     * Lift a ban: customer returns to ACTIVE and ban metadata is cleared.
     * Accounts locked by the ban remain LOCKED until separately reviewed by an administrator.
     *
     * @throws com.bank.exception.BankException if the customer is not banned
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    void unbanCustomer(AdminPrincipal admin, long customerId);

    /** All currently BANNED customers — the admin fraud panel's banned list. */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    List<Customer> getBannedCustomers(AdminPrincipal admin);

    /**
     * Legacy entry point retained for compatibility. Permanent deletion is disabled; use archiveCustomer.
     *
     * @throws BankException because permanent customer deletion is not permitted
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    /** Permanent deletion is disabled; use archiveCustomer to preserve financial history. */
    @Deprecated
    void deleteCustomer(AdminPrincipal admin, long customerId);

    /** Archive a customer and atomically close their ACTIVE/LOCKED accounts without changing balances. */
    void archiveCustomer(AdminPrincipal admin, long customerId);

    /** Restore the customer to their pre-archive status; archived accounts stay CLOSED pending separate approval. */
    void restoreArchivedCustomer(AdminPrincipal admin, long customerId);

    /** Restore one account closed by archive, only after explicit administrator approval. */
    void restoreArchivedAccount(AdminPrincipal admin, long customerId, long accountId);

    /** List archived customers for administrative review. */
    List<Customer> getArchivedCustomers(AdminPrincipal admin);
}