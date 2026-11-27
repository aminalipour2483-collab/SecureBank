package com.bank.service;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.exception.AccessDeniedException;
import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.security.AdminPrincipal;
import com.bank.service.impl.CustomerServiceImpl;
import com.bank.testkit.TestAdmin;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.bank.testkit.TestKit.*;

public final class CustomerLifecycleServiceTest {
    public static void main(String[] args) {
        AdminPrincipal admin = TestAdmin.principal();
        Customer customer = new Customer("NID-1", "Ava", "Example", LocalDate.of(1980, 1, 1),
                "+100000000", "ava@example.test", "Street", "10000", "Analyst");
        customer.setCustomerId(7L);
        customer.setStatus(Customer.CustomerStatus.ACTIVE);
        Account account = new Account(70L, 7L, "100000000070", "4000000000000070", "hash",
                Account.AccountType.SAVINGS, new BigDecimal("125.00"), Account.AccountStatus.CLOSED, LocalDateTime.now());
        account.setClosureReason(Account.ClosureReason.CUSTOMER_ARCHIVED);
        AtomicBoolean accountRestoreCalled = new AtomicBoolean();

        CustomerDAO customerDAO = (CustomerDAO) Proxy.newProxyInstance(CustomerDAO.class.getClassLoader(),
                new Class<?>[]{CustomerDAO.class}, (proxy, method, args1) -> switch (method.getName()) {
                    case "findById" -> Optional.of(customer);
                    case "archiveCustomerAndCloseAccounts" -> {
                        customer.setStatus(Customer.CustomerStatus.ARCHIVED);
                        customer.setArchivedAt(LocalDateTime.now());
                        yield 1;
                    }
                    case "restoreArchivedCustomer" -> {
                        customer.setStatus(Customer.CustomerStatus.ACTIVE);
                        customer.setArchivedAt(null);
                        yield null;
                    }
                    case "restoreArchivedAccount" -> { accountRestoreCalled.set(true); yield true; }
                    case "findAllArchived" -> customer.isArchived() ? List.of(customer) : List.of();
                    case "toString" -> "CustomerLifecycleTestDAO";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AccountDAO accountDAO = (AccountDAO) Proxy.newProxyInstance(AccountDAO.class.getClassLoader(),
                new Class<?>[]{AccountDAO.class}, (proxy, method, args1) -> switch (method.getName()) {
                    case "findById" -> Optional.of(account);
                    case "toString" -> "CustomerLifecycleTestAccountDAO";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        CustomerServiceImpl service = new CustomerServiceImpl(customerDAO, accountDAO);

        section("[1] Archive and restore lifecycle");
        checkThrows("archive requires admin principal", AccessDeniedException.class,
                () -> service.archiveCustomer(null, 7L));
        service.archiveCustomer(admin, 7L);
        check("archive changes customer status", customer.isArchived());
        check("archive records archived timestamp", customer.getArchivedAt() != null);
        check("archive leaves account closed pending approval", account.isClosed());
        check("archive does not alter account balance", new BigDecimal("125.00").compareTo(account.getBalance()) == 0);
        checkThrows("archiving an already archived customer is rejected", BankException.class,
                () -> service.archiveCustomer(admin, 7L));
        service.restoreArchivedCustomer(admin, 7L);
        checkEquals("restore returns customer to ACTIVE", Customer.CustomerStatus.ACTIVE, customer.getStatus());
        check("restore does not automatically reopen account", account.isClosed());
        check("restore clears archived timestamp", customer.getArchivedAt() == null);

        section("[2] Explicit archived-account restoration");
        service.restoreArchivedAccount(admin, 7L, 70L);
        check("separate account approval reaches DAO", accountRestoreCalled.get());
        checkThrows("wrong customer ownership is rejected before DAO", BankException.class,
                () -> service.restoreArchivedAccount(admin, 8L, 70L));
        customer.setStatus(Customer.CustomerStatus.ARCHIVED);
        checkThrows("account cannot be restored while customer remains archived", BankException.class,
                () -> service.restoreArchivedAccount(admin, 7L, 70L));
        customer.setStatus(Customer.CustomerStatus.ACTIVE);

        section("[3] Permanent deletion is disabled");
        checkThrows("legacy hard-delete service entry point is disabled", BankException.class,
                () -> service.deleteCustomer(admin, 7L));
        checkThrows("archived customer listing requires admin", AccessDeniedException.class,
                () -> service.getArchivedCustomers(null));
        finish("CustomerLifecycleServiceTest");
    }
}
