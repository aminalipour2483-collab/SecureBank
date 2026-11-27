package com.bank.fraud.outbox;

import com.bank.config.DatabaseConnection;
import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.model.Transaction.TransactionType;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.service.FraudDetectionService.FraudAssessment;
import com.bank.service.FraudDetectionService.TriggeredRule;
import java.sql.*;
import java.util.Optional;
import java.util.StringJoiner;

/** MySQL 8 repository with SKIP LOCKED claiming and durable attempt history. */
public final class JdbcFraudOutboxRepository implements FraudOutboxRepository {
    private final JdbcTransactionExecutor transactions;
    public JdbcFraudOutboxRepository() { this(JdbcTransactionExecutor.pooled(() -> DatabaseConnection.getInstance().getConnection())); }
    JdbcFraudOutboxRepository(JdbcTransactionExecutor transactions) { this.transactions = java.util.Objects.requireNonNull(transactions); }
    private Connection connection() { return DatabaseConnection.getInstance().getConnection(); }
    private static final String CLAIM_FENCE = " WHERE outbox_id=? AND retry_cycle=? AND attempt_count=? AND status='PROCESSING' AND locked_at >= CURRENT_TIMESTAMP(6) - INTERVAL 5 MINUTE";
    private static void bindFence(PreparedStatement ps, int first, FraudOutboxEvent event) throws SQLException {
        ps.setLong(first,event.outboxId()); ps.setInt(first+1,event.retryCycle()); ps.setInt(first+2,event.attemptCount());
    }
    private static void requireOne(int changed, String message) throws SQLException {
        if (changed != 1) throw new SQLException(message);
    }

