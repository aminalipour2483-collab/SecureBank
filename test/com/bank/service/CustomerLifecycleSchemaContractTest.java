package com.bank.service;

import java.nio.file.Files;
import java.nio.file.Path;
import static com.bank.testkit.TestKit.*;

public final class CustomerLifecycleSchemaContractTest {
    public static void main(String[] args) throws Exception {
        String migration = Files.readString(Path.of("sql/03_customer_lifecycle_archive.sql"));
        String fresh = Files.readString(Path.of("sql/01_install_fresh.sql"));
        section("[1] Existing database migration contract");
        check("migration expands customer status with ARCHIVED", migration.contains("'ACTIVE','BANNED','ARCHIVED'"));
        check("migration stores pre-archive customer status", migration.contains("archive_previous_status"));
        check("migration stores account pre-archive state", migration.contains("archive_previous_lock_reason"));
        check("migration creates customer lifecycle audit table", migration.contains("CREATE TABLE IF NOT EXISTS customer_lifecycle_events"));
        check("migration creates account status history", migration.contains("CREATE TABLE IF NOT EXISTS account_status_history"));
        check("migration is additive and does not drop tables", !migration.toUpperCase().contains("DROP TABLE") && !migration.toUpperCase().contains("DROP DATABASE"));
        section("[2] Fresh install schema contract");
        check("fresh schema supports archived customer", fresh.contains("ENUM('ACTIVE','BANNED','ARCHIVED')"));
        check("fresh schema supports archive closure reason", fresh.contains("CUSTOMER_ARCHIVED"));
        check("fresh schema includes lifecycle audit table", fresh.contains("CREATE TABLE customer_lifecycle_events"));
        String permissions=Files.readString(Path.of("sql/05_release_permissions.sql"));
        String installer=Files.readString(Path.of("sql/INSTALL_ALL.sql"));
        String javaInstaller=Files.readString(Path.of("src/com/bank/tools/LocalDemoSetup.java"));
        for(String table:new String[]{"customer_lifecycle_events","account_status_history"}) {
            check("upgrade grants audit write for "+table,permissions.contains("GRANT SELECT, INSERT ON bank_system_demo."+table));
            check("manual fresh grants audit write for "+table,installer.contains("GRANT SELECT, INSERT ON bank_system_demo."+table));
            check("interactive fresh grants audit write for "+table,javaInstaller.contains("GRANT SELECT, INSERT\" + base + \""+table));
        }
        String dao=Files.readString(Path.of("src/com/bank/dao/impl/CustomerDAOImpl.java"));
        check("restore follows SRS ACTIVE transition",dao.contains("UPDATE customers SET status = 'ACTIVE', archive_previous_status = NULL"));
        finish("CustomerLifecycleSchemaContractTest");
    }
}
