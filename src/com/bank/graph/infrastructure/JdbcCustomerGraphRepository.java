package com.bank.graph.infrastructure;

import com.bank.config.DatabaseConnection;
import com.bank.exception.BankException;
import com.bank.graph.application.CustomerGraphRepository;
import com.bank.graph.domain.CustomerIdentity;
import com.bank.graph.domain.GraphCriteria;
import com.bank.graph.domain.GraphSourceData;
import com.bank.graph.domain.SharedPhoneMember;
import com.bank.graph.domain.TransferFlow;
import com.bank.graph.domain.TransferRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Read-only JDBC adapter for the customer graph. No schema change is required.
 *
 * <p><b>Bounded work.</b> Transfers are aggregated by the database (one row per ordered customer
 * pair, not one per transfer) and every query has a {@code LIMIT}. One extra row is requested so
 * truncation can be reported instead of silently hidden.
 *
 * <p><b>Consistent snapshot.</b> The graph needs two queries. They run in one transaction so that,
 * under the MySQL default REPEATABLE READ isolation, both see the same committed state; otherwise a
 * transfer or registration committed between them would make the two halves disagree.
 *
 * <p><b>Connection lifecycle.</b> Connections are borrowed from the shared pool and returned with
 * try-with-resources ({@code Connection.close()} returns them to the pool); nothing in this class
 * holds a connection between calls. When this class owns the snapshot transaction, the auto-commit
 * change is undone through {@code DatabaseConnection.restoreAutoCommitOrEvict()}: if the restore
 * fails, the connection is evicted from the pool rather than recycled with a polluted state.
 */
public class JdbcCustomerGraphRepository implements CustomerGraphRepository {

    /** Rows of shared-phone members fetched per allowed edge (a group of 10 yields 10 rows, up to 45 edges). */
    private static final int PHONE_ROWS_PER_EDGE = 2;

    /** Strips common phone formatting so "+1 (202) 555-0101" and "+12025550101" match. */
    static final String NORMALIZED_PHONE =
            "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(phone, ' ', ''), '-', ''), '(', ''), ')', ''), '.', '')";

    static final String FLOWS_SQL_HEAD =
            "SELECT sc.customer_id AS sender_id, sc.first_name AS sender_first, "
            + "sc.last_name AS sender_last, sc.status AS sender_status, "
            + "rc.customer_id AS receiver_id, rc.first_name AS receiver_first, "
            + "rc.last_name AS receiver_last, rc.status AS receiver_status, "
            + "COUNT(*) AS transfer_count, SUM(tf.amount) AS total_amount, "
            + "MAX(tf.created_at) AS last_activity "
            + "FROM transfers tf "
            + "JOIN accounts sa ON sa.account_id = tf.sender_account_id "
            + "JOIN accounts ra ON ra.account_id = tf.receiver_account_id "
            + "JOIN customers sc ON sc.customer_id = sa.customer_id "
            + "JOIN customers rc ON rc.customer_id = ra.customer_id "
            + "WHERE tf.status = 'COMPLETED' AND sa.customer_id <> ra.customer_id ";
    static final String FLOWS_SQL_LOOKBACK = "AND tf.created_at >= NOW() - INTERVAL ? DAY ";
    static final String FLOWS_SQL_TAIL =
            "GROUP BY sc.customer_id, sc.first_name, sc.last_name, sc.status, "
            + "rc.customer_id, rc.first_name, rc.last_name, rc.status "
            + "ORDER BY total_amount DESC, sender_id, receiver_id "
            + "LIMIT ?";

    static final String PHONE_SQL =
            "SELECT n.norm_phone, n.customer_id, n.first_name, n.last_name, n.status "
            + "FROM (SELECT customer_id, first_name, last_name, status, "
            + NORMALIZED_PHONE + " AS norm_phone FROM customers) n "
            + "JOIN (SELECT " + NORMALIZED_PHONE + " AS norm_phone FROM customers "
            + "GROUP BY norm_phone HAVING COUNT(*) > 1) d ON d.norm_phone = n.norm_phone "
            + "WHERE n.norm_phone <> '' "
            + "ORDER BY n.norm_phone, n.customer_id "
            + "LIMIT ?";

    static final String DETAIL_SQL_HEAD =
            "SELECT tf.transfer_id, tf.amount, tf.reference_number, tf.status, tf.created_at, "
            + "sc.customer_id AS sender_id, sc.first_name AS sender_first, "
            + "sc.last_name AS sender_last, sc.status AS sender_status, "
            + "rc.customer_id AS receiver_id, rc.first_name AS receiver_first, "
            + "rc.last_name AS receiver_last, rc.status AS receiver_status "
            + "FROM transfers tf "
            + "JOIN accounts sa ON sa.account_id = tf.sender_account_id "
            + "JOIN accounts ra ON ra.account_id = tf.receiver_account_id "
            + "JOIN customers sc ON sc.customer_id = sa.customer_id "
            + "JOIN customers rc ON rc.customer_id = ra.customer_id "
            + "WHERE ";
    static final String DETAIL_SQL_TAIL =
            " ORDER BY tf.created_at DESC, tf.transfer_id DESC LIMIT ?";

    private final Supplier<Connection> connections;

    /** Production constructor: borrows from the application's connection pool. */
    public JdbcCustomerGraphRepository() {
        this(() -> DatabaseConnection.getInstance().getConnection());
    }

    public JdbcCustomerGraphRepository(Supplier<Connection> connections) {
        this.connections = connections;
    }

