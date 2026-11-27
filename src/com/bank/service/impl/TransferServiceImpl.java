package com.bank.service.impl;

import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerAccessGuard;
import com.bank.dao.AccountDAO;
import com.bank.dao.CustomerDAO;
import com.bank.dao.TransferDAO;
import com.bank.exception.*;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.model.Transaction.TransactionType;
import com.bank.model.Transfer;
import com.bank.model.TransferExecutionResult;
import com.bank.service.FraudDetectionService;
import com.bank.service.TransferService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import com.bank.util.IdempotencyUtil;

/**
 * TransferServiceImpl — business logic for inter-account transfers.
 *
 * Folder: BankManagementSystem/src/com/bank/service/impl/TransferServiceImpl.java
 *
 * DEPENDENCY INJECTION:
 *   transferDAO — executes the atomic 7-step DB transaction
 *   accountDAO  — used here only for pre-flight validation BEFORE the DAO is called
 *   customerDAO — v3.0: ban checks on both the sender and the receiver
 *   fraudDetectionService — v3.0: scores the transfer for fraud after posting
 *
 * HOW TO INSTANTIATE:
 *   TransferDAO  transferDAO = new TransferDAOImpl();
 *   AccountDAO   accountDAO  = new AccountDAOImpl();
 *   CustomerDAO  customerDAO = new CustomerDAOImpl();
 *   FraudDetectionService fraud = new FraudDetectionServiceImpl(...);
 *   TransferService transferService =
 *           new TransferServiceImpl(transferDAO, accountDAO, customerDAO, fraud);
 *
 * WHY TWO LAYERS OF CHECKS?
 * The Service layer checks business rules BEFORE touching the DB (fast, clear errors).
 * The DAO layer checks balances again INSIDE the DB transaction using
 * SELECT FOR UPDATE (prevents race conditions under concurrent load).
 * Both layers are needed — one for UX clarity, one for data integrity.
 */
public class TransferServiceImpl implements TransferService {

    private final TransferDAO transferDAO;
    private final AccountDAO  accountDAO;

    // v3.0 — fraud module dependencies
    private final CustomerDAO          customerDAO;
    private final CustomerAccessGuard customerAccessGuard;

    public TransferServiceImpl(TransferDAO transferDAO,
                               AccountDAO accountDAO,
                               CustomerDAO customerDAO,
                               FraudDetectionService fraudDetectionService) {
        this(transferDAO, accountDAO, customerDAO, fraudDetectionService, null);
    }

    public TransferServiceImpl(TransferDAO transferDAO, AccountDAO accountDAO,
                               CustomerDAO customerDAO, FraudDetectionService fraudDetectionService,
                               CustomerAccessGuard customerAccessGuard) {
        this.transferDAO = transferDAO;
        this.accountDAO = accountDAO;
        this.customerDAO = customerDAO;
        // Retained constructor parameter for source compatibility. P6 persists fraud
        // work in the financial transaction and processes it asynchronously from outbox.
        this.customerAccessGuard = customerAccessGuard;
    }

    private void requireCustomerGuard() {
        if (customerAccessGuard == null) {
            throw new AccessDeniedException("Customer authentication guard is not configured.");
        }
    }

    private void rejectLegacyCustomerEntryPoint() {
        if (customerAccessGuard != null) {
            throw new AccessDeniedException("An authenticated CustomerPrincipal is required for this operation.");
        }
    }

    // =========================================================================
    // PROCESS TRANSFER  — the main method
    // =========================================================================
    @Override
    public Transfer processTransfer(long senderAccountId,
                                    long receiverAccountId,
                                    BigDecimal amount,
                                    String idempotencyKey) {
        rejectLegacyCustomerEntryPoint();
        return processTransferInternal(senderAccountId, receiverAccountId, amount, idempotencyKey);
    }

    @Override
    public Transfer processTransfer(CustomerPrincipal principal, long senderAccountId,
                                    long receiverAccountId, BigDecimal amount, String idempotencyKey) {
        requireCustomerGuard();
        customerAccessGuard.requireOwnedActiveAccount(principal, senderAccountId);
        customerAccessGuard.requireActiveReceiver(principal, receiverAccountId);
        return processTransferInternal(senderAccountId, receiverAccountId, amount, idempotencyKey);
    }

    private Transfer processTransferInternal(long senderAccountId, long receiverAccountId,
                                             BigDecimal amount, String idempotencyKey) {
        // ── PRE-FLIGHT BUSINESS RULES (Service layer) ─────────────────────────

        // Rule 1: Amount must be positive
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BankException("Transfer amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BankException("Transfer amount cannot have more than two decimal places.");
        }
        IdempotencyUtil.validateKey(idempotencyKey);

        // Account ownership, status, customer-ban state, and balance are checked
        // under row locks in TransferDAOImpl. Keeping the DAO as the first place
        // that resolves a retry also lets a completed request replay successfully
        // even if an account was later locked, banned, or its balance changed.

        // Rule 2: Sender and receiver must be different accounts
        if (senderAccountId == receiverAccountId) {
            throw new BankException("Cannot transfer money to the same account.");
        }

        // ── BUILD THE TRANSFER OBJECT ──────────────────────────────────────────
        String referenceNumber = generateReferenceNumber();

        Transfer transfer = new Transfer(
                senderAccountId,
                receiverAccountId,
                amount,
                referenceNumber);

        // ── DELEGATE TO DAO (atomic DB transaction) ────────────────────────────
        // TransferDAOImpl.executeTransfer() handles:
        //   SELECT FOR UPDATE → debit → credit → INSERT tx×2 → INSERT transfer
        // All inside conn.setAutoCommit(false) / commit() or rollback()
        TransferExecutionResult execution = transferDAO.executeTransfer(transfer, idempotencyKey);
        long transferId = execution.getTransferId();

        // Fraud analysis is no longer invoked inline. TransferDAOImpl wrote a durable
        // outbox event in the same transaction as the financial posting.

        // ── RETURN THE PERSISTED RECORD ────────────────────────────────────────
        return transferDAO.findById(transferId)
                .orElseThrow(() -> new BankException(
                        "Transfer completed but record not found. Ref: " + referenceNumber));
    }

