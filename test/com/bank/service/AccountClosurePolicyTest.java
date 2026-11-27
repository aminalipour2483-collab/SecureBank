package com.bank.service;

import com.bank.dao.AccountDAO;
import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.security.AdminPrincipal;
import com.bank.service.impl.AccountServiceImpl;
import com.bank.testkit.TestAdmin;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static com.bank.testkit.TestKit.*;

public final class AccountClosurePolicyTest {
    public static void main(String[] args) {
        AdminPrincipal admin = TestAdmin.principal();
        Account account = new Account(1L, 10L, "100000000001", "4000000000000001", "hash",
                Account.AccountType.SAVINGS, new BigDecimal("25.00"), Account.AccountStatus.ACTIVE, LocalDateTime.now());
        AtomicInteger updates = new AtomicInteger();
        AccountDAO dao = (AccountDAO) Proxy.newProxyInstance(AccountDAO.class.getClassLoader(),
                new Class<?>[]{AccountDAO.class}, (proxy, method, args1) -> switch (method.getName()) {
                    case "findById" -> Optional.of(account);
                    case "updateStatus" -> { updates.incrementAndGet(); yield true; }
                    case "toString" -> "AccountClosurePolicyDAO";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AccountServiceImpl service = new AccountServiceImpl(dao, null, null, null);

        section("[1] Normal closure requires zero balance");
        checkThrows("positive balance prevents closure", BankException.class,
                () -> service.setAccountStatus(admin, 1L, Account.AccountStatus.CLOSED));
        checkEquals("rejected close does not reach DAO", 0, updates.get());

        section("[2] Closed accounts cannot be reopened through generic status update");
        account.setBalance(BigDecimal.ZERO);
        account.setStatus(Account.AccountStatus.CLOSED);
        account.setClosureReason(Account.ClosureReason.ADMIN_DECISION);
        checkThrows("generic reopen is rejected", BankException.class,
                () -> service.setAccountStatus(admin, 1L, Account.AccountStatus.ACTIVE));
        checkEquals("rejected reopen does not reach DAO", 0, updates.get());
        finish("AccountClosurePolicyTest");
    }
}
