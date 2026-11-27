package com.bank.service;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.dao.MessageDAO;
import com.bank.exception.AccessDeniedException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.model.Message;
import com.bank.security.CustomerAccessGuard;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerSessionContext;
import com.bank.security.CustomerSessionService;
import com.bank.service.impl.MessageServiceImpl;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bank.testkit.TestKit.*;

public final class CustomerInboxServiceTest {
    public static void main(String[] args) {
        section("Customer inbox requires the authenticated customer principal");
        Account persisted = new Account();
        persisted.setAccountId(7);
        persisted.setCustomerId(21);
        persisted.setStatus(Account.AccountStatus.ACTIVE);
        Customer customer = new Customer();
        customer.setCustomerId(21);
        customer.setNationalId("NID-21");
        customer.setPhone("55500021");

        AtomicReference<Message> saved = new AtomicReference<>();
        AtomicReference<Long> markedCustomer = new AtomicReference<>();
        AtomicReference<Long> markedMessage = new AtomicReference<>();
        AccountDAO accounts = (AccountDAO) Proxy.newProxyInstance(AccountDAO.class.getClassLoader(),
                new Class[]{AccountDAO.class}, (p, m, a) -> "findById".equals(m.getName())
                        ? Optional.of(persisted) : defaultValue(m.getReturnType()));
        CustomerDAO customers = (CustomerDAO) Proxy.newProxyInstance(CustomerDAO.class.getClassLoader(),
                new Class[]{CustomerDAO.class}, (p, m, a) -> "findById".equals(m.getName())
                        ? Optional.of(customer) : defaultValue(m.getReturnType()));
        MessageDAO messages = (MessageDAO) Proxy.newProxyInstance(MessageDAO.class.getClassLoader(),
                new Class[]{MessageDAO.class}, (p, m, a) -> switch (m.getName()) {
                    case "save" -> { saved.set((Message) a[0]); yield 88L; }
                    case "findByCustomerId" -> List.of();
                    case "markAdminMessageAsReadForCustomer" -> {
                        markedCustomer.set((Long) a[0]); markedMessage.set((Long) a[1]); yield true;
                    }
                    case "countUnreadForCustomer" -> 2;
                    default -> defaultValue(m.getReturnType());
                });
        AccountService loginService = (AccountService) Proxy.newProxyInstance(AccountService.class.getClassLoader(),
                new Class[]{AccountService.class}, (p, m, a) -> "login".equals(m.getName())
                        ? persisted : defaultValue(m.getReturnType()));
        CustomerSessionContext context = new CustomerSessionContext();
        CustomerSessionService sessions = new CustomerSessionService(loginService, accounts, customers, context);
        CustomerPrincipal principal = sessions.authenticate("401111111101", "1234");
        CustomerAccessGuard guard = new CustomerAccessGuard(accounts, customers, context);
        MessageService service = new MessageServiceImpl(messages, customers, guard);

        checkEquals("customer can load their own inbox", 0, service.getMessagesForCustomer(principal).size());
        checkEquals("customer sees their own unread admin-message count", 2,
                service.getUnreadCountForCustomer(principal));
        checkEquals("customer message is saved", 88L,
                service.sendMessageFromCustomer(principal, "Question", "Please contact me."));
        checkEquals("customer message sender type", Message.SenderType.CUSTOMER, saved.get().getSenderType());
        checkEquals("customer message is tied to principal customer ID", 21L, saved.get().getCustomerId());
        service.markAsReadByCustomer(principal, 99L);
        checkEquals("read update is scoped to the principal customer", 21L, markedCustomer.get());
        checkEquals("read update targets the selected message", 99L, markedMessage.get());

        section("Customer inbox rejects missing, locked, and expired sessions");
        MessageService withoutGuard = new MessageServiceImpl(messages, customers);
        checkThrows("customer inbox fails closed when no guard is configured", AccessDeniedException.class,
                () -> withoutGuard.getMessagesForCustomer(principal));
        persisted.setStatus(Account.AccountStatus.LOCKED);
        checkThrows("locked login account cannot access the inbox", AccessDeniedException.class,
                () -> service.getMessagesForCustomer(principal));
        persisted.setStatus(Account.AccountStatus.ACTIVE);
        sessions.logout(principal);
        checkThrows("logged-out principal cannot access the inbox", AccessDeniedException.class,
                () -> service.getMessagesForCustomer(principal));

        finish("CustomerInboxServiceTest");
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