    // =========================================================================
    // GET TRANSFER HISTORY
    // =========================================================================
    @Override
    public long resolveRecipientAccountIdByCardNumber(CustomerPrincipal principal, String cardNumber) {
        requireCustomerGuard();
        customerAccessGuard.requireActivePrincipal(principal);
        if (cardNumber == null || !cardNumber.trim().matches("\\d{12}")) {
            throw new BankException("Enter a valid 12-digit recipient card number.");
        }
        Account recipient = accountDAO.findByCardNumber(cardNumber.trim())
                .orElseThrow(() -> new AccountNotFoundException("Recipient account was not found."));
        customerAccessGuard.requireActiveReceiver(principal, recipient.getAccountId());
        return recipient.getAccountId();
    }

    @Override
    public long resolveRecipientAccountIdByAccountNumber(CustomerPrincipal principal, String accountNumber) {
        requireCustomerGuard();
        customerAccessGuard.requireActivePrincipal(principal);
        if (accountNumber == null || !accountNumber.trim().matches("\\d{12}")) {
            throw new BankException("Enter a valid 12-digit recipient account number.");
        }
        Account recipient = accountDAO.findByAccountNumber(accountNumber.trim())
                .orElseThrow(() -> new AccountNotFoundException("Recipient account was not found."));
        customerAccessGuard.requireActiveReceiver(principal, recipient.getAccountId());
        return recipient.getAccountId();
    }

    @Override
    public List<Transfer> getTransferHistory(long accountId) {
        rejectLegacyCustomerEntryPoint();
        accountDAO.findById(accountId).orElseThrow(() -> new AccountNotFoundException("ID=" + accountId));
        return transferDAO.findAllByAccountId(accountId);
    }

    @Override
    public List<Transfer> getTransferHistory(CustomerPrincipal principal, long accountId) {
        requireCustomerGuard();
        customerAccessGuard.requireOwnedActiveAccount(principal, accountId);
        return transferDAO.findAllByAccountId(accountId);
    }

    // =========================================================================
    // FIND BY REFERENCE NUMBER
    // =========================================================================
    @Override
    public Transfer findByReferenceNumber(String referenceNumber) {
        rejectLegacyCustomerEntryPoint();
        return findByReferenceNumberInternal(referenceNumber);
    }

    @Override
    public Transfer findByReferenceNumber(CustomerPrincipal principal, String referenceNumber) {
        requireCustomerGuard();
        Transfer transfer = findByReferenceNumberInternal(referenceNumber);
        customerAccessGuard.requireTransferParticipant(principal,
                transfer.getSenderAccountId(), transfer.getReceiverAccountId());
        return transfer;
    }

    private Transfer findByReferenceNumberInternal(String referenceNumber) {
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new BankException("Reference number cannot be blank.");
        }
        return transferDAO.findByReferenceNumber(referenceNumber.trim())
                .orElseThrow(() -> new BankException(
                        "No transfer found with reference: " + referenceNumber));
    }

    // =========================================================================
    // FIND ALL  (admin panel)
    // =========================================================================
    @Override
    public List<Transfer> findAll(AdminPrincipal admin) {
        AdminGuard.require(admin);
        return transferDAO.findAll();
    }

    // =========================================================================
    // UPDATE TRANSFER STATUS  (admin override)
    // =========================================================================
    @Override
    public void updateTransferStatus(AdminPrincipal admin, long transferId,
                                     Transfer.TransferStatus newStatus) {
        AdminGuard.require(admin);
        Transfer current = transferDAO.findById(transferId)
                .orElseThrow(() -> new BankException("Transfer not found: ID=" + transferId));
        // Never claim to reverse a posted transfer without corresponding ledger movements.
        if (current.getStatus() != Transfer.TransferStatus.PENDING
                || newStatus != Transfer.TransferStatus.FAILED) {
            throw new BankException("Only unposted PENDING transfers can be marked FAILED. "
                    + "Posted transfers cannot be reversed by a status-only update.");
        }
        if (!transferDAO.updateStatus(transferId, newStatus)) {
            throw new BankException("Transfer status update failed.");
        }
    }

    // =========================================================================
    // PRIVATE UTILITIES
    // =========================================================================

    /**
     * Generates a unique, human-readable reference number for receipts.
     *
     * Format: TXF-YYYYMMDD-XXXXXXXX
     * Example: TXF-20240301-A3F2B1C9
     *
     * The UUID suffix makes collisions practically impossible.
     * The date prefix makes customer-support lookups easy.
     */
    private String generateReferenceNumber() {
        String date   = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID()
                            .toString()
                            .replace("-", "")
                            .substring(0, 8)
                            .toUpperCase();
        return "TXF-" + date + "-" + suffix;
    }
}
