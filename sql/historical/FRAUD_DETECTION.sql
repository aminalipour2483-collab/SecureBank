-- =============================================================================
-- FRAUD DETECTION MODULE — schema migration (v2.x → v3.0)
--
-- Run this script on your EXISTING bank_system database to add the
-- AI-driven fraud detection feature without dropping anything:
--
--   mysql -u root -p bank_system < FRAUD_DETECTION.sql
--
-- What it adds:
--   1. customers.status / banned_reason / banned_at   (admin ban capability)
--   2. fraud_alerts table                              (engine output + review)
--   3. Two sample alerts on the demo transactions      (safe to delete)
--
-- A fresh install from BankProject.sql already includes all of this.
-- =============================================================================
USE bank_system;

-- ── 1. Customer ban columns ───────────────────────────────────────────────────
ALTER TABLE customers
    ADD COLUMN status        ENUM('ACTIVE','BANNED') NOT NULL DEFAULT 'ACTIVE'
        AFTER created_at;

ALTER TABLE customers
    ADD COLUMN banned_reason VARCHAR(500) NULL AFTER status,
    ADD COLUMN banned_at     DATETIME     NULL AFTER banned_reason;

CREATE INDEX idx_customers_status ON customers (status);


-- ── 2. Fraud alerts table ─────────────────────────────────────────────────────
--      FKs use ON DELETE SET NULL so the audit trail survives even if the
--      referenced customer / account / transaction rows are ever removed.
CREATE TABLE IF NOT EXISTS fraud_alerts (
    alert_id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    customer_id      BIGINT UNSIGNED  NULL,
    account_id       BIGINT UNSIGNED  NULL,
    transaction_id   BIGINT UNSIGNED  NULL,
    transfer_id      BIGINT UNSIGNED  NULL,
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

    CONSTRAINT chk_fraud_alerts_score
        CHECK (risk_score >= 0 AND risk_score <= 100),

    KEY idx_fraud_alerts_customer_id (customer_id),
    KEY idx_fraud_alerts_account_id  (account_id),
    KEY idx_fraud_alerts_status      (status),
    KEY idx_fraud_alerts_risk_level  (risk_level),
    KEY idx_fraud_alerts_detected_at (detected_at DESC)
)
ENGINE = InnoDB
COMMENT = 'Fraud detection alerts raised by the transaction scoring engine';


-- ── 3. Dashboard view ─────────────────────────────────────────────────────────
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


-- ── 4. Sample alerts (demo data — DELETE FROM fraud_alerts to clear) ─────────
--      These reference the sample transactions created by BankProject.sql
--      (transaction_id 1 = Alice's deposit, 4 = Alice's transfer out).
INSERT INTO fraud_alerts
    (customer_id, account_id, transaction_id, transfer_id,
     rule_code, rule_description, risk_score, risk_level,
     details, status, detected_at)
VALUES
    (1, 1, 1, NULL,
     'ANOMALOUS_AMOUNT',
     'Amount deviates strongly from account history',
     25, 'MEDIUM',
     'Deposit of 1,000.00 is 2.6σ above this account''s historical mean (z-score outlier, Isolation-Forest-style baseline).',
     'PENDING', NOW()),

    (1, 1, 4, NULL,
     'HIGH_VELOCITY+ODD_HOUR',
     '2 fraud rules triggered',
     45, 'MEDIUM',
     'Burst of transactions within 10 minutes on account 100000000001 (+30) during night hours (+15).',
     'PENDING', NOW());

-- ── Verify ────────────────────────────────────────────────────────────────────
SELECT COUNT(*) AS fraud_alert_rows FROM fraud_alerts;
SELECT customer_id, status, banned_reason FROM customers WHERE status = 'BANNED';
SELECT 'Fraud detection schema installed successfully' AS status;
