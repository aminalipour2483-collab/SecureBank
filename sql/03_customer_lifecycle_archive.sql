-- P5: Customer archive/restore lifecycle migration.
-- Run once against an existing bank_system_demo database after taking a backup.
-- MySQL 8.0+; all column additions are guarded through INFORMATION_SCHEMA.
-- This script is additive and does not delete or rewrite financial ledger rows.
USE bank_system_demo;

ALTER TABLE customers
    MODIFY COLUMN status ENUM('ACTIVE','BANNED','ARCHIVED') NOT NULL DEFAULT 'ACTIVE';

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='customers' AND COLUMN_NAME='archived_at') = 0,
    'ALTER TABLE customers ADD COLUMN archived_at DATETIME NULL AFTER banned_at',
    'SELECT 1');
PREPARE p5_stmt FROM @ddl; EXECUTE p5_stmt; DEALLOCATE PREPARE p5_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='customers' AND COLUMN_NAME='archived_by') = 0,
    'ALTER TABLE customers ADD COLUMN archived_by VARCHAR(100) NULL AFTER archived_at',
    'SELECT 1');
PREPARE p5_stmt FROM @ddl; EXECUTE p5_stmt; DEALLOCATE PREPARE p5_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='customers' AND COLUMN_NAME='archive_previous_status') = 0,
    'ALTER TABLE customers ADD COLUMN archive_previous_status ENUM(''ACTIVE'',''BANNED'') NULL AFTER archived_by',
    'SELECT 1');
PREPARE p5_stmt FROM @ddl; EXECUTE p5_stmt; DEALLOCATE PREPARE p5_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='accounts' AND COLUMN_NAME='closure_reason') = 0,
    'ALTER TABLE accounts ADD COLUMN closure_reason ENUM(''NONE'',''CUSTOMER_ARCHIVED'',''CUSTOMER_REQUEST'',''ADMIN_DECISION'') NOT NULL DEFAULT ''NONE'' AFTER lock_reason',
    'SELECT 1');
PREPARE p5_stmt FROM @ddl; EXECUTE p5_stmt; DEALLOCATE PREPARE p5_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='accounts' AND COLUMN_NAME='archive_previous_status') = 0,
    'ALTER TABLE accounts ADD COLUMN archive_previous_status ENUM(''ACTIVE'',''LOCKED'') NULL AFTER closure_reason',
    'SELECT 1');
PREPARE p5_stmt FROM @ddl; EXECUTE p5_stmt; DEALLOCATE PREPARE p5_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='accounts' AND COLUMN_NAME='archive_previous_lock_reason') = 0,
    'ALTER TABLE accounts ADD COLUMN archive_previous_lock_reason ENUM(''NONE'',''FAILED_PIN'',''ADMIN'',''BAN'') NULL AFTER archive_previous_status',
    'SELECT 1');
PREPARE p5_stmt FROM @ddl; EXECUTE p5_stmt; DEALLOCATE PREPARE p5_stmt;

CREATE TABLE IF NOT EXISTS customer_lifecycle_events (
    event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    customer_id BIGINT UNSIGNED NOT NULL,
    event_type ENUM('BAN','UNBAN','ARCHIVE','RESTORE') NOT NULL,
    actor VARCHAR(100) NOT NULL,
    details VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_customer_lifecycle_events_customer FOREIGN KEY (customer_id)
        REFERENCES customers(customer_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    INDEX idx_customer_lifecycle_customer_time (customer_id, created_at)
) ENGINE=InnoDB COMMENT='Append-only customer lifecycle audit events';

CREATE TABLE IF NOT EXISTS account_status_history (
    history_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NOT NULL,
    previous_status ENUM('ACTIVE','LOCKED','CLOSED') NOT NULL,
    new_status ENUM('ACTIVE','LOCKED','CLOSED') NOT NULL,
    previous_lock_reason ENUM('NONE','FAILED_PIN','ADMIN','BAN') NOT NULL DEFAULT 'NONE',
    new_lock_reason ENUM('NONE','FAILED_PIN','ADMIN','BAN') NOT NULL DEFAULT 'NONE',
    reason VARCHAR(80) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (history_id),
    CONSTRAINT fk_account_status_history_account FOREIGN KEY (account_id)
        REFERENCES accounts(account_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    INDEX idx_account_status_history_account_time (account_id, created_at)
) ENGINE=InnoDB COMMENT='Append-only account status transitions';

SELECT 'P5 customer lifecycle migration applied. Review status and audit tables before using archive/restore.' AS migration_status;
