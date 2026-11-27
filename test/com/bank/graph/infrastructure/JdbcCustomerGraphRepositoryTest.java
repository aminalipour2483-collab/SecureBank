package com.bank.graph.infrastructure;

import com.bank.exception.BankException;
import com.bank.graph.domain.GraphCriteria;
import com.bank.graph.domain.GraphSourceData;
import com.bank.graph.domain.TransferRecord;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

import static com.bank.testkit.TestKit.*;

/**
 * Tests the JDBC adapter WITHOUT MySQL by faking Connection/PreparedStatement/ResultSet with
 * dynamic proxies. Verifies parameter binding, column mapping, truncation and transaction handling.
 * It cannot prove the SQL is accepted by MySQL — see the optional SQL dump for a separate check.
 */
public class JdbcCustomerGraphRepositoryTest {

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

    static Map<String, Object> flowRow(long s, String sFirst, String sStatus, long r, String rFirst, String rStatus,
                                       long n, String total) {
        Map<String, Object> m = new HashMap<>();
        m.put("sender_id", s); m.put("sender_first", sFirst); m.put("sender_last", "Doe"); m.put("sender_status", sStatus);
        m.put("receiver_id", r); m.put("receiver_first", rFirst); m.put("receiver_last", "Roe"); m.put("receiver_status", rStatus);
        m.put("transfer_count", n); m.put("total_amount", new BigDecimal(total));
        m.put("last_activity", LocalDateTime.of(2026, 3, 4, 5, 6));
        return m;
    }

    static Map<String, Object> phoneRow(String phone, long id, String first, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("norm_phone", phone); m.put("customer_id", id); m.put("first_name", first);
        m.put("last_name", "X"); m.put("status", status);
        return m;
    }

    public static void main(String[] args) throws IOException {
        mapping();
        truncation();
        failure();
        details();
        brokenProbe();
        brokenRestore();
        dumpSql(args);
        finish("JdbcCustomerGraphRepositoryTest");
    }

    static void mapping() {
        section("[1] loadGraphSource: binding, mapping, transaction");
        FakeDb db = new FakeDb();
        db.rowsFor = sql -> sql.contains("FROM transfers")
                ? List.of(flowRow(1, "Ann", "ACTIVE", 2, "Bob", "BANNED", 3, "300.00"))
                : List.of(phoneRow("+12025550101", 1, "Ann", "ACTIVE"), phoneRow("+12025550101", 2, "Bob", "BANNED"));
        JdbcCustomerGraphRepository repo = new JdbcCustomerGraphRepository(db::connection);

        GraphSourceData data = repo.loadGraphSource(new GraphCriteria(30, true, 50));
        checkEquals("two statements: flows then phones", 2, db.sqls.size());
        check("flows SQL aggregates COMPLETED transfers between different customers",
                db.sqls.get(0).contains("tf.status = 'COMPLETED'") && db.sqls.get(0).contains("sa.customer_id <> ra.customer_id")
                        && db.sqls.get(0).contains("GROUP BY"));
        check("flows SQL is time-bounded by the database clock", db.sqls.get(0).contains("NOW() - INTERVAL ? DAY"));
        checkEquals("flows params: lookback days, then LIMIT maxEdges+1", List.of(30, 51), db.paramSets.get(0));
        checkEquals("phone params: LIMIT maxEdges*2+1", List.of(101), db.paramSets.get(1));
        checkEquals("one flow mapped", 1, data.flows().size());
        checkEquals("sender name built from first+last", "Ann Doe", data.flows().get(0).sender().displayName());
        check("receiver BANNED status mapped", data.flows().get(0).receiver().banned());
        check("sender ACTIVE status mapped", !data.flows().get(0).sender().banned());
        checkEquals("amount mapped", new BigDecimal("300.00"), data.flows().get(0).totalAmount());
        checkEquals("count mapped", 3L, data.flows().get(0).transferCount());
        checkEquals("two phone members mapped", 2, data.phoneMembers().size());
        check("no truncation flagged under the limit", !data.flowsTruncated() && !data.phoneMembersTruncated());
        checkEquals("one snapshot transaction, ended by commit, autoCommit restored",
                List.of("autoCommit=false", "commit", "autoCommit=true"), db.log);

        FakeDb db2 = new FakeDb();
        new JdbcCustomerGraphRepository(db2::connection).loadGraphSource(new GraphCriteria(null, false, 10));
        checkEquals("no lookback → single param (LIMIT) and no INTERVAL clause", List.of(11), db2.paramSets.get(0));
        check("no lookback → clause absent", !db2.sqls.get(0).contains("INTERVAL"));
        checkEquals("phones disabled → phone query not run", 1, db2.sqls.size());

        FakeDb db3 = new FakeDb();
        db3.autoCommit = false;
        new JdbcCustomerGraphRepository(db3::connection).loadGraphSource(new GraphCriteria(null, false, 10));
        checkEquals("caller-owned transaction is left untouched", List.of(), db3.log);
    }

