package com.bank.dao.impl;

import com.bank.config.DatabaseConnection;
import com.bank.dao.MessageDAO;
import com.bank.model.Message;
import com.bank.model.Message.SenderType;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * MessageDAOImpl — JDBC implementation of MessageDAO.
 *
 * Every method borrows one pooled connection and returns it via
 * try-with-resources (Connection.close() = return to the pool).
 */
public class MessageDAOImpl implements MessageDAO {

    private Connection getConnection() {
        return DatabaseConnection.getInstance().getConnection();
    }

    private Message mapRow(ResultSet rs) throws SQLException {
        Message m = new Message();
        m.setMessageId(rs.getLong("message_id"));
        m.setCustomerId(rs.getLong("customer_id"));
        m.setSenderType(SenderType.valueOf(rs.getString("sender_type")));
        m.setSubject(rs.getString("subject"));
        m.setMessage(rs.getString("message"));
        m.setCreatedAt(rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toLocalDateTime() : null);
        m.setRead(rs.getBoolean("is_read"));
        return m;
    }

    @Override
    public long save(Message message) {
        String sql = "INSERT INTO messages (customer_id, sender_type, subject, message) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, message.getCustomerId());
            stmt.setString(2, message.getSenderType().name());
            stmt.setString(3, message.getSubject());
            stmt.setString(4, message.getMessage());
            int affected = stmt.executeUpdate();
            if (affected == 0) return -1;
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save message", e);
        }
        return -1;
    }

    @Override
    public List<Message> findByCustomerId(long customerId) {
        String sql = "SELECT * FROM messages WHERE customer_id = ? ORDER BY created_at DESC";
        List<Message> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find messages for customer", e);
        }
        return list;
    }

    @Override
    public List<Message> findAll() {
        String sql = "SELECT * FROM messages ORDER BY created_at DESC";
        List<Message> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to retrieve all messages", e);
        }
        return list;
    }

    @Override
    public Message findById(long messageId) {
        String sql = "SELECT * FROM messages WHERE message_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, messageId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find message by ID", e);
        }
        return null;
    }

    @Override
    public void markAsRead(long messageId) {
        String sql = "UPDATE messages SET is_read = TRUE WHERE message_id = ? AND sender_type = 'CUSTOMER'";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, messageId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark message as read", e);
        }
    }

    @Override
    public int countUnread() {
        String sql = "SELECT COUNT(*) FROM messages WHERE is_read = FALSE AND sender_type = 'CUSTOMER'";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count unread messages", e);
        }
        return 0;
    }
    @Override
    public boolean markAdminMessageAsReadForCustomer(long customerId, long messageId) {
        String sql = "UPDATE messages SET is_read = TRUE WHERE message_id = ? AND customer_id = ? AND sender_type = 'ADMIN'";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, messageId);
            stmt.setLong(2, customerId);
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark customer message as read", e);
        }
    }

    @Override
    public int countUnreadForCustomer(long customerId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE customer_id = ? AND sender_type = 'ADMIN' AND is_read = FALSE";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count unread customer messages", e);
        }
    }

}
