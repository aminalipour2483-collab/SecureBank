package com.bank.view.admin;

import com.bank.model.Customer;
import com.bank.model.FraudAlert;
import com.bank.model.FraudAlert.AlertStatus;
import com.bank.model.FraudAlert.RiskLevel;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.admin.AdminCompositionRoot;
import com.bank.service.CustomerService;
import com.bank.service.FraudDetectionService;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * FraudManagementPanel — admin tab for the AI-driven Fraud Detection
 * module.                                              (v3.0 — NEW)
 *
 * Folder: BankManagementSystem/src/com/bank/view/admin/FraudManagementPanel.java
 *
 * LAYOUT
 *   ┌────────────────────────────────────────────────────────────┐
 *   │ Stats: Total | Pending | Confirmed | Dismissed | Banned     │
 *   ├────────────────────────────────────────────────────────────┤
 *   │ [status filter ▾] [Refresh] [Details] [Confirm] [Dismiss] [Ban User]
 *   │ ┌──────────────── alerts table ───────────────────────────┐ │
 *   │ │ ID Customer Account Rules Score Risk Status Detected    │ │
 *   └────────────────────────────────────────────────────────────┘
 *   │ BANNED CUSTOMERS                       [Unban User]        │
 *   │ ID | Name | National ID | Banned At | Reason               │
 *   └────────────────────────────────────────────────────────────┘
 *
 * MANAGER WORKFLOW (as requested):
 *   1. The engine flags suspicious transactions as PENDING alerts.
 *   2. The manager sees the alert here, opens Details, and —
 *   3. Confirms the fraud → the admin can ban that user with one click
 *      (customer is BANNED, login blocked, all accounts LOCKED).
 *   4. Banned customers are listed below and can be unbanned.
 *
 * This panel is part of the fully English (LTR) admin console.
 */
public class FraudManagementPanel extends JPanel implements AdminFrame.Refreshable {

    private final AdminPrincipal admin;
    private final FraudDetectionService fraudService;
    private final CustomerService       customerService;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── Alerts table ──────────────────────────────────────────────────────────
    private DefaultTableModel alertTableModel;
    private JTable            alertTable;
    private List<FraudAlert>  allAlerts = List.of();

    // ── Banned customers table ────────────────────────────────────────────────
    private DefaultTableModel bannedTableModel;
    private JTable            bannedTable;
    private List<Customer>    bannedCustomers = List.of();

    // ── Stats dashboard labels ────────────────────────────────────────────────
    private JLabel statTotal, statPending, statConfirmed, statDismissed, statBanned;

    // ── Status filter ─────────────────────────────────────────────────────────
    private JComboBox<String> statusFilter;

    // Row colours by risk level (soft tints of the palette).
    private static final Color ROW_CRITICAL = new Color(0xFD, 0xE9, 0xE7);
    private static final Color ROW_HIGH     = new Color(0xFF, 0xF0, 0xDC);
    private static final Color ROW_MEDIUM   = new Color(0xFF, 0xFB, 0xE8);
    private static final Color ROW_BANNED   = new Color(0xFD, 0xED, 0xEC);

    public FraudManagementPanel(AdminPrincipal admin,
            FraudDetectionService fraudService,
                                CustomerService customerService) {
        this.admin = AdminGuard.require(admin);
        this.fraudService     = fraudService;
        this.customerService  = customerService;

        setLayout(new BorderLayout(0, 10));
        setBackground(UITheme.SURFACE);
        setBorder(new EmptyBorder(12, 12, 12, 12));

        add(buildStatsRow(),  BorderLayout.NORTH);
        add(buildAlertsCard(), BorderLayout.CENTER);
        add(buildBannedCard(), BorderLayout.SOUTH);

        // Fit column headers immediately, even before the first (possibly
        // failed) refresh — prevents ellipsised headers on empty tables.
        UIUtils.adjustColumnWidths(alertTable, 50, 260);
        UIUtils.adjustColumnWidths(bannedTable, 40, 300);

        refresh();
    }

