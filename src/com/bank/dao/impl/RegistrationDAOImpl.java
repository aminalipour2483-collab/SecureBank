package com.bank.dao.impl;

import com.bank.config.DatabaseConnection;
import com.bank.dao.RegistrationDAO;
import com.bank.exception.BankException;
import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.fraud.outbox.FraudOutboxWriter;
import com.bank.model.Transaction.TransactionType;
import com.bank.model.Account;
import com.bank.model.Customer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.security.SecureRandom;
import java.util.function.Supplier;

/** JDBC aggregate repository: customer + first account + opening ledger share one transaction. */
public final class RegistrationDAOImpl implements RegistrationDAO {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTransactionExecutor transactions;

    public RegistrationDAOImpl() {
        this(() -> DatabaseConnection.getInstance().getConnection());
    }

    /** Injection seam for deterministic JDBC fault-injection tests. */
    public RegistrationDAOImpl(Supplier<Connection> connectionSupplier) {
        this(JdbcTransactionExecutor.pooled(connectionSupplier));
    }

    public RegistrationDAOImpl(JdbcTransactionExecutor transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Account registerNewCustomerWithFirstAccount(Customer customer, Account firstAccount) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(firstAccount, "firstAccount");
        if (firstAccount.getBalance() == null || firstAccount.getBalance().signum() < 0) {
            throw new BankException("A valid non-negative opening deposit is required.");
        }

        try {
            return transactions.execute("Could not complete customer registration and first-account opening atomically.", conn -> {
                long customerId = insertCustomer(conn, customer);
                firstAccount.setCustomerId(customerId);
                long accountId = insertFirstAccount(conn, firstAccount);
                if (firstAccount.getBalance().signum() > 0) {
                    insertOpeningLedger(conn, accountId, customerId, firstAccount.getBalance());
                }
                firstAccount.setAccountId(accountId);
                return firstAccount;
            });
        } catch (RuntimeException failure) {
            if (isDuplicateKey(failure)) {
                throw new BankException("Registration conflicts with an existing customer identity or generated account/card number. Verify the details; existing customers must sign in before opening another account.", failure);
            }
            throw failure;
        }
    }

    private long insertCustomer(Connection conn, Customer customer) throws SQLException {
        String sql = "INSERT INTO customers (national_id, first_name, last_name, birth_date, phone, email, address, postal_code, occupation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, customer.getNationalId());
            stmt.setString(2, customer.getFirstName());
            stmt.setString(3, customer.getLastName());
            stmt.setDate(4, java.sql.Date.valueOf(customer.getBirthDate()));
            stmt.setString(5, customer.getPhone());
            stmt.setString(6, customer.getEmail());
            stmt.setString(7, customer.getAddress());
            stmt.setString(8, customer.getPostalCode());
            stmt.setString(9, customer.getOccupation());
            if (stmt.executeUpdate() != 1) throw new SQLException("Customer insert did not affect exactly one row.");
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Customer insert returned no generated ID.");
                return keys.getLong(1);
            }
        }
    }

    private long insertFirstAccount(Connection conn, Account account) throws SQLException {
        String sql = "INSERT INTO accounts (customer_id, account_number, card_number, pin_hash, account_type, balance, status) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')";
        for (int attempt = 1; attempt <= 5; attempt++) {
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setLong(1, account.getCustomerId());
                stmt.setString(2, account.getAccountNumber());
                stmt.setString(3, account.getCardNumber());
                stmt.setString(4, account.getPinHash());
                stmt.setString(5, account.getAccountType().name());
                stmt.setBigDecimal(6, account.getBalance());
                if (stmt.executeUpdate() != 1) throw new SQLException("First account insert did not affect exactly one row.");
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Account insert returned no generated ID.");
                    return keys.getLong(1);
                }
            } catch (SQLException collision) {
                if (collision.getErrorCode() != 1062 || attempt == 5) throw collision;
                // A unique account/card-number collision is safe to retry inside this
                // still-open transaction. Customer identity conflicts occur earlier.
                account.setAccountNumber("10" + String.format(java.util.Locale.ROOT, "%010d", RANDOM.nextLong(10_000_000_000L)));
                account.setCardNumber("40" + String.format(java.util.Locale.ROOT, "%010d", RANDOM.nextLong(10_000_000_000L)));
            }
        }
        throw new SQLException("Could not allocate a unique account and card number.");
    }

    private void insertOpeningLedger(Connection conn, long accountId, long customerId, java.math.BigDecimal amount) throws SQLException {
        String sql = "INSERT INTO transactions (account_id, transaction_type, amount, balance_after_transaction) VALUES (?, 'DEPOSIT', ?, ?)";
        long transactionId;
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, accountId);
            stmt.setBigDecimal(2, amount);
            stmt.setBigDecimal(3, amount);
            if (stmt.executeUpdate() != 1) throw new SQLException("Opening ledger insert did not affect exactly one row.");
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Opening ledger insert returned no transaction ID.");
                transactionId = keys.getLong(1);
            }
        }
        FraudOutboxWriter.enqueue(conn, accountId, customerId, TransactionType.DEPOSIT,
                amount, java.math.BigDecimal.ZERO, transactionId, null);
    }

    private boolean isDuplicateKey(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getErrorCode() == 1062) return true;
        }
        return false;
    }
}
