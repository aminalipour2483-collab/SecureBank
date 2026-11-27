package com.bank.service.impl;

import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.dao.AccountDAO;
import com.bank.dao.FraudAlertDAO;
import com.bank.dao.TransactionDAO;
import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.model.FraudAlert;
import com.bank.model.FraudAlert.AlertStatus;
import com.bank.model.FraudAlert.RiskLevel;
import com.bank.model.Transaction;
import com.bank.model.Transaction.TransactionType;
import com.bank.service.FraudDetectionService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * FraudDetectionServiceImpl — the real-time fraud scoring engine.
 *                                                     (v3.0 — NEW)
 * Folder: BankManagementSystem/src/com/bank/service/impl/FraudDetectionServiceImpl.java
 *
 * DEPENDENCY INJECTION:
 *   fraudAlertDAO  — persists PENDING alerts for manager review
 *   transactionDAO — reads recent history for velocity / z-score rules
 *   accountDAO     — reads account creation date for the new-account rule
 *
 *   TransactionDAO transactionDAO = new TransactionDAOImpl();
 *   AccountDAO     accountDAO    = new AccountDAOImpl();
 *   FraudAlertDAO  alertDAO      = new FraudAlertDAOImpl();
 *   FraudDetectionService engine =
 *           new FraudDetectionServiceImpl(alertDAO, transactionDAO, accountDAO);
 *
 * DESIGN NOTES
 * ------------
 * • FLAG-ONLY POLICY: a suspicious transaction still executes. The engine
 *   only records an alert; banning the customer is a manager decision made
 *   afterwards in the admin panel. (Configurable below — flip AUTO_BLOCK
 *   behaviour by adding a critical-level check in the calling services.)
 * • ONE ALERT PER TRANSACTION: when several rules fire together their codes
 *   are joined ("LARGE_AMOUNT+ODD_HOUR") into a single alert row, so the
 *   manager reviews one item per transaction, with the full breakdown in
 *   the details text.
 * • The unsupervised baseline here is a univariate z-score computed from the
 *   account's recent transaction history. It is a statistical heuristic, NOT
 *   an Isolation Forest. The graph investigation module has a separate
 *   unsupervised Isolation Forest over customer-level graph features.
 */
public class FraudDetectionServiceImpl implements FraudDetectionService {

    private final FraudAlertDAO  fraudAlertDAO;
    private final TransactionDAO transactionDAO;
    private final AccountDAO     accountDAO;
    private final Clock          clock;

    // =========================================================================
    // TUNABLE RULE THRESHOLDS
    // =========================================================================

    /** Single transactions above this amount are inherently suspicious. */
    static final BigDecimal LARGE_AMOUNT_THRESHOLD = new BigDecimal("10000");

    /** Structuring = amounts deliberately kept just BELOW the threshold. */
    static final BigDecimal STRUCTURING_FLOOR = new BigDecimal("9000");

    /** More than this many transactions inside the window → velocity rule. */
    static final int VELOCITY_WINDOW_MINUTES = 10;
    static final int VELOCITY_TX_LIMIT       = 5;

    /** Transactions between 00:00 and this hour are "night hours". */
    static final int ODD_HOUR_END = 5;

    /** Debitting >= 90% of the balance in one go → drain rule. */
    static final BigDecimal DRAIN_RATIO = new BigDecimal("0.90");

    /** High-value activity on accounts younger than this → new-account rule. */
    static final int NEW_ACCOUNT_DAYS   = 30;
    static final BigDecimal NEW_ACCOUNT_AMOUNT = new BigDecimal("5000");

    /** Transfer-out shortly after a deposit of similar size → rapid movement. */
    static final int RAPID_MOVEMENT_MINUTES = 30;
    static final BigDecimal RAPID_MOVEMENT_RATIO = new BigDecimal("0.80");

    /** Univariate z-score baseline over recent transaction amounts. */
    static final int    HISTORY_WINDOW   = 50;   // account history sample size
    static final int    MIN_Z_SAMPLES    = 5;    // need at least 5 samples
    static final double Z_HIGH_THRESHOLD = 3.0;  // +30 points
    static final double Z_MEDIUM_THRESHOLD = 2.0; // +15 points

