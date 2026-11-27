package com.bank.service.impl;

import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerAccessGuard;
import com.bank.exception.AccessDeniedException;
import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.exception.BankException;
import com.bank.exception.CustomerNotFoundException;
import com.bank.model.Customer;
import com.bank.service.CustomerService;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/**
 * CustomerServiceImpl — business logic for customer management.
 *
 * Folder: BankManagementSystem/src/com/bank/service/impl/CustomerServiceImpl.java
 *
 * DEPENDENCY INJECTION:
 * The DAO is passed in through the constructor, not created here.
 * This means if you ever need to switch to a different database or a
 * mock for testing, you just pass a different object — no code change needed.
 *
 *   CustomerDAO dao  = new CustomerDAOImpl();
 *   AccountDAO  acct = new AccountDAOImpl();
 *   CustomerService svc = new CustomerServiceImpl(dao, acct);   // v3.0: + accountDAO
 */
public class CustomerServiceImpl implements CustomerService {

    // The DAO interface — NOT the concrete impl (loose coupling)
    private final CustomerDAO customerDAO;

    // v3.0 — needed so banning can LOCK (and unbanning can unlock) every
    // account the customer owns. Injected the same way as customerDAO.
    private final AccountDAO accountDAO;
    private final CustomerAccessGuard customerAccessGuard;

    /** Compatibility constructor for isolated tests; production injects the customer guard. */
    public CustomerServiceImpl(CustomerDAO customerDAO, AccountDAO accountDAO) {
        this(customerDAO, accountDAO, null);
    }

    public CustomerServiceImpl(CustomerDAO customerDAO, AccountDAO accountDAO,
                               CustomerAccessGuard customerAccessGuard) {
        this.customerDAO = customerDAO;
        this.accountDAO  = accountDAO;
        this.customerAccessGuard = customerAccessGuard;
    }

    // =========================================================================
    // REGISTER CUSTOMER
    // =========================================================================
    @Override
    public long registerCustomer(String nationalId,
                                 String firstName,
                                 String lastName,
                                 LocalDate birthDate,
                                 String phone,
                                 String email,
                                 String address,
                                 String postalCode,
                                 String occupation) {

        // --- Rule 1: Required fields must not be blank -------------------------
        if (isBlank(firstName))  throw new BankException("First name is required.");
        if (isBlank(lastName))   throw new BankException("Last name is required.");
        if (isBlank(nationalId)) throw new BankException("National ID is required.");
        if (isBlank(phone))      throw new BankException("Phone number is required.");
        if (isBlank(email))      throw new BankException("Email address is required.");
        if (isBlank(address))    throw new BankException("Address is required.");
        if (isBlank(postalCode)) throw new BankException("Postal code is required.");
        if (isBlank(occupation)) throw new BankException("Occupation is required.");
        if (birthDate == null)   throw new BankException("Birth date is required.");

        // --- Rule 2: Birth date must be in the past ---------------------------
        if (!birthDate.isBefore(LocalDate.now())) {
            throw new BankException("Birth date must be in the past.");
        }

        // --- Rule 3: Customer must be at least 18 years old -------------------
        int age = Period.between(birthDate, LocalDate.now()).getYears();
        if (age < 18) {
            throw new BankException("Customer must be at least 18 years old. Age: " + age);
        }

        // --- Rule 4: National ID must be unique --------------------------------
        if (customerDAO.findByNationalId(nationalId).isPresent()) {
            throw new BankException("A customer with national ID '" + nationalId + "' already exists.");
        }

        // --- Rule 5: Email must be unique -------------------------------------
        if (customerDAO.findByEmail(email).isPresent()) {
            throw new BankException("Email address '" + email + "' is already registered.");
        }

        // --- All checks passed — persist to DB --------------------------------
        Customer customer = new Customer(
                nationalId, firstName.trim(), lastName.trim(), birthDate,
                phone.trim(), email.trim().toLowerCase(),
                address.trim(), postalCode.trim(), occupation.trim());

        long newId = customerDAO.save(customer);
        if (newId == -1) {
            throw new BankException("Failed to register customer. Please try again.");
        }

        System.out.println("[CustomerService] Registered new customer. ID=" + newId
                + " Name=" + firstName + " " + lastName);
        return newId;
    }

