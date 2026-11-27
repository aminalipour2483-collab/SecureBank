package com.bank.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Database connection settings, supplied by the deployment instead of being compiled into the jar.
 *
 * <p><b>Sources</b> (an environment variable wins over the same key in the file):
 * <table>
 *   <tr><th>Setting</th><th>Environment variable</th><th>Properties key</th></tr>
 *   <tr><td>JDBC URL (optional)</td><td>{@code BANK_DB_URL}</td><td>{@code db.url}</td></tr>
 *   <tr><td>User (required)</td><td>{@code BANK_DB_USER}</td><td>{@code db.user}</td></tr>
 *   <tr><td>Password (required)</td><td>{@code BANK_DB_PASSWORD}</td><td>{@code db.password}</td></tr>
 * </table>
 * Pool sizing keys are file-only (see {@link PoolSettings}); every one of them is optional and
 * falls back to a safe default, so the application always runs when the credentials are present.
 *
 * <p>The file is {@code $BANK_DB_CONFIG}, else the system property {@code bank.db.config}, else
 * {@code ./bank-db.properties} when that file exists. A file that is named explicitly but cannot be
 * read is an error, not silently ignored.
 *
 * <p><b>Fail closed.</b> There is no built-in user or password: if either is missing the application
 * refuses to start with a message that says how to configure it. A <i>malformed</i> pool value is
 * also an error (a pool that silently ran with a different size than the operator wrote down is a
 * production incident waiting to happen); a <i>missing</i> pool value is not.
 *
 * <p><b>No {@code autoReconnect}.</b> The default URL deliberately omits MySQL Connector/J's
 * {@code autoReconnect=true}: with a connection pool it is a documented anti-pattern that can
 * resurrect half-dead connections and hand a borrower a corrupted transaction state. HikariCP
 * validates liveness itself ({@code Connection.isValid}) before a connection is handed out, which
 * is the correct replacement. A URL that still carries the flag is accepted but logged with a loud
 * warning, because silently rewriting an operator's URL would be worse than telling them.
 *
 * <p><b>What this does and does not buy.</b> It removes shared credentials from the distributed jars
 * and lets the customer and admin clients use different, least-privilege database accounts
 * ({@code sql/INSTALL_ALL.sql}). It is still a client that connects directly to MySQL, so
 * anyone who can read the client machine's environment or config file can use those credentials; a
 * real boundary needs a server-side API (Phase 3).
 */
public final class DatabaseConfig {

    public static final String ENV_URL = "BANK_DB_URL";
    public static final String ENV_USER = "BANK_DB_USER";
    public static final String ENV_PASSWORD = "BANK_DB_PASSWORD";
    public static final String ENV_FILE = "BANK_DB_CONFIG";
    public static final String PROP_FILE = "bank.db.config";
    static final String DEFAULT_FILE = "bank-db.properties";

    /**
     * Local-development URL (non-secret). {@code useSSL=false} is only acceptable for localhost.
     * No {@code autoReconnect}: with the pool, liveness is HikariCP's job (see class javadoc).
     */
    static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/bank_system_demo"
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";

    private static final Logger LOG = Logger.getLogger(DatabaseConfig.class.getName());

    private final String url;
    private final String user;
    private final String password;
    private final PoolSettings poolSettings;

