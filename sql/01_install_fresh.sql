-- Fresh local DEMO installation, never destroys an existing database.
-- For a NEW database only; SETUP-WINDOWS.bat checks for an existing database first.
-- No real banking funds. Do not deploy this direct-MySQL desktop design as a live bank.
CREATE DATABASE bank_system_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bank_system_demo;

CREATE TABLE customers (
    customer_id   BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    national_id   VARCHAR(20)      NOT NULL,
    first_name    VARCHAR(60)      NOT NULL,
    last_name     VARCHAR(60)      NOT NULL,
    birth_date    DATE             NOT NULL,
    phone         VARCHAR(20)      NOT NULL,
    email         VARCHAR(120)     NOT NULL,
    created_at    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- v3.0 Fraud module: admin ban capability -------------------------------
    -- BANNED customers cannot log in and all their accounts are LOCKED.
    status         ENUM('ACTIVE','BANNED','ARCHIVED') NOT NULL DEFAULT 'ACTIVE',
    banned_reason  VARCHAR(500)     NULL,
    banned_at      DATETIME         NULL,
    archived_at    DATETIME         NULL,
    archived_by    VARCHAR(100)     NULL,
    archive_previous_status ENUM('ACTIVE','BANNED') NULL,

    -- -------------------------------------------------------------------------
    CONSTRAINT pk_customers
        PRIMARY KEY (customer_id),

    CONSTRAINT uq_customers_national_id
        UNIQUE (national_id),

    CONSTRAINT uq_customers_email
        UNIQUE (email)

    -- NOTE: birth_date < CURDATE() is intentionally omitted.
    -- CURDATE() is non-deterministic and causes MySQL Error 3823.
    -- This rule is enforced in the Java Service layer.
)
ENGINE = InnoDB
COMMENT = 'Registered bank customers';

CREATE INDEX idx_customers_last_name ON customers (last_name);
CREATE INDEX idx_customers_phone     ON customers (phone);
CREATE INDEX idx_customers_status    ON customers (status);


-- =============================================================================
-- 2. TABLE: accounts
--    One row per bank account; each customer may hold multiple accounts.
-- =============================================================================
CREATE TABLE accounts (
    account_id     BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    customer_id    BIGINT UNSIGNED  NOT NULL,
    account_number VARCHAR(20)      NOT NULL,
    card_number    CHAR(16)         NOT NULL,
    pin_hash       VARCHAR(255)     NOT NULL,
    account_type   ENUM('SAVINGS','CHECKING')  NOT NULL DEFAULT 'SAVINGS',
    balance        DECIMAL(18, 2)   NOT NULL    DEFAULT 0.00,
    status         ENUM('ACTIVE','LOCKED','CLOSED') NOT NULL DEFAULT 'ACTIVE',
    lock_reason    ENUM('NONE','FAILED_PIN','ADMIN','BAN') NOT NULL DEFAULT 'NONE',
    closure_reason ENUM('NONE','CUSTOMER_ARCHIVED','CUSTOMER_REQUEST','ADMIN_DECISION') NOT NULL DEFAULT 'NONE',
    archive_previous_status ENUM('ACTIVE','LOCKED') NULL,
    archive_previous_lock_reason ENUM('NONE','FAILED_PIN','ADMIN','BAN') NULL,
    created_at     DATETIME         NOT NULL    DEFAULT CURRENT_TIMESTAMP,

    -- -------------------------------------------------------------------------
    CONSTRAINT pk_accounts
        PRIMARY KEY (account_id),

    CONSTRAINT fk_accounts_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    CONSTRAINT uq_accounts_account_number
        UNIQUE (account_number),

    CONSTRAINT uq_accounts_card_number
        UNIQUE (card_number),

    -- Safe CHECK: balance is a plain column value comparison — fully deterministic
    CONSTRAINT chk_accounts_balance
        CHECK (balance >= 0.00)

    -- NOTE: CHECK on card_number format (REGEXP) removed for portability.
    -- NOTE: CHECK (sender <> receiver) style checks on FK columns are omitted
    -- to avoid MySQL Error 3814. Enforced in the Java Service layer instead.
)
ENGINE = InnoDB
COMMENT = 'Bank accounts linked to customers';