    /** Alerts are persisted from this level upwards; lower scores are only logged. */
    static final RiskLevel MIN_ALERT_LEVEL = RiskLevel.MEDIUM;

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================
    public FraudDetectionServiceImpl(FraudAlertDAO fraudAlertDAO,
                                     TransactionDAO transactionDAO,
                                     AccountDAO accountDAO) {
        this(fraudAlertDAO, transactionDAO, accountDAO, Clock.systemDefaultZone());
    }

    /** Clock is injectable so time-of-day rules (ODD_HOUR) can be tested deterministically. */
    public FraudDetectionServiceImpl(FraudAlertDAO fraudAlertDAO,
                                     TransactionDAO transactionDAO,
                                     AccountDAO accountDAO,
                                     Clock clock) {
        this.fraudAlertDAO  = fraudAlertDAO;
        this.transactionDAO = transactionDAO;
        this.accountDAO     = accountDAO;
        this.clock          = clock;
    }

    // =========================================================================
    // SCORING  — the main entry point
    // =========================================================================
    @Override
    public FraudAssessment evaluateAndRecord(long accountId,
                                             long customerId,
                                             TransactionType type,
                                             BigDecimal amount,
                                             BigDecimal balanceBefore,
                                             Long transactionId,
                                             Long transferId) {
        return evaluateAndRecord(accountId, customerId, type, amount, balanceBefore,
                transactionId, transferId, LocalDateTime.now(clock), null);
    }

    @Override
    public FraudAssessment evaluateAndRecord(long accountId, long customerId,
                                             TransactionType type, BigDecimal amount,
                                             BigDecimal balanceBefore, Long transactionId,
                                             Long transferId, LocalDateTime occurredAt,
                                             Long sourceOutboxId) {
        LocalDateTime now = occurredAt == null ? LocalDateTime.now(clock) : occurredAt;
        FraudAssessment assessment = assessForOutbox(accountId, customerId, type, amount,
                balanceBefore, transactionId, transferId, now);
        var rules = assessment.getRules();
        var level = assessment.getLevel();
        int totalScore = assessment.getTotalScore();
        // ── 11. Persist a PENDING alert when suspicious enough ────────────────
        if (!rules.isEmpty() && level.ordinal() >= MIN_ALERT_LEVEL.ordinal()) {
            FraudAlert alert = buildAlert(assessment, now, transactionId, transferId);
            alert.setSourceOutboxId(sourceOutboxId);
            long alertId = fraudAlertDAO.save(alert);
            alert.setAlertId(alertId);
            assessment.setAlertId(alertId);

            System.out.println("[FraudEngine] ALERT #" + alertId
                    + " — " + type + " " + fmt(amount)
                    + " on account " + accountId
                    + " scored " + totalScore + "/100 (" + level + ")"
                    + " rules=" + alert.getRuleCode());
        } else if (!rules.isEmpty()) {
            System.out.println("[FraudEngine] " + type + " " + fmt(amount)
                    + " on account " + accountId + " scored " + totalScore
                    + "/100 (" + level + ") — below alert threshold, logged only.");
        }

        return assessment;
    }