    DatabaseConfig(String url, String user, String password, PoolSettings poolSettings) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.poolSettings = poolSettings;
    }

    public String url() { return url; }
    public String user() { return user; }
    public String password() { return password; }

    /** Pool sizing/timeouts; never {@code null} — defaults apply when the file has no pool keys. */
    public PoolSettings poolSettings() { return poolSettings; }

    @Override public String toString() {
        return "DatabaseConfig[url=" + url + ", user=" + user + ", password=****, pool=" + poolSettings + "]";
    }

    // ------------------------------------------------------------------------------------------
    /** Reads the process environment and the optional properties file. */
    public static DatabaseConfig load() {
        Map<String, String> env = System.getenv();
        String explicit = firstNonBlank(env.get(ENV_FILE), System.getProperty(PROP_FILE));
        Properties file = new Properties();
        if (explicit != null) {
            readFile(Paths.get(explicit), file);                       // must exist
        } else if (Files.isRegularFile(Paths.get(DEFAULT_FILE))) {
            readFile(Paths.get(DEFAULT_FILE), file);
        }
        return resolve(env, file);
    }

    /** Pure resolution step, separated so it can be tested without touching the environment or disk. */
    static DatabaseConfig resolve(Map<String, String> env, Properties file) {
        String url = firstNonBlank(env.get(ENV_URL), file.getProperty("db.url"));
        String user = firstNonBlank(env.get(ENV_USER), file.getProperty("db.user"));
        String password = env.get(ENV_PASSWORD) != null ? env.get(ENV_PASSWORD) : file.getProperty("db.password");

        if (user == null || password == null) {
            throw new DatabaseConfigException(
                    "Database credentials are not configured.\n\n"
                    + "Set the environment variables " + ENV_USER + " and " + ENV_PASSWORD
                    + " (optionally " + ENV_URL + "),\n"
                    + "or create a properties file with db.user, db.password and optionally db.url and point "
                    + ENV_FILE + " at it.\n"
                    + "See bank-db.properties.example.");
        }
        user = user.trim();
        if (url == null) url = DEFAULT_URL;
        url = url.trim();
        if (!url.startsWith("jdbc:mysql:")) {
            throw new DatabaseConfigException("The database URL must start with \"jdbc:mysql:\".");
        }
        if (password.contains("CHANGE_ME") || user.equalsIgnoreCase("CHANGE_ME")) {
            throw new DatabaseConfigException("Replace placeholder database credentials before launching.");
        }
        if (password.isEmpty()) {
            LOG.warning("The database password is empty. Use a real password for any shared database.");
        }
        if (user.equalsIgnoreCase("root")) {
            LOG.warning("Connecting to the database as root. Use the least-privilege accounts from "
                    + "sql/INSTALL_ALL.sql instead.");
        }
        if ((url.contains("useSSL=false") || url.contains("sslMode=DISABLED"))
                && !url.contains("//localhost") && !url.contains("//127.0.0.1")) {
            throw new DatabaseConfigException("Unencrypted connections to remote MySQL hosts are forbidden.");
        }
        if (url.contains("autoReconnect=true")) {
            LOG.warning("The database URL enables autoReconnect=true. With a connection pool this is "
                    + "unsafe: MySQL Connector/J can silently resurrect a half-dead connection whose "
                    + "transaction state is corrupted, and the pool would recycle it. HikariCP checks "
                    + "liveness itself (Connection.isValid) — remove autoReconnect=true from the URL.");
        }
        PoolSettings poolSettings = PoolSettings.from(file);
        return new DatabaseConfig(url, user, password, poolSettings);
    }

    private static void readFile(Path path, Properties into) {
        try (InputStream in = Files.newInputStream(path)) {
            into.load(in);
        } catch (IOException e) {
            throw new DatabaseConfigException("Cannot read the database configuration file \"" + path + "\": "
                    + e.getMessage(), e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    // ------------------------------------------------------------------------------------------
    /**
     * Immutable, validated HikariCP sizing and timeout settings.
     *
     * <p>All keys are optional properties-file entries (environment variables are not consulted for
     * them). Missing or blank keys fall back to the {@code DEFAULT_*} constants so the application
     * always starts; values that are present but malformed or out of range throw
     * {@link DatabaseConfigException} with the offending key, because guessing what the operator
     * meant is worse than refusing to start.
     *
     * <p>The minimums mirror HikariCP's own documented constraints, so the values handed to
     * {@code HikariConfig} are always accepted without HikariCP having to clamp or warn.
     */
    public static final class PoolSettings {

        // ---- property keys (public so callers/docs/tests reference one spelling) -------------
        public static final String KEY_MAX_SIZE = "db.pool.maxSize";
        public static final String KEY_MIN_IDLE = "db.pool.minIdle";
        public static final String KEY_CONNECTION_TIMEOUT_MS = "db.pool.connectionTimeoutMs";
        public static final String KEY_IDLE_TIMEOUT_MS = "db.pool.idleTimeoutMs";
        public static final String KEY_MAX_LIFETIME_MS = "db.pool.maxLifetimeMs";
        public static final String KEY_LEAK_DETECTION_THRESHOLD_MS = "db.pool.leakDetectionThresholdMs";

        // ---- defaults ------------------------------------------------------------------------
        public static final int DEFAULT_MAX_SIZE = 10;
        public static final int DEFAULT_MIN_IDLE = 2;
        public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L;      // 10 s
        public static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000L;           // 10 min
        public static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000L;         // 30 min
        public static final long DEFAULT_LEAK_DETECTION_THRESHOLD_MS = 15_000L; // 15 s

        /** HikariCP's minimum sensible borrow timeout. */
        static final long MIN_CONNECTION_TIMEOUT_MS = 250L;
        /** HikariCP's minimum idle timeout (below this it warns and behaves unpredictably). */
        static final long MIN_IDLE_TIMEOUT_MS = 10_000L;
        /** HikariCP's minimum connection lifetime. */
        static final long MIN_MAX_LIFETIME_MS = 30_000L;
        /** HikariCP's minimum leak threshold (below 2 s it fires on normal short borrows). */
        static final long MIN_LEAK_DETECTION_THRESHOLD_MS = 2_000L;

        private final int maxSize;
        private final int minIdle;
        private final long connectionTimeoutMs;
        private final long idleTimeoutMs;
        private final long maxLifetimeMs;
        private final long leakDetectionThresholdMs;

        private PoolSettings(int maxSize, int minIdle, long connectionTimeoutMs,
                             long idleTimeoutMs, long maxLifetimeMs, long leakDetectionThresholdMs) {
            this.maxSize = maxSize;
            this.minIdle = minIdle;
            this.connectionTimeoutMs = connectionTimeoutMs;
            this.idleTimeoutMs = idleTimeoutMs;
            this.maxLifetimeMs = maxLifetimeMs;
            this.leakDetectionThresholdMs = leakDetectionThresholdMs;
        }

        /** The defaults, used when the properties file carries no pool keys at all. */
        public static PoolSettings defaults() {
            return new PoolSettings(DEFAULT_MAX_SIZE, DEFAULT_MIN_IDLE, DEFAULT_CONNECTION_TIMEOUT_MS,
                    DEFAULT_IDLE_TIMEOUT_MS, DEFAULT_MAX_LIFETIME_MS, DEFAULT_LEAK_DETECTION_THRESHOLD_MS);
        }

        /**
         * Reads the {@code db.pool.*} keys from {@code file} (missing/blank → default for that key)
         * and validates the result. A malformed or impossible combination throws
         * {@link DatabaseConfigException} naming the key and the problem.
         */
        public static PoolSettings from(Properties file) {
            int maxSize = intSetting(file, KEY_MAX_SIZE, DEFAULT_MAX_SIZE);
            int minIdle = intSetting(file, KEY_MIN_IDLE, DEFAULT_MIN_IDLE);
            long connectionTimeoutMs = longSetting(file, KEY_CONNECTION_TIMEOUT_MS, DEFAULT_CONNECTION_TIMEOUT_MS);
            long idleTimeoutMs = longSetting(file, KEY_IDLE_TIMEOUT_MS, DEFAULT_IDLE_TIMEOUT_MS);
            long maxLifetimeMs = longSetting(file, KEY_MAX_LIFETIME_MS, DEFAULT_MAX_LIFETIME_MS);
            long leakDetectionThresholdMs =
                    longSetting(file, KEY_LEAK_DETECTION_THRESHOLD_MS, DEFAULT_LEAK_DETECTION_THRESHOLD_MS);

            if (maxSize < 1) {
                throw new DatabaseConfigException(KEY_MAX_SIZE + " must be at least 1, but is " + maxSize + ".");
            }
            if (minIdle < 0) {
                throw new DatabaseConfigException(KEY_MIN_IDLE + " cannot be negative, but is " + minIdle + ".");
            }
            if (minIdle > maxSize) {
                throw new DatabaseConfigException(KEY_MIN_IDLE + " (" + minIdle + ") cannot be larger than "
                        + KEY_MAX_SIZE + " (" + maxSize + ").");
            }
            if (connectionTimeoutMs < MIN_CONNECTION_TIMEOUT_MS) {
                throw new DatabaseConfigException(KEY_CONNECTION_TIMEOUT_MS + " must be at least "
                        + MIN_CONNECTION_TIMEOUT_MS + " ms, but is " + connectionTimeoutMs + ".");
            }
            if (idleTimeoutMs < MIN_IDLE_TIMEOUT_MS) {
                throw new DatabaseConfigException(KEY_IDLE_TIMEOUT_MS + " must be at least " + MIN_IDLE_TIMEOUT_MS
                        + " ms, but is " + idleTimeoutMs + ". (To disable idle eviction entirely, set "
                        + KEY_MIN_IDLE + " equal to " + KEY_MAX_SIZE + " for a fixed-size pool.)");
            }
            if (maxLifetimeMs < MIN_MAX_LIFETIME_MS) {
                throw new DatabaseConfigException(KEY_MAX_LIFETIME_MS + " must be at least " + MIN_MAX_LIFETIME_MS
                        + " ms, but is " + maxLifetimeMs + ".");
            }
            if (leakDetectionThresholdMs != 0 && leakDetectionThresholdMs < MIN_LEAK_DETECTION_THRESHOLD_MS) {
                throw new DatabaseConfigException(KEY_LEAK_DETECTION_THRESHOLD_MS + " must be 0 (disabled) or at "
                        + "least " + MIN_LEAK_DETECTION_THRESHOLD_MS + " ms, but is " + leakDetectionThresholdMs + ".");
            }
            if (idleTimeoutMs >= maxLifetimeMs) {
                throw new DatabaseConfigException(KEY_IDLE_TIMEOUT_MS + " (" + idleTimeoutMs + " ms) must be "
                        + "smaller than " + KEY_MAX_LIFETIME_MS + " (" + maxLifetimeMs
                        + " ms); otherwise HikariCP ignores idle eviction entirely.");
            }
            return new PoolSettings(maxSize, minIdle, connectionTimeoutMs, idleTimeoutMs,
                    maxLifetimeMs, leakDetectionThresholdMs);
        }

        public int maxSize() { return maxSize; }
        public int minIdle() { return minIdle; }
        public long connectionTimeoutMs() { return connectionTimeoutMs; }
        public long idleTimeoutMs() { return idleTimeoutMs; }
        public long maxLifetimeMs() { return maxLifetimeMs; }
        public long leakDetectionThresholdMs() { return leakDetectionThresholdMs; }

        @Override public String toString() {
            return "PoolSettings[" + KEY_MAX_SIZE + "=" + maxSize
                    + ", " + KEY_MIN_IDLE + "=" + minIdle
                    + ", " + KEY_CONNECTION_TIMEOUT_MS + "=" + connectionTimeoutMs
                    + ", " + KEY_IDLE_TIMEOUT_MS + "=" + idleTimeoutMs
                    + ", " + KEY_MAX_LIFETIME_MS + "=" + maxLifetimeMs
                    + ", " + KEY_LEAK_DETECTION_THRESHOLD_MS + "=" + leakDetectionThresholdMs + "]";
        }

        private static int intSetting(Properties file, String key, int defaultValue) {
            String raw = rawValue(file, key);
            if (raw == null) return defaultValue;
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new DatabaseConfigException("The setting " + key + " must be a whole number, but is \""
                        + raw + "\".", e);
            }
        }

        private static long longSetting(Properties file, String key, long defaultValue) {
            String raw = rawValue(file, key);
            if (raw == null) return defaultValue;
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new DatabaseConfigException("The setting " + key + " must be a whole number of "
                        + "milliseconds, but is \"" + raw + "\".", e);
            }
        }

        /** Trimmed value, or {@code null} when the key is absent or blank (blank → default). */
        private static String rawValue(Properties file, String key) {
            String value = file.getProperty(key);
            if (value == null || value.isBlank()) return null;
            return value.trim();
        }
    }
}