    // =========================================================================
    // FIND BY ID
    // =========================================================================
    @Override
    public Customer findCustomerById(CustomerPrincipal principal) {
        if (customerAccessGuard == null) {
            throw new AccessDeniedException("Customer authentication guard is not configured.");
        }
        CustomerPrincipal current = customerAccessGuard.requireActivePrincipal(principal);
        return findCustomerByIdInternal(current.customerId());
    }

    @Override
    public Customer findCustomerById(AdminPrincipal admin, long customerId) {
        AdminGuard.require(admin);
        return findCustomerByIdInternal(customerId);
    }

    /** @deprecated Use a principal-bound overload. */
    @Override
    @Deprecated
    public Customer findCustomerById(long customerId) {
        rejectLegacyCustomerLookup();
        return findCustomerByIdInternal(customerId);
    }

    // =========================================================================
    // ADMINISTRATOR LOOKUPS BY IDENTITY FIELDS
    // =========================================================================
    @Override
    public Customer findCustomerByNationalId(AdminPrincipal admin, String nationalId) {
        AdminGuard.require(admin);
        if (isBlank(nationalId)) throw new BankException("National ID cannot be blank.");
        return customerDAO.findByNationalId(nationalId.trim())
                .orElseThrow(() -> new CustomerNotFoundException("National ID=" + nationalId));
    }

    @Override
    @Deprecated
    public Customer findCustomerByNationalId(String nationalId) {
        rejectLegacyCustomerLookup();
        if (isBlank(nationalId)) throw new BankException("National ID cannot be blank.");
        return customerDAO.findByNationalId(nationalId.trim())
                .orElseThrow(() -> new CustomerNotFoundException("National ID=" + nationalId));
    }

