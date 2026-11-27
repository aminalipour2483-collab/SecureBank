package com.bank.dao.impl;

import com.bank.config.DatabaseConnection;
import com.bank.dao.TransactionDAO;
import com.bank.model.Transaction;
import com.bank.model.Transaction.TransactionType;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * TransactionDAOImpl — JDBC implementation of TransactionDAO.
 *
 * Folder: BankManagementSystem/src/com/bank/dao/impl/TransactionDAOImpl.java
 *
 * CONNECTION LIFECYCLE (connection-pool era): every method borrows one pooled
 * connection and returns it via try-with-resources. The ledger INSERT in
 * save() commits immediately because the borrowed connection is in its
 * default auto-commit state — financial flows that need several writes to be
 * atomic (deposits, withdrawals, transfers) manage their own transaction on a
 * single connection inside AccountDAOImpl / TransferDAOImpl instead of calling
 * this class.
 */
public class TransactionDAOImpl implements TransactionDAO {

    private Connection getConnection() {
        return DatabaseConnection.getInstance().getConnection();
    }

    // =========================================================================
    // HELPER — map one ResultSet row → Transaction object
    // =========================================================================
    private Transaction mapRow(ResultSet rs) throws SQLException {
        Transaction t = new Transaction();
        t.setTransactionId          (rs.getLong      ("transaction_id"));
        t.setAccountId              (rs.getLong      ("account_id"));
        t.setTransactionType        (TransactionType.from(rs.getString("transaction_type")));
        t.setAmount                 (rs.getBigDecimal("amount"));
        t.setBalanceAfterTransaction(rs.getBigDecimal("balance_after_transaction"));

        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) t.setCreatedAt(ts.toLocalDateTime());

        return t;
    }

    // =========================================================================
    // SAVE (INSERT)
    // The borrowed connection is in the pool's default auto-commit state, so
    // this single INSERT commits on its own. Multi-write atomic flows live in
    // AccountDAOImpl / TransferDAOImpl, which own their transaction.
    // =========================================================================
    @Override
    public long save(Transaction transaction) {
        String sql = "INSERT INTO transactions "
                   + "(account_id, transaction_type, amount, balance_after_transaction) "
                   + "VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setLong      (1, transaction.getAccountId());
            stmt.setString    (2, transaction.getTransactionType().name());
            stmt.setBigDecimal(3, transaction.getAmount());
            stmt.setBigDecimal(4, transaction.getBalanceAfterTransaction());

            int affected = stmt.executeUpdate();
            if (affected == 0) return -1;

            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }

        } catch (SQLException e) {
            System.err.println("[TransactionDAO] save() failed: " + e.getMessage());
            throw new RuntimeException("Failed to save transaction", e);
        }
        return -1;
    }

    // =========================================================================
    // FIND BY ID
    // =========================================================================
    @Override
    public Optional<Transaction> findById(long transactionId) {
        String sql = "SELECT * FROM transactions WHERE transaction_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, transactionId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[TransactionDAO] findById() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find transaction by ID", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND BY ACCOUNT ID  (full history, newest first)
    // =========================================================================
    @Override
    public List<Transaction> findByAccountId(long accountId) {
        String sql = "SELECT * FROM transactions "
                   + "WHERE account_id = ? "
                   + "ORDER BY created_at DESC";

        List<Transaction> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[TransactionDAO] findByAccountId() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find transactions by account ID", e);
        }
        return list;
    }

    // =========================================================================
    // FIND RECENT BY ACCOUNT ID  (mini-statement — last N rows)
    // =========================================================================
    @Override
    public List<Transaction> findRecentByAccountId(long accountId, int limit) {
        // LIMIT ? requires setInt — always parameterised, never string-concatenated.
        String sql = "SELECT * FROM transactions "
                   + "WHERE account_id = ? "
                   + "ORDER BY created_at DESC "
                   + "LIMIT ?";

        List<Transaction> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);
            stmt.setInt (2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[TransactionDAO] findRecentByAccountId() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find recent transactions", e);
        }
        return list;
    }

    @Override
    public List<Transaction> findRecentByAccountIdBefore(long accountId, int limit, java.time.LocalDateTime occurredAt) {
        String sql = "SELECT * FROM transactions WHERE account_id = ? AND created_at <= ? ORDER BY created_at DESC, transaction_id DESC LIMIT ?";
        List<Transaction> list = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId);
            stmt.setTimestamp(2, Timestamp.valueOf(occurredAt));
            stmt.setInt(3, limit);
            try (ResultSet rs = stmt.executeQuery()) { while (rs.next()) list.add(mapRow(rs)); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load transaction history at event time", e);
        }
        return list;
    }

    @Override public List<Transaction> findRecentBeforeEvent(long accountId, int limit, java.time.LocalDateTime time, long eventId) {
        String sql = "SELECT * FROM transactions WHERE account_id = ? AND created_at <= ? AND transaction_id < ? ORDER BY created_at DESC, transaction_id DESC LIMIT ?";
        List<Transaction> list = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, accountId); stmt.setTimestamp(2, Timestamp.valueOf(time));
            stmt.setLong(3, eventId); stmt.setInt(4, limit);
            try (ResultSet rs = stmt.executeQuery()) { while (rs.next()) list.add(mapRow(rs)); }
        } catch (SQLException e) { throw new RuntimeException("Failed to load transaction history before event", e); }
        return list;
    }


    // =========================================================================
    // FIND ALL  (admin use only)
    // =========================================================================
    @Override
    public List<Transaction> findAll() {
        String sql = "SELECT * FROM transactions ORDER BY created_at DESC";
        List<Transaction> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) list.add(mapRow(rs));

        } catch (SQLException e) {
            System.err.println("[TransactionDAO] findAll() failed: " + e.getMessage());
            throw new RuntimeException("Failed to retrieve all transactions", e);
        }
        return list;
    }
}