    @Override public FraudAssessment assessForOutbox(long accountId, long customerId,
            TransactionType type, BigDecimal amount, BigDecimal balanceBefore,
            Long transactionId, Long transferId, LocalDateTime occurredAt) {
        LocalDateTime now = occurredAt == null ? LocalDateTime.now(clock) : occurredAt;

        // ── 1. Load recent history for this account ──────────────────────────
        // The caller passes the ledger id of the transaction just posted
        // (deposit / withdraw) so it can be EXCLUDED from the statistics —
        // the z-score must compare the new amount against PAST behaviour.
        // For transfers (transactionId == null) the freshly inserted sender
        // leg is still inside the window; with a 50-row sample its tiny
        // influence on mean/σ is acceptable and keeps the code simple.
        List<Transaction> history = new ArrayList<>(transactionId == null
                ? transactionDAO.findRecentByAccountIdBefore(accountId, HISTORY_WINDOW, now)
                : transactionDAO.findRecentBeforeEvent(accountId, HISTORY_WINDOW, now, transactionId));

        List<TriggeredRule> rules = new ArrayList<>();

        // ── 2. Rule 1 — LARGE_AMOUNT (+40) ───────────────────────────────────
        if (amount.compareTo(LARGE_AMOUNT_THRESHOLD) > 0) {
            rules.add(new TriggeredRule(
                    "LARGE_AMOUNT",
                    "Unusually large single transaction",
                    40,
                    String.format("Amount %s exceeds the large-transaction threshold of %s.",
                            fmt(amount), fmt(LARGE_AMOUNT_THRESHOLD))));
        }
        // ── 3. Rule 2 — STRUCTURING (+25) ────────────────────────────────────
        // An amount deliberately just below the reporting threshold is a
        // classic evasion pattern ("smurfing").
        else if (amount.compareTo(STRUCTURING_FLOOR) >= 0) {
            rules.add(new TriggeredRule(
                    "STRUCTURING",
                    "Amount just below the reporting threshold",
                    25,
                    String.format("Amount %s sits just below the %s threshold — possible structuring.",
                            fmt(amount), fmt(LARGE_AMOUNT_THRESHOLD))));
        }

        // ── 4. Rule 3 — HIGH_VELOCITY (+30) ──────────────────────────────────
        LocalDateTime velocityCutoff = now.minusMinutes(VELOCITY_WINDOW_MINUTES);
        long recentCount = history.stream()
                .filter(t -> t.getCreatedAt() != null
                          && !t.getCreatedAt().isBefore(velocityCutoff))
                .count();
        // For deposit/withdraw the current tx was removed above → add it back;
        // for a transfer the sender leg is already part of the history.
        if (transactionId != null) recentCount++;
        if (recentCount > VELOCITY_TX_LIMIT) {
            rules.add(new TriggeredRule(
                    "HIGH_VELOCITY",
                    "Burst of transactions within a short window",
                    30,
                    String.format("%d transactions within %d minutes (velocity limit: %d).",
                            recentCount, VELOCITY_WINDOW_MINUTES, VELOCITY_TX_LIMIT)));
        }

        // ── 5. Rule 4 — ODD_HOUR (+15) ───────────────────────────────────────
        if (now.getHour() < ODD_HOUR_END) {
            rules.add(new TriggeredRule(
                    "ODD_HOUR",
                    "Transaction performed during night hours",
                    15,
                    String.format("Posted between 00:00 and %02d:00 — outside normal banking hours.",
                            ODD_HOUR_END)));
        }

        // ── 6. Rule 5 — BALANCE_DRAIN (+20) ──────────────────────────────────
        if ((type == TransactionType.WITHDRAW || type == TransactionType.TRANSFER_OUT)
                && balanceBefore != null && balanceBefore.signum() > 0) {

            BigDecimal drainLine = balanceBefore.multiply(DRAIN_RATIO);
            if (amount.compareTo(drainLine) >= 0) {
                BigDecimal pct = amount.multiply(BigDecimal.valueOf(100))
                        .divide(balanceBefore, 1, RoundingMode.HALF_UP);
                rules.add(new TriggeredRule(
                        "BALANCE_DRAIN",
                        "Transaction drains nearly the whole balance",
                        20,
                        String.format("Debit of %s takes %s%% of the %s balance.",
                                fmt(amount), fmt(pct), fmt(balanceBefore))));
            }
        }

        // ── 7. Rule 6 — NEW_ACCOUNT (+20) ────────────────────────────────────
        Account account = accountDAO.findById(accountId).orElse(null);
        if (account != null && account.getCreatedAt() != null) {
            long ageDays = Duration.between(account.getCreatedAt(), now).toDays();
            if (ageDays >= 0 && ageDays < NEW_ACCOUNT_DAYS
                    && amount.compareTo(NEW_ACCOUNT_AMOUNT) >= 0) {
                rules.add(new TriggeredRule(
                        "NEW_ACCOUNT",
                        "High-value activity on a brand-new account",
                        20,
                        String.format("Amount %s on an account opened %d day(s) ago.",
                                fmt(amount), ageDays)));
            }
        }

        // ── 8. Rule 7 — RAPID_MOVEMENT (+35) ─────────────────────────────────
        // Money in and straight back out — a hallmark of mule accounts.
        if (type == TransactionType.TRANSFER_OUT) {
            LocalDateTime depositCutoff = now.minusMinutes(RAPID_MOVEMENT_MINUTES);
            history.stream()
                    .filter(t -> t.getTransactionType() == TransactionType.DEPOSIT
                              && t.getCreatedAt() != null
                              && !t.getCreatedAt().isBefore(depositCutoff))
                    .filter(d -> amount.compareTo(
                            d.getAmount().multiply(RAPID_MOVEMENT_RATIO)) >= 0)
                    .findFirst()
                    .ifPresent(d -> rules.add(new TriggeredRule(
                            "RAPID_MOVEMENT",
                            "Funds transferred out right after a deposit",
                            35,
                            String.format("Transfer of %s follows a deposit of %s within %d minutes.",
                                    fmt(amount), fmt(d.getAmount()),
                                    RAPID_MOVEMENT_MINUTES))));
        }

        // ── 9. Rule 8 — ANOMALOUS_AMOUNT (statistical, +30 / +15) ─────────────
        // Univariate z-score against this account's own recent history. This
        // transaction-level heuristic is distinct from the graph-level
        // Isolation Forest used by the administrator fraud-ring analysis.
        List<BigDecimal> samples = history.stream()
                .map(Transaction::getAmount)
                .filter(a -> a != null)
                .toList();
        if (samples.size() >= MIN_Z_SAMPLES) {
            double mean = samples.stream()
                    .mapToDouble(BigDecimal::doubleValue).average().orElse(0);
            double variance = samples.stream()
                    .mapToDouble(BigDecimal::doubleValue)
                    .map(v -> (v - mean) * (v - mean))
                    .average().orElse(0);
            double std = Math.sqrt(variance);

            double z;
            if (std < 1e-9) {
                // Constant history: any different amount is infinitely
                // anomalous; an identical amount is perfectly normal.
                BigDecimal meanBd = BigDecimal.valueOf(mean)
                        .setScale(2, RoundingMode.HALF_UP);
                z = (amount.setScale(2, RoundingMode.HALF_UP).compareTo(meanBd) == 0)
                        ? 0.0 : Double.POSITIVE_INFINITY;
            } else {
                z = Math.abs(amount.doubleValue() - mean) / std;
            }

            if (Double.isInfinite(z) || z >= Z_HIGH_THRESHOLD) {
                rules.add(new TriggeredRule(
                        "ANOMALOUS_AMOUNT",
                        "Statistical outlier vs. account history",
                        30,
                        String.format(
                                "Amount %s deviates %s from this account's history "
                              + "(mean %s, σ %s, n=%d) — historical z-score outlier.",
                                fmt(amount),
                                Double.isInfinite(z) ? "completely" : String.format("%.1fσ", z),
                                fmt(BigDecimal.valueOf(mean).setScale(2, RoundingMode.HALF_UP)),
                                fmt(BigDecimal.valueOf(std).setScale(2, RoundingMode.HALF_UP)),
                                samples.size())));
            } else if (z >= Z_MEDIUM_THRESHOLD) {
                rules.add(new TriggeredRule(
                        "ANOMALOUS_AMOUNT",
                        "Moderate statistical outlier vs. account history",
                        15,
                        String.format("Amount %s is %.1fσ above the account mean "
                                + "(mean %s, σ %s, n=%d).",
                                fmt(amount), z,
                                fmt(BigDecimal.valueOf(mean).setScale(2, RoundingMode.HALF_UP)),
                                fmt(BigDecimal.valueOf(std).setScale(2, RoundingMode.HALF_UP)),
                                samples.size())));
            }
        }

        // ── 10. Aggregate score and level ─────────────────────────────────────
        int totalScore = Math.min(100,
                rules.stream().mapToInt(TriggeredRule::getPoints).sum());
        RiskLevel level = levelFor(totalScore);

        FraudAssessment assessment = new FraudAssessment(
                accountId, customerId, type, amount, totalScore, level, rules);

        return assessment;
    }

