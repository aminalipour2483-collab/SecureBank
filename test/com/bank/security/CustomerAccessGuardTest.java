package com.bank.security;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.dao.PinRecoveryDAO;
import com.bank.dao.TransactionDAO;
import com.bank.dao.TransferDAO;
import com.bank.service.impl.AccountServiceImpl;
import com.bank.service.impl.AccountServiceImpl;
import com.bank.service.impl.TransferServiceImpl;
import com.bank.exception.AccessDeniedException;
import com.bank.exception.CustomerBannedException;
import com.bank.exception.CustomerArchivedException;
import com.bank.model.Account;
import com.bank.model.Customer;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.bank.testkit.TestKit.*;

/** Service-boundary ownership and active-state authorization tests. */
public final class CustomerAccessGuardTest {
    public static void main(String[] args) {
        Fixture f = new Fixture();
        section("[1] Customer-owned account authorization");
        checkEquals("customer can access own active account", f.ownAccount,
                f.guard.requireOwnedActiveAccount(f.principal, 101L));
        checkThrows("customer cannot access another customer's account", AccessDeniedException.class,
                () -> f.guard.requireOwnedActiveAccount(f.principal, 202L));

        section("[2] Current customer/account status is rechecked");
        f.ownAccount.setStatus(Account.AccountStatus.LOCKED);
        checkThrows("locked owned account is denied", AccessDeniedException.class,
                () -> f.guard.requireOwnedActiveAccount(f.principal, 101L));
        f.ownAccount.setStatus(Account.AccountStatus.ACTIVE);
        f.customer.setStatus(Customer.CustomerStatus.BANNED);
        checkThrows("banned customer is denied even with a current principal", CustomerBannedException.class,
                () -> f.guard.requireActivePrincipal(f.principal));
        f.customer.setStatus(Customer.CustomerStatus.ACTIVE);
        f.customer.setStatus(Customer.CustomerStatus.ARCHIVED);
        checkThrows("archived customer is denied even with a current principal", CustomerArchivedException.class,
                () -> f.guard.requireActivePrincipal(f.principal));
        f.customer.setStatus(Customer.CustomerStatus.ACTIVE);

        section("[3] Login account remains a session prerequisite");
        f.loginAccount.setStatus(Account.AccountStatus.LOCKED);
        checkThrows("locking the account used for login invalidates customer use cases", AccessDeniedException.class,
                () -> f.guard.requireOwnedActiveAccount(f.principal, 101L));
        f.loginAccount.setStatus(Account.AccountStatus.ACTIVE);

        section("[4] Transfer receiver eligibility");
        checkEquals("active receiver may belong to another customer", f.receiver,
                f.guard.requireActiveReceiver(f.principal, 202L));
        f.receiver.setStatus(Account.AccountStatus.CLOSED);
        checkThrows("closed receiver cannot receive a transfer", AccessDeniedException.class,
                () -> f.guard.requireActiveReceiver(f.principal, 202L));
        f.receiver.setStatus(Account.AccountStatus.ACTIVE);
        checkThrows("transfer history not involving this customer is denied", AccessDeniedException.class,
                () -> f.guard.requireTransferParticipant(f.principal, 202L, 303L));
        check("transfer involving this customer is visible", f.guardAllowsTransfer(101L, 202L));

        section("[5] Runtime service composition blocks legacy principal-free entry points");
        AccountServiceImpl guardedAccounts = new AccountServiceImpl(
                null, null, (TransactionDAO) null, null, (PinRecoveryDAO) null, f.guard);
        checkThrows("legacy deposit cannot bypass customer authorization", AccessDeniedException.class,
                () -> guardedAccounts.deposit(101L, new BigDecimal("1.00"), "legacy-key"));
        checkThrows("legacy balance lookup cannot bypass customer authorization", AccessDeniedException.class,
                () -> guardedAccounts.getAccountBalance(101L));
        AccountServiceImpl principalAccounts = new AccountServiceImpl(
                f.accountDAO, f.customerDAO, (TransactionDAO) null, null, (PinRecoveryDAO) null, f.guard);
        checkEquals("principal-bound balance lookup succeeds for owned account", new BigDecimal("100.00"),
                principalAccounts.getAccountBalance(f.principal, 101L));
        checkThrows("principal-bound balance lookup denies another customer's account", AccessDeniedException.class,
                () -> principalAccounts.getAccountBalance(f.principal, 202L));
        TransferServiceImpl guardedTransfers = new TransferServiceImpl(
                (TransferDAO) null, f.accountDAO, f.customerDAO, null, f.guard);
        checkThrows("legacy transfer cannot bypass customer authorization", AccessDeniedException.class,
                () -> guardedTransfers.processTransfer(101L, 202L, new BigDecimal("1.00"), "legacy-key"));
        checkEquals("recipient card lookup returns only the active account id", 202L,
                guardedTransfers.resolveRecipientAccountIdByCardNumber(f.principal, "400000000202"));
        checkEquals("recipient account-number lookup returns only the active account id", 202L,
                guardedTransfers.resolveRecipientAccountIdByAccountNumber(f.principal, "100000000202"));
        checkThrows("transfer cannot debit an account owned by another customer", AccessDeniedException.class,
                () -> guardedTransfers.processTransfer(f.principal, 202L, 101L,
                        new BigDecimal("1.00"), "unauthorized-key"));

        section("[6] Replaced sessions are rejected");
        CustomerPrincipal replacement = new CustomerPrincipal(1L, 101L, UUID.randomUUID(), Instant.now());
        f.context.establish(replacement);
        checkThrows("old principal cannot be replayed", AccessDeniedException.class,
                () -> f.guard.requireActivePrincipal(f.principal));
        finish("CustomerAccessGuardTest");
    }

