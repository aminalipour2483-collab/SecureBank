package com.bank.security;

import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.exception.AccessDeniedException;
import com.bank.exception.AccountNotFoundException;
import com.bank.exception.CustomerBannedException;
import com.bank.exception.CustomerArchivedException;
import com.bank.exception.CustomerNotFoundException;
import com.bank.model.Account;
import com.bank.model.Customer;
import java.util.Objects;

/**
 * Service-boundary authorization for customer use cases.
 *
 * <p>The guard validates the current in-process session, the account used to
 * authenticate, customer status, account ownership, and account status on
 * every request. It is an application-layer control for the desktop client,
 * not a substitute for a trusted server-side security boundary.
 */
public final class CustomerAccessGuard {
    private final AccountDAO accountDAO;
    private final CustomerDAO customerDAO;
    private final CustomerSessionContext context;

    public CustomerAccessGuard(AccountDAO accountDAO, CustomerDAO customerDAO,
                               CustomerSessionContext context) {
        this.accountDAO = Objects.requireNonNull(accountDAO, "accountDAO");
        this.customerDAO = Objects.requireNonNull(customerDAO, "customerDAO");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Requires a current session whose original login account and customer remain active. */
    public CustomerPrincipal requireActivePrincipal(CustomerPrincipal supplied) {
        CustomerPrincipal current = context.requireCurrent(supplied);
        Account loginAccount = accountDAO.findById(current.authenticatedAccountId())
                .orElseThrow(() -> new AccessDeniedException("The authenticated account no longer exists."));
        if (loginAccount.getCustomerId() != current.customerId()) {
            throw new AccessDeniedException("The authenticated account no longer belongs to this customer.");
        }
        requireActiveOwner(current.customerId());
        if (!loginAccount.isActive()) {
            throw new AccessDeniedException("The account used to sign in is no longer active.");
        }
        return current;
    }

    /** Requires that the requested account is active and owned by the authenticated customer. */
    public Account requireOwnedActiveAccount(CustomerPrincipal supplied, long accountId) {
        CustomerPrincipal principal = requireActivePrincipal(supplied);
        Account account = accountDAO.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("ID=" + accountId));
        if (account.getCustomerId() != principal.customerId()) {
            throw new AccessDeniedException("You are not authorized to access this account.");
        }
        requireActiveOwner(account.getCustomerId());
        if (!account.isActive()) {
            throw new AccessDeniedException("This account is not active.");
        }
        return account;
    }

    /** Requires an active receiver account; the receiver need not belong to the sender. */
    public Account requireActiveReceiver(CustomerPrincipal supplied, long receiverAccountId) {
        requireActivePrincipal(supplied);
        Account receiver = accountDAO.findById(receiverAccountId)
                .orElseThrow(() -> new AccountNotFoundException("Receiver account ID=" + receiverAccountId));
        requireActiveOwner(receiver.getCustomerId());
        if (!receiver.isActive()) {
            throw new AccessDeniedException("The recipient account is not active and cannot receive transfers.");
        }
        return receiver;
    }

    /** A transfer is visible to a customer only if at least one side belongs to them. */
    public void requireTransferParticipant(CustomerPrincipal supplied, long senderAccountId,
                                            long receiverAccountId) {
        CustomerPrincipal principal = requireActivePrincipal(supplied);
        Account sender = accountDAO.findById(senderAccountId)
                .orElseThrow(() -> new AccountNotFoundException("Sender account ID=" + senderAccountId));
        Account receiver = accountDAO.findById(receiverAccountId)
                .orElseThrow(() -> new AccountNotFoundException("Receiver account ID=" + receiverAccountId));
        if (sender.getCustomerId() != principal.customerId()
                && receiver.getCustomerId() != principal.customerId()) {
            throw new AccessDeniedException("This transfer does not belong to the authenticated customer.");
        }
    }

    private void requireActiveOwner(long customerId) {
        Customer owner = customerDAO.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("ID=" + customerId));
        if (owner.isBanned()) {
            throw new CustomerBannedException(owner.getFullName(), owner.getBannedReason());
        }
        if (owner.isArchived()) {
            throw new CustomerArchivedException(owner.getFullName());
        }
    }
}
