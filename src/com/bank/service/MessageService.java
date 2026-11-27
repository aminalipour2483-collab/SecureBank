package com.bank.service;

import com.bank.security.AdminPrincipal;
import com.bank.security.CustomerPrincipal;
import com.bank.model.Message;
import java.util.List;

public interface MessageService {
    /** Customer messaging requires the current authenticated customer session. */
    long sendMessageFromCustomer(CustomerPrincipal principal, String subject, String content);

    /** Admin sends a message to a customer (by customer ID). */
    long sendMessageFromAdmin(AdminPrincipal admin, long customerId, String subject, String content);

    /** Admin inbox operations. */
    List<Message> getAllMessages(AdminPrincipal admin);
    void markAsRead(AdminPrincipal admin, long messageId);
    int getUnreadCount(AdminPrincipal admin);

    /** Customer inbox operations scoped to the authenticated customer principal. */
    List<Message> getMessagesForCustomer(CustomerPrincipal principal);
    void markAsReadByCustomer(CustomerPrincipal principal, long messageId);
    int getUnreadCountForCustomer(CustomerPrincipal principal);
}
