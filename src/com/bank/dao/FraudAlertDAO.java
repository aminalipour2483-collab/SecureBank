package com.bank.dao;

import com.bank.model.FraudAlert;
import java.util.List;
import java.util.Optional;

/**
 * FraudAlertDAO — contract for fraud alert database operations.
 *                                                     (v3.0 — NEW)
 * Folder: BankManagementSystem/src/com/bank/dao/FraudAlertDAO.java
 *
 * Alerts are written by the Fraud Detection Engine every time a
 * transaction scores MEDIUM or above, and are read / reviewed from
 * the admin Fraud Management panel.
 */
public interface FraudAlertDAO {

    /**
     * Insert a new alert row (always with status = PENDING).
     * @return the auto-generated alert_id, or -1 on failure.
     */
    long save(FraudAlert alert);

    /** Find one alert by its primary key (customer/account names included). */
    Optional<FraudAlert> findById(long alertId);

    /** Every alert, newest first — the admin Fraud Management table. */
    List<FraudAlert> findAll();

    /** Every alert with a given review status, newest first. */
    List<FraudAlert> findByStatus(FraudAlert.AlertStatus status);

    /** Every alert raised against one customer, newest first. */
    List<FraudAlert> findByCustomerId(long customerId);

    /**
     * Review a PENDING alert — set it to CONFIRMED or DISMISSED and stamp
     * who reviewed it, when, and why.
     * The SQL guards on status = 'PENDING', so double-reviewing an already
     * processed alert updates 0 rows and returns false.
     *
     * @return true if the alert was still PENDING and is now reviewed.
     */
    boolean updateReview(long alertId,
                         FraudAlert.AlertStatus newStatus,
                         String reviewedBy,
                         String resolutionNote);
}