    // =========================================================================
    // ADMIN REVIEW OPERATIONS
    // =========================================================================
    @Override
    public List<FraudAlert> getAllAlerts(AdminPrincipal admin) {
        AdminGuard.require(admin);
        return fraudAlertDAO.findAll();
    }

    @Override
    public List<FraudAlert> getAlertsByStatus(AdminPrincipal admin, AlertStatus status) {
        AdminGuard.require(admin);
        return fraudAlertDAO.findByStatus(status);
    }

    @Override
    public void reviewAlert(AdminPrincipal admin,
                            long alertId,
                            AlertStatus newStatus,
                            String resolutionNote) {
        AdminGuard.require(admin);
        // The reviewer recorded in the audit trail is the authenticated administrator,
        // never a caller-supplied string.
        final String reviewedBy = admin.username();
        if (newStatus != AlertStatus.CONFIRMED && newStatus != AlertStatus.DISMISSED) {
            throw new BankException("An alert can only be reviewed as CONFIRMED or DISMISSED.");
        }
        // Confirm the alert exists (throws nothing if missing → DAO returns false)
        fraudAlertDAO.findById(alertId)
                .orElseThrow(() -> new BankException("Fraud alert not found: ID=" + alertId));

        boolean updated = fraudAlertDAO.updateReview(
                alertId, newStatus, reviewedBy, resolutionNote);
        if (!updated) {
            throw new BankException("Alert #" + alertId
                    + " has already been reviewed and cannot be changed.");
        }
        System.out.println("[FraudEngine] Alert #" + alertId + " reviewed as "
                + newStatus + " by " + reviewedBy);
    }

