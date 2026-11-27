package com.bank.fraudring;

import com.bank.fraudring.application.FraudRingResultRepository;
import com.bank.fraudring.domain.FraudRingAnalysis;
import com.bank.fraudring.domain.FraudRingScope;
import com.bank.fraudring.infrastructure.JdbcFraudRingResultRepository;
import com.bank.testkit.TestKit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.bank.testkit.TestKit.check;
import static com.bank.testkit.TestKit.checkEquals;
import static com.bank.testkit.TestKit.section;
import static com.bank.testkit.TestKit.finish;

/**
 * Tests the fraud-ring RESULT JDBC adapter WITHOUT MySQL using dynamic-proxy
 * fakes — the same style as JdbcFraudRingSourceRepositoryTest.
 *
 * <p>Verified here: INSERT column binding (incl. NULL handling for SINGLE /
 * lookback), generated-key return, the SELECT round-trip that must preserve
 * every field (the JSON payloads carry Persian text that must survive
 * byte-for-byte), the duplicate-key → port exception translation, and the
 * miss path (empty Optional).
 */
public final class JdbcFraudRingResultRepositoryTest {

    /** Scripted fake connection: INSERT stores bound params, SELECT returns a canned row. */
    static class FakeDb {
        final List<String> sqls = new ArrayList<>();
        final List<List<Object>> paramSets = new ArrayList<>();
        /** Row returned by SELECT; null = no row. */
        Map<String, Object> storedRow;
        /** Rows returned by getGeneratedKeys (the insert's PK). */
        long generatedId = 1;
        /** When set, executeUpdate throws this (duplicate-key simulation). */
        SQLException failOnExecute;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "prepareStatement" -> statement((String) a[0]);
                        case "close" -> null;
                        case "getAutoCommit" -> true;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        PreparedStatement statement(String sql) {
            sqls.add(sql);
            List<Object> params = new ArrayList<>();
            paramSets.add(params);
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, m, a) -> switch (m.getName()) {
                        case "setString", "setInt", "setLong" -> { params.add(a[1]); yield null; }
                        case "setNull" -> { params.add(null); yield null; }
                        case "setTimestamp" -> { params.add(a[1] == null ? null : ((Timestamp) a[1]).toLocalDateTime()); yield null; }
                        case "executeUpdate" -> {
                            if (failOnExecute != null) throw failOnExecute;
                            yield 1;
                        }
                        case "executeQuery" -> {
                            if (failOnExecute != null) throw failOnExecute;
                            yield resultSet(storedRow);
                        }
                        case "getGeneratedKeys" -> singleKey(generatedId);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        static ResultSet resultSet(Map<String, Object> row) {
            Iterator<Map<String, Object>> it = row == null ? List.<Map<String, Object>>of().iterator()
                    : List.of(row).iterator();
            Map<String, Object>[] current = new Map[1];
            InvocationHandler h = (proxy, m, a) -> {
                switch (m.getName()) {
                    case "next": if (it.hasNext()) { current[0] = it.next(); return true; } return false;
                    case "getLong": return ((Number) value(current[0], positional(a[0]))).longValue();
                    case "getInt": return ((Number) value(current[0], positional(a[0]))).intValue();
                    case "getString": return (String) value(current[0], positional(a[0]));
                    case "getObject": return value(current[0], positional(a[0]));
                    case "getTimestamp": {
                        Object v = value(current[0], positional(a[0]));
                        return v == null ? null : Timestamp.valueOf((LocalDateTime) v);
                    }
                    case "close": return null;
                    default: throw new UnsupportedOperationException(m.getName());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, h);
        }

        static ResultSet singleKey(long id) {
            Map<String, Object> key = new HashMap<>();
            key.put("GENERATED_KEY", id);
            return resultSet(key);
        }

        /**
         * Positional access ({@code getLong(1)}) maps to the N-th value of the
         * row's insertion order; label access passes through unchanged.
         */
        static Object positional(Object label) {
            if (label instanceof Integer position) return "__pos__" + position;
            return label;
        }

        static Object value(Map<String, Object> row, Object label) {
            if (label instanceof String key && key.startsWith("__pos__")) {
                int position = Integer.parseInt(key.substring(7));
                List<Object> ordered = new ArrayList<>(row.values());
                if (position < 1 || position > ordered.size()) {
                    throw new IllegalStateException("Fake row has no column at position " + position);
                }
                return ordered.get(position - 1);
            }
            if (!row.containsKey(label)) throw new IllegalStateException("Fake row has no column '" + label + "'");
            return row.get(label);
        }
    }

    /** A full analysis row with Persian payloads, as Phase 3 produces. */
    static FraudRingAnalysis sample() {
        return new FraudRingAnalysis(0L, "a".repeat(64), FraudRingScope.FULL, null, null,
                "{\"phase\":3,\"nodeCount\":3,\"cycleCount\":1}",
                "{\"phase\":3,\"customersScored\":3,\"customers\":[{\"customerId\":1,"
                        + "\"score\":35,\"reasons\":[{\"code\":\"MONEY_CYCLE\","
                        + "\"detail\":\"در ۱ حلقهٔ انتقال پول شرکت دارد\"}]}]}",
                "{\"phase\":3,\"groupsScored\":1,\"groups\":[{\"communityId\":0,\"score\":55,"
                        + "\"reasons\":[{\"title\":\"حلقهٔ کامل پول داخل گروه\"}]}]}",
                "test-admin", LocalDateTime.of(2026, 11, 17, 12, 0));
    }

    public static void main(String[] args) {
        insertBindsAllColumns();
        selectRoundTripsEveryField();
        singleScopeBindsIdAndLookback();
        missReturnsEmpty();
        duplicateKeyBecomesPortException();
        plainSqlErrorBecomesBankException();
        finish("JdbcFraudRingResultRepositoryTest");
    }

    // ─────────────────────────── tests ───────────────────────────

    static void insertBindsAllColumns() {
        section("[1] INSERT binds all nine columns in order");
        FakeDb db = new FakeDb();
        JdbcFraudRingResultRepository repo = new JdbcFraudRingResultRepository(db::connection);
        long id = repo.insert(sample());

        checkEquals("generated id returned", 1L, id);
        checkEquals("one statement executed", 1, db.sqls.size());
        check("INSERT targets fraud_ring_analyses",
                db.sqls.get(0).startsWith("INSERT INTO fraud_ring_analyses"));
        List<Object> bound = db.paramSets.get(0);
        checkEquals("9 columns bound", 9, bound.size());
        checkEquals("idempotency key first", "a".repeat(64), bound.get(0));
        checkEquals("scope type second", "FULL", bound.get(1));
        check("scope customer id NULL for FULL", bound.get(2) == null);
        check("lookback NULL for all-time", bound.get(3) == null);
        checkEquals("graph stats JSON bound", sample().graphStatsJson(), bound.get(4));
        checkEquals("top customers JSON bound", sample().topCustomersJson(), bound.get(5));
        checkEquals("top groups JSON bound", sample().topGroupsJson(), bound.get(6));
        checkEquals("analyst bound", "test-admin", bound.get(7));
        checkEquals("analysed-at bound", LocalDateTime.of(2026, 11, 17, 12, 0), bound.get(8));
    }

    static void selectRoundTripsEveryField() {
        section("[2] SELECT round-trips every field, Persian byte-for-byte");
        FakeDb db = new FakeDb();
        Map<String, Object> row = new HashMap<>();
        row.put("analysis_id", 42L);
        row.put("idempotency_key", "b".repeat(64));
        row.put("scope_type", "SINGLE");
        row.put("scope_customer_id", 7L);
        row.put("lookback_days", 30);
        row.put("graph_stats", "{\"phase\":3}");
        row.put("top_customers", "{\"persian\":\"در ۱ حلقه\"}");
        row.put("top_groups", "{\"persian\":\"حلقهٔ کامل پول\"}");
        row.put("analyzed_by", "ops-admin");
        row.put("analyzed_at", LocalDateTime.of(2026, 11, 16, 9, 30));
        db.storedRow = row;

        JdbcFraudRingResultRepository repo = new JdbcFraudRingResultRepository(db::connection);
        FraudRingAnalysis loaded = repo.findByKey("b".repeat(64)).orElseThrow();

        checkEquals("SELECT targets the unique key",
                "SELECT analysis_id, idempotency_key, scope_type, scope_customer_id, lookback_days, "
                        + "graph_stats, top_customers, top_groups, analyzed_by, analyzed_at "
                        + "FROM fraud_ring_analyses WHERE idempotency_key = ?", db.sqls.get(0));
        checkEquals("key bound on the select", "b".repeat(64), db.paramSets.get(0).get(0));
        checkEquals("analysis id", 42L, loaded.analysisId());
        checkEquals("scope", FraudRingScope.SINGLE, loaded.scope());
        checkEquals("scope customer", 7L, loaded.scopeCustomerId());
        checkEquals("lookback", 30, loaded.lookbackDays().intValue());
        checkEquals("graph stats", "{\"phase\":3}", loaded.graphStatsJson());
        checkEquals("Persian customer payload survives the round-trip",
                "{\"persian\":\"در ۱ حلقه\"}", loaded.topCustomersJson());
        checkEquals("Persian group payload survives the round-trip",
                "{\"persian\":\"حلقهٔ کامل پول\"}", loaded.topGroupsJson());
        checkEquals("analyst", "ops-admin", loaded.analyzedBy());
        checkEquals("timestamp to LocalDateTime", LocalDateTime.of(2026, 11, 16, 9, 30),
                loaded.analyzedAt());
    }

    static void singleScopeBindsIdAndLookback() {
        section("[3] SINGLE + lookback bind non-null columns");
        FakeDb db = new FakeDb();
        FraudRingAnalysis single = new FraudRingAnalysis(0L, "c".repeat(64),
                FraudRingScope.SINGLE, 7L, 30,
                "{}", "{}", "{}", "admin", LocalDateTime.of(2026, 1, 1, 0, 0));
        new JdbcFraudRingResultRepository(db::connection).insert(single);
        List<Object> bound = db.paramSets.get(0);
        checkEquals("scope customer id bound", 7L, bound.get(2));
        checkEquals("lookback bound", 30, bound.get(3));
    }

    static void missReturnsEmpty() {
        section("[4] unknown key misses cleanly");
        FakeDb db = new FakeDb();           // storedRow = null → SELECT returns no row
        JdbcFraudRingResultRepository repo = new JdbcFraudRingResultRepository(db::connection);
        check("empty Optional on a miss", repo.findByKey("f".repeat(64)).isEmpty());
    }

    static void duplicateKeyBecomesPortException() {
        section("[5] duplicate key → the port's exception type");
        FakeDb db = new FakeDb();
        db.failOnExecute = new SQLException("Duplicate entry 'key' for 'uq_fraud_ring_analyses_key'");
        JdbcFraudRingResultRepository repo = new JdbcFraudRingResultRepository(db::connection);
        try {
            repo.insert(sample());
            check("duplicate key translated", false, "no exception");
        } catch (FraudRingResultRepository.DuplicateAnalysisException e) {
            check("duplicate key translated to the port exception", true);
        }
    }

    static void plainSqlErrorBecomesBankException() {
        section("[6] other SQL failures wrap in BankException");
        FakeDb db = new FakeDb();
        db.failOnExecute = new SQLException("connection reset");
        JdbcFraudRingResultRepository repo = new JdbcFraudRingResultRepository(db::connection);
        try {
            repo.insert(sample());
            check("plain failure wrapped", false, "no exception");
        } catch (com.bank.exception.BankException e) {
            check("plain failure wrapped in BankException", true);
        }
        try {
            repo.findByKey("d".repeat(64));
            check("select failure wrapped", false, "no exception");
        } catch (com.bank.exception.BankException e) {
            check("select failure wrapped in BankException", true);
        }
    }
}