    // ------------------------------------------------------------------------------------------
    @Override
    public GraphSourceData loadGraphSource(GraphCriteria criteria) {
        try (Connection conn = connections.get()) {
            boolean ownsTransaction = false;
            boolean autoCommitProbed = false;
            boolean previousAutoCommit = true;
            try {
                previousAutoCommit = conn.getAutoCommit();
                autoCommitProbed = true;
                if (previousAutoCommit) {
                    conn.setAutoCommit(false);        // begin one snapshot for both queries
                    ownsTransaction = true;
                }
                GraphSourceData data = readSnapshot(conn, criteria);
                if (ownsTransaction) conn.commit();   // read-only: commit just ends the snapshot
                return data;
            } catch (SQLException e) {
                if (ownsTransaction) {
                    rollbackQuietly(conn, e);
                } else if (!autoCommitProbed) {
                    // getAutoCommit() itself failed: the connection is unusable. Evict it so the
                    // pool never recycles it (a caller-owned transaction, by contrast, is left
                    // untouched — only the state probe failing means the connection is broken).
                    DatabaseConnection.evictConnection(conn);
                }
                throw new BankException("Could not load the customer graph: " + e.getMessage(), e);
            } finally {
                // End our snapshot before the connection goes back: restore auto-commit, or
                // evict the connection outright when the restore fails.
                if (ownsTransaction) DatabaseConnection.restoreAutoCommitOrEvict(conn, previousAutoCommit);
            }
        } catch (SQLException e) {
            // Only the implicit Connection.close() (return to the pool) can reach here;
            // the snapshot failure path was already wrapped inside.
            throw new BankException("Could not return the connection to the pool: " + e.getMessage(), e);
        }
    }

    private GraphSourceData readSnapshot(Connection conn, GraphCriteria criteria) throws SQLException {
        int flowLimit = criteria.maxEdges();
        List<TransferFlow> flows = queryFlows(conn, criteria, flowLimit + 1);
        boolean flowsTruncated = flows.size() > flowLimit;
        if (flowsTruncated) flows = new ArrayList<>(flows.subList(0, flowLimit));

        List<SharedPhoneMember> members = List.of();
        boolean membersTruncated = false;
        if (criteria.includeSharedPhone()) {
            int memberLimit = criteria.maxEdges() * PHONE_ROWS_PER_EDGE;
            members = queryPhoneMembers(conn, memberLimit + 1);
            membersTruncated = members.size() > memberLimit;
            if (membersTruncated) members = new ArrayList<>(members.subList(0, memberLimit));
        }
        return new GraphSourceData(flows, flowsTruncated, members, membersTruncated);
    }

    private List<TransferFlow> queryFlows(Connection conn, GraphCriteria criteria, int limit) throws SQLException {
        boolean lookback = criteria.lookbackDays() != null;
        String sql = FLOWS_SQL_HEAD + (lookback ? FLOWS_SQL_LOOKBACK : "") + FLOWS_SQL_TAIL;
        List<TransferFlow> flows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int index = 1;
            if (lookback) stmt.setInt(index++, criteria.lookbackDays());
            stmt.setInt(index, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    flows.add(new TransferFlow(
                            identity(rs, "sender"), identity(rs, "receiver"),
                            rs.getLong("transfer_count"), rs.getBigDecimal("total_amount"),
                            toLocalDateTime(rs.getTimestamp("last_activity"))));
                }
            }
        }
        return flows;
    }

    private List<SharedPhoneMember> queryPhoneMembers(Connection conn, int limit) throws SQLException {
        List<SharedPhoneMember> members = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(PHONE_SQL)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    members.add(new SharedPhoneMember(rs.getString("norm_phone"),
                            CustomerIdentity.of(rs.getLong("customer_id"), rs.getString("first_name"),
                                    rs.getString("last_name"), isBanned(rs.getString("status")))));
                }
            }
        }
        return members;
    }

    // ------------------------------------------------------------------------------------------
    @Override
    public List<TransferRecord> findTransfersInvolving(Set<Long> customerIds, int limit) {
        if (customerIds.isEmpty() || limit < 1) return List.of();
        List<Long> ids = customerIds.stream().sorted(Comparator.naturalOrder()).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = DETAIL_SQL_HEAD + "sa.customer_id IN (" + placeholders + ") "
                + "OR ra.customer_id IN (" + placeholders + ")" + DETAIL_SQL_TAIL;

        List<TransferRecord> rows = new ArrayList<>();
        try (Connection conn = connections.get();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int index = 1;
            for (int pass = 0; pass < 2; pass++) {
                for (Long id : ids) stmt.setLong(index++, id);
            }
            stmt.setInt(index, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TransferRecord(rs.getLong("transfer_id"),
                            identity(rs, "sender"), identity(rs, "receiver"),
                            rs.getBigDecimal("amount"), rs.getString("reference_number"),
                            rs.getString("status"), toLocalDateTime(rs.getTimestamp("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw new BankException("Could not load transfers: " + e.getMessage(), e);
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------
    private static CustomerIdentity identity(ResultSet rs, String prefix) throws SQLException {
        return CustomerIdentity.of(rs.getLong(prefix + "_id"), rs.getString(prefix + "_first"),
                rs.getString(prefix + "_last"), isBanned(rs.getString(prefix + "_status")));
    }

    private static boolean isBanned(String status) {
        return "BANNED".equalsIgnoreCase(status);
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static void rollbackQuietly(Connection conn, SQLException original) {
        try {
            conn.rollback();
        } catch (SQLException rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }
}
