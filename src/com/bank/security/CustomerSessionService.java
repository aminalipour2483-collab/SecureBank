package com.bank.security;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.exception.AccessDeniedException;
import com.bank.exception.CustomerBannedException;
import com.bank.exception.CustomerArchivedException;
import com.bank.exception.CustomerNotFoundException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.service.AccountService;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Authenticates customers and validates the active in-process session against current DB state. */
public final class CustomerSessionService {
    private final AccountService accountService;
    private final AccountDAO accountDAO;
    private final CustomerDAO customerDAO;
    private final CustomerSessionContext context;
    private final Clock clock;

    public CustomerSessionService(AccountService accountService, AccountDAO accountDAO,
                                  CustomerDAO customerDAO, CustomerSessionContext context) {
        this(accountService, accountDAO, customerDAO, context, Clock.systemUTC());
    }

    CustomerSessionService(AccountService accountService, AccountDAO accountDAO,
                           CustomerDAO customerDAO, CustomerSessionContext context, Clock clock) {
        this.accountService = Objects.requireNonNull(accountService, "accountService");
        this.accountDAO = Objects.requireNonNull(accountDAO, "accountDAO");
        this.customerDAO = Objects.requireNonNull(customerDAO, "customerDAO");
        this.context = Objects.requireNonNull(context, "context");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Performs the existing card/PIN authentication, then issues a principal only for an active owner/account. */
    public CustomerPrincipal authenticate(String cardNumber, String pin) {
        Account authenticated = accountService.login(cardNumber, pin);
        Account persisted = accountDAO.findById(authenticated.getAccountId())
                .orElseThrow(() -> new AccessDeniedException("The authenticated account is no longer available."));
        Customer owner = customerDAO.findById(persisted.getCustomerId())
                .orElseThrow(() -> new CustomerNotFoundException("ID=" + persisted.getCustomerId()));
        requireActive(owner, persisted);

        CustomerPrincipal principal = new CustomerPrincipal(
                owner.getCustomerId(), persisted.getAccountId(), UUID.randomUUID(), clock.instant());
        context.establish(principal);
        return principal;
    }

    /** Re-reads account and owner state; call before each customer-facing use case. */
    public Account requireActiveAccount(CustomerPrincipal principal) {
        CustomerPrincipal current = context.requireCurrent(principal);
        Account account = accountDAO.findById(current.authenticatedAccountId())
                .orElseThrow(() -> new AccessDeniedException("Customer session account no longer exists."));
        if (account.getCustomerId() != current.customerId()) {
            throw new AccessDeniedException("Customer session ownership validation failed.");
        }
        Customer owner = customerDAO.findById(current.customerId())
                .orElseThrow(() -> new CustomerNotFoundException("ID=" + current.customerId()));
        requireActive(owner, account);
        return account;
    }

    public void logout(CustomerPrincipal principal) {
        context.invalidate(principal);
    }

    public CustomerSessionContext context() { return context; }

    private static void requireActive(Customer owner, Account account) {
        if (owner.isBanned()) {
            throw new CustomerBannedException(owner.getFullName(), owner.getBannedReason());
        }
        if (owner.isArchived()) {
            throw new CustomerArchivedException(owner.getFullName());
        }
        if (!account.isActive()) {
            throw new AccessDeniedException("This account is not active. Customer sign-in is no longer permitted.");
        }
    }
}
