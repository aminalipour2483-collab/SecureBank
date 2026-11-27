package com.bank.registration;

import com.bank.dao.impl.RegistrationDAOImpl;
import com.bank.exception.BankException;
import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.testkit.TestKit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.bank.testkit.TestKit.*;

public final class RegistrationDAOImplTest {
    public static void main(String[] args) {
        section("Atomic registration DAO transaction tests");
        Fixture success = new Fixture(false);
        RegistrationDAOImpl dao = new RegistrationDAOImpl(new JdbcTransactionExecutor(() -> success.connection(), c -> success.discarded = true));
        Account result = dao.registerNewCustomerWithFirstAccount(customer(), account(new BigDecimal("50.00")));
        checkEquals("customer, account and opening ledger use one connection", 1, success.connectionCount.get());
        checkEquals("customer, account, ledger and outbox inserts executed", 4, success.insertCount.get());
        check("transaction committed", success.committed);
        check("transaction not rolled back", !success.rolledBack);
        checkEquals("generated customer ID assigned", 101L, result.getCustomerId());
        checkEquals("generated account ID assigned", 201L, result.getAccountId());

        Fixture zeroDeposit = new Fixture(false);
        RegistrationDAOImpl zeroDao = new RegistrationDAOImpl(new JdbcTransactionExecutor(() -> zeroDeposit.connection(), c -> zeroDeposit.discarded = true));
        zeroDao.registerNewCustomerWithFirstAccount(customer(), account(BigDecimal.ZERO));
        checkEquals("zero opening deposit does not create a zero-value ledger row", 2, zeroDeposit.insertCount.get());
        check("zero-deposit registration commits", zeroDeposit.committed);

        Fixture failAccount = new Fixture(true);
        RegistrationDAOImpl failingDao = new RegistrationDAOImpl(new JdbcTransactionExecutor(() -> failAccount.connection(), c -> failAccount.discarded = true));
        checkThrows("account insert failure is surfaced", BankException.class,
                () -> failingDao.registerNewCustomerWithFirstAccount(customer(), account(new BigDecimal("10.00"))));
        check("customer insert is rolled back when account insert fails", failAccount.rolledBack);
        check("failed registration is never committed", !failAccount.committed);

        Fixture duplicateCustomer = new Fixture(false, true);
        RegistrationDAOImpl duplicateDao = new RegistrationDAOImpl(new JdbcTransactionExecutor(
                () -> duplicateCustomer.connection(), c -> duplicateCustomer.discarded = true));
        checkThrows("duplicate customer identity is surfaced as a business error", BankException.class,
                () -> duplicateDao.registerNewCustomerWithFirstAccount(customer(), account(BigDecimal.ZERO)));
        check("duplicate identity transaction rolls back", duplicateCustomer.rolledBack);
        check("duplicate identity is never committed", !duplicateCustomer.committed);

        finish("RegistrationDAOImplTest");
    }

    private static Customer customer() {
        return new Customer("N-101", "Ada", "Lovelace", LocalDate.of(1990, 12, 10),
                "555-0101", "ada@example.com", "Address", "A1", "Engineer");
    }
    private static Account account(BigDecimal balance) {
        return new Account(0L, "100000000001", "400000000001", "$2a$12$test-hash", Account.AccountType.SAVINGS, balance);
    }

    private static final class Fixture {
        final AtomicInteger connectionCount = new AtomicInteger();
        final AtomicInteger insertCount = new AtomicInteger();
        final List<String> sql = new ArrayList<>();
        final boolean failAccountInsert;
        final boolean duplicateCustomer;
        boolean committed, rolledBack, discarded, closed;
        Fixture(boolean failAccountInsert) { this(failAccountInsert, false); }
        Fixture(boolean failAccountInsert, boolean duplicateCustomer) {
            this.failAccountInsert = failAccountInsert;
            this.duplicateCustomer = duplicateCustomer;
        }

        Connection connection() {
            connectionCount.incrementAndGet();
            InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "setAutoCommit", "close" -> { if (method.getName().equals("close")) closed = true; yield null; }
                case "commit" -> { committed = true; yield null; }
                case "rollback" -> { rolledBack = true; yield null; }
                case "prepareStatement" -> statement((String) args[0]);
                case "isClosed" -> closed;
                case "toString" -> "RegistrationTestConnection";
                default -> defaultValue(method.getReturnType());
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, h);
        }

        PreparedStatement statement(String statementSql) {
            sql.add(statementSql);
            InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setDate", "setLong", "setBigDecimal", "close" -> null;
                case "executeUpdate" -> {
                    insertCount.incrementAndGet();
                    if (duplicateCustomer && statementSql.contains("INSERT INTO customers")) {
                        throw new SQLException("duplicate customer", "23000", 1062);
                    }
                    if (failAccountInsert && statementSql.contains("INSERT INTO accounts")) {
                        throw new SQLException("injected account insert failure", "HY000");
                    }
                    yield 1;
                }
                case "getGeneratedKeys" -> keys(statementSql.contains("INSERT INTO customers") ? 101L : 201L);
                case "toString" -> "RegistrationTestStatement";
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, h);
        }

        ResultSet keys(long id) {
            final boolean[] first = {true};
            InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> { boolean value = first[0]; first[0] = false; yield value; }
                case "getLong" -> id;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, h);
        }
        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
