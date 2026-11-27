package com.bank.infrastructure.jdbc;

import com.bank.config.DatabaseConnection;
import com.bank.exception.BankException;
import com.bank.exception.CommitOutcomeUnknownException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns one fresh pooled connection for one write transaction. Work must not
 * commit, roll back, close, alter connection state, or borrow other connections.
 * No automatic retries: a failed commit may already have reached the server. */
public final class JdbcTransactionExecutor {
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException; }
    private final Supplier<Connection> borrow;
    private final Consumer<Connection> discard;

    public JdbcTransactionExecutor(Supplier<Connection> borrow, Consumer<Connection> discard) {
        this.borrow = Objects.requireNonNull(borrow);
        this.discard = Objects.requireNonNull(discard);
    }

    public static JdbcTransactionExecutor pooled(Supplier<Connection> borrow) {
        return new JdbcTransactionExecutor(borrow, DatabaseConnection::discardTransactionConnection);
    }

    public <T> T execute(String description, Work<T> work) {
        Objects.requireNonNull(work);
        Connection conn = Objects.requireNonNull(borrow.get());
        boolean started = false, commitAttempted = false, committed = false, unsafe = true;
        Throwable primary = null;
        try {
            if (!conn.getAutoCommit()) {
                throw new BankException("A database transaction is already active on this connection.");
            }
            conn.setAutoCommit(false);
            started = true;
            T result = work.run(conn);
            commitAttempted = true;
            conn.commit();
            committed = true;
            unsafe = false;
            return result;
        } catch (SQLException | RuntimeException ex) {
            RuntimeException failure = commitAttempted
                    ? new CommitOutcomeUnknownException(description, ex)
                    : ex instanceof RuntimeException runtime ? runtime : new BankException(description, ex);
            primary = failure;
            if (started && !committed) {
                // Rollback may clean the connection but cannot establish whether a failed
                // commit actually reached the server. Always discard after commit failure.
                unsafe = !rollback(conn, failure) || commitAttempted;
            }
            throw failure;
        } catch (Error ex) {
            primary = ex;
            if (started && !committed) unsafe = !rollback(conn, ex) || commitAttempted;
            throw ex;
        } finally {
            if (!unsafe) {
                try { conn.setAutoCommit(true); }
                catch (SQLException | RuntimeException ex) {
                    unsafe = true;
                    cleanupIssue(primary, ex);
                }
            }
            // NEVER switch autoCommit to true after an unsuccessful rollback:
            // JDBC can implicitly commit still-pending writes.
            if (unsafe) discardSafely(conn, primary);
            try { conn.close(); }
            catch (SQLException | RuntimeException ex) {
                cleanupIssue(primary, ex);
                if (!unsafe) discardSafely(conn, primary);
            }
        }
    }

    private static boolean rollback(Connection conn, Throwable failure) {
        try { conn.rollback(); return true; }
        catch (SQLException | RuntimeException ex) { failure.addSuppressed(ex); return false; }
    }

    private void discardSafely(Connection conn, Throwable failure) {
        try { discard.accept(conn); }
        catch (RuntimeException ex) {
            cleanupIssue(failure, ex);
            // Best-effort physical abort for a foreign/failing pool adapter.
            try { conn.abort(Runnable::run); }
            catch (SQLException | RuntimeException abortError) { cleanupIssue(failure, abortError); }
        }
    }

    private static void cleanupIssue(Throwable primary, Exception error) {
        if (primary != null) primary.addSuppressed(error);
        // A cleanup failure after acknowledged commit must not present the operation as failed.
        System.err.println("[DB] Transaction connection cleanup failed: " + error.getClass().getSimpleName());
    }
}
