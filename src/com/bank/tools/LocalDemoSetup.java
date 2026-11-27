package com.bank.tools;

import com.bank.util.BCryptUtil;
import java.io.Console;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Properties;

/**
 * Interactive installer for a NEW, local-only MySQL demonstration database.
 * Refuses existing bank_system_demo schemas; never executes DROP, TRUNCATE, or DELETE.
 * Invoked by SETUP-WINDOWS.bat with a real terminal to securely read credentials.
 */
public final class LocalDemoSetup {
    private static final String DATABASE = "bank_system_demo";
    private static final String HOST = "localhost";
    private static final String SCHEMA_FILE = "sql/01_install_fresh.sql";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Path LOCAL = Path.of("local");

    private LocalDemoSetup() {}

    public static void main(String[] args) {
        try {
            if (args.length > 0 && "--check".equals(args[0])) {
                check();
                return;
            }
            if (args.length > 0 && "--help".equals(args[0])) {
                System.out.println("Local demo setup: run SETUP-WINDOWS.bat after installing MySQL 8 and Java 17+." );
                System.out.println("Creates only a NEW bank_system_demo database; refuses any existing database of that name.");
                return;
            }
            install();
        } catch (Exception error) {
            System.err.println("\nSETUP FAILED: " + error.getMessage());
            System.err.println("No existing database was intentionally deleted or replaced.");
            System.exit(1);
        }
    }

    private static void install() throws Exception {
        Console console = System.console();
        if (console == null) throw new IllegalStateException("A terminal is required. Use SETUP-WINDOWS.bat.");
        System.out.println("LOCAL BANK DEMO - NEW DATABASE INSTALLER");
        System.out.println("Only simulated money. Requires local MySQL 8 and Java 17+.");
        System.out.println("Refuses to modify an existing bank_system_demo database.");
        if (!Files.isRegularFile(Path.of(SCHEMA_FILE))) {
            throw new IOException("Missing " + SCHEMA_FILE + "; run installer from the project folder.");
        }
        String user = console.readLine("MySQL administrator user [root]: ");
        if (user == null || user.isBlank()) user = "root";
        char[] secret = console.readPassword("MySQL administrator password: ");
        if (secret == null) throw new IllegalStateException("Password entry cancelled.");
        String administratorPassword = new String(secret);
        Arrays.fill(secret, '\0');
        String serverUrl = jdbcUrl(false);
        System.out.println("Connecting to local MySQL ...");
        try (Connection connection = DriverManager.getConnection(serverUrl, user.trim(), administratorPassword)) {
            if (databaseExists(connection)) {
                throw new IllegalStateException("Database 'bank_system_demo' already exists. Setup refuses to overwrite data. "
                        + "Use an empty MySQL instance for this local demo.");
            }
            if (Files.exists(LOCAL.resolve("customer-db.properties")) || Files.exists(LOCAL.resolve("admin-db.properties"))) {
                throw new IllegalStateException("Local configuration already exists. Back it up before starting a fresh installation.");
            }
            try (Statement stmt = connection.createStatement(); ResultSet users = stmt.executeQuery(
                    "SELECT COUNT(*) FROM mysql.user WHERE Host = 'localhost' AND User IN ('bank_customer_app', 'bank_admin_app')")) {
                users.next();
                if (users.getInt(1) > 0) throw new IllegalStateException("Application database accounts already exist. Use an empty local MySQL instance or configure the existing database manually.");
            }
            String seed = console.readLine("Create two simulated customers (PIN 1234 and 5678)? [Y/n]: ");
            boolean seedDemo = seed == null || seed.isBlank() || seed.equalsIgnoreCase("y")
                    || seed.equalsIgnoreCase("yes");
            String login = console.readLine("Choose administrator login name [bankadmin]: ");
            if (login == null || login.isBlank()) login = "bankadmin";
            char[] adminPin = console.readPassword("Choose ADMIN password (at least 12 characters): ");
            char[] confirm = console.readPassword("Confirm ADMIN password: ");
            if (adminPin == null || confirm == null || adminPin.length < 12
                    || !Arrays.equals(adminPin, confirm)) {
                throw new IllegalStateException("Administrator passwords do not match or are too short.");
            }
            String bcrypt = BCryptUtil.hash(new String(adminPin));
            Arrays.fill(adminPin, '\0');
            Arrays.fill(confirm, '\0');
            System.out.println("Installing schema on NEW bank_system_demo database ...");
            executeSqlScript(connection, Path.of(SCHEMA_FILE));
            System.out.println("Creating local demo customer and administrator database users ...");
            String customerPassword = randomPassword();
            String adminDbPassword = randomPassword();
            configureDbUser(connection, "bank_customer_app", customerPassword, false);
            configureDbUser(connection, "bank_admin_app", adminDbPassword, true);
            if (seedDemo) seed(connection);
            Files.createDirectories(LOCAL);
            writeProperties(LOCAL.resolve("customer-db.properties"), "db.url", jdbcUrl(true),
                    "db.user", "bank_customer_app", "db.password", customerPassword);
            writeProperties(LOCAL.resolve("admin-db.properties"), "db.url", jdbcUrl(true),
                    "db.user", "bank_admin_app", "db.password", adminDbPassword);
            try (PreparedStatement adminInsert = connection.prepareStatement(
                    "INSERT INTO admin_users(username, password_hash, is_active) VALUES (?, ?, TRUE)")) {
                adminInsert.setString(1, login.trim());
                adminInsert.setString(2, bcrypt);
                adminInsert.executeUpdate();
            }
            System.out.println("\nINSTALL COMPLETED. Use START-CUSTOMER.bat and START-ADMIN.bat.");
            if (seedDemo) {
                System.out.println("Demo Alice card: 401111111101 | PIN: 1234");
                System.out.println("Demo Bob   card: 401111111102 | PIN: 5678");
            }
            System.out.println("Administrator login: " + login.trim() + " with the password you selected.");
            System.out.println("Local secrets are stored in local/ (not included in the distribution)." );
        }
    }

