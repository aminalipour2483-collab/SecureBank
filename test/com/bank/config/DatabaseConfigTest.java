package com.bank.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static com.bank.testkit.TestKit.*;

public class DatabaseConfigTest {

    static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    public static void main(String[] args) throws Exception {
        section("[1] Resolution and precedence");
        DatabaseConfig a = DatabaseConfig.resolve(Map.of("BANK_DB_USER", "app", "BANK_DB_PASSWORD", "pw1"), new Properties());
        checkEquals("env user", "app", a.user());
        checkEquals("env password", "pw1", a.password());
        checkEquals("URL defaults to local development", DatabaseConfig.DEFAULT_URL, a.url());

        DatabaseConfig b = DatabaseConfig.resolve(Map.of(),
                props("db.user", "fileuser", "db.password", "filepw", "db.url", "jdbc:mysql://db:3306/bank_system"));
        checkEquals("file user", "fileuser", b.user());
        checkEquals("file url", "jdbc:mysql://db:3306/bank_system", b.url());

        DatabaseConfig c = DatabaseConfig.resolve(Map.of("BANK_DB_USER", "envuser", "BANK_DB_PASSWORD", "envpw"),
                props("db.user", "fileuser", "db.password", "filepw"));
        checkEquals("env overrides file (user)", "envuser", c.user());
        checkEquals("env overrides file (password)", "envpw", c.password());

        DatabaseConfig d = DatabaseConfig.resolve(Map.of("BANK_DB_USER", "  spaced  ", "BANK_DB_PASSWORD", "  keep spaces  "), new Properties());
        checkEquals("user is trimmed", "spaced", d.user());
        checkEquals("password is NOT trimmed", "  keep spaces  ", d.password());

        DatabaseConfig e = DatabaseConfig.resolve(Map.of("BANK_DB_USER", "app", "BANK_DB_PASSWORD", ""), new Properties());
        checkEquals("empty password is allowed (warned), not 'missing'", "", e.password());

        section("[2] Fail closed");
        checkThrows("nothing configured", DatabaseConfigException.class, () -> DatabaseConfig.resolve(Map.of(), new Properties()));
        checkThrows("user without password", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of("BANK_DB_USER", "app"), new Properties()));
        checkThrows("password without user", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of("BANK_DB_PASSWORD", "pw"), new Properties()));
        checkThrows("blank user", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of("BANK_DB_USER", "   ", "BANK_DB_PASSWORD", "pw"), new Properties()));
        checkThrows("non-MySQL URL rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of("BANK_DB_USER", "u", "BANK_DB_PASSWORD", "p", "BANK_DB_URL", "jdbc:h2:mem:x"), new Properties()));
        try {
            DatabaseConfig.resolve(Map.of(), new Properties());
        } catch (DatabaseConfigException ex) {
            check("error message tells how to configure", ex.getMessage().contains("BANK_DB_USER") && ex.getMessage().contains("BANK_DB_PASSWORD"));
        }

        section("[3] Secrets are not leaked");
        check("toString hides the password", !a.toString().contains("pw1") && a.toString().contains("****"));
        try {
            DatabaseConfig.resolve(Map.of("BANK_DB_USER", "u", "BANK_DB_PASSWORD", "TOP-SECRET", "BANK_DB_URL", "bad"), new Properties());
        } catch (DatabaseConfigException ex) {
            check("validation errors never contain the password", !String.valueOf(ex.getMessage()).contains("TOP-SECRET"));
        }

        section("[4] File loading");
        boolean envSet = System.getenv("BANK_DB_USER") != null || System.getenv("BANK_DB_PASSWORD") != null
                || System.getenv("BANK_DB_CONFIG") != null;
        if (envSet) {
            System.out.println("  (skipped: BANK_DB_* environment variables are set in this shell)");
        } else {
            Path f = Files.createTempFile("bank-db", ".properties");
            try {
                Files.writeString(f, "db.user=fromfile\ndb.password=filepw\n");
                System.setProperty(DatabaseConfig.PROP_FILE, f.toString());
                DatabaseConfig loaded = DatabaseConfig.load();
                checkEquals("explicit file is read", "fromfile", loaded.user());
                Files.delete(f);
                checkThrows("explicit file that is missing is an error (not ignored)", DatabaseConfigException.class,
                        DatabaseConfig::load);
            } finally {
                System.clearProperty(DatabaseConfig.PROP_FILE);
                Files.deleteIfExists(f);
            }
            if (!Files.isRegularFile(Path.of(DatabaseConfig.DEFAULT_FILE))) {
                checkThrows("no env, no file: load() fails closed", DatabaseConfigException.class, DatabaseConfig::load);
            } else {
                check("a default properties file exists and may be loaded", DatabaseConfig.load() != null);
            }
        }
        section("[5] Pool settings: defaults, file values, validation");
        DatabaseConfig p1 = DatabaseConfig.resolve(
                Map.of("BANK_DB_USER", "u", "BANK_DB_PASSWORD", "p"), new Properties());
        checkEquals("default maxSize", 10, p1.poolSettings().maxSize());
        checkEquals("default minIdle", 2, p1.poolSettings().minIdle());
        checkEquals("default connectionTimeoutMs", 10_000L, p1.poolSettings().connectionTimeoutMs());
        checkEquals("default idleTimeoutMs", 600_000L, p1.poolSettings().idleTimeoutMs());
        checkEquals("default maxLifetimeMs", 1_800_000L, p1.poolSettings().maxLifetimeMs());
        checkEquals("default leakDetectionThresholdMs", 15_000L, p1.poolSettings().leakDetectionThresholdMs());

        DatabaseConfig p2 = DatabaseConfig.resolve(Map.of(),
                props("db.user", "u", "db.password", "p",
                        "db.pool.maxSize", "5",
                        "db.pool.minIdle", "1",
                        "db.pool.connectionTimeoutMs", "2500",
                        "db.pool.idleTimeoutMs", "300000",
                        "db.pool.maxLifetimeMs", "1500000",
                        "db.pool.leakDetectionThresholdMs", "4000"));
        checkEquals("file maxSize", 5, p2.poolSettings().maxSize());
        checkEquals("file minIdle", 1, p2.poolSettings().minIdle());
        checkEquals("file connectionTimeoutMs", 2_500L, p2.poolSettings().connectionTimeoutMs());
        checkEquals("file idleTimeoutMs", 300_000L, p2.poolSettings().idleTimeoutMs());
        checkEquals("file maxLifetimeMs", 1_500_000L, p2.poolSettings().maxLifetimeMs());
        checkEquals("file leakDetectionThresholdMs", 4_000L, p2.poolSettings().leakDetectionThresholdMs());

        DatabaseConfig p3 = DatabaseConfig.resolve(Map.of(),
                props("db.user", "u", "db.password", "p", "db.pool.maxSize", "   "));
        checkEquals("blank pool key falls back to the default", 10, p3.poolSettings().maxSize());

        DatabaseConfig p4 = DatabaseConfig.resolve(Map.of(),
                props("db.user", "u", "db.password", "p", "db.pool.leakDetectionThresholdMs", "0"));
        checkEquals("leak detection 0 explicitly disables it", 0L, p4.poolSettings().leakDetectionThresholdMs());

        checkThrows("malformed pool number fails closed", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.maxSize", "ten")));
        checkThrows("maxSize below 1 rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.maxSize", "0")));
        checkThrows("negative minIdle rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.minIdle", "-1")));
        checkThrows("minIdle above maxSize rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.maxSize", "3", "db.pool.minIdle", "4")));
        checkThrows("connectionTimeout below 250ms rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.connectionTimeoutMs", "100")));
        checkThrows("idleTimeout below 10s rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.idleTimeoutMs", "5000")));
        checkThrows("maxLifetime below 30s rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.maxLifetimeMs", "10000")));
        checkThrows("leakDetection between 0 and 2s rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.leakDetectionThresholdMs", "1500")));
        checkThrows("idleTimeout at/above maxLifetime rejected", DatabaseConfigException.class,
                () -> DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p",
                        "db.pool.idleTimeoutMs", "1800000", "db.pool.maxLifetimeMs", "1800000")));
        try {
            DatabaseConfig.resolve(Map.of(), props("db.user", "u", "db.password", "p", "db.pool.maxSize", "ten"));
        } catch (DatabaseConfigException ex) {
            check("pool error names the offending key", ex.getMessage().contains("db.pool.maxSize"));
        }

        section("[6] URL hygiene");
        check("default URL contains no autoReconnect", !DatabaseConfig.DEFAULT_URL.contains("autoReconnect"));
        check("default URL still points at the local bank schema",
                DatabaseConfig.DEFAULT_URL.startsWith("jdbc:mysql://localhost:3306/bank_system_demo?"));

        finish("DatabaseConfigTest");
    }
}
