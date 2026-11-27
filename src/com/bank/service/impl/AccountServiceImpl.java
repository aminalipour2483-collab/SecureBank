package com.bank.service.impl;

import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerAccessGuard;
import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.dao.TransactionDAO;
import com.bank.dao.PinRecoveryDAO;
import com.bank.dao.impl.PinRecoveryDAOImpl;
import com.bank.exception.*;
import com.bank.model.Account;
import com.bank.model.BalanceChangeResult;
import com.bank.util.BCryptUtil;
import com.bank.util.IdempotencyUtil;
import com.bank.model.Account.AccountStatus;
import com.bank.model.Account.AccountType;
import com.bank.model.Customer;
import com.bank.model.Transaction;
import com.bank.model.Transaction.TransactionType;
import com.bank.service.AccountService;
import com.bank.service.FraudDetectionService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;

/**
 * AccountServiceImpl — business logic for account operations.
 *
 * v3.0 — every deposit / withdrawal is scored by the Fraud Detection
 * Engine right after the ledger row is written, and every login path
 * rejects BANNED customers.
 */
public class AccountServiceImpl implements AccountService {

    private final AccountDAO           accountDAO;
    private final CustomerDAO          customerDAO;
    private final TransactionDAO       transactionDAO;
    private final PinRecoveryDAO       pinRecoveryDAO;

    private final CustomerAccessGuard customerAccessGuard;

    private static final SecureRandom ACCOUNT_RANDOM = new SecureRandom();
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999999.99");

    public AccountServiceImpl(AccountDAO accountDAO,
                              CustomerDAO customerDAO,
                              TransactionDAO transactionDAO,
                              FraudDetectionService fraudDetectionService) {
        this(accountDAO, customerDAO, transactionDAO, fraudDetectionService, new PinRecoveryDAOImpl(), null);
    }

    public AccountServiceImpl(AccountDAO accountDAO, CustomerDAO customerDAO,
                              TransactionDAO transactionDAO, FraudDetectionService fraudDetectionService,
                              PinRecoveryDAO pinRecoveryDAO) {
        this(accountDAO, customerDAO, transactionDAO, fraudDetectionService, pinRecoveryDAO, null);
    }

    public AccountServiceImpl(AccountDAO accountDAO, CustomerDAO customerDAO,
                              TransactionDAO transactionDAO, FraudDetectionService fraudDetectionService,
                              PinRecoveryDAO pinRecoveryDAO, CustomerAccessGuard customerAccessGuard) {
        this.accountDAO = accountDAO;
        this.customerDAO = customerDAO;
        this.transactionDAO = transactionDAO;
        // Kept in the constructor for source compatibility. P6 moved fraud scoring
        // to a durable outbox worker; this service no longer executes it inline.
        this.pinRecoveryDAO = pinRecoveryDAO;
        this.customerAccessGuard = customerAccessGuard;
    }

    private void requireCustomerGuard() {
        if (customerAccessGuard == null) {
            throw new AccessDeniedException("Customer authentication guard is not configured.");
        }
    }

    private void rejectLegacyCustomerEntryPoint() {
        if (customerAccessGuard != null) {
            throw new AccessDeniedException("An authenticated CustomerPrincipal is required for this operation.");
        }
    }

    // =========================================================================
    // OPEN ACCOUNT
    // =========================================================================
    @Override
    public Account openAccount(long customerId,
                               AccountType accountType,
                               String plainTextPin,
                               BigDecimal initialDeposit) {
        rejectLegacyCustomerEntryPoint();
        return openAccountForCustomer(customerId, accountType, plainTextPin, initialDeposit);
    }

    @Override
    public Account openAccount(CustomerPrincipal principal, AccountType accountType,
                               String plainTextPin, BigDecimal initialDeposit) {
        requireCustomerGuard();
        CustomerPrincipal current = customerAccessGuard.requireActivePrincipal(principal);
        return openAccountForCustomer(current.customerId(), accountType, plainTextPin, initialDeposit);
    }

