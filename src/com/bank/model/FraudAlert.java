package com.bank.model;

import java.time.LocalDateTime;

/**
 * FraudAlert — mirrors the 'fraud_alerts' table.            (v3.0 — NEW)
 *
 * Folder in NetBeans:
 *   BankManagementSystem/src/com/bank/model/FraudAlert.java
 *
 * One row is created for every transaction that the Fraud Detection
 * Engine scores as suspicious (risk level >= MEDIUM). The manager then
 * reviews the alert in the admin panel and either:
 *   CONFIRMS it (optionally banning the customer), or
 *   DISMISSES it as a false positive.
 *
 * The customer / account / transaction / transfer foreign keys are all
 * nullable (ON DELETE SET NULL in the DB) so the audit trail survives
 * even if the referenced rows are later removed.
 */
public class FraudAlert {

    // -------------------------------------------------------------------------
    // ENUMS  (mirror the MySQL ENUM columns exactly)
    // -------------------------------------------------------------------------

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL;

        /** Parse a DB string safely, e.g. rs.getString("risk_level"). */
        public static RiskLevel from(String value) {
            return (value == null || value.isBlank())
                    ? LOW
                    : RiskLevel.valueOf(value.toUpperCase());
        }
    }

    public enum AlertStatus {
        PENDING, CONFIRMED, DISMISSED;

        public static AlertStatus from(String value) {
            return (value == null || value.isBlank())
                    ? PENDING
                    : AlertStatus.valueOf(value.toUpperCase());
        }
    }

    // -------------------------------------------------------------------------
    // PERSISTED FIELDS  (one per table column)
    // -------------------------------------------------------------------------

    private long          alertId;

    /** Idempotency key linking an alert to its durable outbox event. */
    private Long sourceOutboxId;

    /** FK to customers.customer_id — nullable (audit survives deletion). */
    private Long          customerId;

    /** FK to accounts.account_id — nullable. */
    private Long          accountId;

    /** FK to transactions.transaction_id — nullable (set for deposit/withdraw). */
    private Long          transactionId;

    /** FK to transfers.transfer_id — nullable (set for transfers). */
    private Long          transferId;

    /** All triggered rule codes joined with '+', e.g. "LARGE_AMOUNT+ODD_HOUR". */
    private String        ruleCode;

    /** Short human-readable summary, e.g. "2 fraud rules triggered". */
    private String        ruleDescription;

    /** Aggregated risk score 0–100 (sum of rule weights, capped at 100). */
    private int           riskScore;

    private RiskLevel     riskLevel;

    /** Multi-line explanation shown in the alert details dialog. */
    private String        details;

    /** PENDING → CONFIRMED (fraud) or DISMISSED (false positive). */
    private AlertStatus   status = AlertStatus.PENDING;

    private LocalDateTime detectedAt;

    /** Who reviewed the alert ('admin') — NULL while PENDING. */
    private String        reviewedBy;

    private LocalDateTime reviewedAt;

    private String        resolutionNote;

    // -------------------------------------------------------------------------
    // DISPLAY-ONLY FIELDS  (populated by DAO JOINs, never persisted)
    // -------------------------------------------------------------------------

    private String customerName;
    private String accountNumber;

    // -------------------------------------------------------------------------
    // CONSTRUCTORS
    // -------------------------------------------------------------------------

    /** No-arg constructor — required by DAO result mappers. */
    public FraudAlert() {}

    /**
     * Full constructor — use when loading an existing row from the DB.
     */
    public FraudAlert(long alertId, Long customerId, Long accountId,
                      Long transactionId, Long transferId,
                      String ruleCode, String ruleDescription,
                      int riskScore, RiskLevel riskLevel, String details,
                      AlertStatus status, LocalDateTime detectedAt,
                      String reviewedBy, LocalDateTime reviewedAt,
                      String resolutionNote) {
        this.alertId         = alertId;
        this.customerId      = customerId;
        this.accountId       = accountId;
        this.transactionId   = transactionId;
        this.transferId      = transferId;
        this.ruleCode        = ruleCode;
        this.ruleDescription = ruleDescription;
        this.riskScore       = riskScore;
        this.riskLevel       = riskLevel;
        this.details         = details;
        this.status          = status;
        this.detectedAt      = detectedAt;
        this.reviewedBy      = reviewedBy;
        this.reviewedAt      = reviewedAt;
        this.resolutionNote  = resolutionNote;
    }

    // -------------------------------------------------------------------------
    // GETTERS & SETTERS
    // -------------------------------------------------------------------------

    public Long getSourceOutboxId() { return sourceOutboxId; }
    public void setSourceOutboxId(Long sourceOutboxId) { this.sourceOutboxId = sourceOutboxId; }

    public long getAlertId()                       { return alertId; }
    public void setAlertId(long alertId)           { this.alertId = alertId; }

    public Long getCustomerId()                    { return customerId; }
    public void setCustomerId(Long customerId)     { this.customerId = customerId; }

    public Long getAccountId()                     { return accountId; }
    public void setAccountId(Long accountId)       { this.accountId = accountId; }

    public Long getTransactionId()                        { return transactionId; }
    public void setTransactionId(Long transactionId)      { this.transactionId = transactionId; }

    public Long getTransferId()                          { return transferId; }
    public void setTransferId(Long transferId)           { this.transferId = transferId; }

    public String getRuleCode()                          { return ruleCode; }
    public void setRuleCode(String ruleCode)             { this.ruleCode = ruleCode; }

    public String getRuleDescription()                            { return ruleDescription; }
    public void setRuleDescription(String ruleDescription)       { this.ruleDescription = ruleDescription; }

    public int getRiskScore()                              { return riskScore; }
    public void setRiskScore(int riskScore)                { this.riskScore = riskScore; }

    public RiskLevel getRiskLevel()                        { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel)          { this.riskLevel = riskLevel; }

    public String getDetails()                     { return details; }
    public void setDetails(String details)         { this.details = details; }

    public AlertStatus getStatus()                          { return status; }
    public void setStatus(AlertStatus status)               { this.status = status; }

    public LocalDateTime getDetectedAt()                       { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt)       { this.detectedAt = detectedAt; }

    public String getReviewedBy()                          { return reviewedBy; }
    public void setReviewedBy(String reviewedBy)           { this.reviewedBy = reviewedBy; }

    public LocalDateTime getReviewedAt()                          { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt)          { this.reviewedAt = reviewedAt; }

    public String getResolutionNote()                              { return resolutionNote; }
    public void setResolutionNote(String resolutionNote)           { this.resolutionNote = resolutionNote; }

    // ── Display-only ──────────────────────────────────────────────────────────

    public String getCustomerName()                          { return customerName; }
    public void setCustomerName(String customerName)         { this.customerName = customerName; }

    public String getAccountNumber()                         { return accountNumber; }
    public void setAccountNumber(String accountNumber)       { this.accountNumber = accountNumber; }

    // -------------------------------------------------------------------------
    // UTILITY
    // -------------------------------------------------------------------------

    public boolean isPending()   { return status == AlertStatus.PENDING; }
    public boolean isConfirmed() { return status == AlertStatus.CONFIRMED; }

    @Override
    public String toString() {
        return "FraudAlert{"
                + "alertId=" + alertId
                + ", customerId=" + customerId
                + ", ruleCode='" + ruleCode + '\''
                + ", riskScore=" + riskScore
                + ", riskLevel=" + riskLevel
                + ", status=" + status
                + '}';
    }
}
