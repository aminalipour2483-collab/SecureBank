-- P8: run as DBA after migrations 03 and 04 on an existing synthetic demo DB.
-- Adapt user/host names for your installation. No schema or financial data changes.
-- Repeating these GRANT statements is safe for the standard existing users.
USE bank_system_demo;
GRANT SELECT, INSERT ON bank_system_demo.customer_lifecycle_events TO 'bank_admin_app'@'localhost';
GRANT SELECT, INSERT ON bank_system_demo.account_status_history TO 'bank_admin_app'@'localhost';
-- Fraud results now use INSERT only; no extra UPDATE grant is needed.
SHOW GRANTS FOR 'bank_admin_app'@'localhost';
SHOW GRANTS FOR 'bank_customer_app'@'localhost';
-- Older role grants may still include customer SELECT/INSERT on fraud_alerts
-- and admin DELETE on customers. Review SHOW GRANTS; a DBA may revoke those
-- specific legacy grants if present and no other demo tools depend on them.
-- Do not revoke broader grants blindly or use root as the application user.
