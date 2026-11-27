package com.bank.registration;

import com.bank.dao.RegistrationDAO;
import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.service.impl.RegistrationServiceImpl;
import com.bank.util.BCryptUtil;
import com.bank.testkit.TestKit;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bank.testkit.TestKit.*;

public final class RegistrationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
    public static void main(String[] args) {
        AtomicInteger calls = new AtomicInteger();
        final Customer[] capturedCustomer = new Customer[1];
        final Account[] capturedAccount = new Account[1];
        RegistrationDAO fake = (customer, account) -> {
            calls.incrementAndGet(); capturedCustomer[0] = customer; capturedAccount[0] = account;
            customer.setCustomerId(101L); account.setCustomerId(101L); account.setAccountId(501L);
            return account;
        };
        RegistrationServiceImpl service = new RegistrationServiceImpl(fake, CLOCK);

        section("Registration service validation and mapping");
        Account created = service.registerNewCustomer("N-101", " Ada ", " Lovelace ",
                LocalDate.of(1990, 12, 10), " 555-0101 ", " ADA@EXAMPLE.COM ",
                " 1 Analytical Way ", " A1B2 ", " Engineer ", Account.AccountType.SAVINGS,
                "1234", new BigDecimal("125.50"));
        checkEquals("DAO called once for valid registration", 1, calls.get());
        checkEquals("generated account ID returned", 501L, created.getAccountId());
        checkEquals("customer fields trimmed", "Ada", capturedCustomer[0].getFirstName());
        checkEquals("email normalized", "ada@example.com", capturedCustomer[0].getEmail());
        check("PIN is stored as BCrypt hash", BCryptUtil.isBcryptHash(capturedAccount[0].getPinHash()));
        check("PIN is not stored as plaintext", !"1234".equals(capturedAccount[0].getPinHash()));
        checkEquals("opening balance preserved", new BigDecimal("125.50"), capturedAccount[0].getBalance());

        int before = calls.get();
        checkThrows("underage customer rejected", BankException.class, () -> service.registerNewCustomer(
                "N-102", "Young", "Person", LocalDate.of(2010, 1, 1), "555", "y@example.com",
                "Address", "P1", "Student", Account.AccountType.SAVINGS, "1234", BigDecimal.ZERO));
        checkThrows("invalid PIN rejected", BankException.class, () -> service.registerNewCustomer(
                "N-103", "Test", "Person", LocalDate.of(1990, 1, 1), "555", "t@example.com",
                "Address", "P1", "Worker", Account.AccountType.SAVINGS, "12ab", BigDecimal.ZERO));
        checkThrows("negative opening deposit rejected", BankException.class, () -> service.registerNewCustomer(
                "N-104", "Test", "Person", LocalDate.of(1990, 1, 1), "555", "t2@example.com",
                "Address", "P1", "Worker", Account.AccountType.SAVINGS, "1234", new BigDecimal("-1")));
        checkThrows("more than two decimal places rejected", BankException.class, () -> service.registerNewCustomer(
                "N-105", "Test", "Person", LocalDate.of(1990, 1, 1), "555", "t3@example.com",
                "Address", "P1", "Worker", Account.AccountType.SAVINGS, "1234", new BigDecimal("1.001")));
        checkEquals("invalid requests never reach DAO", before, calls.get());
        finish("RegistrationServiceTest");
    }
}
