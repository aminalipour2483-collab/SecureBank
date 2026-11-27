package com.bank.view.admin;

import com.bank.model.Account;
import com.bank.model.Account.AccountStatus;
import com.bank.model.Customer;
import com.bank.model.Transaction;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.service.AccountService;
import com.bank.service.CustomerService;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;
import com.bank.view.dialog.TransactionHistoryDialog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * AccountManagementPanel — admin tab for account records (English, LTR).
 */
public class AccountManagementPanel extends JPanel implements AdminFrame.Refreshable {

    private final AdminPrincipal admin;
    private final AccountService  accountService;
    private final CustomerService customerService;

    private DefaultTableModel tableModel;
    private JTable            table;
    private List<Account>     allAccounts;
    private List<Customer>    allCustomers;

    private JComboBox<String> statusFilter;
    private JLabel            summaryLabel;

    public AccountManagementPanel(AdminPrincipal admin,
            AccountService accountService, CustomerService customerService) {
        this.admin = AdminGuard.require(admin);
        this.accountService  = accountService;
        this.customerService = customerService;
        setLayout(new BorderLayout(0, 8));
        setBackground(UITheme.SURFACE);
        setBorder(new EmptyBorder(12, 12, 12, 12));

        add(buildToolbar(),    BorderLayout.NORTH);
        add(buildTableCard(),  BorderLayout.CENTER);
        add(buildSummaryBar(), BorderLayout.SOUTH);

        refresh();
    }

    /** Two-row toolbar so buttons always render at full width. */
    private JPanel buildToolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setOpaque(false);

        // ── Row 1: status filter ─────────────────────────────────────────────
        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterRow.setOpaque(false);
        filterRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        filterRow.add(UIUtils.bodyLabel("Status Filter:"));
        statusFilter = new JComboBox<>(new String[]{"All", "Active", "Locked", "Closed"});
        statusFilter.setFont(UITheme.FONT_BODY);
        statusFilter.addActionListener(e -> applyFilter());
        filterRow.add(statusFilter);

        // ── Row 2: operations ────────────────────────────────────────────────
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        JButton historyBtn = UIUtils.primaryButton("History");
        JButton lockBtn    = UIUtils.dangerButton("Lock");
        JButton unlockBtn  = UIUtils.successButton("Unlock");
        JButton closeBtn   = UIUtils.warningButton("Close Account");
        JButton refreshBtn = UIUtils.ghostButton("Refresh");

        lockBtn.setToolTipText("Lock the account (temporary)");
        unlockBtn.setToolTipText("Unlock the account");
        closeBtn.setToolTipText("Permanently close the account (required before deleting a customer)");
        historyBtn.setToolTipText("View the transaction history");

        lockBtn.addActionListener(e    -> handleSetStatus(AccountStatus.LOCKED));
        unlockBtn.addActionListener(e  -> handleSetStatus(AccountStatus.ACTIVE));
        closeBtn.addActionListener(e   -> handleCloseAccount());
        historyBtn.addActionListener(e -> handleViewHistory());
        refreshBtn.addActionListener(e -> refresh());

        btnRow.add(historyBtn);
        btnRow.add(lockBtn);
        btnRow.add(unlockBtn);
        btnRow.add(closeBtn);
        btnRow.add(refreshBtn);

