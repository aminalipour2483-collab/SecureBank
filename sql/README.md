# Database setup and migration

Use the interactive installer as described in the root README (it reads `01_install_fresh.sql` and creates the accounts with unique random passwords). Alternatively run `INSTALL_ALL.sql` once on a fresh local instance of MySQL 8.0.18+ and securely capture its generated database passwords. The manual script seeds known, fictional demo login credentials; do not use it in production.

`02_CUSTOMER_RECOVERY_AND_INBOX_UPDATE.sql` is only for older versions of the demo schema missing these features. Review and back up before running it. Never apply it to a fresh or already migrated schema. `historical/` contains archived earlier changes; they target old schemas and are not installation steps. `least_privilege_users.sql` is a pointer to the current installation grants.

Neither installation path is intended to overwrite an existing schema. MySQL DDL can partially succeed; stop on errors, inspect the state and avoid destructive cleanup of any existing data. The Java installer checks existing app users and local properties before creating anything. The manual path assumes an empty local instance with neither app account present.


## P5 — customer archive/restore lifecycle

For an existing P4 database, take a verified backup first, then run `03_customer_lifecycle_archive.sql` once against `bank_system_demo` before starting a P5 build. The migration expands `customers.status` to include `ARCHIVED`, adds reversible archive-state columns to `customers` and `accounts`, and creates append-only lifecycle/account-status audit tables. It is additive and does not delete customers, accounts, balances, transfers, or ledger rows. MySQL DDL can implicitly commit; if the script stops part-way, inspect `INFORMATION_SCHEMA` and rerun only after verifying each step. Do not run the fresh-install scripts against an existing database.

For a new database, `01_install_fresh.sql` and `INSTALL_ALL.sql` already include the P5 schema.


## P6 — transactional fraud outbox

For an existing P5 database, take a verified backup and run `04_fraud_outbox.sql` exactly once as a DBA before starting P6. It adds `fraud_outbox`, attempt history, full assessment results, manual retry audit, and an idempotent `source_outbox_id` link on fraud alerts. It also applies the standard local-demo grants for `bank_customer_app@localhost` and `bank_admin_app@localhost`; adapt these statements if your configured DB accounts differ.

The customer account receives INSERT only on the outbox. The admin account claims and updates outbox work, writes attempt/results/retry records, and inserts idempotently linked fraud alerts. The outbox worker runs only in the administrator application after login. See `../P6-START-HERE.md` for validation queries and operating details.

## P8 — release permissions
Existing databases already at P6/P7 must apply `05_release_permissions.sql` (DBA, after backup); it adds missing lifecycle audit grants only. Fresh P8 installers already grant these. Run `P8_read_only_checks.sql` after upgrade. The new outbox completion inserts results exactly once, so the existing SELECT/INSERT result privilege suffices. Review legacy broader role permissions before supervised acceptance.
