package com.bank.fraudring.infrastructure;

import com.bank.config.DatabaseConnection;
import com.bank.exception.BankException;
import com.bank.fraudring.application.FraudRingResultRepository;
import com.bank.fraudring.domain.FraudRingAnalysis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Persists and replays completed fraud-ring analyses in the
 * {@code fraud_ring_analyses} table (FRAUD_RING_DETECTION.sql).
 *
 * <p><b>Idempotency.</b> {@code idempotency_key} is UNIQUE. The insert runs in its
 * own short auto-commit transaction (the read snapshot is already closed), so a
 * duplicate key from a concurrent run surfaces as {@code findAnalysis} replaying
 * the winner's row — same contract as {@code idempotency_requests}.
 *
 * <p><b>Immutable output.</b> No UPDATE/DELETE exists on purpose: an analysis row
 * is a derived snapshot; re-analysing means a new row with a new key (the key is
 * a fingerprint of the canonical request, so identical requests replay, and
 * changed requests get a changed key).
 *
 * <p><b>Port.</b> Implements the application-layer {@link FraudRingResultRepository}
 * port (Phase 3) so the service depends on the interface, never on this class.
 */
public class JdbcFraudRingResultRepository implements FraudRingResultRepository {

    private static final String INSERT_SQL =
            "INSERT INTO fraud_ring_analyses "
            + "(idempotency_key, scope_type, scope_customer_id, lookback_days, "
            + "graph_stats, top_customers, top_groups, analyzed_by, analyzed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_SQL =
            "SELECT analysis_id, idempotency_key, scope_type, scope_customer_id, lookback_days, "
            + "graph_stats, top_customers, top_groups, analyzed_by, analyzed_at "
            + "FROM fraud_ring_analyses WHERE idempotency_key = ?";

    private final Supplier<Connection> connections;

    /** Production constructor: borrows from the application's connection pool. */
    public JdbcFraudRingResultRepository() {
        this(() -> DatabaseConnection.getInstance().getConnection());
    }

    /** Test constructor: any connection source (JDBC fakes). */
    public JdbcFraudRingResultRepository(Supplier<Connection> connections) {
        this.connections = connections;
    }

    /**
     * Stores one completed analysis. Returns the stored row id.
     * A duplicate idempotency key from a concurrent winner is translated into
     * the port's {@link FraudRingResultRepository.DuplicateAnalysisException}
     * so the service can replay instead.
     */
    @Override
    public long insert(FraudRingAnalysis analysis) {
        try (Connection conn = connections.get();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL,
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, analysis.idempotencyKey());
            stmt.setString(2, analysis.scope().name());
            if (analysis.scopeCustomerId() == null) stmt.setNull(3, Types.BIGINT);
            else stmt.setLong(3, analysis.scopeCustomerId());
            if (analysis.lookbackDays() == null) stmt.setNull(4, Types.INTEGER);
            else stmt.setInt(4, analysis.lookbackDays());
            stmt.setString(5, analysis.graphStatsJson());
            stmt.setString(6, analysis.topCustomersJson());
            stmt.setString(7, analysis.topGroupsJson());
            stmt.setString(8, analysis.analyzedBy());
            stmt.setTimestamp(9, Timestamp.valueOf(analysis.analyzedAt()));
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                throw new FraudRingResultRepository.DuplicateAnalysisException(analysis.idempotencyKey(), e);
            }
            throw new BankException("Could not store the fraud-ring analysis: " + e.getMessage(), e);
        }
    }

    /** Loads a stored analysis by its idempotency key (replay path). */
    @Override
    public Optional<FraudRingAnalysis> findByKey(String idempotencyKey) {
        try (Connection conn = connections.get();
             PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
            stmt.setString(1, idempotencyKey);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new FraudRingAnalysis(
                        rs.getLong("analysis_id"),
                        rs.getString("idempotency_key"),
                        com.bank.fraudring.domain.FraudRingScope.valueOf(rs.getString("scope_type")),
                        rs.getObject("scope_customer_id", Long.class),
                        rs.getObject("lookback_days", Integer.class),
                        rs.getString("graph_stats"),
                        rs.getString("top_customers"),
                        rs.getString("top_groups"),
                        rs.getString("analyzed_by"),
                        toLocalDateTime(rs.getTimestamp("analyzed_at"))));
            }
        } catch (SQLException e) {
            throw new BankException("Could not load the fraud-ring analysis: " + e.getMessage(), e);
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        return e.getMessage() != null && e.getMessage().contains("Duplicate entry");
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
