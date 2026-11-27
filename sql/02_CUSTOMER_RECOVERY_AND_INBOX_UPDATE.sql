-- Apply once to an existing bank_system_demo database created by the previous release.
-- This migration is non-destructive: it does not drop or truncate any customer or financial data.
-- Run in MySQL Workbench as root (or an account allowed to ALTER and GRANT).

USE bank_system_demo;

-- Add explicit lock cause. Existing locked accounts with >=3 failed PIN attempts are
-- classified as FAILED_PIN; other locked accounts remain administrator-locked.
SET @has_lock_reason := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'accounts' AND column_name = 'lock_reason'
);
SET @ddl := IF(@has_lock_reason = 0,
    'ALTER TABLE accounts ADD COLUMN lock_reason ENUM(''NONE'',''FAILED_PIN'',''ADMIN'',''BAN'') NOT NULL DEFAULT ''NONE'' AFTER status',
    'SELECT ''accounts.lock_reason already exists'' AS migration_status');
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

UPDATE accounts a
JOIN customers c ON c.customer_id = a.customer_id
SET a.lock_reason = CASE
    WHEN a.status <> 'LOCKED' THEN 'NONE'
    WHEN c.status = 'BANNED' THEN 'BAN'
    WHEN a.failed_pin_attempts >= 3 THEN 'FAILED_PIN'
    ELSE 'ADMIN'
END;

CREATE TABLE IF NOT EXISTS pin_recovery_attempts (
    card_fingerprint CHAR(64) NOT NULL,
    failed_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
    locked_until DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (card_fingerprint),
    INDEX idx_pin_recovery_locked_until (locked_until)
) ENGINE = InnoDB
COMMENT = 'Throttling state for local customer PIN recovery';

-- Customers need UPDATE only to mark their own admin-originated inbox messages read.
GRANT UPDATE ON bank_system_demo.messages TO 'bank_customer_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON bank_system_demo.pin_recovery_attempts TO 'bank_customer_app'@'localhost';

SELECT 'Customer PIN recovery and authenticated inbox migration applied' AS migration_status;