    private static void check() throws Exception {
        if (!Files.isRegularFile(LOCAL.resolve("customer-db.properties"))
                || !Files.isRegularFile(LOCAL.resolve("admin-db.properties"))) {
            throw new IllegalStateException("Local configuration files are missing. Run SETUP-WINDOWS.bat, or follow the manual SQL configuration steps in README.md.");
        }
        for (String name : new String[]{"customer-db.properties", "admin-db.properties"}) {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(LOCAL.resolve(name))) { props.load(reader); }
            boolean adminRole = "admin-db.properties".equals(name);
            java.util.Set<String> expected = new java.util.TreeSet<>(java.util.List.of(
                    "customers", "accounts", "transactions", "transfers", "messages", "fraud_outbox"));
            if (adminRole) expected.addAll(java.util.List.of("fraud_alerts", "fraud_outbox_attempts",
                    "fraud_event_results", "fraud_outbox_retry_events", "fraud_ring_analyses", "admin_users",
                    "customer_lifecycle_events", "account_status_history"));
            else expected.addAll(java.util.List.of("idempotency_requests", "pin_recovery_attempts"));
            try (Connection connection = DriverManager.getConnection(props.getProperty("db.url"),
                    props.getProperty("db.user"), props.getProperty("db.password"));
                 Statement stmt = connection.createStatement();
                 ResultSet result = stmt.executeQuery("SELECT table_name FROM information_schema.tables "
                         + "WHERE table_schema = 'bank_system_demo' AND table_type='BASE TABLE'")) {
                java.util.Set<String> missing = new java.util.TreeSet<>(expected);
                while (result.next()) missing.remove(result.getString(1));
                if (!missing.isEmpty()) throw new SQLException("Missing or inaccessible tables: " + missing);
                System.out.println(name + ": connection and " + expected.size() + " required visible tables verified (not a write-grant test)");
                if ("admin-db.properties".equals(name)) {
                    try (Statement adminCheck = connection.createStatement();
                         ResultSet admins = adminCheck.executeQuery(
                                 "SELECT COUNT(*) FROM admin_users WHERE is_active = TRUE")) {
                        admins.next();
                        if (admins.getInt(1) < 1) throw new SQLException("No active administrator login exists.");
                        System.out.println("Active administrator login: verified");
                    }
                }
            }
        }
        System.out.println("Local database smoke checks PASSED. Financial UI paths were not exercised.");
    }

    private static boolean databaseExists(Connection c) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?")) {
            stmt.setString(1, DATABASE);
            try (ResultSet r = stmt.executeQuery()) { r.next(); return r.getInt(1) > 0; }
        }
    }

    private static void executeSqlScript(Connection c, Path script) throws Exception {
        StringBuilder sql = new StringBuilder();
        int statements = 0;
        for (String raw : Files.readAllLines(script)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("#")) continue;
            sql.append(raw).append('\n');
            if (line.endsWith(";")) {
                String command = sql.toString().trim();
                command = command.substring(0, command.length() - 1);
                try (Statement statement = c.createStatement()) { statement.execute(command); }
                statements++;
                sql.setLength(0);
            }
        }
        if (!sql.toString().trim().isEmpty()) throw new SQLException("SQL file ended with an unterminated statement.");
        if (statements < 30) throw new SQLException("Incomplete schema script: only " + statements + " statements.");
        System.out.println("Applied " + statements + " schema statements.");
    }

    private static void configureDbUser(Connection c, String name, String password, boolean admin) throws SQLException {
        // The user names are hardcoded constants; passwords consist only of URL-safe base64 characters.
        String ident = "'" + name + "'@'localhost'";
        try (Statement stmt = c.createStatement()) {
            stmt.execute("CREATE USER " + ident + " IDENTIFIED BY '" + password + "'");
            String base = " ON bank_system_demo.";
            if (!admin) {
                stmt.execute("GRANT SELECT, INSERT, UPDATE" + base + "customers TO " + ident);
                stmt.execute("GRANT SELECT, INSERT, UPDATE" + base + "accounts TO " + ident);
                stmt.execute("GRANT SELECT, INSERT" + base + "transactions TO " + ident);
                stmt.execute("GRANT SELECT, INSERT" + base + "transfers TO " + ident);
                stmt.execute("GRANT SELECT, INSERT, UPDATE" + base + "messages TO " + ident);
                stmt.execute("GRANT INSERT" + base + "fraud_outbox TO " + ident);
                stmt.execute("GRANT SELECT, INSERT, UPDATE" + base + "idempotency_requests TO " + ident);
                stmt.execute("GRANT SELECT, INSERT, UPDATE" + base + "pin_recovery_attempts TO " + ident);
            } else {
                stmt.execute("GRANT SELECT, UPDATE" + base + "customers TO " + ident);
                stmt.execute("GRANT SELECT, INSERT" + base + "customer_lifecycle_events TO " + ident);
                stmt.execute("GRANT SELECT, INSERT" + base + "account_status_history TO " + ident);
                stmt.execute("GRANT SELECT, UPDATE" + base + "accounts TO " + ident);
                stmt.execute("GRANT SELECT" + base + "transactions TO " + ident);
                stmt.execute("GRANT SELECT, UPDATE" + base + "transfers TO " + ident);
                stmt.execute("GRANT SELECT, INSERT, UPDATE" + base + "messages TO " + ident);
                stmt.execute("GRANT SELECT, INSERT, UPDATE" + base + "fraud_alerts TO " + ident);
                stmt.execute("GRANT SELECT, UPDATE" + base + "fraud_outbox TO " + ident);
                stmt.execute("GRANT SELECT, INSERT, UPDATE" + base + "fraud_outbox_attempts TO " + ident);
                stmt.execute("GRANT SELECT, INSERT" + base + "fraud_event_results TO " + ident);
                stmt.execute("GRANT SELECT, INSERT" + base + "fraud_outbox_retry_events TO " + ident);
                stmt.execute("GRANT SELECT, INSERT" + base + "fraud_ring_analyses TO " + ident);
                stmt.execute("GRANT SELECT" + base + "admin_users TO " + ident);
            }
        }
    }

    private static void seed(Connection c) throws Exception {
        boolean old = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            demoCustomer(c, "DEMO-ALICE-01", "Alice", "Demo", "401111111101", "100000000001", "alice.demo@invalid.example", "1234", "1500.00");
            demoCustomer(c, "DEMO-BOB-02", "Bob", "Demo", "401111111102", "100000000002", "bob.demo@invalid.example", "5678", "2500.00");
            c.commit();
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(old);
        }
    }

    private static void demoCustomer(Connection c, String nationalId, String first, String last,
                                     String card, String number, String email, String pin, String balance)
            throws SQLException {
        long customerId;
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO customers(national_id, first_name, last_name, birth_date, phone, email) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, nationalId); stmt.setString(2, first); stmt.setString(3, last);
            stmt.setDate(4, java.sql.Date.valueOf(LocalDate.of(1995, 1, 1)));
            stmt.setString(5, "+12025550" + ("Alice".equals(first) ? "101" : "202"));
            stmt.setString(6, email); stmt.executeUpdate();
            try (ResultSet r = stmt.getGeneratedKeys()) { r.next(); customerId = r.getLong(1); }
        }
        BigDecimal initialBalance = new BigDecimal(balance);
        long accountId;
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO accounts(customer_id, account_number, card_number, pin_hash, account_type, balance, status) "
                        + "VALUES (?, ?, ?, ?, 'SAVINGS', ?, 'ACTIVE')", Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, customerId); stmt.setString(2, number); stmt.setString(3, card);
            stmt.setString(4, BCryptUtil.hash(pin)); stmt.setBigDecimal(5, initialBalance);
            stmt.executeUpdate();
            try (ResultSet r = stmt.getGeneratedKeys()) { r.next(); accountId = r.getLong(1); }
        }
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO transactions (account_id, transaction_type, amount, balance_after_transaction) "
                        + "VALUES (?, 'DEPOSIT', ?, ?)")) {
            stmt.setLong(1, accountId); stmt.setBigDecimal(2, initialBalance);
            stmt.setBigDecimal(3, initialBalance); stmt.executeUpdate();
        }
    }

    private static void writeProperties(Path file, String... values) throws IOException {
        Properties settings = new Properties();
        for (int index = 0; index < values.length; index += 2) {
            settings.setProperty(values[index], values[index + 1]);
        }
        try (java.io.OutputStream output = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            settings.store(output, "Generated locally; never commit or share this file");
        }
        try {
            Files.setPosixFilePermissions(file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // NTFS ACLs are inherited from the invoking user's local folder.
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String jdbcUrl(boolean database) {
        return "jdbc:mysql://" + HOST + ":3306/" + (database ? DATABASE : "")
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }
}
