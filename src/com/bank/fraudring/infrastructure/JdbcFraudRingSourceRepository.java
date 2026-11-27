package com.bank.fraudring.infrastructure;

import com.bank.config.DatabaseConnection;
import com.bank.exception.BankException;
import com.bank.fraudring.application.FraudRingSourceRepository;
import com.bank.fraudring.domain.CustomerRiskFact;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.fraudring.domain.FraudRingFlow;
import com.bank.fraudring.domain.FraudRingScope;
import com.bank.graph.domain.CustomerIdentity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * Read-only JDBC adapter for the fraud-ring analysis — mirrors the connection
 * and snapshot contract of {@code JdbcCustomerGraphRepository} exactly:
 *
 * <ul>
 *   <li><b>Bounded work.</b> Flows are aggregated per ordered customer pair by the
 *       database; every query has a LIMIT with one spare row so truncation is
 *       reported, never hidden.</li>
 *   <li><b>Consistent snapshot.</b> The three queries (flows, phone groups, tenure
 *       facts) run in ONE manual-commit transaction, so under MySQL's default
 *       REPEATABLE READ they all see the same committed state.</li>
 *   <li><b>Connection lifecycle.</b> One connection borrowed from the shared pool
 *       per call, returned with try-with-resources. The auto-commit restore goes
 *       through {@code DatabaseConnection.restoreAutoCommitOrEvict()} so a broken
 *       connection is evicted, never recycled with polluted state. No connection
 *       is held while the graph algorithms run — they execute in memory after
 *       this method returns.</li>
 *   <li><b>No locks on live transactions.</b> Only plain SELECTs (no FOR UPDATE):
 *       the analysis never blocks a deposit or transfer.</li>
 * </ul>
 */
public class JdbcFraudRingSourceRepository implements FraudRingSourceRepository {

    /** Mirrors JdbcCustomerGraphRepository: phone groups of 2..10 members are relationships. */
    static final int MAX_SHARED_GROUP_SIZE = 10;

    /** Phone-member rows fetched per expected edge; a group of 10 yields 10 rows. */
    private static final int PHONE_ROWS_PER_NODE = 5;

    /** Phone formatting normaliser — identical to the customer-graph one. */
    static final String NORMALIZED_PHONE =
            "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(phone, ' ', ''), '-', ''), '(', ''), ')', ''), '.', '')";

    // ── flows: one row per ordered customer pair, both directions present ──────
    static final String FLOWS_SQL_HEAD =
            "SELECT sc.customer_id AS customer_id, sc.first_name AS customer_first, "
            + "sc.last_name AS customer_last, sc.status AS customer_status, "
            + "rc.customer_id AS counterpart_id, rc.first_name AS counterpart_first, "
            + "rc.last_name AS counterpart_last, rc.status AS counterpart_status, "
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
            + "ORDER BY customer_id, counterpart_id LIMIT ?";

    // ── shared-phone groups of 2..MAX size, normalised, member rows ────────────
    static final String PHONE_SQL =
            "SELECT n.norm_phone, n.customer_id, n.first_name, n.last_name, n.status "
            + "FROM (SELECT customer_id, first_name, last_name, status, "
            + NORMALIZED_PHONE + " AS norm_phone FROM customers) n "
            + "JOIN (SELECT " + NORMALIZED_PHONE + " AS norm_phone FROM customers "
            + "GROUP BY norm_phone HAVING COUNT(*) > 1 AND COUNT(*) <= " + MAX_SHARED_GROUP_SIZE + ") d "
            + "ON d.norm_phone = n.norm_phone "
            + "WHERE n.norm_phone <> '' "
            + "ORDER BY n.norm_phone, n.customer_id LIMIT ?";

    // ── tenure facts: newest account age + account count per customer ──────────
    static final String FACTS_SQL =
            "SELECT c.customer_id, c.first_name, c.last_name, c.status, "
            + "COUNT(a.account_id) AS account_count, "
            + "MIN(TIMESTAMPDIFF(DAY, a.created_at, NOW())) AS newest_account_age_days "
            + "FROM customers c "
            + "LEFT JOIN accounts a ON a.customer_id = c.customer_id "
            + "GROUP BY c.customer_id, c.first_name, c.last_name, c.status "
            + "ORDER BY c.customer_id LIMIT ?";

    private final Supplier<Connection> connections;

    /** Production constructor: borrows from the application's connection pool. */
    public JdbcFraudRingSourceRepository() {
        this(() -> DatabaseConnection.getInstance().getConnection());
    }

    /** Test constructor: any connection source (JDBC fakes). */
    public JdbcFraudRingSourceRepository(Supplier<Connection> connections) {
        this.connections = connections;
    }

