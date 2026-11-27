-- =============================================================================
-- IDEMPOTENCY MIGRATION — Phase 2
-- Run this ONCE against an existing bank_system database before launching
-- the Phase 2 binaries. Fresh installations already get this table from
-- BankProject.sql.
-- =============================================================================

USE bank_system;

CREATE TABLE IF NOT EXISTS idempotency_requests (
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
