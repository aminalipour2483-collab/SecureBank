package com.bank;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.bank.testkit.TestKit.*;

/**
 * Source-level guard rails. They run from the project root (as run-tests.sh does) and fail if a
 * later change quietly undoes a security decision.
 */
public class ArchitectureRulesTest {

    static List<Path> javaFiles(String dir) throws IOException {
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    static List<String> offenders(List<Path> files, String needle) throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : files) if (Files.readString(p).contains(needle)) bad.add(p.toString());
        return bad;
    }

    public static void main(String[] args) throws Exception {
        check("run from the project root (src/ exists)", Files.isDirectory(Paths.get("src/com/bank")));

        section("[1] Admin views reach data only through guarded services");
        List<Path> adminViews = javaFiles("src/com/bank/view/admin");
        check("found admin view sources", !adminViews.isEmpty());
        checkEquals("no admin view references com.bank.dao", List.of(), offenders(adminViews, "com.bank.dao"));
        checkEquals("no admin view references java.sql", List.of(), offenders(adminViews, "java.sql"));
        checkEquals("no admin view reaches a fraudring repository directly (service only)", List.of(),
                offenders(adminViews, "JdbcFraudRingResultRepository"));
        checkEquals("no admin view reaches the fraudring infrastructure package", List.of(),
                offenders(adminViews, "com.bank.fraudring.infrastructure"));

        section("[1b] Customer views reach account data only through guarded services");
        List<Path> customerViews = javaFiles("src/com/bank/view").stream()
                .filter(p -> !p.toString().replace('\\', '/').contains("/admin/"))
                .toList();
        check("found customer view sources", !customerViews.isEmpty());
        checkEquals("no customer view references com.bank.dao", List.of(), offenders(customerViews, "com.bank.dao"));
        checkEquals("no customer view references java.sql", List.of(), offenders(customerViews, "java.sql"));

        section("[2] Graph application/domain layers are infrastructure-free");
        List<Path> inner = new ArrayList<>(javaFiles("src/com/bank/graph/domain"));
        inner.addAll(javaFiles("src/com/bank/graph/application"));
        check("found graph sources", !inner.isEmpty());
        checkEquals("no java.sql / DAO / Swing in domain+application", List.of(),
                new ArrayList<>(List.of(offenders(inner, "java.sql"), offenders(inner, "com.bank.dao"),
                        offenders(inner, "javax.swing"), offenders(inner, "com.bank.config")).stream()
                        .flatMap(List::stream).toList()));

        section("[2b] Fraud-ring application/domain layers are infrastructure-free (same rule as the graph)");
        List<Path> ringInner = new ArrayList<>(javaFiles("src/com/bank/fraudring/domain"));
        ringInner.addAll(javaFiles("src/com/bank/fraudring/application"));
        check("found fraudring sources", !ringInner.isEmpty());
        checkEquals("no java.sql / DAO / Swing / config in fraudring domain+application", List.of(),
                new ArrayList<>(List.of(offenders(ringInner, "java.sql"), offenders(ringInner, "com.bank.dao"),
                        offenders(ringInner, "javax.swing"), offenders(ringInner, "com.bank.config"),
                        offenders(ringInner, "com.bank.fraudring.infrastructure")).stream()
                        .flatMap(List::stream).toList()));
        // the application layer talks to the result store through its port only
        List<Path> ringApp = javaFiles("src/com/bank/fraudring/application");
        checkEquals("application references the JDBC adapter nowhere (port only)", List.of(),
                offenders(ringApp, "JdbcFraudRingResultRepository"));

        section("[2c] Customer-facing service composition installs the principal guard");
        String factory = Files.readString(Paths.get("src/com/bank/service/ServiceFactory.java"));
        check("ServiceFactory creates CustomerAccessGuard", factory.contains("new CustomerAccessGuard("));
        check("AccountService receives CustomerAccessGuard", factory.contains("pinRecoveryDAO, customerAccessGuard"));
        check("TransferService receives CustomerAccessGuard", factory.contains("fraudDetectionService, customerAccessGuard"));

        section("[3] No credentials compiled into the code; the pool owns all connections");
        List<Path> all = javaFiles("src");
        checkEquals("runtime source has no hardcoded JDBC password property", List.of(),
                offenders(all, "db.password="));
        // The offline installer is intentionally outside the application's service/DAO runtime.
        // It must establish a privileged MySQL connection before the two client pools exist.
        List<String> drivers = offenders(all, "DriverManager.getConnection").stream()
                .filter(s -> !s.replace('\\', '/').endsWith("tools/LocalDemoSetup.java"))
                .toList();
        checkEquals("runtime source opens no direct JDBC connections (installer excluded)",
                List.of(), drivers);
        List<String> poolOwners = offenders(all, "HikariDataSource");
        checkEquals("only one source references the connection pool", 1, poolOwners.size());
        check("...and it is DatabaseConnection", poolOwners.stream().allMatch(s -> s.endsWith("DatabaseConnection.java")), poolOwners.toString());
        checkEquals("DatabaseConnection has no DB_PASSWORD constant", List.of(),
                offenders(List.of(Paths.get("src/com/bank/config/DatabaseConnection.java")), "DB_PASSWORD"));
        checkEquals("the deprecated per-thread closeConnection() has no callers (connections return via try-with-resources)",
                List.of(), offenders(all, ".closeConnection()"));

        section("[4] Only the authentication service mints administrator principals");
        List<Path> main = new ArrayList<>(all);
        List<String> minters = offenders(main, "new AdminPrincipal(");
        check("AdminPrincipal is constructed only inside com.bank.security",
                minters.stream().allMatch(s -> s.replace('\\', '/').contains("com/bank/security/")), minters.toString());
        finish("ArchitectureRulesTest");
    }
}
