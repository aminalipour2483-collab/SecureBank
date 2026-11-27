package com.bank.security;

import java.util.Optional;

/** Application-layer port for verifying administrator credentials. */
public interface AdminAuthenticationService {

    /**
     * @return a principal when the credentials are valid, otherwise empty.
     *         Never reveals whether the username or the password was wrong.
     */
    Optional<AdminPrincipal> authenticate(String username, String password);
}
