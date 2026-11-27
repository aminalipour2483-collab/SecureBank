package com.bank.dao.impl;

import com.bank.config.DatabaseConnection;
import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.fraud.outbox.FraudOutboxWriter;
import com.bank.dao.AccountDAO;
import com.bank.exception.AccountLockedException;
import com.bank.exception.AccountNotFoundException;
import com.bank.exception.BankException;
import com.bank.exception.CustomerBannedException;
import com.bank.exception.CustomerArchivedException;
import com.bank.exception.CustomerNotFoundException;
import com.bank.exception.InsufficientFundsException;
import com.bank.model.BalanceChangeResult;
import com.bank.model.Customer.CustomerStatus;
import com.bank.model.Transaction.TransactionType;
import com.bank.util.IdempotencyUtil;
import com.bank.model.Account;
import com.bank.model.Account.AccountStatus;
import com.bank.model.Account.AccountType;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * AccountDAOImpl — JDBC implementation of AccountDAO.
 *
 * Folder: BankManagementSystem/src/com/bank/dao/impl/AccountDAOImpl.java
 *
 * CONNECTION LIFECYCLE (connection-pool era):
 *   • Every method BORROWS one connection from the shared pool via
 *     DatabaseConnection and RETURNS it with try-with-resources
 *     (Connection.close() = return to the pool, not a physical disconnect).
 *   • Read-only methods use one try-with-resources block wrapping
 *     Connection + PreparedStatement (+ ResultSet where practical).
 *   • Transactional methods use JdbcTransactionExecutor for a single connection,
 *     commit/rollback and safe pool cleanup.
 */
public class AccountDAOImpl implements AccountDAO {

    private final JdbcTransactionExecutor transactions;

    public AccountDAOImpl() {
        this(JdbcTransactionExecutor.pooled(() -> DatabaseConnection.getInstance().getConnection()));
    }

    /** Injection seam for database-free transaction and fault-injection tests. */
    AccountDAOImpl(JdbcTransactionExecutor transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    private Connection getConnection() {
        return DatabaseConnection.getInstance().getConnection();
    }

    // =========================================================================
    // HELPER — map one ResultSet row → Account object
    // =========================================================================
    private Account mapRow(ResultSet rs) throws SQLException {
        Account a = new Account();
        a.setAccountId    (rs.getLong  ("account_id"));
        a.setCustomerId   (rs.getLong  ("customer_id"));
        a.setAccountNumber(rs.getString("account_number"));
        a.setCardNumber   (rs.getString("card_number"));
        a.setPinHash      (rs.getString("pin_hash"));

        // Parse the ENUM string back into the Java enum safely
        a.setAccountType(AccountType.from(rs.getString("account_type")));
        a.setStatus     (AccountStatus.from(rs.getString("status")));
        a.setLockReason (Account.LockReason.from(rs.getString("lock_reason")));
        try { a.setClosureReason(Account.ClosureReason.from(rs.getString("closure_reason"))); }
        catch (SQLException ignoredLegacyColumn) { a.setClosureReason(Account.ClosureReason.NONE); }

        // DECIMAL(18,2) → BigDecimal — already the correct type from JDBC
        a.setBalance(rs.getBigDecimal("balance"));

        // Failed attempt counter — defaults to 0 in DB
        a.setFailedPinAttempts(rs.getInt("failed_pin_attempts"));

        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) a.setCreatedAt(ts.toLocalDateTime());

        return a;
    }

    // =========================================================================
    // SAVE (INSERT)
    // =========================================================================
    @Override
    public long save(Account account) {
        // Keep the legacy DAO entry point safe: account creation must never
        // persist a positive opening balance without its matching ledger row.
        return saveWithOpeningDeposit(account);
    }