        bar.add(filterRow);
        bar.add(btnRow);
        return bar;
    }

    private JPanel buildTableCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new BorderLayout(0, 8));
        card.add(UIUtils.subheading("All Accounts"), BorderLayout.NORTH);

        String[] cols = {"Account Number", "Owner", "Type", "Balance (" + DateUtil.CURRENCY + ")", "Status", "Opened"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        table = new JTable(tableModel);
        UIUtils.styleTable(table);

        table.setFont(UITheme.FONT_BODY);
        table.setRowHeight(28);
        table.setGridColor(UITheme.BORDER);
        table.setShowVerticalLines(false);
        table.setSelectionBackground(UITheme.SELECTION_BG);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) {
                    String status = String.valueOf(t.getValueAt(r, 4));
                    switch (status) {
                        case "Locked" -> setBackground(UITheme.ROW_DEBIT);
                        case "Closed" -> setBackground(new Color(0xE4, 0xE6, 0xEA));
                        default       -> setBackground(r % 2 == 0 ? UITheme.CARD : UITheme.ROW_ALTERNATE);
                    }
                }
                setBorder(new EmptyBorder(4, 8, 4, 8));
                setHorizontalAlignment(SwingConstants.LEFT);
                return this;
            }
        });

        card.add(new JScrollPane(table), BorderLayout.CENTER);
        UIUtils.adjustColumnWidths(table, 80, 300);   // fit headers even before data loads
        return card;
    }

    private JPanel buildSummaryBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        bar.setOpaque(false);
        summaryLabel = UIUtils.secondaryLabel("Loading...");
        bar.add(summaryLabel);
        return bar;
    }

    @Override
    public void refresh() {
        new SwingWorker<Void, Void>() {
            List<Account>  accounts;
            List<Customer> customers;

            @Override protected Void doInBackground() {
                customers = customerService.getAllCustomers(admin);
                accounts  = accountService.getAllAccounts(admin);
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                    allCustomers = customers;
                    allAccounts  = accounts;
                    applyFilter();
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(AccountManagementPanel.this,
                            "Failed to load accounts: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void applyFilter() {
        if (allAccounts == null) return;
        String filter = (String) statusFilter.getSelectedItem();

        List<Account> filtered = allAccounts.stream()
                .filter(a -> {
                    String statusEn = statusToEn(a.getStatus());
                    return "All".equals(filter) || statusEn.equals(filter);
                })
                .toList();

        tableModel.setRowCount(0);
        long totalActive = 0, totalLocked = 0, totalClosed = 0;

        for (Account a : filtered) {
            String ownerName = resolveOwnerName(a.getCustomerId());
            String typeEn = a.getAccountType().name().equals("SAVINGS") ? "Savings" : "Checking";
            String statusEn = statusToEn(a.getStatus());
            String opened = a.getCreatedAt() != null
                    ? DateUtil.formatDate(a.getCreatedAt().toLocalDate()) : "—";

            tableModel.addRow(new Object[]{
                a.getAccountNumber(),
                ownerName,
                typeEn,
                DateUtil.formatAmount(a.getBalance()),
                statusEn,
                opened
            });

            switch (a.getStatus()) {
                case ACTIVE -> totalActive++;
                case LOCKED -> totalLocked++;
                case CLOSED -> totalClosed++;
            }
        }

        if (filtered.isEmpty()) {
            tableModel.addRow(new Object[]{"No accounts match this filter", "", "", "", "", ""});
        }

        summaryLabel.setText(String.format(
                "Showing %d accounts   |   %d active   |   %d locked   |   %d closed",
                filtered.size(), totalActive, totalLocked, totalClosed));

        UIUtils.adjustColumnWidths(table, 80, 300);
    }

    private String statusToEn(AccountStatus status) {
        if (status == null) return "Unknown";
        return switch (status) {
            case ACTIVE -> "Active";
            case LOCKED -> "Locked";
            case CLOSED -> "Closed";
            default     -> status.name();
        };
    }

    private String resolveOwnerName(long customerId) {
        if (allCustomers == null) return "ID=" + customerId;
        return allCustomers.stream()
                .filter(c -> c.getCustomerId() == customerId)
                .map(Customer::getFullName)
                .findFirst().orElse("Unknown");
    }

    private void handleSetStatus(AccountStatus newStatus) {
        Account selected = getSelectedAccount();
        if (selected == null) {
            UIUtils.showWarning(this, "Please select an account first.");
            return;
        }
        if (selected.getStatus() == AccountStatus.CLOSED) {
            UIUtils.showWarning(this, "A closed account cannot change status.");
            return;
        }
        if (selected.getStatus() == newStatus) {
            UIUtils.showWarning(this, "The account is already " + statusToEn(newStatus).toLowerCase() + ".");
            return;
        }

        String action = newStatus == AccountStatus.LOCKED ? "Lock" : "Unlock";
        boolean ok = UIUtils.confirmDialog(this,
                action + " account " + selected.getAccountNumber() + "?");
        if (!ok) return;

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                accountService.setAccountStatus(admin, selected.getAccountId(), newStatus);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(AccountManagementPanel.this,
                            "Account " + selected.getAccountNumber() + " is now " + statusToEn(newStatus).toLowerCase() + ".");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(AccountManagementPanel.this, ex.getCause().getMessage());
                } catch (InterruptedException ex) {
                    UIUtils.showError(AccountManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    /** Permanently close the account (required before deleting the customer). */
    private void handleCloseAccount() {
        Account selected = getSelectedAccount();
        if (selected == null) {
            UIUtils.showWarning(this, "Please select an account first.");
            return;
        }
        if (selected.getStatus() == AccountStatus.CLOSED) {
            UIUtils.showWarning(this, "This account is already closed.");
            return;
        }

        // balance check
        if (selected.getBalance().compareTo(java.math.BigDecimal.ZERO) > 0) {
            UIUtils.showError(this,
                    "An account with a balance above zero cannot be closed.\n" +
                    "Current balance: " + DateUtil.formatMoney(selected.getBalance()) + "\n\n" +
                    "Zero out the balance first (withdraw or transfer).");
            return;
        }

        boolean ok = UIUtils.confirmDialog(this,
                "Are you sure you want to permanently close account \u201C" + selected.getAccountNumber() + "\u201D?\n\n" +
                "This normal closure is permanent.\n" +
                "Customer records are preserved separately through the Archive action.");
        if (!ok) return;

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                accountService.setAccountStatus(admin, selected.getAccountId(), AccountStatus.CLOSED);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(AccountManagementPanel.this,
                            "Account " + selected.getAccountNumber() + " was closed successfully.\n" +
                            "Customer records and financial history remain preserved.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(AccountManagementPanel.this,
                            "Closing the account failed: " + ex.getCause().getMessage());
                } catch (InterruptedException ex) {
                    UIUtils.showError(AccountManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    private void handleViewHistory() {
        Account selected = getSelectedAccount();
        if (selected == null) {
            UIUtils.showWarning(this, "Please select an account first.");
            return;
        }

        new SwingWorker<List<Transaction>, Void>() {
            @Override protected List<Transaction> doInBackground() {
                return accountService.getTransactionHistory(admin, selected.getAccountId());
            }
            @Override protected void done() {
                try {
                    TransactionHistoryDialog dlg = new TransactionHistoryDialog(
                            (Frame) SwingUtilities.getWindowAncestor(AccountManagementPanel.this), get());
                    dlg.setVisible(true);
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(AccountManagementPanel.this,
                            "Could not load the history: " + e.getMessage());
                }
            }
        }.execute();
    }

    private Account getSelectedAccount() {
        int row = table.getSelectedRow();
        if (row < 0 || allAccounts == null) return null;

        Object acctNumObj = tableModel.getValueAt(row, 0);
        if (acctNumObj == null || acctNumObj.toString().trim().isEmpty()
                || acctNumObj.toString().contains("No accounts")) {
            return null;
        }

        String accountNumber = acctNumObj.toString().trim();
        return allAccounts.stream()
                .filter(a -> a.getAccountNumber().equals(accountNumber))
                .findFirst().orElse(null);
    }
}
