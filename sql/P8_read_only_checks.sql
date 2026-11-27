-- Read-only verification; run as DBA on the synthetic database after backup/migration.
USE bank_system_demo;
SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
  AND table_name IN ('customer_lifecycle_events','account_status_history','fraud_outbox','fraud_outbox_attempts','fraud_event_results','fraud_outbox_retry_events');
SHOW GRANTS FOR 'bank_admin_app'@'localhost';
SHOW GRANTS FOR 'bank_customer_app'@'localhost';
-- All following result sets should be empty for a consistent upgraded database.
SELECT customer_id FROM customers WHERE status='ARCHIVED' AND EXISTS
  (SELECT 1 FROM accounts WHERE accounts.customer_id=customers.customer_id AND accounts.status<>'CLOSED');
SELECT outbox_id FROM fraud_outbox WHERE attempt_count>3;
SELECT o.outbox_id FROM fraud_outbox o LEFT JOIN fraud_event_results r ON r.outbox_id=o.outbox_id
  WHERE o.status='COMPLETED' AND r.outbox_id IS NULL;
SELECT o.outbox_id FROM fraud_outbox o JOIN fraud_event_results r ON r.outbox_id=o.outbox_id
  WHERE o.status<>'COMPLETED';
SELECT o.outbox_id FROM fraud_outbox o JOIN fraud_alerts a ON a.source_outbox_id=o.outbox_id
  WHERE o.status<>'COMPLETED'; -- old P6/P7 partial alerts: investigate; do not delete them
SELECT r.outbox_id FROM fraud_event_results r LEFT JOIN fraud_alerts a ON a.source_outbox_id=r.outbox_id
  WHERE (r.risk_level='LOW' AND a.alert_id IS NOT NULL) OR
        (r.risk_level<>'LOW' AND (a.alert_id IS NULL OR a.risk_score<>r.risk_score OR a.risk_level<>r.risk_level));
-- Expired processing rows should be recovered when the admin worker is running.
SELECT outbox_id,retry_cycle,attempt_count,locked_at FROM fraud_outbox WHERE status='PROCESSING'
 AND (locked_at IS NULL OR locked_at<CURRENT_TIMESTAMP(6)-INTERVAL 5 MINUTE);
SELECT status,COUNT(*) AS event_count FROM fraud_outbox GROUP BY status;