    // ------------------------------------------------------------------------------------------
    @Override
    public FraudRingSnapshot loadSnapshot(FraudRingCriteria criteria) {
        try (Connection conn = connections.get()) {
            boolean ownsTransaction = false;
            boolean autoCommitProbed = false;
            boolean previousAutoCommit = true;
            try {
                previousAutoCommit = conn.getAutoCommit();
                autoCommitProbed = true;
                if (previousAutoCommit) {
                    conn.setAutoCommit(false);        // one snapshot for all three queries
                    ownsTransaction = true;
                }
                FraudRingSnapshot data = readSnapshot(conn, criteria);
                if (ownsTransaction) conn.commit();  // read-only: commit just ends the snapshot
                return data;
            } catch (SQLException e) {
                if (ownsTransaction) {
                    rollbackQuietly(conn, e);
                } else if (!autoCommitProbed) {
                    DatabaseConnection.evictConnection(conn);   // state probe failed → unusable
                }
                throw new BankException("Could not load the fraud-ring snapshot: " + e.getMessage(), e);
            } finally {
                if (ownsTransaction) DatabaseConnection.restoreAutoCommitOrEvict(conn, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new BankException("Could not return the connection to the pool: " + e.getMessage(), e);
        }
    }

    private FraudRingSnapshot readSnapshot(Connection conn, FraudRingCriteria criteria) throws SQLException {
        int nodeBudget = criteria.maxNodes();

        // 1 ── flows (pair rows bounded by pairs, not nodes: budget × 4 pair rows per node
        //      is generous; a K10 clique needs 90 pair rows per 10 nodes). One spare row
        //      is requested so hitting the budget is REPORTED as truncation, never hidden.
        int flowLimit = nodeBudget * 4;
        List<FraudRingFlow> flows = queryFlows(conn, criteria, flowLimit + 1);
        boolean flowsTruncated = flows.size() > flowLimit;
        if (flowsTruncated) flows = new ArrayList<>(flows.subList(0, flowLimit));

        // 2 ── shared-phone groups
        Map<String, List<CustomerIdentity>> phoneGroups = new LinkedHashMap<>();
        boolean phonesTruncated = false;
        if (criteria.includeSharedPhone()) {
            int phoneLimit = nodeBudget * PHONE_ROWS_PER_NODE + 1;
            List<Object[]> phoneRows = queryPhoneMembers(conn, phoneLimit);
            phonesTruncated = phoneRows.size() > phoneLimit - 1;
            if (phonesTruncated) phoneRows = new ArrayList<>(phoneRows.subList(0, phoneLimit - 1));
            for (Object[] row : phoneRows) {
                phoneGroups.computeIfAbsent((String) row[0], k -> new ArrayList<>())
                        .add((CustomerIdentity) row[1]);
            }
        }

        // 3 ── tenure facts
        int factLimit = nodeBudget + 1;
        Map<Long, CustomerRiskFact> facts = queryFacts(conn, factLimit);
        boolean factsTruncated = facts.size() > factLimit - 1;

        // 4 ── fold the phone groups into the facts: sharedPhoneCount is the number of
        //      normalised phone numbers this customer shares with ≥ 1 other customer.
        //      The SQL facts row cannot know it (it only counts accounts); the group
        //      membership is right here, so the risk rules get their input without a
        //      fourth query.
        if (!phoneGroups.isEmpty()) {
            Map<Long, CustomerRiskFact> enriched = new LinkedHashMap<>(facts);
            for (Map.Entry<String, List<CustomerIdentity>> group : phoneGroups.entrySet()) {
                if (group.getValue().size() < 2) continue;      // not shared after all
                for (CustomerIdentity member : group.getValue()) {
                    CustomerRiskFact current = enriched.getOrDefault(member.id(),
                            CustomerRiskFact.unknown(member.id()));
                    enriched.put(member.id(), new CustomerRiskFact(member.id(),
                            current.accountCount(), current.newestAccountAgeDays(),
                            current.sharedPhoneCount() + 1));
                }
            }
            facts = enriched;
        }

        return new FraudRingSnapshot(flows, flowsTruncated, phoneGroups, phonesTruncated, facts, factsTruncated);
    }

    // ── flow rows ────────────────────────────────────────────────────────────
    private List<FraudRingFlow> queryFlows(Connection conn, FraudRingCriteria criteria, int limit)
            throws SQLException {
        boolean lookback = criteria.lookbackDays() != null;
        String sql = FLOWS_SQL_HEAD
                + (lookback ? FLOWS_SQL_LOOKBACK : "")
                + FLOWS_SQL_TAIL;
        List<FraudRingFlow> flows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int index = 1;
            if (lookback) stmt.setInt(index++, criteria.lookbackDays());
            stmt.setInt(index, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    CustomerIdentity customer = identity(rs, "customer");
                    CustomerIdentity counterpart = identity(rs, "counterpart");
                    long count = rs.getLong("transfer_count");
                    BigDecimal total = rs.getBigDecimal("total_amount");
                    // outgoing = customer is the sender
                    flows.add(new FraudRingFlow(customer, counterpart, true,
                            count, total == null ? BigDecimal.ZERO : total,
                            toLocalDateTime(rs.getTimestamp("last_activity"))));
                }
            }
        }
        return flows;
    }

    private List<Object[]> queryPhoneMembers(Connection conn, int limit) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(PHONE_SQL)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[] {
                            rs.getString("norm_phone"),
                            CustomerIdentity.of(rs.getLong("customer_id"), rs.getString("first_name"),
                                    rs.getString("last_name"), isBanned(rs.getString("status"))) });
                }
            }
        }
        return rows;
    }

    private Map<Long, CustomerRiskFact> queryFacts(Connection conn, int limit) throws SQLException {
        Map<Long, CustomerRiskFact> facts = new LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(FACTS_SQL)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int accountCount = rs.getInt("account_count");
                    Integer age = rs.getObject("newest_account_age_days", Integer.class);
                    facts.put(rs.getLong("customer_id"),
                            new CustomerRiskFact(rs.getLong("customer_id"),
                                    accountCount, age, 0));
                }
            }
        }
        return facts;
    }

    // ── helpers (same style as JdbcCustomerGraphRepository) ───────────────────
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
