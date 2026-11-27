package com.bank.dao.impl;

import com.bank.config.DatabaseConnection;
import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.dao.CustomerDAO;
import com.bank.exception.BankException;
import com.bank.exception.CustomerNotFoundException;
import com.bank.model.Customer;
import com.bank.model.Customer.CustomerStatus;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CustomerDAOImpl — JDBC implementation of CustomerDAO.
 *
 * Folder: BankManagementSystem/src/com/bank/dao/impl/CustomerDAOImpl.java
 *
 * Rules followed throughout:
 *   • Every query uses PreparedStatement  — prevents SQL injection.
 *   • Every Connection/Statement/ResultSet is closed via try-with-resources
 *     (Connection.close() returns the connection to the shared pool).
 *   • Never hold a connection longer than the method needs it — borrow,
 *     execute, return.
 *   • All exceptions are logged and re-thrown as RuntimeException so the
 *     Service layer (or the Swing UI) can show a proper error message.
 */
public class CustomerDAOImpl implements CustomerDAO {

    // Convenience: borrow a pooled connection for the duration of one call.
    private Connection getConnection() {
        return DatabaseConnection.getInstance().getConnection();
    }

    // =========================================================================
    // HELPER — map one ResultSet row → Customer object
    // =========================================================================
    private Customer mapRow(ResultSet rs) throws SQLException {
        Customer c = new Customer();
        c.setCustomerId(rs.getLong("customer_id"));
        c.setNationalId(rs.getString("national_id"));
        c.setFirstName(rs.getString("first_name"));
        c.setLastName(rs.getString("last_name"));

        // DATE column → java.sql.Date → LocalDate
        Date sqlDate = rs.getDate("birth_date");
        if (sqlDate != null) c.setBirthDate(sqlDate.toLocalDate());

        c.setPhone(rs.getString("phone"));
        c.setEmail(rs.getString("email"));
        c.setAddress(rs.getString("address"));
        c.setPostalCode(rs.getString("postal_code"));
        c.setOccupation(rs.getString("occupation"));

        // DATETIME column → java.sql.Timestamp → LocalDateTime
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) c.setCreatedAt(ts.toLocalDateTime());

        // ── v3.0 fraud-module columns ────────────────────────────────────────
        // Databases created before FRAUD_DETECTION.sql was run do not have
        // these columns yet. Reading them blindly would crash every query,
        // so we probe for the column first and fall back to safe defaults
        // (ACTIVE / no reason / no date) on legacy schemas.
        try { c.setStatus(CustomerStatus.from(rs.getString("status"))); }
        catch (SQLException missingColumn) { c.setStatus(CustomerStatus.ACTIVE); }
        try { c.setBannedReason(rs.getString("banned_reason")); }
        catch (SQLException ignoredLegacyColumn) { /* legacy schema */ }
        try {
            Timestamp banTs = rs.getTimestamp("banned_at");
            if (banTs != null) c.setBannedAt(banTs.toLocalDateTime());
        } catch (SQLException ignoredLegacyColumn) { /* legacy schema */ }
        try {
            Timestamp archiveTs = rs.getTimestamp("archived_at");
            if (archiveTs != null) c.setArchivedAt(archiveTs.toLocalDateTime());
        } catch (SQLException ignoredLegacyColumn) { /* migration 03 not yet applied */ }