CREATE INDEX idx_accounts_customer_id ON accounts (customer_id);
CREATE INDEX idx_accounts_status      ON accounts (status);


-- =============================================================================
-- 2A. CUSTOMER LIFECYCLE AUDIT TABLES (P5)
-- =============================================================================
CREATE TABLE customer_lifecycle_events (
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

CREATE TABLE account_status_history (
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


-- =============================================================================
-- 3. TABLE: transactions
--    Immutable ledger entry for every balance-affecting event on an account.
-- =============================================================================
CREATE TABLE transactions (
    transaction_id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    account_id                BIGINT UNSIGNED  NOT NULL,
    transaction_type          ENUM(
                                  'DEPOSIT',
                                  'WITHDRAW',
                                  'TRANSFER_IN',
                                  'TRANSFER_OUT'
                              ) NOT NULL,
    amount                    DECIMAL(18, 2)   NOT NULL,
    balance_after_transaction DECIMAL(18, 2)   NOT NULL,
    created_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- -------------------------------------------------------------------------
    CONSTRAINT pk_transactions
        PRIMARY KEY (transaction_id),

    CONSTRAINT fk_transactions_account
        FOREIGN KEY (account_id)
        REFERENCES accounts (account_id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    -- Safe CHECKs: plain column value comparisons — fully deterministic
    CONSTRAINT chk_transactions_amount
        CHECK (amount > 0.00),

    CONSTRAINT chk_transactions_balance_after
        CHECK (balance_after_transaction >= 0.00)
)
ENGINE = InnoDB
COMMENT = 'Immutable ledger of all account transactions';

CREATE INDEX idx_transactions_account_id ON transactions (account_id);
CREATE INDEX idx_transactions_created_at ON transactions (created_at);
CREATE INDEX idx_transactions_type       ON transactions (transaction_type);

-- Composite: covers "account history newest-first" — the most common query
CREATE INDEX idx_transactions_account_date
    ON transactions (account_id, created_at DESC);


-- =============================================================================
-- 4. TABLE: transfers
--    Links a paired TRANSFER_OUT / TRANSFER_IN entry across two accounts.
-- =============================================================================
CREATE TABLE transfers (
    transfer_id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    sender_account_id    BIGINT UNSIGNED  NOT NULL,
    receiver_account_id  BIGINT UNSIGNED  NOT NULL,
    amount               DECIMAL(18, 2)   NOT NULL,
    reference_number     VARCHAR(36)      NOT NULL,
    status               ENUM('PENDING','COMPLETED','FAILED','REVERSED')
                             NOT NULL DEFAULT 'PENDING',
    created_at           DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- -------------------------------------------------------------------------
    CONSTRAINT pk_transfers
        PRIMARY KEY (transfer_id),

    CONSTRAINT fk_transfers_sender
        FOREIGN KEY (sender_account_id)
        REFERENCES accounts (account_id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    CONSTRAINT fk_transfers_receiver
        FOREIGN KEY (receiver_account_id)
        REFERENCES accounts (account_id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    CONSTRAINT uq_transfers_reference_number
        UNIQUE (reference_number),

    -- Safe CHECK: plain column value comparison — fully deterministic
    CONSTRAINT chk_transfers_amount
        CHECK (amount > 0.00)

    -- NOTE: CHECK (sender_account_id <> receiver_account_id) is intentionally
    -- omitted. MySQL 8 raises Error 3814 when a CHECK references a column that
    -- is also part of a FOREIGN KEY with a referential action (ON UPDATE CASCADE
    -- / ON DELETE RESTRICT). This rule is enforced in the Java Service layer.
)
ENGINE = InnoDB
COMMENT = 'Inter-account transfer records';

CREATE INDEX idx_transfers_sender     ON transfers (sender_account_id);
CREATE INDEX idx_transfers_receiver   ON transfers (receiver_account_id);
CREATE INDEX idx_transfers_status     ON transfers (status);
CREATE INDEX idx_transfers_created_at ON transfers (created_at);


-- =============================================================================
-- 5. TABLE: idempotency_requests
--    Durable replay records for deposits, withdrawals, and transfers.
--    The request row and the financial posting are committed atomically.
-- =============================================================================
CREATE TABLE idempotency_requests (
    idempotency_key  VARCHAR(64)     NOT NULL,
    operation_type   VARCHAR(20)     NOT NULL,
    request_hash     CHAR(64)        NOT NULL,
    result_status    VARCHAR(16)     NULL,
    result_id        BIGINT UNSIGNED NULL,
    customer_id      BIGINT UNSIGNED NULL,
    balance_before   DECIMAL(18, 2)  NULL,
    balance_after    DECIMAL(18, 2)  NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     DATETIME        NULL,

    CONSTRAINT pk_idempotency_requests PRIMARY KEY (idempotency_key),
    CONSTRAINT chk_idempotency_operation
        CHECK (operation_type IN ('DEPOSIT', 'WITHDRAW', 'TRANSFER')),
    CONSTRAINT chk_idempotency_result_status
        CHECK (result_status IS NULL OR result_status = 'COMPLETED')
)
ENGINE = InnoDB
COMMENT = 'Durable replay protection for financial commands';

CREATE INDEX idx_idempotency_created_at ON idempotency_requests (created_at);


-- =============================================================================
-- 6. TABLE: messages
--    Customer ↔ Admin messaging (support inbox).
-- =============================================================================
CREATE TABLE messages (
    message_id    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    customer_id   BIGINT UNSIGNED  NOT NULL,
    sender_type   ENUM('CUSTOMER', 'ADMIN')  NOT NULL,
    subject       VARCHAR(200)     NOT NULL,
    message       TEXT             NOT NULL,
    created_at    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_read       BOOLEAN          NOT NULL DEFAULT FALSE,

    -- -------------------------------------------------------------------------
    CONSTRAINT pk_messages
        PRIMARY KEY (message_id),

    CONSTRAINT fk_messages_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
)
ENGINE = InnoDB
COMMENT = 'Support messages between customers and admin';

CREATE INDEX idx_messages_customer_id ON messages (customer_id);
CREATE INDEX idx_messages_created_at  ON messages (created_at DESC);
CREATE INDEX idx_messages_is_read     ON messages (is_read);
CREATE INDEX idx_messages_sender_type ON messages (sender_type);


-- =============================================================================
-- 6B. TABLE: pin_recovery_attempts
-- Stores only a SHA-256 card fingerprint, never the raw card number.
-- Five failed identity checks trigger a 15-minute cooldown.
-- =============================================================================
CREATE TABLE pin_recovery_attempts (
    card_fingerprint CHAR(64) NOT NULL,
    failed_attempts  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    locked_until     DATETIME NULL,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (card_fingerprint),
    INDEX idx_pin_recovery_locked_until (locked_until)
) ENGINE = InnoDB
COMMENT = 'Throttling state for local customer PIN recovery';


-- =============================================================================
-- 5B. TABLE: fraud_alerts                                  (v3.0 — NEW)
--      One row per transaction that the Fraud Detection Engine scored as
--      suspicious (risk level >= MEDIUM). The manager reviews each alert in
--      the admin panel and can CONFIRM it (optionally banning the customer)
--      or DISMISS it as a false positive.
--      FKs use ON DELETE SET NULL so the audit trail survives even if the
--      referenced customer/account/transaction rows are ever removed.
-- =============================================================================
CREATE TABLE fraud_alerts (
    alert_id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    customer_id      BIGINT UNSIGNED  NULL,
    account_id       BIGINT UNSIGNED  NULL,
    transaction_id   BIGINT UNSIGNED  NULL,
    transfer_id      BIGINT UNSIGNED  NULL,
    source_outbox_id BIGINT UNSIGNED NULL,
    rule_code        VARCHAR(120)     NOT NULL,
    rule_description VARCHAR(200)     NOT NULL,
    risk_score       INT UNSIGNED     NOT NULL,
    risk_level       ENUM('LOW','MEDIUM','HIGH','CRITICAL') NOT NULL,
    details          TEXT             NOT NULL,
    status           ENUM('PENDING','CONFIRMED','DISMISSED') NOT NULL DEFAULT 'PENDING',
    detected_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by      VARCHAR(60)      NULL,
    reviewed_at      DATETIME         NULL,
    resolution_note  VARCHAR(500)     NULL,

    -- -------------------------------------------------------------------------
    CONSTRAINT pk_fraud_alerts
        PRIMARY KEY (alert_id),

    CONSTRAINT fk_fraud_alerts_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id)
        ON UPDATE CASCADE
        ON DELETE SET NULL,

    CONSTRAINT fk_fraud_alerts_account
        FOREIGN KEY (account_id)
        REFERENCES accounts (account_id)
        ON UPDATE CASCADE
        ON DELETE SET NULL,

    CONSTRAINT fk_fraud_alerts_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES transactions (transaction_id)
        ON UPDATE CASCADE
        ON DELETE SET NULL,

    CONSTRAINT fk_fraud_alerts_transfer
        FOREIGN KEY (transfer_id)
        REFERENCES transfers (transfer_id)
        ON UPDATE CASCADE
        ON DELETE SET NULL,

    -- Safe CHECKs: plain column value comparisons — fully deterministic
    CONSTRAINT chk_fraud_alerts_score
        CHECK (risk_score >= 0 AND risk_score <= 100)
)
ENGINE = InnoDB
COMMENT = 'Fraud detection alerts raised by the transaction scoring engine';

CREATE INDEX idx_fraud_alerts_customer_id ON fraud_alerts (customer_id);
CREATE INDEX idx_fraud_alerts_account_id  ON fraud_alerts (account_id);
CREATE INDEX idx_fraud_alerts_status      ON fraud_alerts (status);
CREATE INDEX idx_fraud_alerts_risk_level  ON fraud_alerts (risk_level);
CREATE INDEX idx_fraud_alerts_detected_at ON fraud_alerts (detected_at DESC);

-- P6 durable transactional fraud outbox
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


ALTER TABLE fraud_alerts
    ADD UNIQUE KEY uq_fraud_alert_source_outbox (source_outbox_id),
    ADD CONSTRAINT fk_fraud_alert_source_outbox FOREIGN KEY (source_outbox_id)
        REFERENCES fraud_outbox(outbox_id) ON DELETE SET NULL;





-- Columns originally appended at the end of the legacy example SQL.
ALTER TABLE customers ADD COLUMN address TEXT NULL AFTER email;
ALTER TABLE customers ADD COLUMN postal_code VARCHAR(20) NULL AFTER address;
ALTER TABLE customers ADD COLUMN occupation VARCHAR(100) NULL AFTER postal_code;
ALTER TABLE accounts ADD COLUMN failed_pin_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER pin_hash;

CREATE OR REPLACE VIEW vw_customer_summary AS
SELECT
    c.customer_id,
    CONCAT(c.first_name, ' ', c.last_name) AS full_name,
    c.email,
    COUNT(a.account_id)                     AS account_count,
    COALESCE(SUM(a.balance), 0.00)          AS total_balance
FROM customers c
LEFT JOIN accounts a ON a.customer_id = c.customer_id
GROUP BY c.customer_id, full_name, c.email;


-- Account statement: all transactions with customer name
CREATE OR REPLACE VIEW vw_account_statement AS
SELECT
    t.transaction_id,
    a.account_number,
    CONCAT(c.first_name, ' ', c.last_name) AS customer_name,
    t.transaction_type,
    t.amount,
    t.balance_after_transaction             AS running_balance,
    t.created_at                            AS transaction_date
FROM transactions t
JOIN accounts  a ON a.account_id  = t.account_id
JOIN customers c ON c.customer_id = a.customer_id
ORDER BY t.account_id, t.created_at DESC;


-- Transfer history: both sides resolved to human-readable names
CREATE OR REPLACE VIEW vw_transfer_history AS
SELECT
    tf.transfer_id,
    tf.reference_number,
    CONCAT(sc.first_name, ' ', sc.last_name) AS sender_name,
    sa.account_number                         AS sender_account,
    CONCAT(rc.first_name, ' ', rc.last_name) AS receiver_name,
    ra.account_number                         AS receiver_account,
    tf.amount,
    tf.status,
    tf.created_at
FROM transfers tf
JOIN accounts  sa ON sa.account_id  = tf.sender_account_id
JOIN customers sc ON sc.customer_id = sa.customer_id
JOIN accounts  ra ON ra.account_id  = tf.receiver_account_id
JOIN customers rc ON rc.customer_id = ra.customer_id
ORDER BY tf.created_at DESC;


-- Fraud alert dashboard: alerts with customer / account context (v3.0 — NEW)
CREATE OR REPLACE VIEW vw_fraud_alerts AS
SELECT
    fa.alert_id,
    fa.customer_id,
    CONCAT(c.first_name, ' ', c.last_name)     AS customer_name,
    c.status                                   AS customer_status,
    a.account_number,
    fa.rule_code,
    fa.risk_score,
    fa.risk_level,
    fa.status,
    fa.detected_at,
    fa.reviewed_by,
    fa.reviewed_at,
    fa.resolution_note
FROM fraud_alerts fa
LEFT JOIN customers c ON c.customer_id = fa.customer_id
LEFT JOIN accounts  a ON a.account_id  = fa.account_id
ORDER BY fa.detected_at DESC;


CREATE TABLE IF NOT EXISTS fraud_ring_analyses (
    analysis_id       BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    idempotency_key   CHAR(64)          NOT NULL,
    scope_type        ENUM('FULL','SUBSET','SINGLE') NOT NULL,
    scope_customer_id BIGINT UNSIGNED  NULL,       -- set for SINGLE only
    lookback_days     INT               NULL,       -- NULL = all time
    graph_stats       JSON              NOT NULL,   -- nodes/edges/communities/cycles summary
    top_customers     JSON              NOT NULL,   -- ranked CustomerRisk[] with XAI reasons
    top_groups        JSON              NOT NULL,   -- ranked GroupRisk[] with XAI reasons
    analyzed_by       VARCHAR(60)       NOT NULL,   -- admin username
    analyzed_at       DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_fraud_ring_analyses
        PRIMARY KEY (analysis_id),

    CONSTRAINT uq_fraud_ring_analyses_key
        UNIQUE (idempotency_key),

    -- Safe CHECKs: plain column value comparisons — fully deterministic
    CONSTRAINT chk_fraud_ring_lookback
        CHECK (lookback_days IS NULL OR (lookback_days >= 1 AND lookback_days <= 3650)),

    CONSTRAINT chk_fraud_ring_scope
        CHECK (scope_customer_id IS NULL OR scope_type = 'SINGLE'),

    CONSTRAINT chk_fraud_ring_scope_single
        CHECK (scope_type <> 'SINGLE' OR scope_customer_id IS NOT NULL)
)
ENGINE = InnoDB
COMMENT = 'Fraud-ring analysis runs (analytic output; read by the admin panel)';

CREATE INDEX idx_fraud_ring_analyzed_by ON fraud_ring_analyses (analyzed_by);
CREATE INDEX idx_fraud_ring_analyzed_at ON fraud_ring_analyses (analyzed_at DESC);



-- =============================================================================
-- 9. TABLE: admin_users
--    Local administrator credentials. Passwords are stored as BCrypt hashes.
-- =============================================================================
CREATE TABLE admin_users (
    admin_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username       VARCHAR(60) NOT NULL,
    password_hash  VARCHAR(60) NOT NULL,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (admin_id),
    CONSTRAINT uq_admin_users_username UNIQUE (username)
) ENGINE = InnoDB
COMMENT = 'Administrator login credentials (BCrypt hashes only)';

CREATE INDEX idx_admin_users_active ON admin_users (is_active);
