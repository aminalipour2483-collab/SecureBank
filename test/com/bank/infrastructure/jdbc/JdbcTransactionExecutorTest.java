package com.bank.infrastructure.jdbc;

import com.bank.exception.BankException;
import com.bank.exception.CommitOutcomeUnknownException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class JdbcTransactionExecutorTest {
    private static int passed;
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        passed++;
    }
    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        boolean autoCommit = true;
        final Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> {
            String name = m.getName();
            if (name.equals("setAutoCommit")) name += ":" + a[0];
            calls.add(name);
            if (failures.contains(name)) throw new SQLException(name);
            return switch (m.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> { autoCommit = (boolean)a[0]; yield null; }
                case "commit", "rollback", "close", "abort" -> null;
                default -> throw new AssertionError("unexpected method " + name);
            };
        });
        JdbcTransactionExecutor executor() {
            return new JdbcTransactionExecutor(() -> { calls.add("borrow"); return connection; },
                    c -> { if (c != connection) throw new AssertionError("wrong connection"); calls.add("discard"); });
        }
        void expect(String... expected) { check(calls.equals(Arrays.asList(expected)), "calls: " + calls); }
        String work(Connection c) { check(c == connection, "same connection"); calls.add("work"); return "ok"; }
    }
    private static RuntimeException failure(Runnable run) {
        try { run.run(); } catch (RuntimeException e) { return e; }
        throw new AssertionError("expected exception");
    }
    public static void main(String[] args) {
        Fixture success = new Fixture();
        check(success.executor().execute("test", success::work).equals("ok"), "return value");
        success.expect("borrow", "getAutoCommit", "setAutoCommit:false", "work", "commit", "setAutoCommit:true", "close");

        Fixture business = new Fixture(); BankException original = new BankException("denied");
        check(failure(() -> business.executor().execute("test", c -> { throw original; })) == original, "preserve business exception");
        business.expect("borrow", "getAutoCommit", "setAutoCommit:false", "rollback", "setAutoCommit:true", "close");

        Fixture sql = new Fixture(); SQLException workError = new SQLException("insert failure");
        RuntimeException wrapped = failure(() -> sql.executor().execute("test", c -> { throw workError; }));
        check(wrapped instanceof BankException && wrapped.getCause() == workError, "wrap SQL failure");
        sql.expect("borrow", "getAutoCommit", "setAutoCommit:false", "rollback", "setAutoCommit:true", "close");

        Fixture rollback = new Fixture(); rollback.failures.add("rollback");
        RuntimeException denied = failure(() -> rollback.executor().execute("test", c -> { throw new BankException("denied"); }));
        check(denied.getSuppressed().length == 1, "rollback failure preserved");
        rollback.expect("borrow", "getAutoCommit", "setAutoCommit:false", "rollback", "discard", "close");

        Fixture commit = new Fixture(); commit.failures.add("commit");
        RuntimeException unknown = failure(() -> commit.executor().execute("test", commit::work));
        check(unknown instanceof CommitOutcomeUnknownException, "commit uncertainty preserved");
        commit.expect("borrow", "getAutoCommit", "setAutoCommit:false", "work", "commit", "rollback", "discard", "close");

        Fixture commitRollback = new Fixture(); commitRollback.failures.addAll(List.of("commit", "rollback"));
        RuntimeException unknown2 = failure(() -> commitRollback.executor().execute("test", commitRollback::work));
        check(unknown2 instanceof CommitOutcomeUnknownException && unknown2.getSuppressed().length == 1, "commit and rollback errors");
        commitRollback.expect("borrow", "getAutoCommit", "setAutoCommit:false", "work", "commit", "rollback", "discard", "close");

        Fixture reset = new Fixture(); reset.failures.add("setAutoCommit:true");
        check(reset.executor().execute("test", reset::work).equals("ok"), "committed result survives reset failure");
        reset.expect("borrow", "getAutoCommit", "setAutoCommit:false", "work", "commit", "setAutoCommit:true", "discard", "close");

        Fixture close = new Fixture(); close.failures.add("close");
        check(close.executor().execute("test", close::work).equals("ok"), "committed result survives close failure");
        close.expect("borrow", "getAutoCommit", "setAutoCommit:false", "work", "commit", "setAutoCommit:true", "close", "discard");

        Fixture dirty = new Fixture(); dirty.autoCommit = false;
        check(failure(() -> dirty.executor().execute("test", dirty::work)) instanceof BankException, "reject dirty connection");
        dirty.expect("borrow", "getAutoCommit", "discard", "close");

        Fixture begin = new Fixture(); begin.failures.add("setAutoCommit:false");
        failure(() -> begin.executor().execute("test", begin::work));
        begin.expect("borrow", "getAutoCommit", "setAutoCommit:false", "discard", "close");

        Fixture read = new Fixture(); read.failures.add("getAutoCommit");
        failure(() -> read.executor().execute("test", read::work));
        read.expect("borrow", "getAutoCommit", "discard", "close");

        Fixture discardFailure = new Fixture(); discardFailure.failures.add("rollback");
        JdbcTransactionExecutor executor = new JdbcTransactionExecutor(() -> { discardFailure.calls.add("borrow"); return discardFailure.connection; },
                c -> { discardFailure.calls.add("discard"); throw new IllegalStateException("pool eviction failed"); });
        RuntimeException primary = failure(() -> executor.execute("test", c -> { throw new BankException("denied"); }));
        check(primary.getSuppressed().length == 2, "rollback and eviction failures retained");
        discardFailure.expect("borrow", "getAutoCommit", "setAutoCommit:false", "rollback", "discard", "abort", "close");

        Fixture err = new Fixture(); AssertionError fatal = new AssertionError("work error");
        try { err.executor().execute("test", c -> { throw fatal; }); throw new IllegalStateException("expected error"); }
        catch (AssertionError e) { check(e == fatal, "preserve Error"); }
        err.expect("borrow", "getAutoCommit", "setAutoCommit:false", "rollback", "setAutoCommit:true", "close");

        System.out.println("JdbcTransactionExecutorTest: " + passed + " passed, 0 failed");
    }
}
