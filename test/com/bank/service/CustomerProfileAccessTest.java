package com.bank.service;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.exception.AccessDeniedException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.security.ConfiguredAdminAuthenticationService;
import com.bank.security.CustomerAccessGuard;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerSessionContext;
import com.bank.security.CustomerSessionService;
import com.bank.security.AdminPrincipal;
import com.bank.service.impl.CustomerServiceImpl;
import com.bank.util.BCryptUtil;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Optional;

import static com.bank.testkit.TestKit.*;

public final class CustomerProfileAccessTest {
    public static void main(String[] args) {
        section("Customer profile reads are principal-bound");
        Account persisted = new Account();
        persisted.setAccountId(7);
        persisted.setCustomerId(21);
        persisted.setStatus(Account.AccountStatus.ACTIVE);
        Customer customer = new Customer();
        customer.setCustomerId(21);
        customer.setNationalId("NID-21");
        customer.setPhone("55500021");
        customer.setEmail("ada@example.test");

        AccountDAO accounts = (AccountDAO) Proxy.newProxyInstance(AccountDAO.class.getClassLoader(),
                new Class[]{AccountDAO.class}, (p, m, a) -> "findById".equals(m.getName())
                        ? Optional.of(persisted) : defaultValue(m.getReturnType()));
        CustomerDAO customers = (CustomerDAO) Proxy.newProxyInstance(CustomerDAO.class.getClassLoader(),
                new Class[]{CustomerDAO.class}, (p, m, a) -> switch (m.getName()) {
                    case "findById", "findByNationalId", "findByEmail" -> Optional.of(customer);
                    default -> defaultValue(m.getReturnType());
                });
        AccountService loginService = (AccountService) Proxy.newProxyInstance(AccountService.class.getClassLoader(),
                new Class[]{AccountService.class}, (p, m, a) -> "login".equals(m.getName())
                        ? persisted : defaultValue(m.getReturnType()));
        CustomerSessionContext context = new CustomerSessionContext();
        CustomerSessionService sessions = new CustomerSessionService(loginService, accounts, customers, context);
        CustomerPrincipal principal = sessions.authenticate("401111111101", "1234");
        CustomerAccessGuard guard = new CustomerAccessGuard(accounts, customers, context);
        CustomerService service = new CustomerServiceImpl(customers, accounts, guard);
        AdminPrincipal admin = new ConfiguredAdminAuthenticationService(
                "review-admin", BCryptUtil.hash("review-password"), Clock.systemUTC())
                .authenticate("review-admin", "review-password").orElseThrow();

        checkEquals("customer can read their own profile", 21L,
                service.findCustomerById(principal).getCustomerId());
        checkEquals("administrator can read a customer by ID", 21L,
                service.findCustomerById(admin, 21L).getCustomerId());
        checkEquals("administrator can search by national ID", 21L,
                service.findCustomerByNationalId(admin, "NID-21").getCustomerId());
        checkEquals("administrator can search by email", 21L,
                service.findCustomerByEmail(admin, "ada@example.test").getCustomerId());

        checkThrows("legacy unscoped customer-ID lookup fails closed", AccessDeniedException.class,
                () -> service.findCustomerById(999L));
        checkThrows("legacy unscoped national-ID lookup fails closed", AccessDeniedException.class,
                () -> service.findCustomerByNationalId("NID-21"));
        checkThrows("legacy unscoped email lookup fails closed", AccessDeniedException.class,
                () -> service.findCustomerByEmail("ada@example.test"));

        persisted.setStatus(Account.AccountStatus.LOCKED);
        checkThrows("locked login account cannot read a customer profile", AccessDeniedException.class,
                () -> service.findCustomerById(principal));
        persisted.setStatus(Account.AccountStatus.ACTIVE);
        sessions.logout(principal);
        checkThrows("expired customer session cannot read a customer profile", AccessDeniedException.class,
                () -> service.findCustomerById(principal));

        finish("CustomerProfileAccessTest");
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
