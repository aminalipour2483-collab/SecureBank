-- =============================================================================
-- FIX: Add missing 'messages' table
-- Run this script on your existing bank_system database if you don't want
-- to drop and recreate everything.
-- =============================================================================

USE bank_system;

CREATE TABLE IF NOT EXISTS messages (
    message_id    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    customer_id   BIGINT UNSIGNED  NOT NULL,
    sender_type   ENUM('CUSTOMER', 'ADMIN')  NOT NULL,
    subject       VARCHAR(200)     NOT NULL,
    message       TEXT             NOT NULL,
    created_at    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_read       BOOLEAN          NOT NULL DEFAULT FALSE,

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

CREATE INDEX IF NOT EXISTS idx_messages_customer_id ON messages (customer_id);
CREATE INDEX IF NOT EXISTS idx_messages_created_at  ON messages (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_is_read     ON messages (is_read);
CREATE INDEX IF NOT EXISTS idx_messages_sender_type ON messages (sender_type);

-- Verify
SHOW CREATE TABLE messages;
SELECT 'messages table created successfully' AS status;
