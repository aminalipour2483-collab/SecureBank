package com.bank.dao.impl;

import com.bank.config.DatabaseConnection;
import com.bank.dao.FraudAlertDAO;
import com.bank.model.FraudAlert;
import com.bank.model.FraudAlert.AlertStatus;
import com.bank.model.FraudAlert.RiskLevel;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FraudAlertDAOImpl — JDBC implementation of FraudAlertDAO.
 *                                                     (v3.0 — NEW)
 * Folder: BankManagementSystem/src/com/bank/dao/impl/FraudAlertDAOImpl.java
 *
 * All read queries JOIN customers + accounts so the admin table can show
 * "Alice Johnson" and "100000000001" instead of raw numeric IDs.
 * The JOINs are LEFT JOINs: FKs use ON DELETE SET NULL, so an alert must
 * still be readable after its customer or account has been deleted.
 *
 * Every method borrows one pooled connection and returns it via
 * try-with-resources (Connection.close() = return to the pool).
 */
public class FraudAlertDAOImpl implements FraudAlertDAO {

    // Base SELECT shared by every read method.
    private static final String BASE_SELECT =
            "SELECT fa.*, "
          + "       CONCAT(c.first_name, ' ', c.last_name) AS customer_name, "
          + "       a.account_number "
          + "FROM fraud_alerts fa "
          + "LEFT JOIN customers c ON c.customer_id = fa.customer_id "
          + "LEFT JOIN accounts  a ON a.account_id  = fa.account_id ";

    private Connection getConnection() {
        return DatabaseConnection.getInstance().getConnection();
    }

    // =========================================================================
    // HELPER — map one ResultSet row → FraudAlert object
    // =========================================================================
    private FraudAlert mapRow(ResultSet rs) throws SQLException {
        FraudAlert alert = new FraudAlert();

        alert.setAlertId        (rs.getLong      ("alert_id"));
        long sourceOutboxId = rs.getLong("source_outbox_id");
        alert.setSourceOutboxId(rs.wasNull() ? null : sourceOutboxId);

        // Nullable FK columns → getObject → Long
        long customerId = rs.getLong("customer_id");
        alert.setCustomerId     (rs.wasNull() ? null : customerId);

        long accountId = rs.getLong("account_id");
        alert.setAccountId      (rs.wasNull() ? null : accountId);

        long transactionId = rs.getLong("transaction_id");
        alert.setTransactionId  (rs.wasNull() ? null : transactionId);

        long transferId = rs.getLong("transfer_id");
        alert.setTransferId     (rs.wasNull() ? null : transferId);

        alert.setRuleCode        (rs.getString    ("rule_code"));
        alert.setRuleDescription (rs.getString    ("rule_description"));
        alert.setRiskScore       (rs.getInt       ("risk_score"));
        alert.setRiskLevel       (RiskLevel.from  (rs.getString("risk_level")));
        alert.setDetails         (rs.getString    ("details"));
        alert.setStatus          (AlertStatus.from(rs.getString("status")));

        Timestamp detected = rs.getTimestamp("detected_at");
        if (detected != null) alert.setDetectedAt(detected.toLocalDateTime());

        alert.setReviewedBy      (rs.getString    ("reviewed_by"));

        Timestamp reviewed = rs.getTimestamp("reviewed_at");
        if (reviewed != null) alert.setReviewedAt(reviewed.toLocalDateTime());

        alert.setResolutionNote  (rs.getString    ("resolution_note"));

        // Display-only JOIN columns
        alert.setCustomerName    (rs.getString    ("customer_name"));
        alert.setAccountNumber   (rs.getString    ("account_number"));

        return alert;
    }

