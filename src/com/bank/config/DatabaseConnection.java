package com.bank.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Process-wide JDBC connection pool (HikariCP) for both banking clients.
 *
 * <p><b>Architecture.</b> One lazily-created singleton owns a single {@link HikariDataSource}
 * named {@code bank-pool}. Callers borrow a pooled connection from {@link #getConnection()}
 * and return it by calling {@link Connection#close()} — under a pool, {@code close()} is a
 * <i>return to the pool</i>, not a physical disconnect. Every DAO method therefore wraps its
 * borrowed connection in try-with-resources: borrow → execute → return. The pool caps
 * concurrency ({@code maximumPoolSize}), keeps warm connections ready ({@code minimumIdle})
 * and validates/repairs connections in the background, replacing the old
 * {@code ThreadLocal<Connection>} design in which every Swing worker thread held one
 * dedicated physical connection for its whole lifetime and a crashed daemon could strand one.
 *
 * <p><b>Credentials and pool sizing.</b> Both are supplied by the deployment through
 * {@link DatabaseConfig#load()} (environment variables or a properties file — see the source
 * list on that class); nothing is compiled into the jar. Pool sizing, lifetimes and the leak
 * detector come from the optional {@code db.pool.*} keys documented on
 * {@link DatabaseConfig.PoolSettings}, each with a safe default so the application runs even
 * when the file has no pool section. A missing configuration fails closed with
 * {@link DatabaseConfigException} when the singleton is first created.
 *
 * <p><b>Connection hygiene.</b> Write transactions use JdbcTransactionExecutor,
 * which restores auto-commit only after an acknowledged commit or successful
 * rollback. If the outcome or connection state is unsafe, the executor discards
 * the connection instead. The legacy restore helper is retained for other callers.
 *
 * <p><b>Exception contract (unchanged from the ThreadLocal era).</b> {@link #getConnection()}
 * throws {@code RuntimeException("Database connection failed", e)} when no connection can be
 * borrowed — the same unchecked type and message the DAO/Service/UI layers have always handled.
 *
 * <p><b>Lifecycle.</b> The pool starts in the background (no connection attempt blocks the
 * first {@link #getInstance()} call); the first {@link #getConnection()} waits up to
 * {@code connectionTimeout} (default 10 s) and then fails cleanly. A JVM shutdown hook closes the
 * pool when either Swing client exits; {@link #shutdown()} is idempotent and can also be
 * called explicitly.
 */
public final class DatabaseConnection {

    // -------------------------------------------------------------------------
    // 1. THE SINGLE INSTANCE (double-checked locking on a volatile field)
    // -------------------------------------------------------------------------
    private static volatile DatabaseConnection instance = null;

    // -------------------------------------------------------------------------
    // 2. THE POOL — the only owner of physical JDBC connections in this process
    // -------------------------------------------------------------------------
    private final HikariDataSource dataSource;

    // -------------------------------------------------------------------------
    // 3. PRIVATE CONSTRUCTOR — build the pool from the deployment configuration
    // -------------------------------------------------------------------------
    private DatabaseConnection() {
        DatabaseConfig config = DatabaseConfig.load();   // fails closed with DatabaseConfigException

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("[DB] ERROR: MySQL JDBC Driver not found.");
            throw new RuntimeException("JDBC Driver not found", e);
        }

        // Start the pool in the background: constructing the singleton must not throw when
        // the database is temporarily unreachable (mirrors the old behaviour, where the
        // connection was only established on the first getConnection() call). The first
        // borrower enforces connectionTimeout and receives the usual
        // RuntimeException("Database connection failed") on failure.
        this.dataSource = new HikariDataSource(buildHikariConfig(config));

        // Close the pool gracefully when the JVM exits, whichever way the Swing app quits.
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "bank-pool-shutdown"));

        DatabaseConfig.PoolSettings pool = config.poolSettings();
        System.out.println("[DB] Connection pool \"" + dataSource.getPoolName() + "\" initialised"
                + " (max=" + pool.maxSize()
                + ", minIdle=" + pool.minIdle()
                + ", connectionTimeout=" + pool.connectionTimeoutMs() + "ms"
                + ", idleTimeout=" + pool.idleTimeoutMs() + "ms"
                + ", maxLifetime=" + pool.maxLifetimeMs() + "ms"
                + ", leakDetection=" + pool.leakDetectionThresholdMs() + "ms).");
    }

    /**
     * Translates a validated {@link DatabaseConfig} into the HikariCP pool configuration.
     * Package-private and free of side effects so the wiring can be unit-tested without
     * starting a pool or reaching a database.
     */
    static HikariConfig buildHikariConfig(DatabaseConfig config) {
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setPoolName("bank-pool");
        poolConfig.setJdbcUrl(config.url());
        poolConfig.setUsername(config.user());
        poolConfig.setPassword(config.password());

        // Pool sizing / timeouts — externalised to the db.pool.* properties keys with
        // safe defaults (see DatabaseConfig.PoolSettings). Client-desktop workload:
        // one user, a few Swing worker threads.
        DatabaseConfig.PoolSettings pool = config.poolSettings();
        poolConfig.setMaximumPoolSize(pool.maxSize());
        poolConfig.setMinimumIdle(pool.minIdle());
        poolConfig.setConnectionTimeout(pool.connectionTimeoutMs()); // ms a borrower waits before failing
        poolConfig.setIdleTimeout(pool.idleTimeoutMs());             // idle connections are retired after this
        poolConfig.setMaxLifetime(pool.maxLifetimeMs());             // every connection is retired in-flight-free
        poolConfig.setLeakDetectionThreshold(pool.leakDetectionThresholdMs()); // 0 disables; default 15 s

        // MySQL Connector/J performance properties: client-side and server-side
        // prepared-statement caching, so hot statements are parsed once per connection.
        // No autoReconnect here (or in the URL): liveness is HikariCP's job — it validates
        // with Connection.isValid() before handing a connection out.
        poolConfig.addDataSourceProperty("cachePrepStmts", "true");
        poolConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        poolConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        poolConfig.addDataSourceProperty("useServerPrepStmts", "true");

        // See the constructor comment: background start, first borrow enforces the timeout.
        poolConfig.setInitializationFailTimeout(-1);
        return poolConfig;
    }

    // -------------------------------------------------------------------------
    // 4. getInstance() — Double-Checked Locking
    // -------------------------------------------------------------------------
    public static DatabaseConnection getInstance() {
        if (instance == null) {
            synchronized (DatabaseConnection.class) {
                if (instance == null) {
                    instance = new DatabaseConnection();
                }
            }
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // 5. getConnection() — BORROW a pooled connection
    //
    //    The caller MUST return it via try-with-resources (Connection.close()).
    //    The pool — not the caller — owns the connection's physical lifetime.
    // -------------------------------------------------------------------------
    public Connection getConnection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            System.err.println("[DB] ERROR: Could not connect to the database.");
            System.err.println("[DB] Message: " + e.getMessage());
            throw new RuntimeException("Database connection failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // 6. restoreAutoCommitOrEvict() — the eviction-safe way to end a transaction
    //
    //    Call this in the finally block of every transactional method, BEFORE
    //    try-with-resources returns the connection to the pool:
    //
    //        boolean previousAutoCommit = true;   // pool baseline; getAutoCommit() overwrites
    //        ...
    //        finally { DatabaseConnection.restoreAutoCommitOrEvict(conn, previousAutoCommit); }
    //
    //    Happy path: the connection goes back to the pool in the state it was
    //    borrowed in. Failure path: a connection whose auto-commit cannot be
    //    restored is broken or has unknown transaction state — it is evicted so
    //    no later borrower ever sees it. Note that if getAutoCommit() itself
    //    failed at the start of the transaction, previousAutoCommit still holds
    //    the safe default true, so this call either cleanly restores true or
    //    evicts the unusable connection.
    // -------------------------------------------------------------------------
    /**
     * Restores a borrowed connection's auto-commit state, evicting the connection from the pool
     * when the restore fails.
     *
     * <p>Why eviction matters: if {@code setAutoCommit(previousAutoCommit)} throws, the connection
     * is dead or its transaction state is unknown. Simply returning it via {@code close()} would
     * rely on HikariCP's own reset logic succeeding on a connection that just failed a trivial
     * round-trip — and a reset that silently no-ops would recycle a connection stuck in manual
     * commit, poisoning the next borrower. Evicting removes the entry from the pool immediately;
     * the caller's later {@code close()} of the evicted connection is quiet by design in
     * HikariCP 5.x.
     *
     * <p>Never throws: this runs in {@code finally} blocks, where a new exception would mask the
     * original business exception. Never creates the pool singleton (see {@link #evictConnection}).
     *
     * @param conn the borrowed connection; {@code null} is tolerated (no-op)
     * @param previousAutoCommit the auto-commit state captured before {@code setAutoCommit(false)}
     */
    public static void restoreAutoCommitOrEvict(Connection conn, boolean previousAutoCommit) {
        if (conn == null) return;
        try {
            conn.setAutoCommit(previousAutoCommit);
        } catch (SQLException ex) {
            System.err.println("[DB] WARNING: Could not restore autoCommit=" + previousAutoCommit
                    + " on a borrowed connection (" + ex.getMessage() + ")."
                    + " Evicting it from the pool so its polluted state is never recycled.");
            evictConnection(conn);
        }
    }

    // -------------------------------------------------------------------------
    // 7. evictConnection() — remove a broken/polluted connection from the pool
    //
    //    HikariCP semantics (verified against HikariCP 5.1.0):
    //      • the entry leaves circulation immediately, and the physical close
    //        happens off the caller's thread;
    //      • the borrower's own Connection.close() afterwards is a quiet no-op
    //        for an evicted connection (ProxyConnection swallows the reset
    //        failure when the entry is marked evicted);
    //      • connections that are not HikariCP proxies (unit-test fakes,
    //        foreign DataSources) are ignored, and so is an already-closed pool.
    // -------------------------------------------------------------------------
    /**
     * Evicts a connection from the pool so it is never handed out again. Safe to call on
     * {@code null}, on connections this pool does not own, before the pool exists, and after
     * {@link #shutdown()} — in every such case this is a no-op. Never throws.
     *
     * @param conn the connection to evict; may be a connection borrowed from this pool or a
     *             foreign/test connection (which is simply ignored)
     */
    public static void evictConnection(Connection conn) {
        if (conn == null) return;
        DatabaseConnection pool = instance;          // deliberately does NOT create the singleton
        if (pool == null) return;                    // no pool yet → nothing to evict from
        pool.evictFromPool(conn);
    }

    /** Strict discard adapter for the write transaction executor. Unlike the legacy
     * helper it propagates failure so the executor can attempt a physical abort. */
    public static void discardTransactionConnection(Connection conn) {
        DatabaseConnection pool = instance;
        if (pool == null || pool.dataSource.isClosed()) {
            throw new IllegalStateException("Transaction pool unavailable for eviction");
        }
        pool.dataSource.evictConnection(conn);
    }

    private void evictFromPool(Connection conn) {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.evictConnection(conn);
            }
        } catch (RuntimeException ex) {
            // Defensive: HikariCP documents evictConnection as a no-op for foreign connections,
            // but this helper must never take an application down from inside a finally block.
            System.err.println("[DB] WARNING: Could not evict a broken connection from the pool: "
                    + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 8. closeConnection() — DEPRECATED, deliberately a no-op
    //
    //    Legacy entry point from the ThreadLocal era. Connections are no longer
    //    owned per-thread: closing the thread's connection would return a pooled
    //    connection that other, still-running code may hold. Borrowers return
    //    connections themselves through try-with-resources, so there is nothing
    //    to do here. Kept only so old call sites keep compiling.
    // -------------------------------------------------------------------------
    @Deprecated
    public void closeConnection() {
        // Intentionally empty — see javadoc above.
    }

    // -------------------------------------------------------------------------
    // 9. shutdown() — close the pool and release every physical connection
    //
    //    Idempotent; registered as a JVM shutdown hook in the constructor and
    //    safe to call explicitly (for example from a managed lifecycle).
    // -------------------------------------------------------------------------
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            System.out.println("[DB] Shutting down connection pool \"" + dataSource.getPoolName() + "\".");
            dataSource.close();
        }
    }
}
