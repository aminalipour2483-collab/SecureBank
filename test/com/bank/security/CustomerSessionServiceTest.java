package com.bank.security;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.exception.AccessDeniedException;
import com.bank.exception.CustomerBannedException;
import com.bank.exception.CustomerArchivedException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.service.AccountService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import static com.bank.testkit.TestKit.*;

/** Offline tests for customer principal issuance, current-session checks and invalidation. */
public final class CustomerSessionServiceTest {
    public static void main(String[] args) {
        section("[1] Customer principal and session lifecycle");
        Fixture f = new Fixture();
        CustomerSessionService service = f.service();
        CustomerPrincipal principal = service.authenticate("123456789012", "1234");
        check("successful login issues a principal", principal != null);
        checkEquals("principal identifies the owner", 41L, principal.customerId());
        checkEquals("principal is bound to authenticated account", 73L, principal.authenticatedAccountId());
        checkEquals("principal is current in context", principal.sessionId(), f.context.currentPrincipal().orElseThrow().sessionId());
        checkEquals("active account can be loaded", 73L, service.requireActiveAccount(principal).getAccountId());

        CustomerPrincipal replacement = new CustomerPrincipal(41L, 73L,
                java.util.UUID.randomUUID(), Instant.parse("2026-11-25T12:00:00Z"));
        f.context.establish(replacement);
        checkThrows("replaced session rejects stale principal", AccessDeniedException.class,
                () -> service.requireActiveAccount(principal));
        check("replacement principal remains current", f.context.currentPrincipal().isPresent());

        f.context.establish(principal);
        f.customer.setStatus(Customer.CustomerStatus.BANNED);
        checkThrows("customer banned during session is denied on next guarded request", CustomerBannedException.class,
                () -> service.requireActiveAccount(principal));
        f.customer.setStatus(Customer.CustomerStatus.ACTIVE);
        f.customer.setStatus(Customer.CustomerStatus.ARCHIVED);
        checkThrows("archived customer session is rejected on next guarded request", CustomerArchivedException.class,
                () -> service.requireActiveAccount(principal));
        f.customer.setStatus(Customer.CustomerStatus.ACTIVE);

        f.account.setStatus(Account.AccountStatus.LOCKED);
        checkThrows("account locked during session is denied", AccessDeniedException.class,
                () -> service.requireActiveAccount(principal));
        f.account.setStatus(Account.AccountStatus.ACTIVE);

        check("logout invalidates current principal", f.context.invalidate(principal));
        checkThrows("logged-out principal cannot be reused", AccessDeniedException.class,
                () -> service.requireActiveAccount(principal));
        check("logout leaves no current principal", f.context.currentPrincipal().isEmpty());
        finish("CustomerSessionServiceTest");
    }

    private static final class Fixture {
        final CustomerSessionContext context = new CustomerSessionContext();
        final Account account = new Account(73L, 41L, "ACCT-73", "123456789012", "hash",
                Account.AccountType.CHECKING, new BigDecimal("100.00"),
                Account.AccountStatus.ACTIVE, null);
        final Customer customer = new Customer(41L, "N-41", "Test", "Customer", LocalDate.of(1990, 1, 1),
                "5550001", "test@example.invalid", "Address", "10000", "Tester", null);

        CustomerSessionService service() {
            AccountService accountService = proxy(AccountService.class, (p, m, a) -> {
                if (m.getName().equals("login")) return account;
                return defaultValue(m.getReturnType());
            });
            AccountDAO accountDAO = proxy(AccountDAO.class, (p, m, a) -> {
                if (m.getName().equals("findById")) return Optional.of(account);
                return defaultValue(m.getReturnType());
            });
            CustomerDAO customerDAO = proxy(CustomerDAO.class, (p, m, a) -> {
                if (m.getName().equals("findById")) return Optional.of(customer);
                return defaultValue(m.getReturnType());
            });
            Clock clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);
            return new CustomerSessionService(accountService, accountDAO, customerDAO, context, clock);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
}