    // =========================================================================
    // PRIVATE UTILITIES
    // =========================================================================

    /** Map a 0–100 score onto the four risk levels. */
    private RiskLevel levelFor(int score) {
        if (score >= 75) return RiskLevel.CRITICAL;
        if (score >= 50) return RiskLevel.HIGH;
        if (score >= 25) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** Build the single alert row that summarises a whole assessment. */
    private FraudAlert buildAlert(FraudAssessment assessment,
                                  LocalDateTime detectedAt,
                                  Long transactionId,
                                  Long transferId) {
        FraudAlert alert = new FraudAlert();
        alert.setCustomerId(assessment.getCustomerId());
        alert.setAccountId(assessment.getAccountId());
        alert.setTransactionId(transactionId);
        alert.setTransferId(transferId);
        alert.setRiskScore(assessment.getTotalScore());
        alert.setRiskLevel(assessment.getLevel());
        alert.setStatus(AlertStatus.PENDING);
        alert.setDetectedAt(detectedAt);

        // Rule codes joined with '+' → one reviewable alert per transaction.
        StringJoiner codes = new StringJoiner("+");
        for (TriggeredRule rule : assessment.getRules()) {
            codes.add(rule.getCode());
        }
        alert.setRuleCode(codes.toString());
        alert.setRuleDescription(assessment.getRules().size()
                + (assessment.getRules().size() == 1
                        ? " fraud rule triggered"
                        : " fraud rules triggered"));
        alert.setDetails(buildDetails(assessment));
        return alert;
    }

    /** Multi-line human-readable breakdown shown in the details dialog. */
    private String buildDetails(FraudAssessment assessment) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Transaction: %s of %s on account %d%n",
                assessment.getType(), fmt(assessment.getAmount()),
                assessment.getAccountId()));
        sb.append(String.format("Risk score: %d/100 (%s)%n",
                assessment.getTotalScore(), assessment.getLevel()));
        sb.append("Triggered rules:");
        for (TriggeredRule rule : assessment.getRules()) {
            sb.append(String.format("%n  • %s (+%d): %s",
                    rule.getCode(), rule.getPoints(), rule.getDetails()));
        }
        sb.append(String.format("%nEngine: weighted rules + univariate historical z-score baseline"));
        return sb.toString();
    }

    /** Money formatter shared by all rule descriptions. */
    private String fmt(BigDecimal value) {
        return String.format("%,.2f", value);
    }
}