    static void truncation() {
        section("[2] loadGraphSource: bounded results are reported as truncated");
        FakeDb db = new FakeDb();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(flowRow(i + 1, "A", "ACTIVE", i + 100, "B", "ACTIVE", 1, "1.00"));
        db.rowsFor = sql -> sql.contains("FROM transfers") ? rows : List.of();
        GraphSourceData data = new JdbcCustomerGraphRepository(db::connection)
                .loadGraphSource(new GraphCriteria(null, false, 3));
        check("limit+1 rows → truncated", data.flowsTruncated());
        checkEquals("extra row discarded", 3, data.flows().size());
    }

    static void failure() {
        section("[3] loadGraphSource: failure rolls back and is wrapped");
        FakeDb db = new FakeDb();
        db.failOnExecute = new SQLException("boom");
        try {
            new JdbcCustomerGraphRepository(db::connection).loadGraphSource(GraphCriteria.defaults());
            check("exception thrown", false);
        } catch (BankException e) {
            check("wrapped in BankException with cause", e.getCause() == db.failOnExecute && e.getMessage().contains("boom"));
        }
        checkEquals("rolled back and autoCommit restored", List.of("autoCommit=false", "rollback", "autoCommit=true"), db.log);
    }

    static void details() {
        section("[4] findTransfersInvolving");
        FakeDb db = new FakeDb();
        Map<String, Object> row = new HashMap<>(flowRow(7, "Ann", "ACTIVE", 8, "Bob", "ACTIVE", 1, "1"));
        row.put("transfer_id", 99L); row.put("amount", new BigDecimal("12.50"));
        row.put("reference_number", "TXF-1"); row.put("status", "PENDING");
        row.put("created_at", LocalDateTime.of(2026, 1, 2, 3, 4));
        db.rowsFor = sql -> List.of(row);
        JdbcCustomerGraphRepository repo = new JdbcCustomerGraphRepository(db::connection);

        List<TransferRecord> out = repo.findTransfersInvolving(new LinkedHashSet<>(List.of(8L, 7L)), 25);
        checkEquals("two IN lists × 2 ids + LIMIT", 2 * 2 + 1, db.paramSets.get(0).size());
        checkEquals("ids bound in sorted order, repeated for both IN lists, LIMIT last",
                List.of(7L, 8L, 7L, 8L, 25), db.paramSets.get(0));
        check("placeholders generated per id", db.sqls.get(0).contains("IN (?,?)"));
        check("result is newest-first and limited in SQL", db.sqls.get(0).contains("ORDER BY tf.created_at DESC")
                && db.sqls.get(0).endsWith("LIMIT ?"));
        checkEquals("row mapped", "TXF-1", out.get(0).referenceNumber());
        checkEquals("status preserved (all statuses are shown)", "PENDING", out.get(0).status());
        check("non-completed transfer reports isCompleted=false", !out.get(0).isCompleted());
        check("empty id set → no query", repo.findTransfersInvolving(Set.of(), 10).isEmpty() && db.sqls.size() == 1);
    }

    static void brokenProbe() {
        section("[5] loadGraphSource: a connection whose state cannot be probed is evicted, not recycled");
        FakeDb db = new FakeDb();
        db.failOnGetAutoCommit = new SQLException("dead connection");
        try {
            new JdbcCustomerGraphRepository(db::connection).loadGraphSource(GraphCriteria.defaults());
            check("exception thrown", false);
        } catch (BankException e) {
            check("still wrapped in BankException with the original cause",
                    e.getCause() == db.failOnGetAutoCommit && e.getMessage().contains("dead connection"));
        }
        // The eviction call itself runs against a foreign (fake) connection, where it is a
        // documented no-op — the point is that this path neither throws nor hangs.
        checkEquals("no transaction was ever started on the broken connection", List.of(), db.log);
    }

    static void brokenRestore() {
        section("[6] loadGraphSource: a failing autoCommit restore is swallowed and cannot mask the failure");
        FakeDb db = new FakeDb();
        db.failOnSetAutoCommitTrue = true;
        db.failOnExecute = new SQLException("boom");
        try {
            new JdbcCustomerGraphRepository(db::connection).loadGraphSource(GraphCriteria.defaults());
            check("exception thrown", false);
        } catch (BankException e) {
            check("the business failure propagates (the restore failure did not mask it)",
                    e.getCause() == db.failOnExecute);
        }
        checkEquals("snapshot began and rolled back; the broken restore is logged, not thrown",
                List.of("autoCommit=false", "rollback"), db.log);
    }

    /** When a path is passed, writes the exact SQL strings so an external engine can sanity-check them. */
    static void dumpSql(String[] args) throws IOException {
        if (args.length == 0) return;
        String out = "FLOWS_HEAD=" + JdbcCustomerGraphRepository.FLOWS_SQL_HEAD + "\n"
                + "FLOWS_LOOKBACK=" + JdbcCustomerGraphRepository.FLOWS_SQL_LOOKBACK + "\n"
                + "FLOWS_TAIL=" + JdbcCustomerGraphRepository.FLOWS_SQL_TAIL + "\n"
                + "PHONE=" + JdbcCustomerGraphRepository.PHONE_SQL + "\n"
                + "DETAIL_HEAD=" + JdbcCustomerGraphRepository.DETAIL_SQL_HEAD + "\n"
                + "DETAIL_TAIL=" + JdbcCustomerGraphRepository.DETAIL_SQL_TAIL + "\n";
        Files.writeString(Path.of(args[0]), out);
    }
}
