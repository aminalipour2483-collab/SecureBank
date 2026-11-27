package com.bank.service.impl;

import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerAccessGuard;
import com.bank.dao.CustomerDAO;
import com.bank.dao.MessageDAO;
import com.bank.exception.AccessDeniedException;
import com.bank.exception.CustomerNotFoundException;
import com.bank.exception.BankException;
import com.bank.model.Customer;
import com.bank.model.Message;
import com.bank.model.Message.SenderType;
import com.bank.service.MessageService;

import java.util.List;

public class MessageServiceImpl implements MessageService {
    private final MessageDAO messageDAO;
    private final CustomerDAO customerDAO;
    private final CustomerAccessGuard customerAccessGuard;

    /** Compatibility constructor for admin-only tests; customer operations fail closed without a guard. */
    public MessageServiceImpl(MessageDAO messageDAO, CustomerDAO customerDAO) {
        this(messageDAO, customerDAO, null);
    }

    public MessageServiceImpl(MessageDAO messageDAO, CustomerDAO customerDAO,
                              CustomerAccessGuard customerAccessGuard) {
        this.messageDAO = messageDAO;
        this.customerDAO = customerDAO;
        this.customerAccessGuard = customerAccessGuard;
    }

    @Override
    public long sendMessageFromCustomer(CustomerPrincipal principal, String subject, String content) {
        CustomerPrincipal current = requireCustomerSession(principal);
        validateMessage(subject, content);
        return messageDAO.save(new Message(current.customerId(), SenderType.CUSTOMER, subject.trim(), content.trim()));
    }

    @Override
    public long sendMessageFromAdmin(AdminPrincipal admin, long customerId, String subject, String content) {
        AdminGuard.require(admin);
        Customer customer = customerDAO.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("ID=" + customerId));
        validateMessage(subject, content);
        return messageDAO.save(new Message(customer.getCustomerId(), SenderType.ADMIN, subject.trim(), content.trim()));
    }

    @Override
    public List<Message> getAllMessages(AdminPrincipal admin) {
        AdminGuard.require(admin);
        return messageDAO.findAll();
    }

    @Override
    public void markAsRead(AdminPrincipal admin, long messageId) {
        AdminGuard.require(admin);
        messageDAO.markAsRead(messageId);
    }

    @Override
    public int getUnreadCount(AdminPrincipal admin) {
        AdminGuard.require(admin);
        return messageDAO.countUnread();
    }

    @Override
    public List<Message> getMessagesForCustomer(CustomerPrincipal principal) {
        CustomerPrincipal current = requireCustomerSession(principal);
        return messageDAO.findByCustomerId(current.customerId());
    }

    @Override
    public void markAsReadByCustomer(CustomerPrincipal principal, long messageId) {
        CustomerPrincipal current = requireCustomerSession(principal);
        if (!messageDAO.markAdminMessageAsReadForCustomer(current.customerId(), messageId)) {
            throw new AccessDeniedException("That message is not addressed to this customer.");
        }
    }

    @Override
    public int getUnreadCountForCustomer(CustomerPrincipal principal) {
        CustomerPrincipal current = requireCustomerSession(principal);
        return messageDAO.countUnreadForCustomer(current.customerId());
    }

    private CustomerPrincipal requireCustomerSession(CustomerPrincipal principal) {
        if (customerAccessGuard == null) {
            throw new AccessDeniedException("Customer authentication guard is not configured.");
        }
        return customerAccessGuard.requireActivePrincipal(principal);
    }

    private void validateMessage(String subject, String content) {
        if (subject == null || subject.isBlank() || subject.trim().length() > 200) {
            throw new BankException("Subject is required and must be 200 characters or fewer.");
        }
        if (content == null || content.isBlank() || content.trim().length() > 5000) {
            throw new BankException("Message is required and must be 5,000 characters or fewer.");
        }
    }
}