    private Account openAccountForCustomer(long customerId, AccountType accountType,
                                           String plainTextPin, BigDecimal initialDeposit) {
        if (accountType == null) throw new BankException("Account type is required.");

        // --- Rule 1: Customer must exist --------------------------------------
        Customer owner = customerDAO.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("ID=" + customerId));

        // --- v3.0: banned customers cannot open new accounts ------------------
        if (owner.isBanned()) {
            throw new CustomerBannedException(owner.getFullName(), owner.getBannedReason());
        }
        if (owner.isArchived()) {
            throw new CustomerArchivedException(owner.getFullName());
        }

        // --- NEW RULE: Customer can have at most 4 accounts --------------------
        List<Account> existingAccounts = accountDAO.findByCustomerId(customerId);
        if (existingAccounts.size() >= AccountDAO.MAX_ACCOUNTS_PER_CUSTOMER) {
            throw new BankException(
                    "You cannot have more than " + AccountDAO.MAX_ACCOUNTS_PER_CUSTOMER
                    + " accounts in total, including locked and closed accounts.");
        }

        // --- Rule 2: Initial deposit must be a valid monetary amount ---------
        if (initialDeposit == null || initialDeposit.compareTo(BigDecimal.ZERO) < 0) {
            throw new BankException("Initial deposit cannot be negative or null.");
        }
        if (initialDeposit.compareTo(MAX_BALANCE) > 0) {
            throw new BankException("Opening balance exceeds the database's supported range.");
        }
        if (initialDeposit.stripTrailingZeros().scale() > 2) {
            throw new BankException("Initial deposit cannot have more than two decimal places.");
        }

        // --- Rule 3: PIN must be 4–6 digits ----------------------------------
        if (plainTextPin == null || !plainTextPin.matches("\\d{4,6}")) {
            throw new BankException("PIN must be 4 to 6 digits.");
        }

        // --- Generate a UNIQUE 12-digit account number -----------------------
        String accountNumber;
        int attempts = 0;
        do {
            accountNumber = generateAccountNumber();
            if (++attempts > 10)
                throw new BankException("Could not generate a unique account number. Try again.");
        } while (accountDAO.findByAccountNumber(accountNumber).isPresent());

        // --- Generate a UNIQUE 12-digit card number --------------------------
        String cardNumber;
        attempts = 0;
        do {
            cardNumber = generateCardNumber();
            if (++attempts > 10)
                throw new BankException("Could not generate a unique card number. Try again.");
        } while (accountDAO.findByCardNumber(cardNumber).isPresent());

        // Store only a one-way password hash. BCrypt includes a random salt.
        String pinHash = BCryptUtil.hash(plainTextPin);

        Account account = new Account(customerId, accountNumber, cardNumber,
                                      pinHash, accountType, initialDeposit);

        // Account creation and the opening-deposit ledger row are one ACID
        // operation. A ledger failure now rolls back the account insert too.
        long newAccountId = accountDAO.saveWithOpeningDeposit(account);
        if (newAccountId == -1) {
            throw new BankException("Failed to create account. Please try again.");
        }

