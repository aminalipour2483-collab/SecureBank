package com.bank.fraud.outbox;

import com.bank.model.Transaction.TransactionType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Writes an outbox row using the caller's financial JDBC transaction. */
public final class FraudOutboxWriter {
    private FraudOutboxWriter() { }

    public static void enqueue(Connection connection, long accountId, long customerId,
            TransactionType type, BigDecimal amount, BigDecimal balanceBefore,
            Long transactionId, Long transferId) throws SQLException {
        if (connection == null || type == null || amount == null || balanceBefore == null || transactionId == null) {
            throw new IllegalArgumentException("A complete committed transaction event is required.");
        }
        String sql = "INSERT INTO fraud_outbox (event_key, account_id, customer_id, transaction_type, amount, balance_before, transaction_id, transfer_id, occurred_at, status, attempt_count, retry_cycle, available_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, (SELECT created_at FROM transactions WHERE transaction_id=?), 'PENDING', 0, 0, CURRENT_TIMESTAMP(6))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "TX:" + transactionId);
            statement.setLong(2, accountId);
            statement.setLong(3, customerId);
            statement.setString(4, type.name());
            statement.setBigDecimal(5, amount);
            statement.setBigDecimal(6, balanceBefore);
            statement.setLong(7, transactionId);
            if (transferId == null) statement.setNull(8, java.sql.Types.BIGINT);
            else statement.setLong(8, transferId);
            statement.setLong(9, transactionId);
            if (statement.executeUpdate() != 1) throw new SQLException("Fraud outbox insert affected no rows.");
        }
    }
}
