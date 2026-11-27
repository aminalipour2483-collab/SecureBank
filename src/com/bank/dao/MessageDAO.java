package com.bank.dao;

import com.bank.model.Message;
import java.util.List;

public interface MessageDAO {

    /** Save a new message (customer or admin). */
    long save(Message message);

    /** Find all messages for a specific customer (ordered newest first). */
    List<Message> findByCustomerId(long customerId);

    /** Find all messages for the admin inbox (all customers, newest first). */
    List<Message> findAll();

    /** Find a single message by ID. */
    Message findById(long messageId);

    /** Mark a message as read. */
    void markAsRead(long messageId);

    /** Count unread customer messages (for admin badge). */
    int countUnread();

    /** Mark one admin-originated message read, scoped to its recipient customer. */
    boolean markAdminMessageAsReadForCustomer(long customerId, long messageId);

    /** Count unread admin-originated messages for one customer. */
    int countUnreadForCustomer(long customerId);
}