        return c;
    }

    // =========================================================================
    // SAVE (INSERT)
    // =========================================================================
    @Override
    public long save(Customer customer) {
        String sql = "INSERT INTO customers "
                   + "(national_id, first_name, last_name, birth_date, phone, email, "
                   + " address, postal_code, occupation) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        // RETURN_GENERATED_KEYS tells JDBC to give us the AUTO_INCREMENT value.
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, customer.getNationalId());
            stmt.setString(2, customer.getFirstName());
            stmt.setString(3, customer.getLastName());
            stmt.setDate  (4, Date.valueOf(customer.getBirthDate())); // LocalDate → sql.Date
            stmt.setString(5, customer.getPhone());
            stmt.setString(6, customer.getEmail());
            stmt.setString(7, customer.getAddress());
            stmt.setString(8, customer.getPostalCode());
            stmt.setString(9, customer.getOccupation());

            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) return -1;

            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] save() failed: " + e.getMessage());
            throw new RuntimeException("Failed to save customer", e);
        }
        return -1;
    }

    // =========================================================================
    // FIND BY ID
    // =========================================================================
    @Override
    public Optional<Customer> findById(long customerId) {
        String sql = "SELECT * FROM customers WHERE customer_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, customerId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] findById() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find customer by ID", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND BY NATIONAL ID
    // =========================================================================
    @Override
    public Optional<Customer> findByNationalId(String nationalId) {
        String sql = "SELECT * FROM customers WHERE national_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nationalId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] findByNationalId() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find customer by national ID", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND BY EMAIL
    // =========================================================================
    @Override
    public Optional<Customer> findByEmail(String email) {
        String sql = "SELECT * FROM customers WHERE email = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] findByEmail() failed: " + e.getMessage());
            throw new RuntimeException("Failed to find customer by email", e);
        }
        return Optional.empty();
    }

    // =========================================================================
    // FIND ALL
    // =========================================================================
    @Override
    public List<Customer> findAll() {
        String sql = "SELECT * FROM customers ORDER BY last_name, first_name";
        List<Customer> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) list.add(mapRow(rs));

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] findAll() failed: " + e.getMessage());
            throw new RuntimeException("Failed to retrieve all customers", e);
        }
        return list;
    }

    // =========================================================================
    // SEARCH BY NAME  (case-insensitive LIKE)
    // =========================================================================
    @Override
    public List<Customer> searchByName(String keyword) {
        String sql = "SELECT * FROM customers "
                   + "WHERE first_name LIKE ? OR last_name LIKE ? "
                   + "ORDER BY last_name, first_name";

        List<Customer> list = new ArrayList<>();
        String pattern = "%" + keyword + "%"; // wrap keyword with wildcards

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pattern);
            stmt.setString(2, pattern);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] searchByName() failed: " + e.getMessage());
            throw new RuntimeException("Failed to search customers by name", e);
        }
        return list;
    }

    // =========================================================================
    // UPDATE
    // =========================================================================
    @Override
    public boolean update(Customer customer) {
        // Mutable fields: phone, email, address, postal_code, occupation.
        // national_id, birth_date, created_at are intentionally excluded.
        String sql = "UPDATE customers "
                   + "SET first_name = ?, last_name = ?, phone = ?, email = ?, "
                   + "    address = ?, postal_code = ?, occupation = ? "
                   + "WHERE customer_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, customer.getFirstName());
            stmt.setString(2, customer.getLastName());
            stmt.setString(3, customer.getPhone());
            stmt.setString(4, customer.getEmail());
            stmt.setString(5, customer.getAddress());
            stmt.setString(6, customer.getPostalCode());
            stmt.setString(7, customer.getOccupation());
            stmt.setLong  (8, customer.getCustomerId());

            return stmt.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] update() failed: " + e.getMessage());
            throw new RuntimeException("Failed to update customer", e);
        }
    }

    // =========================================================================
    // DELETE
    // =========================================================================
    @Override
    public boolean delete(long customerId) {
        String sql = "DELETE FROM customers WHERE customer_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, customerId);
            return stmt.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] delete() failed: " + e.getMessage());
            throw new RuntimeException("Failed to delete customer", e);
        }
    }

    // =========================================================================
    // UPDATE BAN STATUS                                     (v3.0 — FRAUD MODULE)
    // =========================================================================
    @Override
    @Deprecated
    public boolean updateBanStatus(long customerId,
                                   Customer.CustomerStatus newStatus,
                                   String reason) {
        // Compatibility method; never allow a direct customer-only ban flip that
        // would leave ACTIVE accounts usable. Route through the aggregate transaction.
        if (newStatus == Customer.CustomerStatus.BANNED) {
            banCustomerAndLockAccounts(customerId, reason, "system");
            return true;
        }
        if (newStatus == Customer.CustomerStatus.ACTIVE) {
            unbanCustomerAndUnlockAccounts(customerId, "system");
            return true;
        }
        throw new BankException("ARCHIVED is a separate customer lifecycle operation.");
    }

    // =========================================================================
    // ATOMIC BAN / UNBAN
    // The customer row and all of the customer's account rows change in ONE
    // transaction. Locks are taken customer-first, then accounts by id — the
    // same order TransferDAOImpl uses — so ban and transfer cannot deadlock,
    // and a transfer that started earlier finishes before the ban takes effect.
    // =========================================================================
    @Override
    public int banCustomerAndLockAccounts(long customerId, String reason) {
        return banCustomerAndLockAccounts(customerId, reason, "system");
    }

    @Override
    public int banCustomerAndLockAccounts(long customerId, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new BankException("A ban reason is required.");
        }
        if (actor == null || actor.isBlank()) throw new BankException("Administrator identity is required.");
        return inTransaction("ban customer", conn -> {
            requireStatus(conn, customerId, "ACTIVE", "Customer is already banned.");
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE customers SET status = 'BANNED', banned_reason = ?, banned_at = ? "
                    + "WHERE customer_id = ?")) {
                stmt.setString(1, reason.trim());
                stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                stmt.setLong(3, customerId);
                if (stmt.executeUpdate() != 1) throw new SQLException("Customer row was not updated.");
            }
            lockAccountRows(conn, customerId);
            List<long[]> activeAccountRows = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT account_id FROM accounts WHERE customer_id = ? AND status = 'ACTIVE' ORDER BY account_id")) {
                stmt.setLong(1, customerId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) activeAccountRows.add(new long[]{rs.getLong("account_id")});
                }
            }
            int locked = updateAccounts(conn, customerId,
                    "UPDATE accounts SET status = 'LOCKED', lock_reason = 'BAN' "
                    + "WHERE customer_id = ? AND status = 'ACTIVE'");
            for (long[] row : activeAccountRows) {
                insertAccountStatusHistory(conn, row[0], "ACTIVE", "LOCKED", "NONE", "BAN",
                        "CUSTOMER_BAN", actor.trim());
            }
            insertLifecycleEvent(conn, customerId, "BAN", actor.trim(), reason.trim());
            return locked;
        });
    }

    @Override
    public int unbanCustomerAndUnlockAccounts(long customerId) {
        return unbanCustomerAndUnlockAccounts(customerId, "system");
    }

    @Override
    public int unbanCustomerAndUnlockAccounts(long customerId, String actor) {
        if (actor == null || actor.isBlank()) throw new BankException("Administrator identity is required.");
        return inTransaction("unban customer", conn -> {
            requireStatus(conn, customerId, "BANNED", "Customer is not banned.");
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE customers SET status = 'ACTIVE', banned_reason = NULL, banned_at = NULL "
                    + "WHERE customer_id = ?")) {
                stmt.setLong(1, customerId);
                if (stmt.executeUpdate() != 1) throw new SQLException("Customer row was not updated.");
            }
            // Removing a ban does not silently reactivate accounts. The administrator
            // must explicitly review and unlock accounts that were locked by the ban.
            insertLifecycleEvent(conn, customerId, "UNBAN", actor.trim(), "Ban lifted; account review required.");
            return 0;
        });
    }

    /** Locks the customer row and verifies its current status; throws if missing or in the wrong state. */
    private void requireStatus(Connection conn, long customerId, String expected, String wrongStateMessage)
            throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT status FROM customers WHERE customer_id = ? FOR UPDATE")) {
            stmt.setLong(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) throw new CustomerNotFoundException("ID=" + customerId);
                if (!expected.equals(rs.getString("status"))) throw new BankException(wrongStateMessage);
            }
        }
    }

    private void lockAccountRows(Connection conn, long customerId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT account_id FROM accounts WHERE customer_id = ? ORDER BY account_id FOR UPDATE")) {
            stmt.setLong(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) { /* rows are locked as they are read */ }
            }
        }
    }

    private int updateAccounts(Connection conn, long customerId, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, customerId);
            return stmt.executeUpdate();
        }
    }

    private <T> T inTransaction(String description, JdbcTransactionExecutor.Work<T> work) {
        return JdbcTransactionExecutor.pooled(this::getConnection).execute("Failed to " + description + ".", work);
    }

    // =========================================================================
    // CUSTOMER LIFECYCLE — ARCHIVE / RESTORE (P5)
    // =========================================================================
    @Override
    public int archiveCustomerAndCloseAccounts(long customerId, String actor) {
        if (actor == null || actor.isBlank()) throw new BankException("Administrator identity is required.");
        return inTransaction("archive customer", conn -> {
            String previousCustomerStatus = lockCustomerStatus(conn, customerId);
            if ("ARCHIVED".equals(previousCustomerStatus)) {
                throw new BankException("Customer is already archived.");
            }
            if (!"ACTIVE".equals(previousCustomerStatus) && !"BANNED".equals(previousCustomerStatus)) {
                throw new BankException("Customer cannot be archived from status " + previousCustomerStatus + ".");
            }

            List<long[]> accountRows = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT account_id, status, lock_reason FROM accounts "
                    + "WHERE customer_id = ? ORDER BY account_id FOR UPDATE")) {
                stmt.setLong(1, customerId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String status = rs.getString("status");
                        String lockReason = rs.getString("lock_reason");
                        long statusCode = "ACTIVE".equals(status) ? 1L : "LOCKED".equals(status) ? 2L : 3L;
                        long lockCode = lockReason == null ? 0L : switch (lockReason) {
                            case "FAILED_PIN" -> 1L; case "ADMIN" -> 2L; case "BAN" -> 3L; default -> 0L;
                        };
                        accountRows.add(new long[]{rs.getLong("account_id"), statusCode, lockCode});
                    }
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE customers SET archive_previous_status = status, status = 'ARCHIVED', "
                    + "archived_at = ?, archived_by = ? WHERE customer_id = ?")) {
                stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                stmt.setString(2, actor.trim());
                stmt.setLong(3, customerId);
                if (stmt.executeUpdate() != 1) throw new SQLException("Customer row was not archived.");
            }

            int closed = 0;
            for (long[] row : accountRows) {
                if (row[1] == 1L || row[1] == 2L) {
                    String previousStatus = row[1] == 1L ? "ACTIVE" : "LOCKED";
                    String previousLock = switch ((int) row[2]) {
                        case 1 -> "FAILED_PIN"; case 2 -> "ADMIN"; case 3 -> "BAN"; default -> "NONE";
                    };
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE accounts SET archive_previous_status = ?, archive_previous_lock_reason = ?, "
                            + "status = 'CLOSED', closure_reason = 'CUSTOMER_ARCHIVED' "
                            + "WHERE account_id = ? AND status = ?")) {
                        stmt.setString(1, previousStatus);
                        stmt.setString(2, previousLock);
                        stmt.setLong(3, row[0]);
                        stmt.setString(4, previousStatus);
                        if (stmt.executeUpdate() != 1) throw new SQLException("Account changed during archive: " + row[0]);
                    }
                    insertAccountStatusHistory(conn, row[0], previousStatus, "CLOSED", previousLock,
                            previousLock, "CUSTOMER_ARCHIVED", actor.trim());
                    closed++;
                }
            }
            insertLifecycleEvent(conn, customerId, "ARCHIVE", actor.trim(),
                    "Customer archived; " + closed + " active/locked account(s) closed. Balances and ledger retained.");
            return closed;
        });
    }

    @Override
    public void restoreArchivedCustomer(long customerId, String actor) {
        if (actor == null || actor.isBlank()) throw new BankException("Administrator identity is required.");
        inTransaction("restore archived customer", conn -> {
            String previousStatus;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT status, archive_previous_status FROM customers WHERE customer_id = ? FOR UPDATE")) {
                stmt.setLong(1, customerId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new CustomerNotFoundException("ID=" + customerId);
                    if (!"ARCHIVED".equals(rs.getString("status"))) throw new BankException("Customer is not archived.");
                    previousStatus = rs.getString("archive_previous_status");
                }
            }
            if (!"ACTIVE".equals(previousStatus) && !"BANNED".equals(previousStatus)) previousStatus = "ACTIVE";
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE customers SET status = 'ACTIVE', archive_previous_status = NULL, "
                    + "archived_at = NULL, archived_by = NULL, banned_reason = NULL, banned_at = NULL WHERE customer_id = ? AND status = 'ARCHIVED'")) {
                stmt.setLong(1, customerId);
                if (stmt.executeUpdate() != 1) throw new SQLException("Customer restore did not update exactly one row.");
            }
            insertLifecycleEvent(conn, customerId, "RESTORE", actor.trim(),
                    "Customer restored to ACTIVE (pre-archive state: " + previousStatus + "); archived accounts remain CLOSED pending separate approval.");
            return null;
        });
    }

    @Override
    public boolean restoreArchivedAccount(long customerId, long accountId, String actor) {
        if (actor == null || actor.isBlank()) throw new BankException("Administrator identity is required.");
        return inTransaction("restore archived account", conn -> {
            String customerStatus = lockCustomerStatus(conn, customerId);
            if (!"ACTIVE".equals(customerStatus)) {
                throw new BankException("Customer must be ACTIVE before an archived account can be restored.");
            }
            String previousStatus;
            String previousLock;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT status, lock_reason, closure_reason, archive_previous_status, "
                    + "archive_previous_lock_reason FROM accounts WHERE account_id = ? AND customer_id = ? FOR UPDATE")) {
                stmt.setLong(1, accountId);
                stmt.setLong(2, customerId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) throw new BankException("Account does not belong to this customer.");
                    if (!"CLOSED".equals(rs.getString("status"))
                            || !"CUSTOMER_ARCHIVED".equals(rs.getString("closure_reason"))) {
                        throw new BankException("Only an account closed by customer archive can be restored here.");
                    }
                    previousStatus = rs.getString("archive_previous_status");
                    previousLock = rs.getString("archive_previous_lock_reason");
                }
            }
            if (!"ACTIVE".equals(previousStatus) && !"LOCKED".equals(previousStatus)) {
                throw new BankException("The account has no valid pre-archive state to restore.");
            }
            if (previousLock == null || previousLock.isBlank()) previousLock = "NONE";
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE accounts SET status = ?, lock_reason = ?, closure_reason = 'NONE', "
                    + "archive_previous_status = NULL, archive_previous_lock_reason = NULL "
                    + "WHERE account_id = ? AND customer_id = ? AND status = 'CLOSED'")) {
                stmt.setString(1, previousStatus);
                stmt.setString(2, previousLock);
                stmt.setLong(3, accountId);
                stmt.setLong(4, customerId);
                if (stmt.executeUpdate() != 1) throw new SQLException("Archived account restore failed.");
            }
            insertAccountStatusHistory(conn, accountId, "CLOSED", previousStatus, "NONE", previousLock,
                    "ARCHIVE_RESTORE", actor.trim());
            insertLifecycleEvent(conn, customerId, "RESTORE", actor.trim(),
                    "Account " + accountId + " restored to " + previousStatus + " after explicit approval.");
            return true;
        });
    }

    @Override
    public List<Customer> findAllArchived() {
        String sql = "SELECT * FROM customers WHERE status = 'ARCHIVED' ORDER BY archived_at DESC";
        List<Customer> list = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load archived customers. Apply migration 03 first.", e);
        }
        return list;
    }

    private String lockCustomerStatus(Connection conn, long customerId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT status FROM customers WHERE customer_id = ? FOR UPDATE")) {
            stmt.setLong(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) throw new CustomerNotFoundException("ID=" + customerId);
                return rs.getString("status");
            }
        }
    }

    private void insertLifecycleEvent(Connection conn, long customerId, String eventType,
                                      String actor, String details) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO customer_lifecycle_events (customer_id, event_type, actor, details) VALUES (?, ?, ?, ?)")) {
            stmt.setLong(1, customerId); stmt.setString(2, eventType);
            stmt.setString(3, actor); stmt.setString(4, details);
            stmt.executeUpdate();
        }
    }

    private void insertAccountStatusHistory(Connection conn, long accountId,
            String previousStatus, String newStatus, String previousLock, String newLock,
            String reason, String actor) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO account_status_history (account_id, previous_status, new_status, "
                + "previous_lock_reason, new_lock_reason, reason, actor) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setLong(1, accountId); stmt.setString(2, previousStatus); stmt.setString(3, newStatus);
            stmt.setString(4, previousLock); stmt.setString(5, newLock);
            stmt.setString(6, reason); stmt.setString(7, actor);
            stmt.executeUpdate();
        }
    }

    // =========================================================================
    // FIND ALL BANNED                                        (v3.0 — FRAUD MODULE)
    // =========================================================================
    @Override
    public List<Customer> findAllBanned() {
        String sql = "SELECT * FROM customers WHERE status = 'BANNED' "
                   + "ORDER BY banned_at DESC";

        List<Customer> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) list.add(mapRow(rs));

        } catch (SQLException e) {
            System.err.println("[CustomerDAO] findAllBanned() failed: " + e.getMessage());
            throw new RuntimeException("Failed to load banned customers. "
                    + "Did you run FRAUD_DETECTION.sql?", e);
        }
        return list;
    }
}
