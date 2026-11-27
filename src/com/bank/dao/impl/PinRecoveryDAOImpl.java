package com.bank.dao.impl;

import com.bank.config.DatabaseConnection;
import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.dao.PinRecoveryDAO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** JDBC-backed recovery throttle. Five failures lock the card fingerprint for 15 minutes. */
public final class PinRecoveryDAOImpl implements PinRecoveryDAO {
    private static final int MAX_FAILURES = 5;
    private static final int LOCK_MINUTES = 15;

    private Connection getConnection() {
        return DatabaseConnection.getInstance().getConnection();
    }

    @Override
    public boolean isTemporarilyLocked(String fingerprint) {
        String sql = "SELECT locked_until FROM pin_recovery_attempts WHERE card_fingerprint = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, fingerprint);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return false;
                Timestamp until = rs.getTimestamp("locked_until");
                return until != null && until.toLocalDateTime().isAfter(LocalDateTime.now());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not check PIN-recovery throttling", e);
        }
    }

    @Override
    public int recordFailure(String fingerprint) {
        return JdbcTransactionExecutor.pooled(this::getConnection).execute("Could not record PIN-recovery failure.", conn -> {
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT IGNORE INTO pin_recovery_attempts(card_fingerprint, failed_attempts, locked_until) VALUES (?, 0, NULL)")) {
                insert.setString(1, fingerprint);
                insert.executeUpdate();
            }

            int failures;
            LocalDateTime lockedUntil = null;
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT failed_attempts, locked_until FROM pin_recovery_attempts WHERE card_fingerprint = ? FOR UPDATE")) {
                select.setString(1, fingerprint);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Recovery throttle row disappeared");
                    failures = rs.getInt("failed_attempts");
                    Timestamp ts = rs.getTimestamp("locked_until");
                    if (ts != null) lockedUntil = ts.toLocalDateTime();
                }
            }

            LocalDateTime now = LocalDateTime.now();
            if (lockedUntil != null && lockedUntil.isAfter(now)) {
                return -1;
            }
            // An expired lock starts a fresh five-attempt window.
            failures = (lockedUntil != null) ? 1 : failures + 1;
            LocalDateTime newLock = failures >= MAX_FAILURES ? now.plusMinutes(LOCK_MINUTES) : null;
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE pin_recovery_attempts SET failed_attempts = ?, locked_until = ?, updated_at = CURRENT_TIMESTAMP WHERE card_fingerprint = ?")) {
                update.setInt(1, failures);
                if (newLock == null) update.setNull(2, java.sql.Types.TIMESTAMP);
                else update.setTimestamp(2, Timestamp.valueOf(newLock));
                update.setString(3, fingerprint);
                update.executeUpdate();
            }
            return failures;
        });
    }

    @Override
    public void clear(String fingerprint) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE pin_recovery_attempts SET failed_attempts = 0, locked_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE card_fingerprint = ?")) {
            stmt.setString(1, fingerprint);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not clear PIN-recovery attempts", e);
        }
    }
}
