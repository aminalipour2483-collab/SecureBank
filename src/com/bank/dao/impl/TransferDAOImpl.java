package com.bank.dao.impl;

import com.bank.config.DatabaseConnection;
import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.fraud.outbox.FraudOutboxWriter;
import com.bank.dao.TransferDAO;
import com.bank.exception.AccountLockedException;
import com.bank.exception.AccountNotFoundException;
import com.bank.exception.BankException;
import com.bank.exception.CustomerBannedException;
import com.bank.exception.CustomerNotFoundException;
import com.bank.exception.InsufficientFundsException;
import com.bank.model.Account;
import com.bank.model.Transaction;
import com.bank.model.Transaction.TransactionType;
import com.bank.model.Transfer;
import com.bank.model.TransferExecutionResult;
import com.bank.model.Transfer.TransferStatus;
import com.bank.util.IdempotencyUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.List;
import java.util.Optional;

/**
 * TransferDAOImpl — JDBC implementation of TransferDAO.
 *
 * Folder: BankManagementSystem/src/com/bank/dao/impl/TransferDAOImpl.java
 *
 * The centrepiece of this class is executeTransfer(), which wraps the full
 * financial posting inside a single JDBC transaction:
 *
 *   Step 1 — Fetch sender's current balance  (SELECT … FOR UPDATE)
 *   Step 2 — Fetch receiver's current balance
 *   Step 3 — Debit sender   (UPDATE accounts SET balance = balance - amount)
 *   Step 4 — Credit receiver(UPDATE accounts SET balance = balance + amount)
 *   Step 5 — Insert TRANSFER_OUT ledger entry
 *   Step 6 — Insert TRANSFER_IN  ledger entry
 *   Step 7 — Insert transfer record with status = COMPLETED
 *
 * JdbcTransactionExecutor handles commit, rollback and connection cleanup.
 * An unacknowledged commit is reported as unknown rather than as a definite rollback.
 *
 * CONNECTION LIFECYCLE (connection-pool era): executeTransfer() borrows ONE
 * connection and passes it explicitly to every statement and helper
 * (IdempotencyUtil.reserve/complete, insertLedgerEntry) — the transaction
 * never borrows a second connection. Read methods borrow and return a
 * connection per call via try-with-resources.
 */
public class TransferDAOImpl implements TransferDAO {

    private Connection getConnection() {
        return DatabaseConnection.getInstance().getConnection();
    }

    // =========================================================================
    // HELPER — map one ResultSet row → Transfer object
    // =========================================================================
    private Transfer mapRow(ResultSet rs) throws SQLException {
        Transfer t = new Transfer();
        t.setTransferId        (rs.getLong  ("transfer_id"));
        t.setSenderAccountId   (rs.getLong  ("sender_account_id"));
        t.setReceiverAccountId (rs.getLong  ("receiver_account_id"));
        t.setAmount            (rs.getBigDecimal("amount"));
        t.setReferenceNumber   (rs.getString("reference_number"));
        t.setStatus            (TransferStatus.from(rs.getString("status")));

        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) t.setCreatedAt(ts.toLocalDateTime());