    // =========================================================================
    // STATS DASHBOARD (top row of five cards)
    // =========================================================================
    private JPanel buildStatsRow() {
        JPanel row = new JPanel(new GridLayout(1, 5, 10, 0));
        row.setOpaque(false);

        StatCard total     = new StatCard("TOTAL ALERTS",    UITheme.PRIMARY);
        StatCard pending   = new StatCard("PENDING",         UITheme.WARNING);
        StatCard confirmed = new StatCard("CONFIRMED FRAUD", UITheme.DANGER);
        StatCard dismissed = new StatCard("DISMISSED",       UITheme.TEXT_SECONDARY);
        StatCard banned    = new StatCard("BANNED USERS",    UITheme.PRIMARY_DARK);

        row.add(total.card);
        row.add(pending.card);
        row.add(confirmed.card);
        row.add(dismissed.card);
        row.add(banned.card);

        statTotal     = total.value;
        statPending   = pending.value;
        statConfirmed = confirmed.value;
        statDismissed = dismissed.value;
        statBanned    = banned.value;
        return row;
    }

    /** One dashboard tile: caption + big coloured number. */
    private static final class StatCard {
        final JPanel card;
        final JLabel value;

        StatCard(String caption, Color color) {
            card = UIUtils.cardPanel();
            card.setLayout(new BorderLayout(0, 2));
            card.setBorder(new EmptyBorder(10, 12, 10, 12));

            JLabel cap = UIUtils.secondaryLabel(caption);
            value = new JLabel("0");
            value.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 24));
            value.setForeground(color);