    private static final class Fixture {
        final CustomerSessionContext context = new CustomerSessionContext();
        final Customer customer = customer(1L);
        final Customer otherCustomer = customer(2L);
        final Account loginAccount = account(100L, 1L);
        final Account ownAccount = account(101L, 1L);
        final Account receiver = account(202L, 2L);
        final Account unrelated = account(303L, 3L);
        final Customer thirdCustomer = customer(3L);
        final Map<Long, Account> accounts = Map.of(100L, loginAccount, 101L, ownAccount,
                202L, receiver, 303L, unrelated);
        final Map<Long, Customer> customers = Map.of(1L, customer, 2L, otherCustomer, 3L, thirdCustomer);
        final CustomerPrincipal principal = new CustomerPrincipal(1L, 100L, UUID.randomUUID(), Instant.now());
        final AccountDAO accountDAO;
        final CustomerDAO customerDAO;
        final CustomerAccessGuard guard;

        Fixture() {
            context.establish(principal);
            accountDAO = (AccountDAO) Proxy.newProxyInstance(AccountDAO.class.getClassLoader(),
                    new Class[]{AccountDAO.class}, (proxy, method, args) -> {
                        if (method.getName().equals("findById")) return Optional.ofNullable(accounts.get((Long) args[0]));
                        if (method.getName().equals("findByCardNumber")) return accounts.values().stream()
                                .filter(a -> a.getCardNumber().equals(args[0])).findFirst();
                        if (method.getName().equals("findByAccountNumber")) return accounts.values().stream()
                                .filter(a -> a.getAccountNumber().equals(args[0])).findFirst();
                        return defaultValue(method.getReturnType());
                    });
            customerDAO = (CustomerDAO) Proxy.newProxyInstance(CustomerDAO.class.getClassLoader(),
                    new Class[]{CustomerDAO.class}, (proxy, method, args) -> {
                        if (method.getName().equals("findById")) return Optional.ofNullable(customers.get((Long) args[0]));
                        return defaultValue(method.getReturnType());
                    });
            guard = new CustomerAccessGuard(accountDAO, customerDAO, context);
        }

        boolean guardAllowsTransfer(long sender, long receiverId) {
            try {
                guard.requireTransferParticipant(principal, sender, receiverId);
                return true;
            } catch (RuntimeException ex) {
                return false;
            }
        }
    }

    private static Account account(long id, long owner) {
        return new Account(id, owner, String.format("10%010d", id), String.format("40%010d", id), "hash",
                Account.AccountType.CHECKING, new BigDecimal("100.00"), Account.AccountStatus.ACTIVE, null);
    }

    private static Customer customer(long id) {
        Customer c = new Customer();
        c.setCustomerId(id);
        c.setFirstName("Test");
        c.setLastName("Customer" + id);
        return c;
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
