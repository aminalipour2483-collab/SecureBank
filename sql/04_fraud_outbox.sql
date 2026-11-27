-- SecureBank P6 — durable transactional fraud outbox (MySQL 8.0+, InnoDB)
-- Back up the database before applying. This migration is for an existing P5 database.
USE bank_system_demo;

ALTER TABLE fraud_alerts
    ADD COLUMN source_outbox_id BIGINT UNSIGNED NULL AFTER transfer_id;

CREATE TABLE fraud_outbox (
    outbox_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(80) NOT NULL,
    account_id BIGINT UNSIGNED NOT NULL,
    customer_id BIGINT UNSIGNED NOT NULL,
    transaction_type ENUM('DEPOSIT','WITHDRAW','TRANSFER_OUT') NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    balance_before DECIMAL(18,2) NOT NULL,
    transaction_id BIGINT UNSIGNED NOT NULL,
    transfer_id BIGINT UNSIGNED NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    status ENUM('PENDING','PROCESSING','RETRY_WAIT','FAILED','COMPLETED') NOT NULL DEFAULT 'PENDING',
    attempt_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
    retry_cycle INT UNSIGNED NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    locked_at DATETIME(6) NULL,
    last_error VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (outbox_id),
    UNIQUE KEY uq_fraud_outbox_event_key (event_key),
    KEY idx_fraud_outbox_claim (status, available_at, outbox_id),
    KEY idx_fraud_outbox_stale (status, locked_at),
    CONSTRAINT fk_fraud_outbox_account FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fraud_outbox_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fraud_outbox_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fraud_outbox_transfer FOREIGN KEY (transfer_id) REFERENCES transfers(transfer_id) ON DELETE RESTRICT,
    CONSTRAINT chk_fraud_outbox_amount CHECK (amount > 0),
    CONSTRAINT chk_fraud_outbox_attempts CHECK (attempt_count <= 3)
) ENGINE=InnoDB COMMENT='Durable fraud analysis work items committed with financial ledger entries';

ALTER TABLE fraud_alerts
    ADD UNIQUE KEY uq_fraud_alert_source_outbox (source_outbox_id),
    ADD CONSTRAINT fk_fraud_alert_source_outbox FOREIGN KEY (source_outbox_id)
        REFERENCES fraud_outbox(outbox_id) ON DELETE SET NULL;

CREATE TABLE fraud_outbox_attempts (
    attempt_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    outbox_id BIGINT UNSIGNED NOT NULL,
    retry_cycle INT UNSIGNED NOT NULL,
    attempt_no TINYINT UNSIGNED NOT NULL,
    status ENUM('PROCESSING','COMPLETED','FAILED') NOT NULL,
    error_message VARCHAR(2000) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uq_fraud_outbox_attempt (outbox_id,retry_cycle,attempt_no),
    CONSTRAINT fk_fraud_outbox_attempt_event FOREIGN KEY (outbox_id) REFERENCES fraud_outbox(outbox_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Append-only execution attempt history for fraud outbox events';

CREATE TABLE fraud_event_results (
    result_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    outbox_id BIGINT UNSIGNED NOT NULL,
    risk_score INT UNSIGNED NOT NULL,
    risk_level ENUM('LOW','MEDIUM','HIGH','CRITICAL') NOT NULL,
    rule_codes VARCHAR(1000) NOT NULL DEFAULT '',
    details TEXT NOT NULL,
    evaluated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (result_id),
    UNIQUE KEY uq_fraud_event_result_outbox (outbox_id),
    CONSTRAINT fk_fraud_event_result_outbox FOREIGN KEY (outbox_id) REFERENCES fraud_outbox(outbox_id) ON DELETE RESTRICT,
    CONSTRAINT chk_fraud_event_result_score CHECK (risk_score BETWEEN 0 AND 100)
) ENGINE=InnoDB COMMENT='Stores every fraud assessment including LOW/no-alert results';

CREATE TABLE fraud_outbox_retry_events (
    retry_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    outbox_id BIGINT UNSIGNED NOT NULL,
    admin_username VARCHAR(100) NOT NULL,
    retry_cycle INT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (retry_event_id),
    KEY idx_fraud_retry_event (outbox_id,created_at),
    CONSTRAINT fk_fraud_retry_event_outbox FOREIGN KEY (outbox_id) REFERENCES fraud_outbox(outbox_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Audit trail for administrator-triggered fraud retries';


-- Standard local-demo least-privilege grants. If your MySQL users/hosts differ,
-- adapt these names before execution. Customer app only inserts outbox work;
-- administrator app claims/processes work and writes assessment/alert records.
GRANT INSERT ON bank_system_demo.fraud_outbox TO 'bank_customer_app'@'localhost';
GRANT SELECT, UPDATE ON bank_system_demo.fraud_outbox TO 'bank_admin_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON bank_system_demo.fraud_outbox_attempts TO 'bank_admin_app'@'localhost';
GRANT SELECT, INSERT ON bank_system_demo.fraud_event_results TO 'bank_admin_app'@'localhost';
GRANT SELECT, INSERT ON bank_system_demo.fraud_outbox_retry_events TO 'bank_admin_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON bank_system_demo.fraud_alerts TO 'bank_admin_app'@'localhost';