    // =========================================================================
    // SAVE (INSERT) — always a fresh PENDING alert
    // =========================================================================
    @Override
    public long save(FraudAlert alert) {
        String sql = "INSERT INTO fraud_alerts "
                   + "(customer_id, account_id, transaction_id, transfer_id, source_outbox_id, "
                   + " rule_code, rule_description, risk_score, risk_level, details, status) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                   + "ON DUPLICATE KEY UPDATE alert_id=LAST_INSERT_ID(alert_id)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setNullableLong(stmt, 1, alert.getCustomerId());
            setNullableLong(stmt, 2, alert.getAccountId());
            setNullableLong(stmt, 3, alert.getTransactionId());
            setNullableLong(stmt, 4, alert.getTransferId());
            setNullableLong(stmt, 5, alert.getSourceOutboxId());
            stmt.setString(6, alert.getRuleCode());
            stmt.setString(7, alert.getRuleDescription());
            stmt.setInt(8, alert.getRiskScore());
            stmt.setString(9, alert.getRiskLevel().name());
            stmt.setString(10, alert.getDetails());
            stmt.setString(11, alert.getStatus().name());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) { if (keys.next()) return keys.getLong(1); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save fraud alert. Run the P6 outbox migration.", e);
        }
        return -1;
    }

    // =========================================================================
    // FIND BY ID
    // =========================================================================
    @Override
    public Optional<FraudAlert> findById(long alertId) {
        String sql = BASE_SELECT + "WHERE fa.alert_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, alertId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[FraudAlertDAO] findById() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find fraud alert by ID", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND ALL  (admin Fraud Management table)
    // =========================================================================
    @Override
    public List<FraudAlert> findAll() {
        String sql = BASE_SELECT + "ORDER BY fa.detected_at DESC";

        List<FraudAlert> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) list.add(mapRow(rs));

        } catch (SQLException e) {
            System.err.println("[FraudAlertDAO] findAll() failed: " + e.getMessage());
            throw new RuntimeException("Failed to load fraud alerts. "
                    + "Did you run FRAUD_DETECTION.sql?", e);
        }
        return list;
    }

    // =========================================================================
    // FIND BY STATUS  (filter combo in the admin panel)
    // =========================================================================
    @Override
    public List<FraudAlert> findByStatus(AlertStatus status) {
        String sql = BASE_SELECT + "WHERE fa.status = ? ORDER BY fa.detected_at DESC";

        List<FraudAlert> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[FraudAlertDAO] findByStatus() failed: " + e.getMessage());
            throw new RuntimeException("Failed to load fraud alerts by status", e);
        }
        return list;
    }

    // =========================================================================
    // FIND BY CUSTOMER
    // =========================================================================
    @Override
    public List<FraudAlert> findByCustomerId(long customerId) {
        String sql = BASE_SELECT + "WHERE fa.customer_id = ? ORDER BY fa.detected_at DESC";

        List<FraudAlert> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, customerId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[FraudAlertDAO] findByCustomerId() failed: " + e.getMessage());
            throw new RuntimeException("Failed to load fraud alerts by customer", e);
        }
        return list;
    }

    // =========================================================================
    // UPDATE REVIEW  (confirm / dismiss a PENDING alert)
    // =========================================================================
    @Override
    public boolean updateReview(long alertId,
                                AlertStatus newStatus,
                                String reviewedBy,
                                String resolutionNote) {
        // The "AND status = 'PENDING'" guard makes the review idempotent:
        // an alert that was already confirmed or dismissed can never be
        // silently reviewed a second time — the method returns false instead.
        String sql = "UPDATE fraud_alerts "
                   + "SET status = ?, reviewed_by = ?, "
                   + "    reviewed_at = NOW(), resolution_note = ? "
                   + "WHERE alert_id = ? AND status = 'PENDING'";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newStatus.name());
            stmt.setString(2, reviewedBy == null ? "admin" : reviewedBy);

            if (resolutionNote != null && !resolutionNote.isBlank()) {
                stmt.setString(3, resolutionNote.trim());
            } else {
                stmt.setNull(3, Types.VARCHAR);
            }

            stmt.setLong(4, alertId);

            return stmt.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("[FraudAlertDAO] updateReview() failed: " + e.getMessage());
            throw new RuntimeException("Failed to review fraud alert", e);
        }
    }

    // =========================================================================
    // PRIVATE UTILITIES
    // =========================================================================

    /** Bind a nullable Long parameter (FK columns are ON DELETE SET NULL). */
    private void setNullableLong(PreparedStatement stmt, int index, Long value)
            throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.BIGINT);
        } else {
            stmt.setLong(index, value);
        }
    }
}
