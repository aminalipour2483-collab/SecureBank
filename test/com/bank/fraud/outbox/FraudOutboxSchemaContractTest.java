package com.bank.fraud.outbox;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards the P6 schema contract without requiring a running MySQL instance. */
public final class FraudOutboxSchemaContractTest {
    private static int passed;
    private static void check(String n, boolean ok) { if(!ok) throw new AssertionError(n); passed++; }
    public static void main(String[] args) throws Exception {
        String migration=Files.readString(Path.of("sql/04_fraud_outbox.sql")).toLowerCase();
        String fresh=Files.readString(Path.of("sql/01_install_fresh.sql")).toLowerCase();
        String installer=Files.readString(Path.of("sql/INSTALL_ALL.sql")).toLowerCase();
        for(String table:new String[]{"fraud_outbox","fraud_outbox_attempts","fraud_event_results","fraud_outbox_retry_events"}) {
            check("migration creates "+table,migration.contains("create table "+table));
            check("fresh install creates "+table,fresh.contains("create table "+table));
            check("one-time installer creates "+table,installer.contains("create table "+table));
        }
        check("outbox uses unique event key",migration.contains("unique key uq_fraud_outbox_event_key"));
        check("claim supports retry and stale recovery",migration.contains("retry_wait") && migration.contains("processing"));
        check("attempt limit constrained",migration.contains("attempt_count <= 3"));
        check("all assessment results retained",migration.contains("fraud_event_results"));
        check("alert source is unique",migration.contains("uq_fraud_alert_source_outbox"));
        check("writer uses caller transaction",Files.readString(Path.of("src/com/bank/fraud/outbox/FraudOutboxWriter.java")).contains("enqueue(Connection connection"));
        check("deposit and withdrawal enqueue before transaction commit",Files.readString(Path.of("src/com/bank/dao/impl/AccountDAOImpl.java")).contains("FraudOutboxWriter.enqueue(conn"));
        check("transfer enqueues before transaction commit",Files.readString(Path.of("src/com/bank/dao/impl/TransferDAOImpl.java")).contains("FraudOutboxWriter.enqueue(conn"));
        check("new-customer opening deposit is enqueued atomically",Files.readString(Path.of("src/com/bank/dao/impl/RegistrationDAOImpl.java")).contains("FraudOutboxWriter.enqueue(conn"));
        check("additional-account opening deposit is enqueued atomically",Files.readString(Path.of("src/com/bank/dao/impl/AccountDAOImpl.java")).contains("FraudOutboxWriter.enqueue(conn, accountId, account.getCustomerId()"));
        check("service no longer runs fraud scoring inline",!Files.readString(Path.of("src/com/bank/service/impl/TransferServiceImpl.java")).contains("fraudDetectionService.evaluateAndRecord"));
        String installerJava=Files.readString(Path.of("src/com/bank/tools/LocalDemoSetup.java"));
        check("interactive installer grants customer outbox INSERT",installerJava.contains("GRANT INSERT"+"\" + base + \"fraud_outbox"));
        check("interactive installer grants admin outbox processing",installerJava.contains("fraud_outbox_attempts") && installerJava.contains("fraud_event_results") && installerJava.contains("fraud_outbox_retry_events"));
        System.out.println("FraudOutboxSchemaContractTest: "+passed+" passed, 0 failed");
    }
}
