package com.bank.dao.impl;

import com.bank.dao.AccountDAO;
import com.bank.exception.BankException;
import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.model.Account;
import com.bank.testkit.TestKit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bank.testkit.TestKit.*;

/** Database-free checks for the account-creation transaction boundary. */
public final class AccountDAOImplTest {
    public static void main(String[] args) {
        section("Account DAO atomic account-limit tests");

        Fixture full = new Fixture(AccountDAO.MAX_ACCOUNTS_PER_CUSTOMER);
        AccountDAOImpl fullDao = new AccountDAOImpl(new JdbcTransactionExecutor(
                full::connection, connection -> full.discarded = true));
        checkThrows("fifth account is rejected inside the write transaction", BankException.class,
                () -> fullDao.saveWithOpeningDeposit(account(Account.AccountType.SAVINGS, BigDecimal.ZERO)));
        int lockIndex = indexOfContaining(full.sql, "FOR UPDATE");
        int countIndex = indexOfContaining(full.sql, "SELECT COUNT(*) FROM accounts");
        check("customer row is locked before the account count", lockIndex >= 0 && lockIndex < countIndex);
        checkEquals("no account insert occurs after the limit is reached", 0, full.insertCount.get());
        check("limit rejection rolls back the transaction", full.rolledBack && !full.committed);
        check("borrowed connection is returned", full.closed);

        Fixture available = new Fixture(AccountDAO.MAX_ACCOUNTS_PER_CUSTOMER - 1);
        AccountDAOImpl availableDao = new AccountDAOImpl(new JdbcTransactionExecutor(
                available::connection, connection -> available.discarded = true));
        long accountId = availableDao.saveWithOpeningDeposit(account(Account.AccountType.CHECKING, BigDecimal.ZERO));
        checkEquals("fourth account is allowed", 201L, accountId);
        check("account insert commits when one slot remains", available.committed && !available.rolledBack);
        checkEquals("exactly one account row is inserted for a zero opening balance", 1, available.insertCount.get());

        Fixture invalid = new Fixture(0);
        AccountDAOImpl invalidDao = new AccountDAOImpl(new JdbcTransactionExecutor(
                invalid::connection, connection -> invalid.discarded = true));
        checkThrows("missing account type returns a business validation error", BankException.class,
                () -> invalidDao.saveWithOpeningDeposit(account(null, BigDecimal.ZERO)));
        checkEquals("invalid account is rejected before borrowing a connection", 0, invalid.connectionCount.get());

        finish("AccountDAOImplTest");
    }

    private static int indexOfContaining(List<String> values, String fragment) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).contains(fragment)) return i;
        return -1;
    }

    private static Account account(Account.AccountType type, BigDecimal balance) {
        return new Account(7L, "100000000007", "400000000007", "$2a$12$test-hash", type, balance);
    }

    private static final class Fixture {
        final int existingAccounts;
        final AtomicInteger connectionCount = new AtomicInteger();
        final AtomicInteger insertCount = new AtomicInteger();
        final List<String> sql = new ArrayList<>();
        boolean committed;
        boolean rolledBack;
        boolean discarded;
        boolean closed;

        Fixture(int existingAccounts) { this.existingAccounts = existingAccounts; }

        Connection connection() {
            connectionCount.incrementAndGet();
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "setAutoCommit", "close" -> {
                    if ("close".equals(method.getName())) closed = true;
                    yield null;
                }
                case "commit" -> { committed = true; yield null; }
                case "rollback" -> { rolledBack = true; yield null; }
                case "prepareStatement" -> statement((String) args[0]);
                case "isClosed" -> closed;
                case "toString" -> "AccountDaoTestConnection";
                default -> defaultValue(method.getReturnType());
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        PreparedStatement statement(String statementSql) {
            sql.add(statementSql);
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setLong", "setString", "setBigDecimal", "close" -> null;
                case "executeQuery" -> statementSql.contains("SELECT COUNT(*) FROM accounts")
                        ? countResult(existingAccounts) : customerResult();
                case "executeUpdate" -> { insertCount.incrementAndGet(); yield 1; }
                case "getGeneratedKeys" -> keys(201L);
                case "toString" -> "AccountDaoTestStatement";
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        ResultSet customerResult() {
            return resultSet((method, args) -> switch (method) {
                case "getString" -> switch ((String) args[0]) {
                    case "first_name" -> "Ada";
                    case "last_name" -> "Lovelace";
                    case "status" -> "ACTIVE";
                    case "banned_reason" -> null;
                    default -> null;
                };
                default -> defaultValue(methodType(method));
            });
        }

        ResultSet countResult(int count) {
            return resultSet((method, args) -> "getInt".equals(method) ? count : defaultValue(methodType(method)));
        }

        ResultSet keys(long id) {
            return resultSet((method, args) -> "getLong".equals(method) ? id : defaultValue(methodType(method)));
        }

        private ResultSet resultSet(ResultValue value) {
            final boolean[] first = {true};
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> { boolean hasRow = first[0]; first[0] = false; yield hasRow; }
                case "close" -> null;
                default -> value.get(method.getName(), args == null ? new Object[0] : args);
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }

        private Class<?> methodType(String methodName) {
            try {
                for (var method : ResultSet.class.getMethods()) {
                    if (method.getName().equals(methodName)) return method.getReturnType();
                }
            } catch (RuntimeException ignored) { }
            return Object.class;
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

    @FunctionalInterface
    private interface ResultValue {
        Object get(String method, Object[] args) throws SQLException;
    }
}
