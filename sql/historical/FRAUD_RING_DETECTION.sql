-- =============================================================================
-- Fraud Ring Detection — analytic OUTPUT schema (Phase 2)                  v3.2
-- =============================================================================
--
-- WHAT THIS IS
-- ------------
-- One NEW table that stores the result of each "Analyze Fraud Rings" run.
--
-- WHAT THIS IS NOT
-- ----------------
-- NOT a change to any system-of-record table. customers, accounts, transfers,
-- transactions, fraud_alerts and idempotency_requests are untouched: this file
-- only CREATEs a new table, so the migration is purely additive (opt-in).
-- Every existing statement in BankProject.sql keeps working unchanged.
--
-- SAFETY NOTES (matching BankProject.sql conventions)
-- ----------------------------------------------------
-- * MySQL Workbench safe: no non-deterministic functions (no CURDATE/NOW in
--   CHECKs), no CHECK on FK columns with referential actions.
-- * risk_score is a plain column comparison — fully deterministic CHECK.
-- * The table is written AFTER the analysis finished (read-only snapshot is
--   closed first), so a long analysis never holds row locks that could block
--   live transactions. The only write is one INSERT in its own short
--   transaction.
-- * Idempotency: idempotency_key is UNIQUE. A re-run with the same canonical
--   request fingerprint replays the stored result instead of inserting again
--   (same contract as idempotency_requests). Concurrent first-runs wait on the
--   PK/unique index and then read the winner's committed row.
--
-- JSON COLUMNS
-- ------------
-- graph_stats / top_customers / top_groups hold the full derived snapshot
-- (nodes, edges, communities, cycles, centrality, risk scores, XAI reasons).
-- They are versioned output, not source data: a new metric extends the JSON
-- payload WITHOUT another schema change, and the admin UI can re-paginate a
-- stored result without re-running the graph algorithms.
-- =============================================================================

USE bank_system;

-- -----------------------------------------------------------------------------
-- TABLE: fraud_ring_analyses
-- One row per completed fraud-ring analysis run. Written by
-- JdbcFraudRingResultRepository (com.bank.fraudring.infrastructure).
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- OPTIONAL least-privilege note (mirrors sql/least_privilege_users.sql):
-- the fraud-ring analyst account only needs:
--   SELECT on customers, accounts, transfers
--   INSERT, SELECT on fraud_ring_analyses
-- No UPDATE/DELETE is required: an analysis row is immutable once written.
-- -----------------------------------------------------------------------------