    @Override public Optional<FraudOutboxEvent> claimNext() {
        return transactions.execute("Could not claim fraud outbox event.", c -> {
            String sql = "SELECT * FROM fraud_outbox WHERE ((status IN ('PENDING','RETRY_WAIT') AND attempt_count < 3 AND available_at <= CURRENT_TIMESTAMP(6)) OR (status='PROCESSING' AND (locked_at IS NULL OR locked_at < CURRENT_TIMESTAMP(6) - INTERVAL 5 MINUTE))) ORDER BY available_at, outbox_id LIMIT 1 FOR UPDATE SKIP LOCKED";
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long id = rs.getLong("outbox_id");
                int previousAttempts = rs.getInt("attempt_count");
                int cycle = rs.getInt("retry_cycle");
                String previousStatus = rs.getString("status");
                if ("PROCESSING".equals(previousStatus) && previousAttempts > 0) {
                    try (PreparedStatement stale = c.prepareStatement("UPDATE fraud_outbox_attempts SET status='FAILED',error_message='Worker lease expired before completion.',finished_at=CURRENT_TIMESTAMP(6) WHERE outbox_id=? AND retry_cycle=? AND attempt_no=? AND status='PROCESSING'")) {
                        stale.setLong(1,id); stale.setInt(2,cycle); stale.setInt(3,previousAttempts);
                        requireOne(stale.executeUpdate(), "Missing active attempt history during lease recovery.");
                    }
                }
                if (previousAttempts >= 3) {
                    try (PreparedStatement exhausted = c.prepareStatement("UPDATE fraud_outbox SET status='FAILED',locked_at=NULL,last_error='Final worker lease expired; manual retry required.',updated_at=CURRENT_TIMESTAMP(6) WHERE outbox_id=?")) {
                        exhausted.setLong(1,id); requireOne(exhausted.executeUpdate(), "Missing expired event.");
                    }
                    return Optional.empty();
                }
                int attempts = previousAttempts + 1;
                FraudOutboxEvent event = new FraudOutboxEvent(id, rs.getLong("account_id"),
                        rs.getLong("customer_id"), TransactionType.valueOf(rs.getString("transaction_type")),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("balance_before"),
                        rs.getLong("transaction_id"), nullableLong(rs, "transfer_id"),
                        rs.getTimestamp("occurred_at").toLocalDateTime(), attempts, cycle);
                try (PreparedStatement update = c.prepareStatement("UPDATE fraud_outbox SET status='PROCESSING', attempt_count=?, locked_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6) WHERE outbox_id=?")) {
                    update.setInt(1, attempts); update.setLong(2, id); requireOne(update.executeUpdate(), "Missing claimed event.");
                }
                try (PreparedStatement attempt = c.prepareStatement("INSERT INTO fraud_outbox_attempts (outbox_id,retry_cycle,attempt_no,status,started_at) VALUES (?,?,?,'PROCESSING',CURRENT_TIMESTAMP(6))")) {
                    attempt.setLong(1,id); attempt.setInt(2,cycle); attempt.setInt(3,attempts); requireOne(attempt.executeUpdate(), "Attempt insert failed.");
                }
                return Optional.of(event);
            }
        });
    }

    @Override public void complete(FraudOutboxEvent event, FraudAssessment assessment) {
        transactions.execute("Could not persist fraud analysis result.", c -> {
            // Fence first: an old worker cannot publish any result, alert or history.
            try (PreparedStatement ps = c.prepareStatement("UPDATE fraud_outbox SET status='COMPLETED',locked_at=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP(6)" + CLAIM_FENCE)) {
                bindFence(ps,1,event); if (ps.executeUpdate() == 0) return null;
            }
            StringJoiner codes = new StringJoiner("+");
            StringBuilder details = new StringBuilder();
            for (TriggeredRule rule : assessment.getRules()) {
                codes.add(rule.getCode());
                if (details.length() > 0) details.append('\n');
                details.append(rule.getCode()).append(" (+").append(rule.getPoints()).append("): ").append(rule.getDetails());
            }
            // Completed events cannot be retried: insert once, without UPDATE privilege.
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO fraud_event_results (outbox_id,risk_score,risk_level,rule_codes,details,evaluated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(6))")) {
                ps.setLong(1,event.outboxId()); ps.setInt(2,assessment.getTotalScore()); ps.setString(3,assessment.getLevel().name());
                ps.setString(4,codes.toString()); ps.setString(5,details.toString()); requireOne(ps.executeUpdate(), "Result insert failed.");
            }
            publishAlert(c,event,assessment,codes.toString(),details.toString());
            try (PreparedStatement ps = c.prepareStatement("UPDATE fraud_outbox_attempts SET status='COMPLETED',finished_at=CURRENT_TIMESTAMP(6) WHERE outbox_id=? AND retry_cycle=? AND attempt_no=? AND status='PROCESSING'")) {
                bindFence(ps,1,event); requireOne(ps.executeUpdate(), "Active attempt history missing.");
            }
            return null;
        });
    }

    @Override public void fail(FraudOutboxEvent event, Throwable failure) {
        String message = failure == null ? "Unknown fraud analysis failure" : failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        if (message.length() > 2000) message = message.substring(0,2000);
        final String error = message;
        transactions.execute("Could not record fraud outbox failure.", c -> {
            boolean exhausted = event.attemptCount() >= 3;
            int delay = event.attemptCount() == 1 ? 1 : 5;
            try (PreparedStatement ps = c.prepareStatement("UPDATE fraud_outbox SET status=?,available_at=CASE WHEN ? THEN available_at ELSE CURRENT_TIMESTAMP(6) + INTERVAL ? SECOND END,locked_at=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP(6)" + CLAIM_FENCE)) {
                ps.setString(1,exhausted?"FAILED":"RETRY_WAIT"); ps.setBoolean(2,exhausted); ps.setInt(3,delay); ps.setString(4,error); bindFence(ps,5,event);
                if (ps.executeUpdate() == 0) return null;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE fraud_outbox_attempts SET status='FAILED',error_message=?,finished_at=CURRENT_TIMESTAMP(6) WHERE outbox_id=? AND retry_cycle=? AND attempt_no=? AND status='PROCESSING'")) {
                ps.setString(1,error); bindFence(ps,2,event); requireOne(ps.executeUpdate(), "Active attempt history missing.");
            }
            return null;
        });
    }

    /** Publish only under the fenced event lock in the result transaction. */
    private static void publishAlert(Connection c, FraudOutboxEvent event, FraudAssessment assessment,
                                     String codes, String details) throws SQLException {
        boolean suspicious = assessment.getLevel() != com.bank.model.FraudAlert.RiskLevel.LOW;
        // Older releases could commit an alert without its result. Preserve its review.
        try (PreparedStatement ps = c.prepareStatement("SELECT risk_score,risk_level,rule_code FROM fraud_alerts WHERE source_outbox_id=? FOR UPDATE")) {
            ps.setLong(1,event.outboxId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    if (!suspicious || rs.getInt("risk_score") != assessment.getTotalScore()
                            || !assessment.getLevel().name().equals(rs.getString("risk_level"))
                            || !codes.equals(rs.getString("rule_code"))) {
                        throw new IllegalStateException("Legacy alert differs from new assessment; administrator reconciliation required.");
                    }
                    return;
                }
            }
        }
        if (!suspicious) return;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fraud_alerts (customer_id,account_id,transaction_id,transfer_id,source_outbox_id,rule_code,rule_description,risk_score,risk_level,details,status) VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING')")) {
            ps.setLong(1,event.customerId()); ps.setLong(2,event.accountId()); ps.setLong(3,event.transactionId());
            if (event.transferId() == null) ps.setNull(4,Types.BIGINT); else ps.setLong(4,event.transferId());
            ps.setLong(5,event.outboxId()); ps.setString(6,codes); ps.setString(7,assessment.getRules().size()+" fraud rule(s) triggered");
            ps.setInt(8,assessment.getTotalScore()); ps.setString(9,assessment.getLevel().name()); ps.setString(10,details);
            requireOne(ps.executeUpdate(), "Alert insert failed.");
        }
    }

    @Override public void retryFailed(AdminPrincipal admin, long outboxId) {
        AdminGuard.require(admin);
        transactions.execute("Could not schedule manual fraud retry.", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE fraud_outbox SET status='PENDING',attempt_count=0,retry_cycle=retry_cycle+1,available_at=CURRENT_TIMESTAMP(6),locked_at=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE outbox_id=? AND status='FAILED'")) {
                ps.setLong(1,outboxId);
                if (ps.executeUpdate()!=1) throw new IllegalStateException("Only FAILED outbox events can be retried.");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO fraud_outbox_retry_events (outbox_id,admin_username,retry_cycle,created_at) SELECT outbox_id,?,retry_cycle,CURRENT_TIMESTAMP(6) FROM fraud_outbox WHERE outbox_id=?")) {
                ps.setString(1,admin.username()); ps.setLong(2,outboxId); ps.executeUpdate();
            }
            return null;
        });
    }

    /** Bounded read-only administrative view including evaluations without alerts. */
    public java.util.List<FraudEventSummary> recentEvents(AdminPrincipal admin, boolean failedOnly) {
        AdminGuard.require(admin);
        String sql = "SELECT o.outbox_id,o.account_id,o.transaction_type,o.status,o.attempt_count,o.retry_cycle,"
                + "r.risk_score,r.risk_level,r.rule_codes,r.details,o.last_error FROM fraud_outbox o "
                + "LEFT JOIN fraud_event_results r ON r.outbox_id=o.outbox_id "
                + (failedOnly ? "WHERE o.status='FAILED' " : "") + "ORDER BY o.outbox_id DESC LIMIT 200";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            java.util.List<FraudEventSummary> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                int score = rs.getInt("risk_score"); Integer nullableScore = rs.wasNull() ? null : score;
                rows.add(new FraudEventSummary(rs.getLong("outbox_id"), rs.getLong("account_id"), rs.getString("transaction_type"),
                        rs.getString("status"), rs.getInt("attempt_count"), rs.getInt("retry_cycle"), nullableScore,
                        rs.getString("risk_level"), rs.getString("rule_codes"), rs.getString("details"), rs.getString("last_error")));
            }
            return java.util.List.copyOf(rows);
        } catch (SQLException ex) { throw new com.bank.exception.BankException("Could not load fraud event results.", ex); }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException { long v=rs.getLong(column); return rs.wasNull()?null:v; }
}
