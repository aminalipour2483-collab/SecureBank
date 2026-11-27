package com.bank.security;

import com.bank.util.BCryptUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bank.testkit.TestKit.*;

/** Offline tests for database-backed administrator authentication. */
public final class DatabaseAdminAuthenticationServiceTest {
    public static void main(String[] args) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
        String hash = BCryptUtil.hash("LocalAdmin!2026");

        section("[1] Database-backed administrator authentication");
        DatabaseAdminAuthenticationService valid = service(true, true, hash, clock);
        Optional<AdminPrincipal> accepted = valid.authenticate(" bankadmin ", "LocalAdmin!2026");
        check("valid active admin credentials accepted", accepted.isPresent());
        checkEquals("authenticated username returned", "bankadmin", accepted.orElseThrow().username());
        checkEquals("injected clock used", clock.instant(), accepted.orElseThrow().authenticatedAt());

        check("wrong password rejected", service(true, true, hash, clock)
                .authenticate("bankadmin", "incorrect").isEmpty());
        check("inactive administrator rejected", service(true, false, hash, clock)
                .authenticate("bankadmin", "LocalAdmin!2026").isEmpty());
        check("unknown administrator rejected", service(false, false, hash, clock)
                .authenticate("unknown", "LocalAdmin!2026").isEmpty());
        check("null username rejected", service(true, true, hash, clock)
                .authenticate(null, "LocalAdmin!2026").isEmpty());
        check("null password rejected", service(true, true, hash, clock)
                .authenticate("bankadmin", null).isEmpty());
        finish("DatabaseAdminAuthenticationServiceTest");
    }

    private static DatabaseAdminAuthenticationService service(
            boolean rowExists, boolean active, String hash, Clock clock) {
        ResultSet rows = proxy(ResultSet.class, new InvocationHandler() {
            private boolean returned;
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> { if (!returned && rowExists) { returned = true; yield true; } yield false; }
                    case "getString" -> hash;
                    case "getBoolean" -> active;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                };
            }
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "executeQuery" -> rows;
                    case "setString", "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
        Connection connection = proxy(Connection.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "prepareStatement" -> statement;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
        return new DatabaseAdminAuthenticationService(() -> connection, clock);
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
