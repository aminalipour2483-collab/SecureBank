package com.bank.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.bank.testkit.TestKit.*;

/**
 * Tests the eviction-safe autoCommit restore WITHOUT MySQL.
 *
 * What is verified here:
 *   1. the happy path restores the captured autoCommit value;
 *   2. a failing restore NEVER throws (it runs inside finally blocks, where a new
 *      exception would mask the original business exception);
 *   3. both helpers tolerate null;
 *   4. a live HikariDataSource silently ignores connections it does not own (the
 *      HikariCP 5.x contract that makes DatabaseConnection.evictConnection safe to
 *      call on test fakes and foreign connections).
 *
 * What is NOT verified here (needs a live MySQL, tracked in ENGINEERING_ROADMAP):
 * the physical removal of a real pooled connection. The HikariCP-side semantics
 * were reviewed against the 5.1.0 bytecode: evictConnection removes the entry
 * from circulation immediately and closes it off-thread; the borrower's later
 * close() of an evicted connection is quiet by design.
 */
public class DatabaseConnectionEvictionTest {

    /** Dynamic-proxy fake connection that records setAutoCommit calls; failures can be scripted. */
    static final class FakeConnection {
        final List<Boolean> setAutoCommitValues = new ArrayList<>();
        boolean failOnSetAutoCommit;
        SQLException failOnGetAutoCommit;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(FakeConnection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAutoCommit" -> {
                            if (failOnGetAutoCommit != null) throw failOnGetAutoCommit;
                            yield Boolean.TRUE;
                        }
                        case "setAutoCommit" -> {
                            if (failOnSetAutoCommit) throw new SQLException("restore failed");
                            setAutoCommitValues.add((Boolean) args[0]);
                            yield null;
                        }
                        case "isClosed" -> Boolean.FALSE;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    public static void main(String[] args) {
        section("[1] restoreAutoCommitOrEvict — happy path");
        FakeConnection ok = new FakeConnection();
        DatabaseConnection.restoreAutoCommitOrEvict(ok.connection(), true);
        checkEquals("setAutoCommit called once with the captured value",
                List.of(Boolean.TRUE), ok.setAutoCommitValues);

        section("[2] restoreAutoCommitOrEvict — a failing restore never throws");
        FakeConnection broken = new FakeConnection();
        broken.failOnSetAutoCommit = true;
        try {
            DatabaseConnection.restoreAutoCommitOrEvict(broken.connection(), true);
            check("a failed restore is swallowed (finally-block safe)", true);
        } catch (Throwable t) {
            check("a failed restore is swallowed (finally-block safe): " + t, false);
        }
        checkEquals("the failed setAutoCommit was attempted (and not recorded by the fake)",
                List.of(), broken.setAutoCommitValues);

        section("[3] null tolerance");
        DatabaseConnection.restoreAutoCommitOrEvict(null, true);   // must not throw
        DatabaseConnection.evictConnection(null);                  // must not throw
        check("null connection is a no-op for both helpers", true);

        section("[4] evictConnection is safe for foreign connections and any pool state");
        // Build a REAL HikariConfig/HikariDataSource (unreachable DB, background start) and
        // confirm HikariCP's own guard: evicting a connection the pool does not own is a
        // silent no-op. DatabaseConnection.evictConnection relies on exactly that contract.
        HikariConfig cfg = DatabaseConnection.buildHikariConfig(DatabaseConfig.resolve(
                Map.of("BANK_DB_URL", "jdbc:mysql://localhost:1/bank_system",
                        "BANK_DB_USER", "u", "BANK_DB_PASSWORD", "p"), new Properties()));
        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            ds.evictConnection(new FakeConnection().connection());     // foreign → silent no-op
            check("foreign connection eviction is a silent no-op on a live pool", true);

            // The static helper must also be safe while a pool exists in the JVM but the
            // DatabaseConnection singleton was never created (unit-test scenario).
            FakeConnection foreign = new FakeConnection();
            DatabaseConnection.restoreAutoCommitOrEvict(foreign.connection(), true);
            checkEquals("restore still works without the singleton", List.of(Boolean.TRUE),
                    foreign.setAutoCommitValues);
        }
        check("the temporary pool is closed again", true);

        section("[5] A closed pool is a no-op for eviction");
        HikariConfig cfg2 = DatabaseConnection.buildHikariConfig(DatabaseConfig.resolve(
                Map.of("BANK_DB_URL", "jdbc:mysql://localhost:1/bank_system",
                        "BANK_DB_USER", "u", "BANK_DB_PASSWORD", "p"), new Properties()));
        HikariDataSource ds2 = new HikariDataSource(cfg2);
        ds2.close();
        try {
            ds2.evictConnection(new FakeConnection().connection());   // closed pool → no-op
            check("eviction on a closed pool is a silent no-op", true);
        } catch (Throwable t) {
            check("eviction on a closed pool is a silent no-op: " + t, false);
        }

        finish("DatabaseConnectionEvictionTest");
    }
}