    @Override
    public Customer findCustomerByEmail(AdminPrincipal admin, String email) {
        AdminGuard.require(admin);
        if (isBlank(email)) throw new BankException("Email cannot be blank.");
        return customerDAO.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new CustomerNotFoundException("Email=" + email));
    }

    @Override
    @Deprecated
    public Customer findCustomerByEmail(String email) {
        rejectLegacyCustomerLookup();
        if (isBlank(email)) throw new BankException("Email cannot be blank.");
        return customerDAO.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new CustomerNotFoundException("Email=" + email));
    }

    private Customer findCustomerByIdInternal(long customerId) {
        return customerDAO.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("ID=" + customerId));
    }

    private void rejectLegacyCustomerLookup() {
        if (customerAccessGuard != null) {
            throw new AccessDeniedException("A CustomerPrincipal or AdminPrincipal is required for customer lookup.");
        }
    }

    // =========================================================================
    // SEARCH BY NAME
    // =========================================================================
    @Override
    public List<Customer> searchCustomersByName(AdminPrincipal admin, String keyword) {
        AdminGuard.require(admin);
        if (isBlank(keyword)) throw new BankException("Search keyword cannot be empty.");
        return customerDAO.searchByName(keyword.trim());
    }

    // =========================================================================
    // GET ALL
    // =========================================================================
    @Override
    public List<Customer> getAllCustomers(AdminPrincipal admin) {
        AdminGuard.require(admin);
        return customerDAO.findAll();
    }

    // =========================================================================
    // UPDATE CONTACT INFO
    // =========================================================================
    @Override
    public void updateCustomerInfo(AdminPrincipal admin, long customerId, String phone, String email) {
        AdminGuard.require(admin);

        // Confirm the customer exists first
        Customer existing = findCustomerByIdInternal(customerId); // throws if not found

        if (isBlank(phone)) throw new BankException("Phone number cannot be blank.");
        if (isBlank(email)) throw new BankException("Email address cannot be blank.");

        // Check the new email isn't already used by a DIFFERENT customer
        customerDAO.findByEmail(email.trim().toLowerCase()).ifPresent(other -> {
            if (other.getCustomerId() != customerId) {
                throw new BankException("Email '" + email + "' is already used by another customer.");
            }
        });

        existing.setPhone(phone.trim());
        existing.setEmail(email.trim().toLowerCase());

        boolean updated = customerDAO.update(existing);
        if (!updated) {
            throw new BankException("Failed to update customer info. Please try again.");
        }
    }

    // =========================================================================
    // DELETE CUSTOMER
    // =========================================================================
    @Override
    @Deprecated
    public void deleteCustomer(AdminPrincipal admin, long customerId) {
        AdminGuard.require(admin);
        throw new BankException("Permanent customer deletion is disabled. Use Archive Customer to preserve financial history.");
    }

    @Override
    public void archiveCustomer(AdminPrincipal admin, long customerId) {
        AdminGuard.require(admin);
        Customer customer = findCustomerByIdInternal(customerId);
        if (customer.isArchived()) throw new BankException("Customer is already archived.");
        int closed = customerDAO.archiveCustomerAndCloseAccounts(customerId, admin.username());
        System.out.println("[CustomerService] ARCHIVED customer ID=" + customerId
                + "; " + closed + " active/locked account(s) closed. Balances and ledger retained.");
    }

    @Override
    public void restoreArchivedCustomer(AdminPrincipal admin, long customerId) {
        AdminGuard.require(admin);
        Customer customer = findCustomerByIdInternal(customerId);
        if (!customer.isArchived()) throw new BankException("Customer is not archived.");
        customerDAO.restoreArchivedCustomer(customerId, admin.username());
        Customer restored = findCustomerByIdInternal(customerId);
        System.out.println("[CustomerService] RESTORED customer ID=" + customerId
                + " to " + restored.getStatus() + "; accounts remain closed pending separate approval.");
    }

    @Override
    public void restoreArchivedAccount(AdminPrincipal admin, long customerId, long accountId) {
        AdminGuard.require(admin);
        Customer customer = findCustomerByIdInternal(customerId);
        if (customer.isArchived() || customer.isBanned()) {
            throw new BankException("Customer must be ACTIVE before an account can be restored.");
        }
        com.bank.model.Account account = accountDAO.findById(accountId)
                .orElseThrow(() -> new BankException("Account does not exist."));
        if (account.getCustomerId() != customerId) {
            throw new BankException("Selected account does not belong to this customer.");
        }
        if (!customerDAO.restoreArchivedAccount(customerId, accountId, admin.username())) {
            throw new BankException("Archived account could not be restored.");
        }
    }

    @Override
    public List<Customer> getArchivedCustomers(AdminPrincipal admin) {
        AdminGuard.require(admin);
        return customerDAO.findAllArchived();
    }

    // =========================================================================
    // BAN CUSTOMER                                (v3.0 — FRAUD MODULE)
    // Called from the Fraud Management panel after a fraud alert is confirmed,
    // or directly from the Customer Management panel.
    // =========================================================================
    @Override
    public void banCustomer(AdminPrincipal admin, long customerId, String reason) {
        AdminGuard.require(admin);

        Customer customer = findCustomerByIdInternal(customerId);   // throws if unknown

        if (customer.isBanned()) {
            throw new BankException("Customer " + customer.getFullName()
                    + " is already banned.");
        }
        if (isBlank(reason)) {
            throw new BankException("A ban reason is required.");
        }

        // Customer row + every account row change in ONE transaction (DAO),
        // so a banned customer can never be left with an active account.
        int lockedAccounts = customerDAO.banCustomerAndLockAccounts(customerId, reason.trim(), admin.username());

        System.out.println("[CustomerService] BANNED customer ID=" + customerId
                + " (" + customer.getFullName() + ") — " + lockedAccounts
                + " account(s) locked. Reason: " + reason.trim());
    }

    // =========================================================================
    // UNBAN CUSTOMER                              (v3.0 — FRAUD MODULE)
    // =========================================================================
    @Override
    public void unbanCustomer(AdminPrincipal admin, long customerId) {
        AdminGuard.require(admin);

        Customer customer = findCustomerByIdInternal(customerId);   // throws if unknown

        if (!customer.isBanned()) {
            throw new BankException("Customer " + customer.getFullName()
                    + " is not banned.");
        }

        // Clear the ban without bypassing independently imposed account locks.
        int unlockedAccounts = customerDAO.unbanCustomerAndUnlockAccounts(customerId, admin.username());

        System.out.println("[CustomerService] UNBANNED customer ID=" + customerId
                + " (" + customer.getFullName() + ") — " + unlockedAccounts
                + " account(s) automatically unlocked; remaining locks require administrator review.");
    }

    // =========================================================================
    // GET BANNED CUSTOMERS                        (v3.0 — FRAUD MODULE)
    // =========================================================================
    @Override
    public List<Customer> getBannedCustomers(AdminPrincipal admin) {
        AdminGuard.require(admin);
        return customerDAO.findAllBanned();
    }

    // =========================================================================
    // PRIVATE UTILITIES
    // =========================================================================
    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}