        return t;
    }

    // =========================================================================
    // EXECUTE TRANSFER  — the atomic core operation
    //
    // "SELECT … FOR UPDATE" locks the two account rows so no other concurrent
    // transfer can read a stale balance between our SELECT and our UPDATE.
    // =========================================================================
    @Override
    public TransferExecutionResult executeTransfer(Transfer transfer, String idempotencyKey) {
        if (transfer == null) {
            throw new BankException("Transfer request is required.");
        }
        if (transfer.getSenderAccountId() == transfer.getReceiverAccountId()) {
            throw new BankException("Cannot transfer money to the same account.");
        }
        validateMoneyAmount(transfer.getAmount());
        if (transfer.getReferenceNumber() == null || transfer.getReferenceNumber().isBlank()) {
            throw new BankException("A transfer reference number is required.");
        }
        IdempotencyUtil.validateKey(idempotencyKey);
        String requestHash = IdempotencyUtil.fingerprint("TRANSFER",
                Long.toString(transfer.getSenderAccountId()),
                Long.toString(transfer.getReceiverAccountId()),
                IdempotencyUtil.money(transfer.getAmount()));

        final String SQL_FIND_OWNERS =
                "SELECT account_id, customer_id FROM accounts "
                + "WHERE account_id IN (?, ?) ORDER BY account_id";
        final String SQL_LOCK_CUSTOMER =
                "SELECT first_name, last_name, status, banned_reason "
                + "FROM customers WHERE customer_id = ? FOR UPDATE";
        final String SQL_LOCK_ACCOUNT =
                "SELECT customer_id, balance, status, account_number "
                + "FROM accounts WHERE account_id = ? FOR UPDATE";
        final String SQL_DEBIT =
                "UPDATE accounts SET balance = balance - ? WHERE account_id = ? AND status = 'ACTIVE'";
        final String SQL_CREDIT =
                "UPDATE accounts SET balance = balance + ? WHERE account_id = ? AND status = 'ACTIVE'";
        final String SQL_INSERT_TX =
                "INSERT INTO transactions "
                + "(account_id, transaction_type, amount, balance_after_transaction) "
                + "VALUES (?, ?, ?, ?)";
        final String SQL_INSERT_TRANSFER =
                "INSERT INTO transfers "
                + "(sender_account_id, receiver_account_id, amount, reference_number, status) "
                + "VALUES (?, ?, ?, ?, ?)";


        // Borrow exactly ONE connection for the whole transaction.
        return JdbcTransactionExecutor.pooled(this::getConnection).execute("Transfer could not be completed.", conn -> {

            long generatedTransferId;
            IdempotencyUtil.Reservation reservation = IdempotencyUtil.reserve(
                    conn, idempotencyKey, "TRANSFER", requestHash);
            if (reservation.isReplay()) {

                return new TransferExecutionResult(reservation.getResultId(),
                        reservation.getCustomerId(), reservation.getBalanceBefore(), true);
            }

            // Determine owners, then acquire row locks in a deterministic order:
            // all customer rows by customer_id, followed by account rows by
            // account_id. This avoids opposite-direction transfer deadlocks and
            // makes customer-ban checks part of the same atomic operation.
            Map<Long, Long> customerByAccount = new TreeMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(SQL_FIND_OWNERS)) {
                stmt.setLong(1, transfer.getSenderAccountId());
                stmt.setLong(2, transfer.getReceiverAccountId());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        customerByAccount.put(rs.getLong("account_id"), rs.getLong("customer_id"));
                    }
                }
            }
            if (!customerByAccount.containsKey(transfer.getSenderAccountId())) {
                throw new AccountNotFoundException("Sender account ID=" + transfer.getSenderAccountId());
            }
            if (!customerByAccount.containsKey(transfer.getReceiverAccountId())) {
                throw new AccountNotFoundException("Receiver account ID=" + transfer.getReceiverAccountId());
            }

            Map<Long, CustomerSnapshot> customers = new TreeMap<>();
            for (Long customerId : new TreeSet<>(customerByAccount.values())) {
                try (PreparedStatement stmt = conn.prepareStatement(SQL_LOCK_CUSTOMER)) {
                    stmt.setLong(1, customerId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            throw new CustomerNotFoundException("ID=" + customerId);
                        }
                        CustomerSnapshot customer = new CustomerSnapshot(
                                rs.getString("first_name") + " " + rs.getString("last_name"),
                                rs.getString("status"),
                                rs.getString("banned_reason"));
                        if ("BANNED".equals(customer.status)) {
                            throw new CustomerBannedException(customer.name, customer.bannedReason);
                        }
                        if ("ARCHIVED".equals(customer.status)) {
                            throw new BankException("An archived customer cannot send or receive transfers.");
                        }
                        customers.put(customerId, customer);
                    }
                }
            }

            Map<Long, AccountSnapshot> accounts = new TreeMap<>();
            for (Long accountId : customerByAccount.keySet()) {
                try (PreparedStatement stmt = conn.prepareStatement(SQL_LOCK_ACCOUNT)) {
                    stmt.setLong(1, accountId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            throw new AccountNotFoundException("ID=" + accountId);
                        }
                        AccountSnapshot account = new AccountSnapshot(
                                rs.getLong("customer_id"),
                                rs.getBigDecimal("balance"),
                                rs.getString("status"),
                                rs.getString("account_number"));
                        if (!account.customerIdEquals(customerByAccount.get(accountId))) {
                            throw new BankException("Account ownership changed during transfer. Please retry.");
                        }
                        if (!"ACTIVE".equals(account.status)) {
                            throw new AccountLockedException(account.accountNumber);
                        }
                        accounts.put(accountId, account);
                    }
                }
            }

            AccountSnapshot sender = accounts.get(transfer.getSenderAccountId());
            AccountSnapshot receiver = accounts.get(transfer.getReceiverAccountId());
            BigDecimal amount = transfer.getAmount();
            if (sender.balance.compareTo(amount) < 0) {
                throw new InsufficientFundsException(sender.balance, amount);
            }

            BigDecimal newSenderBalance = sender.balance.subtract(amount);
            BigDecimal newReceiverBalance = receiver.balance.add(amount);
            if (newReceiverBalance.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
                throw new BankException("Recipient balance exceeds supported monetary range.");
            }

            try (PreparedStatement stmt = conn.prepareStatement(SQL_DEBIT)) {
                stmt.setBigDecimal(1, amount);
                stmt.setLong(2, transfer.getSenderAccountId());
                if (stmt.executeUpdate() != 1) {
                    throw new BankException("Could not debit the sender account.");
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement(SQL_CREDIT)) {
                stmt.setBigDecimal(1, amount);
                stmt.setLong(2, transfer.getReceiverAccountId());
                if (stmt.executeUpdate() != 1) {
                    throw new BankException("Could not credit the receiver account.");
                }
            }

            // Both ledger entries are inserted on the SAME borrowed connection
            // as the transaction — the helper receives conn as a parameter.
            long senderTransactionId = insertLedgerEntry(conn, SQL_INSERT_TX, transfer.getSenderAccountId(),
                    TransactionType.TRANSFER_OUT, amount, newSenderBalance);
            insertLedgerEntry(conn, SQL_INSERT_TX, transfer.getReceiverAccountId(),
                    TransactionType.TRANSFER_IN, amount, newReceiverBalance);

            try (PreparedStatement stmt = conn.prepareStatement(
                    SQL_INSERT_TRANSFER, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setLong(1, transfer.getSenderAccountId());
                stmt.setLong(2, transfer.getReceiverAccountId());
                stmt.setBigDecimal(3, amount);
                stmt.setString(4, transfer.getReferenceNumber());
                stmt.setString(5, TransferStatus.COMPLETED.name());
                if (stmt.executeUpdate() != 1) {
                    throw new SQLException("Transfer record insert affected no rows.");
                }
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No transfer ID was returned by the database.");
                    }
                    generatedTransferId = keys.getLong(1);
                }
            }

            // One fraud event per financial transfer, linked to the sender ledger leg.
            // It is committed atomically with both balance updates and both ledger rows.
            FraudOutboxWriter.enqueue(conn, transfer.getSenderAccountId(),
                    customerByAccount.get(transfer.getSenderAccountId()),
                    TransactionType.TRANSFER_OUT, amount, sender.balance,
                    senderTransactionId, generatedTransferId);

            IdempotencyUtil.complete(conn, idempotencyKey, generatedTransferId,
                    customerByAccount.get(transfer.getSenderAccountId()), sender.balance, newSenderBalance);

            return new TransferExecutionResult(generatedTransferId,
                    customerByAccount.get(transfer.getSenderAccountId()), sender.balance, false);
        });
    }

    private long insertLedgerEntry(Connection conn, String sql, long accountId,
                                   TransactionType type, BigDecimal amount,
                                   BigDecimal balanceAfter) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, accountId);
            stmt.setString(2, type.name());
            stmt.setBigDecimal(3, amount);
            stmt.setBigDecimal(4, balanceAfter);
            if (stmt.executeUpdate() != 1) throw new SQLException("Failed to insert " + type + " ledger entry.");
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No transaction ID returned for " + type + ".");
                return keys.getLong(1);
            }
        }
    }

    private void validateMoneyAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BankException("Transfer amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BankException("Transfer amount cannot have more than two decimal places.");
        }
    }


    private static final class CustomerSnapshot {
        private final String name;
        private final String status;
        private final String bannedReason;

        private CustomerSnapshot(String name, String status, String bannedReason) {
            this.name = name;
            this.status = status;
            this.bannedReason = bannedReason;
        }
    }

    private static final class AccountSnapshot {
        private final long customerId;
        private final BigDecimal balance;
        private final String status;
        private final String accountNumber;

        private AccountSnapshot(long customerId, BigDecimal balance,
                                String status, String accountNumber) {
            this.customerId = customerId;
            this.balance = balance;
            this.status = status;
            this.accountNumber = accountNumber;
        }

        private boolean customerIdEquals(Long expected) {
            return expected != null && customerId == expected.longValue();
        }
    }

    // =========================================================================
    // FIND BY ID
    // =========================================================================
    @Override
    public Optional<Transfer> findById(long transferId) {
        String sql = "SELECT * FROM transfers WHERE transfer_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, transferId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[TransferDAO] findById() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find transfer by ID", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND BY REFERENCE NUMBER
    // =========================================================================
    @Override
    public Optional<Transfer> findByReferenceNumber(String referenceNumber) {
        String sql = "SELECT * FROM transfers WHERE reference_number = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, referenceNumber);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[TransferDAO] findByReferenceNumber() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find transfer by reference number", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND BY SENDER ACCOUNT ID
    // =========================================================================
    @Override
    public List<Transfer> findBySenderAccountId(long accountId) {
        String sql = "SELECT * FROM transfers WHERE sender_account_id = ? "
                   + "ORDER BY created_at DESC";
        List<Transfer> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[TransferDAO] findBySenderAccountId() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find transfers by sender", e);
        }
        return list;
    }

    // =========================================================================
    // FIND BY RECEIVER ACCOUNT ID
    // =========================================================================
    @Override
    public List<Transfer> findByReceiverAccountId(long accountId) {
        String sql = "SELECT * FROM transfers WHERE receiver_account_id = ? "
                   + "ORDER BY created_at DESC";
        List<Transfer> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[TransferDAO] findByReceiverAccountId() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find transfers by receiver", e);
        }
        return list;
    }

    // =========================================================================
    // FIND ALL BY ACCOUNT ID  (both directions — for customer history screen)
    // =========================================================================
    @Override
    public List<Transfer> findAllByAccountId(long accountId) {
        String sql = "SELECT * FROM transfers "
                   + "WHERE sender_account_id = ? OR receiver_account_id = ? "
                   + "ORDER BY created_at DESC";
        List<Transfer> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);
            stmt.setLong(2, accountId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[TransferDAO] findAllByAccountId() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find transfers by account ID", e);
        }
        return list;
    }

    // =========================================================================
    // FIND ALL  (admin use only)
    // =========================================================================
    @Override
    public List<Transfer> findAll() {
        String sql = "SELECT * FROM transfers ORDER BY created_at DESC";
        List<Transfer> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) list.add(mapRow(rs));

        } catch (SQLException e) {
            System.err.println("[TransferDAO] findAll() failed: " + e.getMessage());
            throw new RuntimeException("Failed to retrieve all transfers", e);
        }
        return list;
    }

    // =========================================================================
    // UPDATE STATUS
    // =========================================================================
    @Override
    public boolean updateStatus(long transferId, TransferStatus newStatus) {
        String sql = "UPDATE transfers SET status = ? WHERE transfer_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newStatus.name());
            stmt.setLong  (2, transferId);
            return stmt.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("[TransferDAO] updateStatus() failed: " + e.getMessage());
            throw new RuntimeException("Failed to update transfer status", e);
        }
    }
}