    // =========================================================================
    // CREATE ACCOUNT + OPENING LEDGER ENTRY ATOMICALLY
    // =========================================================================
    @Override
    public long saveWithOpeningDeposit(Account account) {
        if (account == null || account.getBalance() == null
                || account.getBalance().compareTo(BigDecimal.ZERO) < 0) {
            throw new BankException("A valid non-negative opening balance is required.");
        }
        if (account.getAccountType() == null) {
            throw new BankException("Account type is required.");
        }
        if (account.getBalance().stripTrailingZeros().scale() > 2) {
            throw new BankException("Opening balance cannot have more than two decimal places.");
        }

        // Borrow one connection for the whole transaction; try-with-resources
        // guarantees it is returned to the pool on every path.
        return transactions.execute("Could not create the account and opening ledger entry atomically.", conn -> {

            String customerName;
            String customerStatus;
            String bannedReason;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT first_name, last_name, status, banned_reason "
                    + "FROM customers WHERE customer_id = ? FOR UPDATE")) {
                stmt.setLong(1, account.getCustomerId());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new CustomerNotFoundException("ID=" + account.getCustomerId());
                    }
                    customerName = rs.getString("first_name") + " " + rs.getString("last_name");
                    customerStatus = rs.getString("status");
                    bannedReason = rs.getString("banned_reason");
                }
            }
            if (CustomerStatus.BANNED.name().equals(customerStatus)) {
                throw new CustomerBannedException(customerName, bannedReason);
            }
            if (CustomerStatus.ARCHIVED.name().equals(customerStatus)) {
                throw new CustomerArchivedException(customerName);
            }

            // The customer row is already locked FOR UPDATE. Every account creation
            // through this DAO takes the same lock before counting, so concurrent
            // requests cannot both observe the same remaining slot and exceed the cap.
            // Deliberately count CLOSED and LOCKED accounts as part of the lifetime cap.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM accounts WHERE customer_id = ?")) {
                stmt.setLong(1, account.getCustomerId());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Could not count existing customer accounts.");
                    int existingAccounts = rs.getInt(1);
                    if (existingAccounts >= AccountDAO.MAX_ACCOUNTS_PER_CUSTOMER) {
                        throw new BankException("A customer cannot have more than "
                                + AccountDAO.MAX_ACCOUNTS_PER_CUSTOMER
                                + " accounts in total, including locked and closed accounts.");
                    }
                }
            }

            long accountId;
            String insertAccount = "INSERT INTO accounts "
                    + "(customer_id, account_number, card_number, pin_hash, account_type, balance, status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(
                    insertAccount, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setLong(1, account.getCustomerId());
                stmt.setString(2, account.getAccountNumber());
                stmt.setString(3, account.getCardNumber());
                stmt.setString(4, account.getPinHash());
                stmt.setString(5, account.getAccountType().name());
                stmt.setBigDecimal(6, account.getBalance());
                stmt.setString(7, account.getStatus().name());
                if (stmt.executeUpdate() != 1) {
                    throw new SQLException("Account insert affected no rows.");
                }
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No account ID was returned by the database.");
                    }
                    accountId = keys.getLong(1);
                }
            }

            if (account.getBalance().compareTo(BigDecimal.ZERO) > 0) {
                long openingTransactionId;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO transactions "
                        + "(account_id, transaction_type, amount, balance_after_transaction) "
                        + "VALUES (?, 'DEPOSIT', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, accountId);
                    stmt.setBigDecimal(2, account.getBalance());
                    stmt.setBigDecimal(3, account.getBalance());
                    if (stmt.executeUpdate() != 1) throw new SQLException("Opening-deposit ledger insert affected no rows.");
                    try (ResultSet keys = stmt.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Opening-deposit ledger returned no transaction ID.");
                        openingTransactionId = keys.getLong(1);
                    }
                }
                FraudOutboxWriter.enqueue(conn, accountId, account.getCustomerId(),
                        TransactionType.DEPOSIT, account.getBalance(), BigDecimal.ZERO,
                        openingTransactionId, null);
            }

            return accountId;
        });
    }

    // =========================================================================
    // ATOMIC DEPOSIT / WITHDRAW
    // The owner row is locked before the account row. This is the lock order
    // used by transfer processing as well, and it coordinates safely with a
    // concurrent customer ban.
    // =========================================================================
    @Override
    public BalanceChangeResult executeBalanceChange(long accountId,
                                                     TransactionType type,
                                                     BigDecimal amount,
                                                     String idempotencyKey) {
        if (type != TransactionType.DEPOSIT && type != TransactionType.WITHDRAW) {
            throw new BankException("Only DEPOSIT and WITHDRAW are valid balance changes.");
        }
        validateMoneyAmount(amount);
        IdempotencyUtil.validateKey(idempotencyKey);
        String requestHash = IdempotencyUtil.fingerprint(
                type.name(), Long.toString(accountId), IdempotencyUtil.money(amount));

        // One borrowed connection carries the reservation, the financial
        // writes and the completion marker in a single transaction.
        return JdbcTransactionExecutor.pooled(this::getConnection).execute("Could not complete " + type + " for account " + accountId + ".", conn -> {

            IdempotencyUtil.Reservation reservation = IdempotencyUtil.reserve(
                    conn, idempotencyKey, type.name(), requestHash);
            if (reservation.isReplay()) {

                return new BalanceChangeResult(reservation.getCustomerId(),
                        reservation.getBalanceBefore(), reservation.getBalanceAfter(),
                        reservation.getResultId(), true);
            }

            long customerId;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT customer_id FROM accounts WHERE account_id = ?")) {
                stmt.setLong(1, accountId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new AccountNotFoundException("ID=" + accountId);
                    customerId = rs.getLong("customer_id");
                }
            }

            String customerName;
            String customerStatus;
            String bannedReason;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT first_name, last_name, status, banned_reason "
                    + "FROM customers WHERE customer_id = ? FOR UPDATE")) {
                stmt.setLong(1, customerId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new CustomerNotFoundException("ID=" + customerId);
                    customerName = rs.getString("first_name") + " " + rs.getString("last_name");
                    customerStatus = rs.getString("status");
                    bannedReason = rs.getString("banned_reason");
                }
            }
            if (CustomerStatus.BANNED.name().equals(customerStatus)) {
                throw new CustomerBannedException(customerName, bannedReason);
            }
            if (CustomerStatus.ARCHIVED.name().equals(customerStatus)) {
                throw new CustomerArchivedException(customerName);
            }

            BigDecimal balanceBefore;
            String accountStatus;
            String accountNumber;
            long lockedCustomerId;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT customer_id, balance, status, account_number "
                    + "FROM accounts WHERE account_id = ? FOR UPDATE")) {
                stmt.setLong(1, accountId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new AccountNotFoundException("ID=" + accountId);
                    lockedCustomerId = rs.getLong("customer_id");
                    balanceBefore = rs.getBigDecimal("balance");
                    accountStatus = rs.getString("status");
                    accountNumber = rs.getString("account_number");
                }
            }
            if (lockedCustomerId != customerId) {
                throw new BankException("Account ownership changed during the operation. Please retry.");
            }
            if (!AccountStatus.ACTIVE.name().equals(accountStatus)) {
                throw new AccountLockedException(accountNumber);
            }
            if (type == TransactionType.WITHDRAW && balanceBefore.compareTo(amount) < 0) {
                throw new InsufficientFundsException(balanceBefore, amount);
            }

            BigDecimal balanceAfter = type == TransactionType.DEPOSIT
                    ? balanceBefore.add(amount) : balanceBefore.subtract(amount);
            if (balanceAfter.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
                throw new BankException("Account balance exceeds supported monetary range.");
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE accounts SET balance = ? WHERE account_id = ? AND status = 'ACTIVE'")) {
                stmt.setBigDecimal(1, balanceAfter);
                stmt.setLong(2, accountId);
                if (stmt.executeUpdate() != 1) {
                    throw new BankException("Account balance update failed. Please retry.");
                }
            }

            long transactionId;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO transactions "
                    + "(account_id, transaction_type, amount, balance_after_transaction) "
                    + "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                stmt.setLong(1, accountId);
                stmt.setString(2, type.name());
                stmt.setBigDecimal(3, amount);
                stmt.setBigDecimal(4, balanceAfter);
                if (stmt.executeUpdate() != 1) {
                    throw new SQLException("Ledger insert affected no rows.");
                }
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No transaction ID was returned.");
                    transactionId = keys.getLong(1);
                }
            }

            // Transactional outbox: if enqueue fails, the financial posting rolls back too.
            FraudOutboxWriter.enqueue(conn, accountId, customerId, type, amount,
                    balanceBefore, transactionId, null);

            IdempotencyUtil.complete(conn, idempotencyKey, transactionId,
                    customerId, balanceBefore, balanceAfter);

            return new BalanceChangeResult(customerId, balanceBefore, balanceAfter, transactionId, false);
        });
    }

    private void validateMoneyAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BankException("Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BankException("Amount cannot have more than two decimal places.");
        }
    }


    // =========================================================================
    // FIND BY ID
    // =========================================================================
    @Override
    public Optional<Account> findById(long accountId) {
        String sql = "SELECT * FROM accounts WHERE account_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[AccountDAO] findById() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find account by ID", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND BY ACCOUNT NUMBER
    // =========================================================================
    @Override
    public Optional<Account> findByAccountNumber(String accountNumber) {
        String sql = "SELECT * FROM accounts WHERE account_number = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, accountNumber);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[AccountDAO] findByAccountNumber() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find account by number", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND BY CARD NUMBER
    // =========================================================================
    @Override
    public Optional<Account> findByCardNumber(String cardNumber) {
        String sql = "SELECT * FROM accounts WHERE card_number = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, cardNumber);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[AccountDAO] findByCardNumber() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find account by card number", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND BY CUSTOMER ID
    // =========================================================================
    @Override
    public List<Account> findByCustomerId(long customerId) {
        String sql = "SELECT * FROM accounts WHERE customer_id = ? "
                   + "ORDER BY created_at ASC";
        List<Account> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, customerId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[AccountDAO] findByCustomerId() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find accounts by customer ID", e);
        }
        return list;
    }

    // =========================================================================
    // FIND ALL
    // =========================================================================
    @Override
    public List<Account> findAll() {
        String sql = "SELECT * FROM accounts ORDER BY account_id ASC";
        List<Account> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) list.add(mapRow(rs));

        } catch (SQLException e) {
            System.err.println("[AccountDAO] findAll() failed: " + e.getMessage());
            throw new RuntimeException("Failed to retrieve all accounts", e);
        }
        return list;
    }

    // =========================================================================
    // UPDATE STATUS  (ACTIVE ↔ LOCKED)
    // =========================================================================
    @Override
    public boolean updateStatus(long accountId, AccountStatus newStatus) {
        return updateStatus(accountId, newStatus, "system");
    }

    @Override
    public boolean updateStatus(long accountId, AccountStatus newStatus, String actor) {
        if (newStatus == null) throw new BankException("Account status is required.");
        if (actor == null || actor.isBlank()) throw new BankException("Administrator identity is required.");
        return JdbcTransactionExecutor.pooled(this::getConnection).execute(
                "Could not update account status.", conn -> {
            long customerId;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT customer_id FROM accounts WHERE account_id = ?")) {
                stmt.setLong(1, accountId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new AccountNotFoundException("ID=" + accountId);
                    customerId = rs.getLong("customer_id");
                }
            }
            String customerStatus;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT status FROM customers WHERE customer_id = ? FOR UPDATE")) {
                stmt.setLong(1, customerId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new CustomerNotFoundException("ID=" + customerId);
                    customerStatus = rs.getString("status");
                }
            }
            String currentStatus;
            String lockReason;
            BigDecimal balance;
            String closureReason;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT status, lock_reason, balance, closure_reason FROM accounts "
                    + "WHERE account_id = ? AND customer_id = ? FOR UPDATE")) {
                stmt.setLong(1, accountId); stmt.setLong(2, customerId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new AccountNotFoundException("ID=" + accountId);
                    currentStatus = rs.getString("status");
                    lockReason = rs.getString("lock_reason");
                    balance = rs.getBigDecimal("balance");
                    closureReason = rs.getString("closure_reason");
                }
            }
            if (AccountStatus.CLOSED.name().equals(currentStatus)
                    && newStatus != AccountStatus.CLOSED) {
                throw new BankException("A CLOSED account cannot be reopened through ordinary status management. "
                        + "If it was closed by customer archive, use the separate Restore Archived Account action.");
            }
            if (newStatus == AccountStatus.ACTIVE && !CustomerStatus.ACTIVE.name().equals(customerStatus)) {
                throw new BankException("An account can only be activated while its customer is ACTIVE.");
            }
            if (newStatus == AccountStatus.CLOSED && balance.compareTo(BigDecimal.ZERO) != 0) {
                throw new BankException("An account can only be closed when its balance is exactly zero.");
            }
            if (newStatus.name().equals(currentStatus)) return true;

            String newLockReason = newStatus == AccountStatus.LOCKED ? "ADMIN" :
                    newStatus == AccountStatus.ACTIVE ? "NONE" : (lockReason == null ? "NONE" : lockReason);
            String newClosureReason = newStatus == AccountStatus.CLOSED ? "ADMIN_DECISION" : "NONE";
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE accounts SET status = ?, lock_reason = ?, closure_reason = ?, "
                    + "failed_pin_attempts = CASE WHEN ? = 'ACTIVE' THEN 0 ELSE failed_pin_attempts END "
                    + "WHERE account_id = ?")) {
                stmt.setString(1, newStatus.name()); stmt.setString(2, newLockReason);
                stmt.setString(3, newClosureReason); stmt.setString(4, newStatus.name());
                stmt.setLong(5, accountId);
                if (stmt.executeUpdate() != 1) return false;
            }
            insertAccountStatusHistory(conn, accountId, currentStatus, newStatus.name(),
                    lockReason == null ? "NONE" : lockReason, newLockReason,
                    newStatus == AccountStatus.CLOSED ? "ADMIN_DECISION" : "ADMIN_STATUS_CHANGE", actor.trim());
            return true;
        });
    }

    private void insertAccountStatusHistory(Connection conn, long accountId,
            String previousStatus, String newStatus, String previousLock, String newLock,
            String reason, String actor) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO account_status_history (account_id, previous_status, new_status, "
                + "previous_lock_reason, new_lock_reason, reason, actor) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setLong(1, accountId); stmt.setString(2, previousStatus); stmt.setString(3, newStatus);
            stmt.setString(4, previousLock); stmt.setString(5, newLock);
            stmt.setString(6, reason); stmt.setString(7, actor);
            stmt.executeUpdate();
        }
    }

    @Override
    public boolean lockAfterFailedPinAttempts(long accountId) {
        String sql = "UPDATE accounts SET status = 'LOCKED', lock_reason = 'FAILED_PIN' "
                   + "WHERE account_id = ? AND status = 'ACTIVE' AND failed_pin_attempts >= 3";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to lock account after PIN failures", e);
        }
    }

    @Override
    public boolean resetPinAndUnlockAfterRecovery(long accountId, String newPinHash) {
        String sql = "UPDATE accounts SET pin_hash = ?, status = 'ACTIVE', lock_reason = 'NONE', "
                   + "failed_pin_attempts = 0 WHERE account_id = ? AND status = 'LOCKED' "
                   + "AND lock_reason = 'FAILED_PIN'";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newPinHash);
            stmt.setLong(2, accountId);
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reset PIN and unlock account", e);
        }
    }

    // =========================================================================
    // UPDATE PIN HASH
    // =========================================================================
    @Override
    public boolean updatePinHash(long accountId, String newPinHash) {
        String sql = "UPDATE accounts SET pin_hash = ? WHERE account_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newPinHash);
            stmt.setLong  (2, accountId);
            return stmt.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("[AccountDAO] updatePinHash() failed: " + e.getMessage());
            throw new RuntimeException("Failed to update PIN hash", e);
        }
    }

    // =========================================================================
    // INCREMENT FAILED PIN ATTEMPTS
    // Atomically increments the counter in the DB and returns the new value.
    // Using SQL "= failed_pin_attempts + 1" avoids a read-then-write race.
    // Both statements run on ONE borrowed connection so the read-back is
    // guaranteed to observe the increment just performed.
    // =========================================================================
    @Override
    public int incrementFailedAttempts(long accountId) {
        String updateSql = "UPDATE accounts "
                         + "SET failed_pin_attempts = failed_pin_attempts + 1 "
                         + "WHERE account_id = ?";
        String selectSql = "SELECT failed_pin_attempts FROM accounts WHERE account_id = ?";

        try (Connection conn = getConnection()) {
            // Step 1: increment
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setLong(1, accountId);
                stmt.executeUpdate();
            }
            // Step 2: read back the new value
            try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                stmt.setLong(1, accountId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getInt("failed_pin_attempts");
                }
            }
        } catch (SQLException e) {
            System.err.println("[AccountDAO] incrementFailedAttempts() failed: " + e.getMessage());
            throw new RuntimeException("Failed to increment failed attempts", e);
        }
        return -1;
    }

    // =========================================================================
    // RESET FAILED PIN ATTEMPTS  (called on successful login)
    // =========================================================================
    @Override
    public boolean resetFailedAttempts(long accountId) {
        String sql = "UPDATE accounts SET failed_pin_attempts = 0 WHERE account_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);
            return stmt.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("[AccountDAO] resetFailedAttempts() failed: " + e.getMessage());
            throw new RuntimeException("Failed to reset failed attempts", e);
        }
    }

    // =========================================================================
    // DELETE
    // =========================================================================
    @Override
    public boolean delete(long accountId) {
        String sql = "DELETE FROM accounts WHERE account_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);
            return stmt.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("[AccountDAO] delete() failed: " + e.getMessage());
            throw new RuntimeException("Failed to delete account", e);
        }
    }
}
