package com.bank.util;

import com.bank.exception.BankException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.prefs.Preferences;

/**
 * Shared helpers for durable, database-backed idempotency.
 *
 * A reservation and the financial writes are committed in the SAME database
 * transaction. A competing request with the same key waits on the unique
 * primary key, then reads the first request's committed result. If the first
 * transaction rolls back, the competing request can become the owner instead.
 */
public final class IdempotencyUtil {

    private static final String CLIENT_PREF_NODE = "pending-idempotency-requests";

    private IdempotencyUtil() { }

    public static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new BankException("A non-empty idempotency key of at most 64 characters is required.");
        }
    }

    /** SHA-256 over length-prefixed canonical fields; amount strings should be normalized first. */
    public static String fingerprint(String operationType, String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateField(digest, operationType);
            for (String field : fields) updateField(digest, field);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    public static String money(BigDecimal amount) {
        return amount == null ? "null" : amount.stripTrailingZeros().toPlainString();
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    /**
     * Insert a key, or lock and load the existing key. INSERT IGNORE on the
     * primary key is intentional: InnoDB waits for an in-flight duplicate
     * insert to commit or roll back before this transaction proceeds.
     */
    public static Reservation reserve(Connection connection,
                                       String key,
                                       String operationType,
                                       String requestHash) throws SQLException {
        validateKey(key);
        int inserted;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO idempotency_requests "
                + "(idempotency_key, operation_type, request_hash) VALUES (?, ?, ?)")) {
            insert.setString(1, key);
            insert.setString(2, operationType);
            insert.setString(3, requestHash);
            inserted = insert.executeUpdate();
        }

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT operation_type, request_hash, result_status, result_id, "
                + "customer_id, balance_before, balance_after "
                + "FROM idempotency_requests WHERE idempotency_key = ? FOR UPDATE")) {
            select.setString(1, key);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Idempotency reservation could not be loaded.");
                }
                if (!operationType.equals(rs.getString("operation_type"))
                        || !requestHash.equals(rs.getString("request_hash"))) {
                    throw new BankException(
                            "This idempotency key was already used for a different financial request.");
                }
                if (inserted == 0) {
                    if (!"COMPLETED".equals(rs.getString("result_status"))) {
                        throw new BankException(
                                "A previous request with this key has no completed result. Contact support before retrying.");
                    }
                    long resultId = rs.getLong("result_id");
                    if (rs.wasNull()) throw new SQLException("Completed idempotency result has no result ID.");
                    long customerId = rs.getLong("customer_id");
                    if (rs.wasNull()) throw new SQLException("Completed idempotency result has no customer ID.");
                    BigDecimal balanceBefore = rs.getBigDecimal("balance_before");
                    BigDecimal balanceAfter = rs.getBigDecimal("balance_after");
                    if (balanceBefore == null || balanceAfter == null) {
                        throw new SQLException("Completed idempotency result has incomplete balance snapshots.");
                    }
                    return new Reservation(true, resultId, customerId, balanceBefore, balanceAfter);
                }
                return new Reservation(false, -1L, -1L, null, null);
            }
        }
    }

    /** Persist the replayable outcome before the enclosing financial transaction commits. */
    public static void complete(Connection connection,
                                String key,
                                long resultId,
                                long customerId,
                                BigDecimal balanceBefore,
                                BigDecimal balanceAfter) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE idempotency_requests SET result_status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP, result_id = ?, "
                + "customer_id = ?, balance_before = ?, balance_after = ? WHERE idempotency_key = ?")) {
            update.setLong(1, resultId);
            update.setLong(2, customerId);
            update.setBigDecimal(3, balanceBefore);
            update.setBigDecimal(4, balanceAfter);
            update.setString(5, key);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Idempotency result could not be stored.");
            }
        }
    }

    /** Stable client-side key that survives application restarts until success is acknowledged. */
    public static String getOrCreateClientKey(String requestIdentity) {
        String preferenceKey = fingerprint("CLIENT_REQUEST", requestIdentity);
        String key = UUID.randomUUID().toString();
        try {
            Preferences preferences = Preferences.userNodeForPackage(IdempotencyUtil.class)
                    .node(CLIENT_PREF_NODE);
            String stored = preferences.get(preferenceKey, null);
            if (stored != null && !stored.isBlank()) return stored;
            preferences.put(preferenceKey, key);
            preferences.flush();
        } catch (Exception e) {
            System.err.println("[Idempotency] Could not persist client request key; retries across app restarts may not be deduplicated: "
                    + e.getMessage());
        }
        return key;
    }

    /** Remove the pending client key only after the service returned success. */
    public static void markClientRequestCompleted(String requestIdentity) {
        String preferenceKey = fingerprint("CLIENT_REQUEST", requestIdentity);
        try {
            Preferences preferences = Preferences.userNodeForPackage(IdempotencyUtil.class)
                    .node(CLIENT_PREF_NODE);
            preferences.remove(preferenceKey);
            preferences.flush();
        } catch (Exception e) {
            System.err.println("[Idempotency] Could not clear completed client request key: " + e.getMessage());
        }
    }

    public static final class Reservation {
        private final boolean replay;
        private final long resultId;
        private final long customerId;
        private final BigDecimal balanceBefore;
        private final BigDecimal balanceAfter;

        private Reservation(boolean replay, long resultId, long customerId,
                            BigDecimal balanceBefore, BigDecimal balanceAfter) {
            this.replay = replay;
            this.resultId = resultId;
            this.customerId = customerId;
            this.balanceBefore = balanceBefore;
            this.balanceAfter = balanceAfter;
        }

        public boolean isReplay() { return replay; }
        public long getResultId() { return resultId; }
        public long getCustomerId() { return customerId; }
        public BigDecimal getBalanceBefore() { return balanceBefore; }
        public BigDecimal getBalanceAfter() { return balanceAfter; }
    }
}
