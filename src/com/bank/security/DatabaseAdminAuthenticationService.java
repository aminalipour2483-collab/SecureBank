package com.bank.security;

import com.bank.config.DatabaseConnection;
import com.bank.util.BCryptUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

/** Authenticates administrator accounts stored in bank_system_demo.admin_users. */
public final class DatabaseAdminAuthenticationService implements AdminAuthenticationService {
    private static final String LOOKUP_SQL =
            "SELECT password_hash, is_active FROM admin_users WHERE username = ?";
    // A fixed valid BCrypt hash keeps unknown-user attempts on a comparable code path.
    private static final String DUMMY_HASH =
            "$2a$12$3VCYv.l1KmPv8jkbjtCp0.uaQWUKIrlZ.LZSnfblDIP.zPVys25XK";

    private final Supplier<Connection> connections;
    private final Clock clock;

    public DatabaseAdminAuthenticationService() {
        this(() -> DatabaseConnection.getInstance().getConnection(), Clock.systemUTC());
    }

    DatabaseAdminAuthenticationService(Supplier<Connection> connections, Clock clock) {
        this.connections = connections;
        this.clock = clock;
    }

    @Override
    public Optional<AdminPrincipal> authenticate(String username, String password) {
        String suppliedUsername = username == null ? "" : username.trim();
        String suppliedPassword = password == null ? "" : password;
        if (suppliedUsername.isEmpty()) {
            BCryptUtil.verify(suppliedPassword, DUMMY_HASH);
            return Optional.empty();
        }

        try (Connection connection = connections.get();
             PreparedStatement statement = connection.prepareStatement(LOOKUP_SQL)) {
            statement.setString(1, suppliedUsername);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    BCryptUtil.verify(suppliedPassword, DUMMY_HASH);
                    return Optional.empty();
                }
                String hash = result.getString("password_hash");
                boolean active = result.getBoolean("is_active");
                boolean passwordMatches = BCryptUtil.verify(suppliedPassword, hash);
                if (active && passwordMatches && !suppliedPassword.isEmpty()) {
                    return Optional.of(new AdminPrincipal(suppliedUsername, clock.instant()));
                }
                return Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw new IllegalStateException("Administrator authentication could not reach the database.", failure);
        }
    }
}
