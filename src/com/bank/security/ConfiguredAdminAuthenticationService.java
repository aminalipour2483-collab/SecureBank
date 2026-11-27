package com.bank.security;

import com.bank.util.BCryptUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.Properties;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Authenticates the single configured administrator account.
 *
 * <p>Credentials come from the environment:
 * <ul>
 *   <li>{@code BANK_ADMIN_USERNAME}</li>
 *   <li>{@code BANK_ADMIN_PASSWORD_HASH} &mdash; a jBCrypt hash ({@code $2a$...})</li>
 * </ul>
 * If neither is set, the documented demo credentials ({@code admin} /
 * {@code admin123}) are used and a warning is logged, which preserves the
 * behaviour of earlier releases. Setting only one of the two variables is a
 * configuration error and fails closed.
 */
public final class ConfiguredAdminAuthenticationService implements AdminAuthenticationService {

    static final String ENV_USERNAME = "BANK_ADMIN_USERNAME";
    static final String ENV_PASSWORD_HASH = "BANK_ADMIN_PASSWORD_HASH";

    private static final String DEMO_USERNAME = "admin";
    private static final String DEMO_PASSWORD = "admin123";
    private static final Logger LOG = Logger.getLogger(ConfiguredAdminAuthenticationService.class.getName());

    private final byte[] usernameBytes;
    private final String passwordHash;
    private final Clock clock;

    public ConfiguredAdminAuthenticationService(String username, String passwordHash, Clock clock) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Administrator username must not be blank.");
        }
        if (!BCryptUtil.isBcryptHash(passwordHash)) {
            throw new IllegalArgumentException("Administrator password hash must be a jBCrypt hash.");
        }
        this.usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        this.passwordHash = passwordHash;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Uses explicitly configured credentials only; never enables a known fallback password. */
    public static ConfiguredAdminAuthenticationService fromEnvironment() {
        String username = System.getenv(ENV_USERNAME);
        String hash = System.getenv(ENV_PASSWORD_HASH);
        if ((username == null || username.isBlank()) && (hash == null || hash.isBlank())) {
            String path = System.getProperty("bank.admin.config");
            if (path != null && !path.isBlank()) {
                Properties properties = new Properties();
                try (InputStream input = Files.newInputStream(Path.of(path))) {
                    properties.load(input);
                } catch (Exception ex) {
                    throw new IllegalStateException("Cannot load the configured administrator login file.", ex);
                }
                username = properties.getProperty("admin.username");
                hash = properties.getProperty("admin.passwordHash");
            }
        }
        return fromValues(username, hash, Clock.systemUTC());
    }

    static ConfiguredAdminAuthenticationService fromValues(String username, String hash, Clock clock) {
        if (username == null || username.isBlank() || hash == null || hash.isBlank()) {
            throw new IllegalStateException("Administrator username and password hash must both be configured. "
                    + "Run SETUP-WINDOWS.bat or set " + ENV_USERNAME + " and " + ENV_PASSWORD_HASH + ".");
        }
        return new ConfiguredAdminAuthenticationService(username.trim(), hash.trim(), clock);
    }

    @Override
    public Optional<AdminPrincipal> authenticate(String username, String password) {
        // Always run the BCrypt comparison, and compare names in constant time,
        // so response time does not reveal whether the username was correct.
        boolean passwordOk = BCryptUtil.verify(password == null ? "" : password, passwordHash);
        byte[] supplied = (username == null ? "" : username.trim()).getBytes(StandardCharsets.UTF_8);
        boolean nameOk = MessageDigest.isEqual(usernameBytes, supplied);
        if (passwordOk && nameOk && password != null && !password.isEmpty()) {
            return Optional.of(new AdminPrincipal(new String(usernameBytes, StandardCharsets.UTF_8),
                    clock.instant()));
        }
        return Optional.empty();
    }
}
