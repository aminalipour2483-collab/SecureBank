package com.bank.config;

import com.zaxxer.hikari.HikariConfig;

import java.util.Map;
import java.util.Properties;

import static com.bank.testkit.TestKit.*;

/**
 * Verifies that every externalised pool setting actually reaches the HikariConfig that
 * DatabaseConnection builds — without starting a pool or touching a database.
 * (DatabaseConfigTest covers the parsing/validation of the db.pool.* keys; this suite
 * covers the wiring from DatabaseConfig into HikariCP.)
 */
public class DatabaseConnectionPoolConfigTest {

    static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    public static void main(String[] args) {
        section("[1] Every db.pool.* setting reaches the HikariConfig");
        DatabaseConfig cfg = DatabaseConfig.resolve(Map.of(),
                props("db.user", "u", "db.password", "p",
                        "db.url", "jdbc:mysql://db:3306/bank_system",
                        "db.pool.maxSize", "5",
                        "db.pool.minIdle", "1",
                        "db.pool.connectionTimeoutMs", "2500",
                        "db.pool.idleTimeoutMs", "300000",
                        "db.pool.maxLifetimeMs", "1500000",
                        "db.pool.leakDetectionThresholdMs", "4000"));
        HikariConfig hc = DatabaseConnection.buildHikariConfig(cfg);
        checkEquals("poolName", "bank-pool", hc.getPoolName());
        checkEquals("jdbcUrl", "jdbc:mysql://db:3306/bank_system", hc.getJdbcUrl());
        checkEquals("username", "u", hc.getUsername());
        checkEquals("password", "p", hc.getPassword());
        checkEquals("maximumPoolSize", 5, hc.getMaximumPoolSize());
        checkEquals("minimumIdle", 1, hc.getMinimumIdle());
        checkEquals("connectionTimeout", 2_500L, hc.getConnectionTimeout());
        checkEquals("idleTimeout", 300_000L, hc.getIdleTimeout());
        checkEquals("maxLifetime", 1_500_000L, hc.getMaxLifetime());
        checkEquals("leakDetectionThreshold", 4_000L, hc.getLeakDetectionThreshold());
        checkEquals("initializationFailTimeout (-1 = background start, first borrow enforces the timeout)",
                -1L, hc.getInitializationFailTimeout());

        section("[2] MySQL Connector/J statement-cache properties are set");
        checkEquals("cachePrepStmts", "true", hc.getDataSourceProperties().getProperty("cachePrepStmts"));
        checkEquals("prepStmtCacheSize", "250", hc.getDataSourceProperties().getProperty("prepStmtCacheSize"));
        checkEquals("prepStmtCacheSqlLimit", "2048",
                hc.getDataSourceProperties().getProperty("prepStmtCacheSqlLimit"));
        checkEquals("useServerPrepStmts", "true", hc.getDataSourceProperties().getProperty("useServerPrepStmts"));

        section("[3] Defaults when the deployment provides no pool keys");
        HikariConfig d = DatabaseConnection.buildHikariConfig(DatabaseConfig.resolve(
                Map.of("BANK_DB_USER", "u", "BANK_DB_PASSWORD", "p"), new Properties()));
        checkEquals("default maximumPoolSize", 10, d.getMaximumPoolSize());
        checkEquals("default minimumIdle", 2, d.getMinimumIdle());
        checkEquals("default connectionTimeout", 10_000L, d.getConnectionTimeout());
        checkEquals("default idleTimeout", 600_000L, d.getIdleTimeout());
        checkEquals("default maxLifetime", 1_800_000L, d.getMaxLifetime());
        checkEquals("default leakDetectionThreshold", 15_000L, d.getLeakDetectionThreshold());

        finish("DatabaseConnectionPoolConfigTest");
    }
}
