package com.bank.service;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.dao.PinRecoveryDAO;
import com.bank.dao.TransactionDAO;
import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.service.impl.AccountServiceImpl;
import com.bank.util.BCryptUtil;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bank.testkit.TestKit.*;

public final class CustomerPinRecoveryTest {
    public static void main(String[] args) {
        successfulRecovery();
        incorrectIdentity();
        administratorLockCannotBeRecovered();
        recoveryThrottle();
        finish("CustomerPinRecoveryTest");
    }

    private static Fixture fixture(Account.LockReason reason) {
        Account account = new Account();
        account.setAccountId(44);
        account.setCustomerId(12);
        account.setCardNumber("401111111101");
        account.setAccountNumber("100000000044");
        account.setStatus(Account.AccountStatus.LOCKED);
        account.setLockReason(reason);
        account.setFailedPinAttempts(3);
        account.setPinHash(BCryptUtil.hash("1234"));

        Customer customer = new Customer();
        customer.setCustomerId(12);
        customer.setNationalId("AB-12345");
        customer.setPhone("+1 (202) 555-0101");

        AtomicInteger resetCalls = new AtomicInteger();
        AccountDAO accounts = (AccountDAO) Proxy.newProxyInstance(AccountDAO.class.getClassLoader(),
                new Class[]{AccountDAO.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findByCardNumber", "findById" -> Optional.of(account);
                    case "resetPinAndUnlockAfterRecovery" -> {
                        resetCalls.incrementAndGet();
                        account.setPinHash((String) args[1]);
                        account.setStatus(Account.AccountStatus.ACTIVE);
                        account.setLockReason(Account.LockReason.NONE);
                        account.setFailedPinAttempts(0);
                        yield true;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        CustomerDAO customers = (CustomerDAO) Proxy.newProxyInstance(CustomerDAO.class.getClassLoader(),
                new Class[]{CustomerDAO.class}, (proxy, method, args) ->
                        "findById".equals(method.getName()) ? Optional.of(customer) : defaultValue(method.getReturnType()));
        FakeThrottle throttle = new FakeThrottle();
        AccountService service = new AccountServiceImpl(accounts, customers, (TransactionDAO) null, null, throttle);
        return new Fixture(service, account, throttle, resetCalls);
    }

    private static void successfulRecovery() {
        section("[1] Recovery requires all three registered identity values");
        Fixture f = fixture(Account.LockReason.FAILED_PIN);
        f.service.recoverPin("401111111101", "AB12345", "12025550101", "9876");
        check("account unlocked after exact three-factor match", f.account.isActive());
        checkEquals("failed PIN count cleared", 0, f.account.getFailedPinAttempts());
        checkEquals("lock reason cleared", Account.LockReason.NONE, f.account.getLockReason());
        check("new PIN stored as BCrypt", BCryptUtil.verify("9876", f.account.getPinHash()));
        checkEquals("recovery throttle cleared", 0, f.throttle.failures.get());
        checkEquals("one atomic account reset", 1, f.resetCalls.get());
    }

    private static void incorrectIdentity() {
        section("[2] A mismatched identity value cannot reset the PIN");
        Fixture f = fixture(Account.LockReason.FAILED_PIN);
        checkThrows("wrong registered phone rejected", BankException.class,
                () -> f.service.recoverPin("401111111101", "AB12345", "12025550999", "9876"));
        check("account remains locked", f.account.isLocked());
        checkEquals("one failed recovery attempt recorded", 1, f.throttle.failures.get());
        checkEquals("PIN reset was not called", 0, f.resetCalls.get());
    }

    private static void administratorLockCannotBeRecovered() {
        section("[3] Recovery cannot bypass an administrator lock");
        Fixture f = fixture(Account.LockReason.ADMIN);
        checkThrows("admin lock rejected even when identity matches", BankException.class,
                () -> f.service.recoverPin("401111111101", "AB12345", "12025550101", "9876"));
        check("account remains locked", f.account.isLocked());
        checkEquals("no reset performed", 0, f.resetCalls.get());
    }

    private static void recoveryThrottle() {
        section("[4] Repeated failed recovery checks are throttled");
        Fixture f = fixture(Account.LockReason.FAILED_PIN);
        for (int i = 0; i < 5; i++) {
            try { f.service.recoverPin("401111111101", "wrong", "wrong", "9876"); }
            catch (BankException expected) { }
        }
        check("five failed checks trigger cooldown", f.throttle.locked);
        checkThrows("sixth check is rejected by cooldown", BankException.class,
                () -> f.service.recoverPin("401111111101", "AB12345", "12025550101", "9876"));
        checkEquals("no PIN reset during cooldown", 0, f.resetCalls.get());
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

    private record Fixture(AccountService service, Account account, FakeThrottle throttle, AtomicInteger resetCalls) { }

    private static final class FakeThrottle implements PinRecoveryDAO {
        final AtomicInteger failures = new AtomicInteger();
        volatile boolean locked;
        @Override public boolean isTemporarilyLocked(String key) { return locked; }
        @Override public int recordFailure(String key) {
            int count = failures.incrementAndGet();
            if (count >= 5) locked = true;
            return count;
        }
        @Override public void clear(String key) { failures.set(0); locked = false; }
    }
}
