package com.bank.service;

import com.bank.security.AdminPrincipal;
import com.bank.model.FraudAlert;
import com.bank.model.Transaction.TransactionType;

import java.math.BigDecimal;
import java.util.List;

/**
 * FraudDetectionService — real-time transaction fraud scoring
 * plus admin review operations.                          (v3.0 — NEW)
 *
 * Folder: BankManagementSystem/src/com/bank/service/FraudDetectionService.java
 *
 * HOW THE ENGINE WORKS
 * --------------------
 * Every balance-affecting operation (deposit, withdrawal, transfer) is
 * scored AFTER it is posted to the ledger:
 *
 *   1. Eight weighted rules evaluate the transaction against the account's
 *      recent history (large amount, structuring, velocity, odd hour,
 *      balance drain, new account, rapid movement of funds).
 *   2. An unsupervised statistical baseline (z-score of the amount vs. the
 *      account's own history — a univariate z-score heuristic
 *      distinct from graph-level Isolation Forest) adds anomaly points.
 *   3. The weighted sum (capped at 100) maps to a risk level:
 *      0–24 LOW · 25–49 MEDIUM · 50–74 HIGH · 75–100 CRITICAL.
 *   4. MEDIUM and above are persisted as PENDING fraud_alerts rows for
 *      manager review; the transaction itself still executes (flag-only
 *      policy — the manager decides, optionally banning the customer).
 *
 * HOW TO USE FROM A SERVICE:
 *   FraudDetectionService fraud = ServiceFactory.getInstance().getFraudDetectionService();
 *   fraud.evaluateAndRecord(accountId, customerId, TransactionType.DEPOSIT,
 *                           amount, balanceBefore, transactionId, null);
 *
 * HOW TO USE FROM THE ADMIN PANEL:
 *   List<FraudAlert> pending = fraud.getAlertsByStatus(admin, FraudAlert.AlertStatus.PENDING);
 *   fraud.reviewAlert(admin, alertId, FraudAlert.AlertStatus.CONFIRMED, note);
 */
public interface FraudDetectionService {

    // =========================================================================
    // SCORING  (called by AccountServiceImpl / TransferServiceImpl)
    // =========================================================================

    /**
     * Score a just-posted transaction and, when it is suspicious
     * (risk level >= MEDIUM), persist a PENDING fraud alert.
     *
     * This method NEVER throws for a suspicious transaction — the
     * flag-only policy means the money has already legitimately moved;
     * raising an alert is an audit action, not a rollback.
     *
     * @param accountId     the account the transaction was posted to
     * @param customerId    owner of that account
     * @param type          DEPOSIT / WITHDRAW / TRANSFER_OUT
     *                      (TRANSFER_IN is scored on the sender side)
     * @param amount        transaction amount
     * @param balanceBefore account balance immediately BEFORE the transaction
     * @param transactionId ledger row id, or null when evaluating a transfer
     * @param transferId    transfer row id, or null for deposit / withdraw
     * @return the full assessment (score, level, triggered rules)
     */
    FraudAssessment evaluateAndRecord(long accountId,
                                      long customerId,
                                      TransactionType type,
                                      BigDecimal amount,
                                      BigDecimal balanceBefore,
                                      Long transactionId,
                                      Long transferId);

    /** Durable-outbox entry point: uses the original event time and idempotent alert source. */
    default FraudAssessment evaluateAndRecord(long accountId, long customerId, TransactionType type,
                                      BigDecimal amount, BigDecimal balanceBefore,
                                      Long transactionId, Long transferId,
                                      java.time.LocalDateTime occurredAt, Long sourceOutboxId) {
        return evaluateAndRecord(accountId, customerId, type, amount, balanceBefore, transactionId, transferId);
    }

    /** Read-only scoring for durable work. The outbox repository alone publishes alerts. */
    default FraudAssessment assessForOutbox(long accountId, long customerId, TransactionType type,
            BigDecimal amount, BigDecimal balanceBefore, Long transactionId, Long transferId,
            java.time.LocalDateTime occurredAt) {
        throw new UnsupportedOperationException("Read-only outbox scoring is not implemented.");
    }

    // =========================================================================
    // ADMIN REVIEW  (called by the Fraud Management panel)
    // =========================================================================

    /** Every alert, newest first. */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    List<FraudAlert> getAllAlerts(AdminPrincipal admin);

    /** Every alert with the given review status, newest first. */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    List<FraudAlert> getAlertsByStatus(AdminPrincipal admin, FraudAlert.AlertStatus status);

    /**
     * Confirm or dismiss a PENDING alert.
     *
     * @throws com.bank.exception.BankException if the alert does not exist
     *         or has already been reviewed
     */
    // ADMIN ONLY — requires an authenticated AdminPrincipal (AccessDeniedException otherwise).
    void reviewAlert(AdminPrincipal admin,
                     long alertId,
                     FraudAlert.AlertStatus newStatus,
                     String resolutionNote);

    // =========================================================================
    // RESULT TYPES
    // =========================================================================

    /** Immutable result of one scoring run. */
    final class FraudAssessment {

        private final long              accountId;
        private final long              customerId;
        private final TransactionType   type;
        private final BigDecimal        amount;
        private final int               totalScore;
        private final FraudAlert.RiskLevel level;
        private final List<TriggeredRule> rules;
        private long                    alertId;   // 0 = no alert persisted

        public FraudAssessment(long accountId, long customerId,
                               TransactionType type, BigDecimal amount,
                               int totalScore, FraudAlert.RiskLevel level,
                               List<TriggeredRule> rules) {
            this.accountId  = accountId;
            this.customerId = customerId;
            this.type       = type;
            this.amount     = amount;
            this.totalScore = totalScore;
            this.level      = level;
            this.rules      = List.copyOf(rules);
        }

        public long                  getAccountId()  { return accountId; }
        public long                  getCustomerId() { return customerId; }
        public TransactionType       getType()       { return type; }
        public BigDecimal            getAmount()     { return amount; }
        public int                   getTotalScore() { return totalScore; }
        public FraudAlert.RiskLevel  getLevel()      { return level; }
        public List<TriggeredRule>   getRules()      { return rules; }
        public long                  getAlertId()    { return alertId; }
        public boolean               hasAlert()      { return alertId > 0; }

        public void setAlertId(long alertId) { this.alertId = alertId; }

        @Override
        public String toString() {
            return "FraudAssessment{type=" + type + ", amount=" + amount
                    + ", score=" + totalScore + ", level=" + level
                    + ", rules=" + rules.size() + '}';
        }
    }

    /** One rule that fired during a scoring run. */
    final class TriggeredRule {

        private final String code;
        private final String description;
        private final int    points;
        private final String details;

        public TriggeredRule(String code, String description, int points, String details) {
            this.code        = code;
            this.description = description;
            this.points      = points;
            this.details     = details;
        }

        public String getCode()        { return code; }
        public String getDescription() { return description; }
        public int    getPoints()      { return points; }
        public String getDetails()     { return details; }

        @Override
        public String toString() {
            return code + "(+" + points + ")";
        }
    }
}