        account.setAccountId(newAccountId);
        return account;
    }

    // =========================================================================
    // CUSTOMER PIN RECOVERY — national ID + card number + registered phone
    // =========================================================================
    @Override
    public void recoverPin(String cardNumber, String nationalId, String registeredPhone, String newPin) {
        if (cardNumber == null || !cardNumber.trim().matches("\\d{12}")) {
            throw new BankException("Enter the 12-digit card number.");
        }
        if (nationalId == null || nationalId.isBlank() || registeredPhone == null || registeredPhone.isBlank()) {
            throw new BankException("All identity fields are required.");
        }
        if (newPin == null || !newPin.matches("\\d{4,6}")) {
            throw new BankException("New PIN must contain 4 to 6 digits.");
        }

        String normalizedCard = cardNumber.trim();
        String throttleKey = sha256(normalizedCard);
        if (pinRecoveryDAO.isTemporarilyLocked(throttleKey)) {
            throw new BankException("Too many recovery attempts. Please wait 15 minutes before trying again.");
        }

        Account account = accountDAO.findByCardNumber(normalizedCard).orElse(null);
        Customer owner = account == null ? null : customerDAO.findById(account.getCustomerId()).orElse(null);
        boolean eligible = account != null && owner != null && !owner.isBanned() && !owner.isArchived()
                && account.isLocked()
                && account.getLockReason() == Account.LockReason.FAILED_PIN
                && account.getFailedPinAttempts() >= MAX_FAILED_ATTEMPTS;
        boolean identityMatches = eligible
                && constantTimeEquals(normalizeNationalId(owner.getNationalId()), normalizeNationalId(nationalId))
                && constantTimeEquals(normalizePhone(owner.getPhone()), normalizePhone(registeredPhone));

        if (!identityMatches) {
            int failures = pinRecoveryDAO.recordFailure(throttleKey);
            if (failures < 0 || failures >= 5) {
                throw new BankException("We could not verify these details. Recovery is temporarily locked for 15 minutes.");
            }
            throw new BankException("We could not verify these details, or this account is not eligible for self-service recovery.");
        }

        String newHash = BCryptUtil.hash(newPin);
        if (!accountDAO.resetPinAndUnlockAfterRecovery(account.getAccountId(), newHash)) {
            throw new BankException("PIN recovery could not be completed because the account state changed. Please try again.");
        }
        try {
            pinRecoveryDAO.clear(throttleKey);
        } catch (RuntimeException throttleCleanupFailure) {
            // The PIN reset and account unlock have already committed. A stale, non-locked
            // throttle row is harmless; retain success and record the cleanup issue.
            System.err.println("[AccountService] Could not clear recovery throttle after successful reset: "
                    + throttleCleanupFailure.getMessage());
        }
        System.out.println("[AccountService] PIN recovered for account ID=" + account.getAccountId());
    }

    private String normalizeNationalId(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
    }

    private String normalizePhone(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    // =========================================================================
    // LOGIN (card + PIN) – normal login with attempt tracking
    // =========================================================================
    private static final int MAX_FAILED_ATTEMPTS = 3;

    @Override
    public Account login(String cardNumber, String plainTextPin) {

        Account account = accountDAO.findByCardNumber(cardNumber)
                .orElseThrow(() -> new AccountNotFoundException(cardNumber));

        // v3.0 — reject banned customers FIRST, with the recorded reason,
        // so the login screen can tell them exactly why access is denied.
        Customer owner = customerDAO.findById(account.getCustomerId())
                .orElseThrow(() -> new CustomerNotFoundException("ID=" + account.getCustomerId()));
        if (owner.isBanned()) {
            throw new CustomerBannedException(owner.getFullName(), owner.getBannedReason());
        }
        if (owner.isArchived()) {
            throw new CustomerArchivedException(owner.getFullName());
        }

        if (account.isClosed()) {
            throw new BankException("This account is closed and cannot be used to sign in.");
        }
        // Check lock reason
        if (account.isLocked()) {
            if (account.getLockReason() == Account.LockReason.FAILED_PIN) {
                throw new AccountAutoLockedException(account.getAccountNumber(), MAX_FAILED_ATTEMPTS);
            }
            throw new AdminLockedException(account.getAccountNumber());
        }

        boolean pinCorrect = verifyPin(account, plainTextPin);

        if (!pinCorrect) {
            int newCount = accountDAO.incrementFailedAttempts(account.getAccountId());
            int remaining = MAX_FAILED_ATTEMPTS - newCount;

            if (newCount >= MAX_FAILED_ATTEMPTS) {
                accountDAO.lockAfterFailedPinAttempts(account.getAccountId());
                throw new AccountAutoLockedException(account.getAccountNumber(), MAX_FAILED_ATTEMPTS);
            }

            throw new InvalidPinWithAttemptsException(newCount, MAX_FAILED_ATTEMPTS, remaining);
        }

        // PIN correct: reset attempts and return
        accountDAO.resetFailedAttempts(account.getAccountId());
        return account;
    }

    // =========================================================================
    // GET BALANCE
    // =========================================================================
    @Override
    public BigDecimal getAccountBalance(long accountId) {
        rejectLegacyCustomerEntryPoint();
        return accountDAO.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("ID=" + accountId)).getBalance();
    }

    @Override
    public BigDecimal getAccountBalance(CustomerPrincipal principal, long accountId) {
        requireCustomerGuard();
        customerAccessGuard.requireOwnedActiveAccount(principal, accountId);
        return accountDAO.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("ID=" + accountId)).getBalance();
    }

    // =========================================================================
    // GET ACCOUNTS BY CUSTOMER
    // =========================================================================
    @Override
    public List<Account> getAllAccounts(AdminPrincipal admin) {
        AdminGuard.require(admin);
        return accountDAO.findAll();
    }

    @Override
    public List<Account> getAccountsByCustomer(AdminPrincipal admin, long customerId) {
        AdminGuard.require(admin);
        customerDAO.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("ID=" + customerId));
        return accountDAO.findByCustomerId(customerId);
    }

    // =========================================================================
    // DEPOSIT   (v3.0 — scored by the Fraud Detection Engine after posting)
    // =========================================================================
    @Override
    public void deposit(long accountId, BigDecimal amount, String idempotencyKey) {
        rejectLegacyCustomerEntryPoint();
        depositInternal(accountId, amount, idempotencyKey);
    }

    @Override
    public void deposit(CustomerPrincipal principal, long accountId, BigDecimal amount, String idempotencyKey) {
        requireCustomerGuard();
        customerAccessGuard.requireOwnedActiveAccount(principal, accountId);
        depositInternal(accountId, amount, idempotencyKey);
    }

    private void depositInternal(long accountId, BigDecimal amount, String idempotencyKey) {
        // The DAO locks the owner and account, updates the balance, and writes
        // the immutable ledger row in one ACID transaction.
        BalanceChangeResult result = accountDAO.executeBalanceChange(
                accountId, TransactionType.DEPOSIT, amount, idempotencyKey);

        // Fraud scoring is now asynchronous and durable: the DAO inserted an outbox
        // event in the same transaction as the balance and ledger updates.
        if (result.isReplayed()) return;
    }

    // =========================================================================
    // WITHDRAW  (v3.0 — scored by the Fraud Detection Engine after posting)
    // =========================================================================
    @Override
    public void withdraw(long accountId, BigDecimal amount, String idempotencyKey) {
        rejectLegacyCustomerEntryPoint();
        withdrawInternal(accountId, amount, idempotencyKey);
    }

    @Override
    public void withdraw(CustomerPrincipal principal, long accountId, BigDecimal amount, String idempotencyKey) {
        requireCustomerGuard();
        customerAccessGuard.requireOwnedActiveAccount(principal, accountId);
        withdrawInternal(accountId, amount, idempotencyKey);
    }

    private void withdrawInternal(long accountId, BigDecimal amount, String idempotencyKey) {
        // Balance validation, balance update, and ledger insertion happen
        // under the same row locks and database transaction.
        BalanceChangeResult result = accountDAO.executeBalanceChange(
                accountId, TransactionType.WITHDRAW, amount, idempotencyKey);

        // Fraud scoring is handled by the durable transactional outbox.
        if (result.isReplayed()) return;
    }

    // =========================================================================
    // v3.0 — FRAUD MODULE HELPERS
    // =========================================================================

    /**
     * Defense-in-depth: even though a banned customer cannot log in, block
     * money movement on their accounts too (e.g. an already-open session).
     */
    private void assertNotBanned(Account account) {
        customerDAO.findById(account.getCustomerId()).ifPresent(owner -> {
            if (owner.isBanned()) {
                throw new CustomerBannedException(owner.getFullName(), owner.getBannedReason());
            }
            if (owner.isArchived()) {
                throw new CustomerArchivedException(owner.getFullName());
            }
        });
    }

    // =========================================================================
    // CHANGE PIN
    // =========================================================================
    @Override
    public void changePin(long accountId, String currentPin, String newPin) {
        rejectLegacyCustomerEntryPoint();
        changePinInternal(accountId, currentPin, newPin);
    }

    @Override
    public void changePin(CustomerPrincipal principal, long accountId, String currentPin, String newPin) {
        requireCustomerGuard();
        customerAccessGuard.requireOwnedActiveAccount(principal, accountId);
        changePinInternal(accountId, currentPin, newPin);
    }

    private void changePinInternal(long accountId, String currentPin, String newPin) {
        Account account = accountDAO.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("ID=" + accountId));

        if (!verifyPin(account, currentPin)) {
            throw new InvalidPinException();
        }
        if (newPin == null || !newPin.matches("\\d{4,6}")) {
            throw new BankException("New PIN must be 4 to 6 digits.");
        }
        if (currentPin.equals(newPin)) {
            throw new BankException("New PIN must be different from the current PIN.");
        }

        String newHash = BCryptUtil.hash(newPin);
        accountDAO.updatePinHash(accountId, newHash);
    }

    // =========================================================================
    // SET ACCOUNT STATUS (admin)
    // =========================================================================
    @Override
    public void setAccountStatus(AdminPrincipal admin, long accountId, AccountStatus newStatus) {
        AdminGuard.require(admin);
        Account account = accountDAO.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("ID=" + accountId));
        if (newStatus == AccountStatus.CLOSED && account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new BankException("An account can only be closed when its balance is exactly zero.");
        }
        if (account.isClosed() && newStatus != AccountStatus.CLOSED) {
            throw new BankException("A CLOSED account cannot be reopened through ordinary status management. "
                    + "Use Restore Archived Account when applicable.");
        }
        // DAO rechecks customer state, account state and balance under row locks.
        if (!accountDAO.updateStatus(accountId, newStatus, admin.username())) {
            throw new BankException("Account status was not changed.");
        }
    }

    // =========================================================================
    // TRANSACTION HISTORY
    // =========================================================================
    @Override
    public List<Transaction> getTransactionHistory(long accountId) {
        rejectLegacyCustomerEntryPoint();
        return transactionDAO.findByAccountId(accountId);
    }

    @Override
    public List<Transaction> getTransactionHistory(CustomerPrincipal principal, long accountId) {
        requireCustomerGuard();
        customerAccessGuard.requireOwnedActiveAccount(principal, accountId);
        return transactionDAO.findByAccountId(accountId);
    }

    @Override
    public List<Transaction> getTransactionHistory(AdminPrincipal admin, long accountId) {
        AdminGuard.require(admin);
        accountDAO.findById(accountId).orElseThrow(() -> new AccountNotFoundException("ID=" + accountId));
        return transactionDAO.findByAccountId(accountId);
    }

    @Override
    public List<Transaction> getMiniStatement(long accountId, int limit) {
        rejectLegacyCustomerEntryPoint();
        return miniStatementInternal(accountId, limit);
    }

    @Override
    public List<Transaction> getMiniStatement(CustomerPrincipal principal, long accountId, int limit) {
        requireCustomerGuard();
        customerAccessGuard.requireOwnedActiveAccount(principal, accountId);
        return miniStatementInternal(accountId, limit);
    }

    private List<Transaction> miniStatementInternal(long accountId, int limit) {
        if (limit <= 0 || limit > 500) throw new BankException("Statement limit must be between 1 and 500.");
        accountDAO.findById(accountId).orElseThrow(() -> new AccountNotFoundException("ID=" + accountId));
        return transactionDAO.findRecentByAccountId(accountId, limit);
    }

    /** Legacy plaintext PINs must be reset through the recovery flow before login. */
    private boolean verifyPin(Account account, String enteredPin) {
        return account != null && BCryptUtil.verify(enteredPin, account.getPinHash());
    }

    // =========================================================================
    // PRIVATE UTILITIES
    // =========================================================================
    private String generateAccountNumber() {
        long random = ACCOUNT_RANDOM.nextLong(10_000_000_000L);
        return String.format("10%010d", random);
    }

    private String generateCardNumber() {
        long random = ACCOUNT_RANDOM.nextLong(10_000_000_000L);
        return String.format("40%010d", random);
    }

    private String normalisePhone(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[\\s()\\-]", "");
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}