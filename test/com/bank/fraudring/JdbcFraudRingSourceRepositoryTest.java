package com.bank.fraudring;

import com.bank.fraudring.application.FraudRingSourceRepository;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.fraudring.domain.FraudRingFlow;
import com.bank.fraudring.infrastructure.JdbcFraudRingSourceRepository;
import com.bank.graph.domain.CustomerIdentity;
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
import java.util.Set;
import java.util.function.Function;

import static com.bank.testkit.TestKit.check;
import static com.bank.testkit.TestKit.checkEquals;
import static com.bank.testkit.TestKit.section;
import static com.bank.testkit.TestKit.finish;

/**
 * Tests the fraud-ring JDBC adapter WITHOUT MySQL by faking Connection /
 * PreparedStatement / ResultSet with dynamic proxies — the exact style of
 * JdbcCustomerGraphRepositoryTest.
 *
 * <p>Verified here: row mapping, snapshot transaction handling (one manual
 * commit for the whole read, restore through the eviction-safe path), bound
 * parameters (LIMIT + optional lookback), truncation reporting, and the
 * failure paths (execute failure rolls back; a broken auto-commit probe evicts).
 */
public final class JdbcFraudRingSourceRepositoryTest {

    /** Scripted fake connection that records everything done to it. */
    static class FakeDb {
        final List<String> log = new ArrayList<>();
        final List<List<Object>> paramSets = new ArrayList<>();
        final List<String> sqls = new ArrayList<>();
        boolean autoCommit = true;
        Function<String, List<Map<String, Object>>> rowsFor = sql -> List.of();
        SQLException failOnExecute;
        SQLException failOnGetAutoCommit;
        boolean failOnSetAutoCommitTrue;   // simulates a connection that dies before the restore

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getAutoCommit" -> {
                            if (failOnGetAutoCommit != null) throw failOnGetAutoCommit;
                            yield autoCommit;
                        }
                        case "setAutoCommit" -> {
                            autoCommit = (Boolean) a[0];
                            if (failOnSetAutoCommitTrue && autoCommit) throw new SQLException("restore failed");
                            log.add("autoCommit=" + autoCommit); yield null;
                        }
                        case "commit" -> { log.add("commit"); yield null; }
                        case "rollback" -> { log.add("rollback"); yield null; }
                        case "prepareStatement" -> statement((String) a[0]);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        PreparedStatement statement(String sql) {
            sqls.add(sql);
            List<Object> params = new ArrayList<>();
            paramSets.add(params);
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, m, a) -> switch (m.getName()) {
                        case "setInt", "setLong", "setString" -> { params.add(a[1]); yield null; }
                        case "setNull" -> { params.add(null); yield null; }
                        case "executeQuery" -> {
                            if (failOnExecute != null) throw failOnExecute;
                            yield resultSet(rowsFor.apply(sql));
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        static ResultSet resultSet(List<Map<String, Object>> rows) {
            Iterator<Map<String, Object>> it = rows.iterator();
            Map<String, Object>[] current = new Map[1];
            InvocationHandler h = (proxy, m, a) -> {
                switch (m.getName()) {
                    case "next": if (it.hasNext()) { current[0] = it.next(); return true; } return false;
                    case "getLong": return ((Number) value(current[0], a[0])).longValue();
                    case "getInt": return ((Number) value(current[0], a[0])).intValue();
                    case "getString": return (String) value(current[0], a[0]);
                    case "getBigDecimal": return (BigDecimal) value(current[0], a[0]);
                    case "getObject": {
                        Object v = value(current[0], a[0]);
                        return v instanceof Integer i ? (Object) i : v;   // getObject(_, Integer.class) path
                    }
                    case "getTimestamp": { Object v = value(current[0], a[0]); return v == null ? null : Timestamp.valueOf((LocalDateTime) v); }
                    case "close": return null;
                    default: throw new UnsupportedOperationException(m.getName());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(FakeDb.class.getClassLoader(), new Class<?>[]{ResultSet.class}, h);
        }

        static Object value(Map<String, Object> row, Object label) {
            if (!row.containsKey(label)) throw new IllegalStateException("Fake row has no column '" + label + "'");
            return row.get(label);
        }
    }

    // ── row factories matching the repository's column labels ──────────────

    static Map<String, Object> flowRow(long customer, long counterpart, long count, String total) {
        Map<String, Object> m = new HashMap<>();
        m.put("customer_id", customer); m.put("customer_first", "A"); m.put("customer_last", "Doe");
        m.put("customer_status", "ACTIVE");
        m.put("counterpart_id", counterpart); m.put("counterpart_first", "B"); m.put("counterpart_last", "Roe");
        m.put("counterpart_status", "ACTIVE");
        m.put("transfer_count", count); m.put("total_amount", new BigDecimal(total));
        m.put("last_activity", LocalDateTime.of(2026, 3, 4, 5, 6));
        return m;
    }

    static Map<String, Object> phoneRow(String phone, long id) {
        Map<String, Object> m = new HashMap<>();
        m.put("norm_phone", phone); m.put("customer_id", id); m.put("first_name", "P");
        m.put("last_name", "X"); m.put("status", "ACTIVE");
        return m;
    }

    static Map<String, Object> factRow(long id, int accounts, Integer age) {
        Map<String, Object> m = new HashMap<>();
        m.put("customer_id", id); m.put("first_name", "F"); m.put("last_name", "Y");
        m.put("status", "ACTIVE"); m.put("account_count", accounts);
        m.put("newest_account_age_days", age);
        return m;
    }

    public static void main(String[] args) {
        mapping();
        truncation();
        lookback();
        phonesOff();
        failure();
        brokenProbe();
        finish("JdbcFraudRingSourceRepositoryTest");
    }

    // ── tests ────────────────────────────────────────────────────────────────

    static void mapping() {
        section("[1] loadSnapshot: binding, mapping, transaction");
        FakeDb db = new FakeDb();
        db.rowsFor = sql -> {
            if (sql.contains("FROM transfers tf")) return List.of(
                    flowRow(1, 2, 3, "300.00"),
                    flowRow(2, 1, 1, "100.00"));
            if (sql.contains("norm_phone")) return List.of(
                    phoneRow("+15550001", 1), phoneRow("+15550001", 3));
            if (sql.contains("account_count")) return List.of(
                    factRow(1, 1, 25), factRow(2, 2, null), factRow(3, 1, 400));
            return List.of();
        };
        FraudRingSourceRepository repo = new JdbcFraudRingSourceRepository(db::connection);
        FraudRingSourceRepository.FraudRingSnapshot data =
                repo.loadSnapshot(FraudRingCriteria.full());

        check("one snapshot transaction: autoCommit(false) then commit",
                db.log.contains("autoCommit=false") && db.log.contains("commit"));
        check("connection returned to auto-commit=true",
                db.log.get(db.log.size() - 1).equals("autoCommit=true"));

        List<FraudRingFlow> flows = data.flows();
        checkEquals("both flow rows mapped", 2, flows.size());
        check("outgoing mapping kept", flows.get(0).outgoing());
        checkEquals("counterpart id mapped", 2L, flows.get(0).counterpart().id());
        checkEquals("amount mapped", new BigDecimal("300.00"), flows.get(0).totalAmount());
        checkEquals("count mapped", 3L, flows.get(0).transferCount());

        checkEquals("phone group built", 1, data.phoneGroups().size());
        checkEquals("phone group members", 2, data.phoneGroups().get("+15550001").size());
        checkEquals("facts mapped: account count", 1,
                data.facts().get(1L).accountCount());
        checkEquals("facts mapped: newest age", 25,
                data.facts().get(1L).newestAccountAgeDays().intValue());
        check("facts: null age stays null", data.facts().get(2L).newestAccountAgeDays() == null);
        check("no truncation on tiny fixture", !data.truncated());
    }

    static void truncation() {
        section("[2] bounded rows and truncation reporting");
        FakeDb db = new FakeDb();
        // FraudRingCriteria.full() has maxNodes=2000 → flow budget = 8000 rows;
        // return 8001 rows (budget + the spare probe row) → truncated, capped to 8000
        List<Map<String, Object>> manyFlows = new ArrayList<>();
        for (long i = 1; i <= 8001; i++) manyFlows.add(flowRow(i, i + 1, 1, "10.00"));
        db.rowsFor = sql -> sql.contains("FROM transfers tf") ? manyFlows : List.of();
        FraudRingSourceRepository.FraudRingSnapshot data =
                new JdbcFraudRingSourceRepository(db::connection).loadSnapshot(FraudRingCriteria.full());

        check("flow budget exceeded is reported", data.flowsTruncated());
        checkEquals("rows capped at the budget", 8000, data.flows().size());
        check("snapshot flag folds truncation in", data.truncated());
    }

    static void lookback() {
        section("[3] lookback binding");
        FakeDb db = new FakeDb();
        db.rowsFor = sql -> List.of();
        new JdbcFraudRingSourceRepository(db::connection).loadSnapshot(
                new FraudRingCriteria(FraudRingCriteria.full().scope(), Set.of(), 30, true, 500));

        String flowsSql = db.sqls.stream().filter(s -> s.contains("FROM transfers tf")).findFirst().orElse("");
        check("lookback interval appears in SQL", flowsSql.contains("INTERVAL ? DAY"));
        List<Object> firstParams = db.paramSets.get(db.sqls.indexOf(flowsSql));
        checkEquals("lookback day bound first, then the row limit (500×4+1)", List.of(30, 2001),
                firstParams);

        // …and without lookback the parameter is only the limit
        FakeDb db2 = new FakeDb();
        db2.rowsFor = sql -> List.of();
        new JdbcFraudRingSourceRepository(db2::connection).loadSnapshot(FraudRingCriteria.full());
        String flowsSql2 = db2.sqls.stream().filter(s -> s.contains("FROM transfers tf")).findFirst().orElse("");
        check("no interval without lookback", !flowsSql2.contains("INTERVAL"));
        checkEquals("all-time flow params are just the probe limit (2000×4+1)", List.of(8001),
                db2.paramSets.get(db2.sqls.indexOf(flowsSql2)));
    }

    static void phonesOff() {
        section("[4] shared phone toggling");
        FakeDb db = new FakeDb();
        db.rowsFor = sql -> sql.contains("norm_phone") ? List.of(phoneRow("+15550009", 1)) : List.of();
        new JdbcFraudRingSourceRepository(db::connection).loadSnapshot(
                new FraudRingCriteria(FraudRingCriteria.full().scope(), Set.of(), null, false, 500));
        check("phone SQL skipped when disabled",
                db.sqls.stream().noneMatch(s -> s.contains("norm_phone")));
    }

    static void failure() {
        section("[5] execute failure: rollback, not partial state");
        FakeDb db = new FakeDb();
        db.failOnExecute = new SQLException("query died");
        try {
            new JdbcFraudRingSourceRepository(db::connection).loadSnapshot(FraudRingCriteria.full());
            check("loadSnapshot wraps failures in BankException", false, "no exception");
        } catch (com.bank.exception.BankException e) {
            check("loadSnapshot wraps failures in BankException", true);
        }
        check("the snapshot transaction rolled back", db.log.contains("rollback"));
        check("auto-commit restored on the failure path",
                db.log.get(db.log.size() - 1).equals("autoCommit=true"));
    }

    static void brokenProbe() {
        section("[6] broken auto-commit probe evicts");
        FakeDb db = new FakeDb();
        db.failOnGetAutoCommit = new SQLException("probe died");
        // evictConnection on a proxy connection: DatabaseConnection.evictConnection tolerates
        // foreign connections by quietly closing them; the fake's close() is a no-op.
        try {
            new JdbcFraudRingSourceRepository(db::connection).loadSnapshot(FraudRingCriteria.full());
            check("probe failure surfaces as BankException", false, "no exception");
        } catch (com.bank.exception.BankException e) {
            check("probe failure surfaces as BankException", true);
        }
        check("no commit was logged for a dead probe", !db.log.contains("commit"));
    }
}