            card.add(cap,   BorderLayout.NORTH);
            card.add(value, BorderLayout.CENTER);
        }
    }

    // =========================================================================
    // ALERTS CARD (filter + actions + table)
    // =========================================================================
    private JPanel buildAlertsCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new BorderLayout(0, 8));

        // ── Toolbar (two rows: filters above, actions below — buttons never truncate) ──
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.setOpaque(false);

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filterRow.setOpaque(false);
        filterRow.setBorder(new EmptyBorder(0, 0, 8, 0));
        filterRow.add(UIUtils.bodyLabel("Status:"));
        statusFilter = new JComboBox<>(
                new String[]{"All", "PENDING", "CONFIRMED", "DISMISSED"});
        statusFilter.setFont(UITheme.FONT_BODY);
        statusFilter.addActionListener(e -> populateAlertTable());
        filterRow.add(statusFilter);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        JButton refreshBtn = UIUtils.primaryButton("Refresh");
        JButton detailsBtn = UIUtils.ghostButton("Details");
        JButton confirmBtn = UIUtils.warningButton("Confirm Fraud");
        JButton dismissBtn = UIUtils.ghostButton("Dismiss");
        JButton banBtn     = UIUtils.dangerButton("Ban User");
        JButton retryBtn   = UIUtils.ghostButton("Retry Failed Analysis");

        refreshBtn.addActionListener(e -> refresh());
        detailsBtn.addActionListener(e -> handleDetails());
        confirmBtn.addActionListener(e -> handleConfirmFraud());
        dismissBtn.addActionListener(e -> handleDismissAlert());
        banBtn.addActionListener(e     -> handleBanFromAlert());
        retryBtn.addActionListener(e   -> handleRetryFailedAnalysis());

        btnRow.add(detailsBtn);
        btnRow.add(confirmBtn);
        btnRow.add(dismissBtn);
        btnRow.add(banBtn);
        btnRow.add(retryBtn);
        btnRow.add(refreshBtn);

        toolbar.add(filterRow);
        toolbar.add(btnRow);

        // ── Table ──────────────────────────────────────────────────────────────
        String[] cols = {"ID", "Customer", "Account", "Rules",
                         "Score", "Risk", "Status", "Detected"};
        alertTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        alertTable = buildStyledTable(alertTableModel);

        // Risk-level row colouring + banned-customer highlighting.
        alertTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) {
                    String risk = String.valueOf(t.getValueAt(r, 5));
                    Color base = rowColor(risk);
                    if (base == null) base = r % 2 == 0 ? UITheme.CARD : UITheme.ROW_ALTERNATE;
                    if (isBannedRow(t, r)) base = ROW_BANNED;
                    setBackground(base);
                }
                setBorder(new EmptyBorder(4, 6, 4, 6));
                return this;
            }
        });

        // Double-click → details dialog
        alertTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) handleDetails();
            }
        });

        card.add(toolbar, BorderLayout.NORTH);
        card.add(new JScrollPane(alertTable), BorderLayout.CENTER);
        return card;
    }

    /** Soft tint per risk level (null → zebra striping). */
    private Color rowColor(String risk) {
        switch (risk) {
            case "CRITICAL": return ROW_CRITICAL;
            case "HIGH":     return ROW_HIGH;
            case "MEDIUM":   return ROW_MEDIUM;
            default:         return null;
        }
    }

    /** True when the alert's customer is currently banned. */
    private boolean isBannedRow(JTable t, int row) {
        Object idObj = t.getValueAt(row, 0);
        FraudAlert alert = findAlertById(idObj);
        return alert != null && alert.getCustomerId() != null
                && bannedCustomers.stream()
                        .anyMatch(c -> c.getCustomerId() == alert.getCustomerId());
    }

    private void handleRetryFailedAnalysis() {
        String value = JOptionPane.showInputDialog(this,
                "Enter the failed Fraud Outbox event ID to retry:", "Manual Fraud Retry",
                JOptionPane.QUESTION_MESSAGE);
        if (value == null || value.isBlank()) return;
        final long eventId;
        try {
            eventId = Long.parseLong(value.trim());
            if (eventId <= 0) throw new NumberFormatException("ID must be positive");
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Enter a valid positive event ID.",
                    "Invalid Event ID", JOptionPane.WARNING_MESSAGE);
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                AdminCompositionRoot.getInstance().retryFailedFraudEvent(admin, eventId);
                return null;
            }
            @Override protected void done() {
                if (!isDisplayable()) return;
                try {
                    get();
                    JOptionPane.showMessageDialog(FraudManagementPanel.this,
                        "A new retry cycle has been scheduled for event #" + eventId + ".", "Retry Scheduled", JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    JOptionPane.showMessageDialog(FraudManagementPanel.this, rootMessage(ex), "Retry Not Scheduled", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // =========================================================================
    // BANNED CUSTOMERS CARD (bottom)
    // =========================================================================
    private JPanel buildBannedCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new BorderLayout(0, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(UIUtils.subheading("Banned Customers"), BorderLayout.WEST);

        JButton unbanBtn = UIUtils.successButton("Unban User");
        unbanBtn.addActionListener(e -> handleUnban());
        header.add(unbanBtn, BorderLayout.EAST);

        String[] cols = {"ID", "Name", "National ID", "Banned At", "Reason"};
        bannedTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        bannedTable = buildStyledTable(bannedTableModel);

        card.add(header, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(bannedTable);
        scroll.setPreferredSize(new Dimension(100, 170));
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    // =========================================================================
    // REFRESH  (tab change + button)
    // =========================================================================
    @Override
    public void refresh() {
        new SwingWorker<DashboardData, Void>() {
            @Override protected DashboardData doInBackground() {
                return new DashboardData(
                        fraudService.getAllAlerts(admin),
                        customerService.getBannedCustomers(admin));
            }
            @Override protected void done() {
                try {
                    DashboardData data = get();
                    allAlerts        = data.alerts();
                    bannedCustomers  = data.banned();
                    populateAlertTable();
                    populateBannedTable();
                    updateStats();
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(FraudManagementPanel.this,
                            "Failed to load fraud data: "
                                    + rootMessage(e) + "\n\n"
                                    + "Did you run FRAUD_DETECTION.sql on the bank_system database?");
                }
            }
        }.execute();
    }

    private record DashboardData(List<FraudAlert> alerts, List<Customer> banned) {}

    private String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    // =========================================================================
    // POPULATE TABLES + STATS
    // =========================================================================
    private void populateAlertTable() {
        alertTableModel.setRowCount(0);

        String filter = (String) statusFilter.getSelectedItem();
        for (FraudAlert a : allAlerts) {
            if (filter != null && !filter.equals("All")
                    && !filter.equals(a.getStatus().name())) {
                continue;
            }
            alertTableModel.addRow(new Object[]{
                a.getAlertId(),
                a.getCustomerName() != null ? a.getCustomerName() : "(deleted customer)",
                a.getAccountNumber() != null ? a.getAccountNumber() : "—",
                a.getRuleCode(),
                a.getRiskScore() + "/100",
                a.getRiskLevel(),
                a.getStatus(),
                a.getDetectedAt() != null ? DATE_FMT.format(a.getDetectedAt()) : "—"
            });
        }
        if (allAlerts.isEmpty()) {
            alertTableModel.addRow(new Object[]{
                "—", "No fraud alerts yet", "", "", "", "", "", ""});
        }
        UIUtils.adjustColumnWidths(alertTable, 50, 260);
    }

    private void populateBannedTable() {
        bannedTableModel.setRowCount(0);
        for (Customer c : bannedCustomers) {
            bannedTableModel.addRow(new Object[]{
                c.getCustomerId(),
                c.getFullName(),
                c.getNationalId(),
                c.getBannedAt() != null ? DATE_FMT.format(c.getBannedAt()) : "—",
                c.getBannedReason() != null ? c.getBannedReason() : "—"
            });
        }
        if (bannedCustomers.isEmpty()) {
            bannedTableModel.addRow(new Object[]{
                "—", "No banned customers", "", "", ""});
        }
        UIUtils.adjustColumnWidths(bannedTable, 40, 300);
    }

    private void updateStats() {
        long pending   = allAlerts.stream().filter(FraudAlert::isPending).count();
        long confirmed = allAlerts.stream().filter(FraudAlert::isConfirmed).count();
        long dismissed = allAlerts.stream()
                .filter(a -> a.getStatus() == AlertStatus.DISMISSED).count();

        statTotal.setText(String.valueOf(allAlerts.size()));
        statPending.setText(String.valueOf(pending));
        statConfirmed.setText(String.valueOf(confirmed));
        statDismissed.setText(String.valueOf(dismissed));
        statBanned.setText(String.valueOf(bannedCustomers.size()));
    }

    // =========================================================================
    // ACTION: VIEW DETAILS
    // =========================================================================
    private void handleDetails() {
        FraudAlert alert = getSelectedAlert();
        if (alert == null) {
            UIUtils.showWarning(this, "Please select a fraud alert first.");
            return;
        }

        JTextArea area = new JTextArea(buildDetailsText(alert));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(UITheme.CARD);
        area.setBorder(new EmptyBorder(8, 10, 8, 10));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(560, 320));

        showOptionDialog(scroll, "Fraud Alert #" + alert.getAlertId(), "OK");
    }

    private String buildDetailsText(FraudAlert a) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Alert ID      : %d%n", a.getAlertId()));
        sb.append(String.format("Customer      : %s (ID=%s)%n",
                a.getCustomerName() != null ? a.getCustomerName() : "(deleted)",
                a.getCustomerId() != null ? a.getCustomerId() : "—"));
        sb.append(String.format("Account       : %s%n",
                a.getAccountNumber() != null ? a.getAccountNumber() : "—"));
        sb.append(String.format("Ledger row    : %s%n",
                a.getTransactionId() != null ? "transaction #" + a.getTransactionId()
                                             : (a.getTransferId() != null
                                                     ? "transfer #" + a.getTransferId()
                                                     : "—")));
        sb.append(String.format("Rules         : %s%n", a.getRuleCode()));
        sb.append(String.format("Risk score    : %d/100 (%s)%n",
                a.getRiskScore(), a.getRiskLevel()));
        sb.append(String.format("Status        : %s%n", a.getStatus()));
        sb.append(String.format("Detected at   : %s%n",
                a.getDetectedAt() != null ? DATE_FMT.format(a.getDetectedAt()) : "—"));
        sb.append(String.format("Reviewed by   : %s%n",
                a.getReviewedBy() != null ? a.getReviewedBy() : "—"));
        sb.append(String.format("Reviewed at   : %s%n",
                a.getReviewedAt() != null ? DATE_FMT.format(a.getReviewedAt()) : "—"));
        sb.append(String.format("Resolution    : %s%n%n",
                a.getResolutionNote() != null ? a.getResolutionNote() : "—"));
        sb.append("── Engine analysis ──\n");
        sb.append(a.getDetails() != null ? a.getDetails() : "—");
        return sb.toString();
    }

    // =========================================================================
    // ACTION: CONFIRM FRAUD  (+ optional one-click ban)
    // =========================================================================
    private void handleConfirmFraud() {
        FraudAlert alert = getSelectedAlert();
        if (alert == null) {
            UIUtils.showWarning(this, "Please select a fraud alert first.");
            return;
        }
        if (!alert.isPending()) {
            UIUtils.showWarning(this, "Only PENDING alerts can be confirmed.\n"
                    + "Alert #" + alert.getAlertId() + " is already " + alert.getStatus() + ".");
            return;
        }

        // ── Review form: resolution note + "ban the customer" checkbox ────────
        JTextArea noteArea = new JTextArea(4, 30);
        noteArea.setFont(UITheme.FONT_BODY);
        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        noteArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER, 1),
                new EmptyBorder(6, 8, 6, 8)));

        JCheckBox banNow = new JCheckBox("Ban this customer now (blocks login and locks all accounts)");
        banNow.setFont(UITheme.FONT_BODY);
        banNow.setSelected(alert.getRiskLevel() == RiskLevel.CRITICAL
                || alert.getRiskLevel() == RiskLevel.HIGH);

        JPanel form = new JPanel(new BorderLayout(0, 10));
        form.setBackground(UITheme.CARD);
        form.add(UIUtils.bodyLabel("Resolution note:"), BorderLayout.NORTH);
        form.add(new JScrollPane(noteArea), BorderLayout.CENTER);
        form.add(banNow, BorderLayout.SOUTH);

        int choice = showOptionDialog(form,
                "Confirm Fraud — Alert #" + alert.getAlertId(),
                "Confirm Fraud", "Cancel");
        if (choice != 0) return;

        final String note      = noteArea.getText().trim();
        final boolean doBan    = banNow.isSelected();
        final long    alertId  = alert.getAlertId();
        final Long    customerId = alert.getCustomerId();
        final String  customerName = alert.getCustomerName() != null
                ? alert.getCustomerName() : "customer";
        final String  ruleCode = alert.getRuleCode();

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                // 1. Mark the alert CONFIRMED (idempotent — throws if already reviewed)
                fraudService.reviewAlert(admin, alertId, AlertStatus.CONFIRMED, note);

                // 2. Optionally ban the customer right away.
                if (doBan && customerId != null) {
                    String reason = "Confirmed fraud alert #" + alertId
                            + " [" + ruleCode + "]";
                    if (!note.isEmpty()) reason += " — " + note;
                    customerService.banCustomer(admin, customerId, reason);
                }
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    String msg = "Alert #" + alertId + " confirmed as fraud.";
                    if (doBan && customerId != null) {
                        msg += "\n\n" + customerName + " has been BANNED — "
                             + "login blocked and all accounts locked.";
                    }
                    UIUtils.showSuccess(FraudManagementPanel.this, msg);
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(FraudManagementPanel.this,
                            rootMessage(ex));
                    refresh();
                } catch (InterruptedException ex) {
                    UIUtils.showError(FraudManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    // =========================================================================
    // ACTION: DISMISS ALERT (false positive)
    // =========================================================================
    private void handleDismissAlert() {
        FraudAlert alert = getSelectedAlert();
        if (alert == null) {
            UIUtils.showWarning(this, "Please select a fraud alert first.");
            return;
        }
        if (!alert.isPending()) {
            UIUtils.showWarning(this, "Only PENDING alerts can be dismissed.\n"
                    + "Alert #" + alert.getAlertId() + " is already " + alert.getStatus() + ".");
            return;
        }

        int choice = showOptionDialog(
                "Dismiss alert #" + alert.getAlertId() + " as a false positive?",
                "Dismiss Alert", "Dismiss", "Cancel");
        if (choice != 0) return;

        final long alertId = alert.getAlertId();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                fraudService.reviewAlert(admin, alertId, AlertStatus.DISMISSED,
                        "False positive — dismissed by manager.");
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(FraudManagementPanel.this,
                            "Alert #" + alertId + " dismissed.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(FraudManagementPanel.this, rootMessage(ex));
                    refresh();
                } catch (InterruptedException ex) {
                    UIUtils.showError(FraudManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    // =========================================================================
    // ACTION: BAN USER FROM AN ALERT
    // =========================================================================
    private void handleBanFromAlert() {
        FraudAlert alert = getSelectedAlert();
        if (alert == null) {
            UIUtils.showWarning(this, "Please select a fraud alert first.");
            return;
        }
        if (alert.getCustomerId() == null) {
            UIUtils.showError(this, "This alert's customer no longer exists — cannot ban.");
            return;
        }
        banCustomerDialog(alert.getCustomerId(), alert.getCustomerName(),
                "Fraud alert #" + alert.getAlertId() + " [" + alert.getRuleCode() + "]");
    }

    /** Shared ban flow: reason dialog → confirmation → service call → refresh. */
    private void banCustomerDialog(long customerId, String customerName, String context) {

        // Already banned?
        if (bannedCustomers.stream().anyMatch(c -> c.getCustomerId() == customerId)) {
            UIUtils.showWarning(this, customerName + " is already banned "
                    + "(see the Banned Customers list below).");
            return;
        }

        JTextArea reasonArea = new JTextArea(3, 30);
        reasonArea.setText(context);
        reasonArea.setFont(UITheme.FONT_BODY);
        reasonArea.setLineWrap(true);
        reasonArea.setWrapStyleWord(true);
        reasonArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER, 1),
                new EmptyBorder(6, 8, 6, 8)));

        JPanel form = new JPanel(new BorderLayout(0, 8));
        form.setBackground(UITheme.CARD);
        form.add(UIUtils.bodyLabel("Ban reason (required):"), BorderLayout.NORTH);
        form.add(new JScrollPane(reasonArea), BorderLayout.CENTER);
        form.add(UIUtils.secondaryLabel(
                "Banning blocks login and LOCKS every account owned by this customer."),
                BorderLayout.SOUTH);

        int choice = showOptionDialog(form,
                "Ban User — " + customerName, "Ban User", "Cancel");
        if (choice != 0) return;

        final String reason = reasonArea.getText().trim();
        if (reason.isEmpty()) {
            UIUtils.showWarning(this, "A ban reason is required.");
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                customerService.banCustomer(admin, customerId, reason);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(FraudManagementPanel.this,
                            customerName + " has been BANNED.\n"
                            + "Login is now rejected and all their accounts are LOCKED.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(FraudManagementPanel.this, rootMessage(ex));
                    refresh();
                } catch (InterruptedException ex) {
                    UIUtils.showError(FraudManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    // =========================================================================
    // ACTION: UNBAN USER
    // =========================================================================
    private void handleUnban() {
        int row = bannedTable.getSelectedRow();
        Customer selected = null;
        if (row >= 0) {
            Object idObj = bannedTableModel.getValueAt(row, 0);
            if (idObj != null && !idObj.toString().equals("—")) {
                try {
                    long id = Long.parseLong(idObj.toString().trim());
                    selected = bannedCustomers.stream()
                            .filter(c -> c.getCustomerId() == id)
                            .findFirst().orElse(null);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (selected == null) {
            UIUtils.showWarning(this, "Please select a banned customer first.");
            return;
        }

        final Customer customer = selected;
        int choice = showOptionDialog(
                "Lift the ban on " + customer.getFullName() + "?\n\n"
              + "The customer returns to ACTIVE, their accounts are unlocked\n"
              + "and they can log in again.",
                "Unban User", "Unban", "Cancel");
        if (choice != 0) return;

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                customerService.unbanCustomer(admin, customer.getCustomerId());
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(FraudManagementPanel.this,
                            customer.getFullName() + " has been unbanned "
                            + "and their accounts are active again.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(FraudManagementPanel.this, rootMessage(ex));
                    refresh();
                } catch (InterruptedException ex) {
                    UIUtils.showError(FraudManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    // =========================================================================
    // SELECTION HELPERS
    // =========================================================================
    private FraudAlert getSelectedAlert() {
        int row = alertTable.getSelectedRow();
        if (row < 0) return null;
        return findAlertById(alertTableModel.getValueAt(row, 0));
    }

    private FraudAlert findAlertById(Object idObj) {
        if (idObj == null || idObj.toString().equals("—")) return null;
        try {
            long id = Long.parseLong(idObj.toString().trim());
            return allAlerts.stream()
                    .filter(a -> a.getAlertId() == id)
                    .findFirst().orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =========================================================================
    // UI HELPERS
    // =========================================================================

    /** JOptionPane with explicit English buttons. */
    private int showOptionDialog(Object message, String title,
                                 Object... options) {
        return JOptionPane.showOptionDialog(this, message, title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[0]);
    }

    /** LTR table styled like the rest of the app. */
    private JTable buildStyledTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFont(UITheme.FONT_BODY);
        table.setRowHeight(28);
        table.setGridColor(UITheme.BORDER);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(UITheme.SELECTION_BG);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setFont(UITheme.FONT_BUTTON);
        table.getTableHeader().setBackground(UITheme.HEADER_BG);
        table.getTableHeader().setForeground(UITheme.TEXT_ON_DARK);
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }
}
