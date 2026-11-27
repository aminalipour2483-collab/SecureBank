package com.bank.view.admin;

import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.model.Transfer;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.service.AccountService;
import com.bank.service.CustomerService;
import com.bank.service.TransferService;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * ReportsPanel — admin tab with system statistics (English, LTR).
 */
public class ReportsPanel extends JPanel implements AdminFrame.Refreshable {

    private final AdminPrincipal admin;
    private final CustomerService customerService;
    private final AccountService  accountService;
    private final TransferService transferService;

    private JLabel totalCustomersVal;
    private JLabel activeAccountsVal;
    private JLabel lockedAccountsVal;
    private JLabel totalDepositsVal;
    private JLabel completedTransfersVal;
    private JLabel pendingTransfersVal;
    private JLabel totalTransferVolVal;

    private DefaultTableModel topBalancesModel;
    private JTable            topBalancesTable;
    private DefaultTableModel recentCustomersModel;
    private JTable            recentCustomersTable;

    private JLabel lastRefreshedLabel;

    public ReportsPanel(AdminPrincipal admin,
            CustomerService customerService,
                        AccountService  accountService,
                        TransferService transferService) {
        this.admin = AdminGuard.require(admin);
        this.customerService = customerService;
        this.accountService  = accountService;
        this.transferService = transferService;

        setLayout(new BorderLayout(0, 12));
        setBackground(UITheme.SURFACE);
        setBorder(new EmptyBorder(12, 12, 12, 12));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(),    BorderLayout.CENTER);

        refresh();
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(0, 0, 4, 0));

        JLabel title = UIUtils.subheading("System Reports & Statistics");
        bar.add(title, BorderLayout.LINE_START);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);

        lastRefreshedLabel = UIUtils.secondaryLabel("Last refreshed: —");
        JButton refreshBtn = UIUtils.primaryButton("Refresh Now");
        refreshBtn.addActionListener(e -> refresh());

        right.add(lastRefreshedLabel);
        right.add(refreshBtn);
        bar.add(right, BorderLayout.LINE_END);
        return bar;
    }

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);

        body.add(buildStatCardsRow(), BorderLayout.NORTH);
        body.add(buildTablesRow(),    BorderLayout.CENTER);
        return body;
    }

    private JPanel buildStatCardsRow() {
        JPanel row = new JPanel(new GridLayout(1, 4, 12, 0));
        row.setOpaque(false);

        totalCustomersVal = statValue("—");
        row.add(buildStatCard("Customers", UITheme.PRIMARY,
                new String[]{"Registered"},
                new JLabel[]{totalCustomersVal}));

        activeAccountsVal = statValue("—");
        lockedAccountsVal = statValue("—");
        row.add(buildStatCard("Accounts", UITheme.ACCENT,
                new String[]{"Active", "Locked"},
                new JLabel[]{activeAccountsVal, lockedAccountsVal}));

        totalDepositsVal = statValue("—");
        row.add(buildStatCard("System Balance", UITheme.WARNING,
                new String[]{"Total funds"},
                new JLabel[]{totalDepositsVal}));

        completedTransfersVal = statValue("—");
        pendingTransfersVal   = statValue("—");
        totalTransferVolVal   = statValue("—");
        row.add(buildStatCard("Transfers", new Color(0x6A, 0x5A, 0xCD),
                new String[]{"Completed", "Pending", "Volume"},
                new JLabel[]{completedTransfersVal, pendingTransfersVal, totalTransferVolVal}));

        return row;
    }

    private JPanel buildStatCard(String title, Color accentColor,
                                  String[] labels, JLabel[] values) {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(UITheme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, accentColor),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(UITheme.BORDER, 1),
                        new EmptyBorder(14, 16, 14, 16))));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridx   = 0;
        gc.insets  = new Insets(1, 0, 1, 0);

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(UITheme.FONT_BUTTON);
        titleLbl.setForeground(UITheme.TEXT_SECONDARY);
        gc.gridy = 0;
        card.add(titleLbl, gc);

        gc.gridy = 1;
        gc.insets = new Insets(4, 0, 8, 0);
        card.add(new JSeparator(), gc);
        gc.insets = new Insets(3, 0, 3, 0);

        for (int i = 0; i < labels.length; i++) {
            JPanel r = new JPanel(new BorderLayout(6, 0));
            r.setOpaque(false);
            JLabel cap = UIUtils.secondaryLabel(labels[i] + ":");
            r.add(cap,      BorderLayout.LINE_START);
            r.add(values[i], BorderLayout.LINE_END);
            gc.gridy = i + 2;
            card.add(r, gc);
        }

        return card;
    }

    private JLabel statValue(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 14));
        lbl.setForeground(UITheme.TEXT_PRIMARY);
        lbl.setHorizontalAlignment(SwingConstants.RIGHT);
        return lbl;
    }

    private JPanel buildTablesRow() {
        JPanel row = new JPanel(new GridLayout(1, 2, 12, 0));
        row.setOpaque(false);

        // Top 10 balances table
        JPanel balCard = UIUtils.cardPanel();
        balCard.setLayout(new BorderLayout(0, 8));
        balCard.add(UIUtils.subheading("Top 10 Account Balances"), BorderLayout.NORTH);

        topBalancesModel = new DefaultTableModel(
                new String[]{"Rank", "Account Number", "Owner", "Type", "Balance (" + DateUtil.CURRENCY + ")"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        topBalancesTable = new JTable(topBalancesModel);
        UIUtils.styleTable(topBalancesTable);
        topBalancesTable.setFont(UITheme.FONT_BODY);
        topBalancesTable.setRowHeight(26);
        topBalancesTable.setGridColor(UITheme.BORDER);
        topBalancesTable.setShowVerticalLines(false);
        topBalancesTable.setFillsViewportHeight(true);
        topBalancesTable.setSelectionBackground(UITheme.SELECTION_BG);

        topBalancesTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) setBackground(r % 2 == 0 ? UITheme.CARD : UITheme.ROW_ALTERNATE);
                setBorder(new EmptyBorder(4, 8, 4, 8));
                setHorizontalAlignment(SwingConstants.LEFT);
                return this;
            }
        });

        balCard.add(new JScrollPane(topBalancesTable), BorderLayout.CENTER);
        UIUtils.adjustColumnWidths(topBalancesTable, 60, 200);   // fit headers even before data loads

        // Recent customers table
        JPanel custCard = UIUtils.cardPanel();
        custCard.setLayout(new BorderLayout(0, 8));
        custCard.add(UIUtils.subheading("Recent Customer Registrations"), BorderLayout.NORTH);

        recentCustomersModel = new DefaultTableModel(
                new String[]{"ID", "Full Name", "E-mail", "Occupation", "Registered"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        recentCustomersTable = new JTable(recentCustomersModel);
        UIUtils.styleTable(recentCustomersTable);
        recentCustomersTable.setFont(UITheme.FONT_BODY);
        recentCustomersTable.setRowHeight(26);
        recentCustomersTable.setGridColor(UITheme.BORDER);
        recentCustomersTable.setShowVerticalLines(false);
        recentCustomersTable.setFillsViewportHeight(true);
        recentCustomersTable.setSelectionBackground(UITheme.SELECTION_BG);

        recentCustomersTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) setBackground(r % 2 == 0 ? UITheme.CARD : UITheme.ROW_ALTERNATE);
                setBorder(new EmptyBorder(4, 8, 4, 8));
                setHorizontalAlignment(SwingConstants.LEFT);
                return this;
            }
        });

        custCard.add(new JScrollPane(recentCustomersTable), BorderLayout.CENTER);
        UIUtils.adjustColumnWidths(recentCustomersTable, 60, 220);   // fit headers even before data loads

        row.add(balCard);
        row.add(custCard);
        return row;
    }

    @Override
    public void refresh() {
        new SwingWorker<ReportData, Void>() {
            @Override
            protected ReportData doInBackground() {
                ReportData d = new ReportData();
                d.customers = customerService.getAllCustomers(admin);
                d.accounts  = accountService.getAllAccounts(admin);
                d.transfers = transferService.findAll(admin);
                return d;
            }

            @Override
            protected void done() {
                try {
                    ReportData d = get();
                    applyStats(d);
                    lastRefreshedLabel.setText("Last refreshed: " +
                            DateUtil.formatDateTime(LocalDateTime.now()));

                    UIUtils.adjustColumnWidths(topBalancesTable, 60, 200);
                    UIUtils.adjustColumnWidths(recentCustomersTable, 60, 220);
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(ReportsPanel.this,
                            "Failed to load reports: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void applyStats(ReportData d) {
        totalCustomersVal.setText(String.valueOf(d.customers.size()));

        long active = d.accounts.stream()
                .filter(a -> a.getStatus() == Account.AccountStatus.ACTIVE).count();
        long locked = d.accounts.stream()
                .filter(a -> a.getStatus() == Account.AccountStatus.LOCKED).count();
        activeAccountsVal.setText(String.valueOf(active));
        lockedAccountsVal.setText(String.valueOf(locked));

        BigDecimal total = d.accounts.stream()
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        totalDepositsVal.setText(DateUtil.formatMoney(total));

        long completed = d.transfers.stream()
                .filter(t -> t.getStatus() == Transfer.TransferStatus.COMPLETED).count();
        long pending   = d.transfers.stream()
                .filter(t -> t.getStatus() == Transfer.TransferStatus.PENDING).count();
        BigDecimal volume = d.transfers.stream()
                .filter(t -> t.getStatus() == Transfer.TransferStatus.COMPLETED)
                .map(Transfer::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        completedTransfersVal.setText(String.valueOf(completed));
        pendingTransfersVal.setText(String.valueOf(pending));
        totalTransferVolVal.setText(DateUtil.formatMoney(volume));

        // Top 10 balances
        topBalancesModel.setRowCount(0);
        d.accounts.stream()
                .sorted((a, b) -> b.getBalance().compareTo(a.getBalance()))
                .limit(10)
                .forEach(a -> {
                    String owner = d.customers.stream()
                            .filter(c -> c.getCustomerId() == a.getCustomerId())
                            .map(Customer::getFullName)
                            .findFirst().orElse("Unknown");
                    String typeEn = a.getAccountType().name().equals("SAVINGS") ? "Savings" : "Checking";
                    topBalancesModel.addRow(new Object[]{
                        topBalancesModel.getRowCount() + 1,
                        a.getAccountNumber(),
                        owner,
                        typeEn,
                        DateUtil.formatAmount(a.getBalance())
                    });
                });
        if (topBalancesModel.getRowCount() == 0)
            topBalancesModel.addRow(new Object[]{"—","No accounts exist","","",""});

        // Recent customers
        recentCustomersModel.setRowCount(0);
        d.customers.stream()
                .filter(c -> c.getCreatedAt() != null)
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(10)
                .forEach(c -> recentCustomersModel.addRow(new Object[]{
                    c.getCustomerId(),
                    c.getFullName(),
                    c.getEmail(),
                    c.getOccupation() != null ? c.getOccupation() : "—",
                    DateUtil.formatDate(c.getCreatedAt().toLocalDate())
                }));
        if (recentCustomersModel.getRowCount() == 0)
            recentCustomersModel.addRow(new Object[]{"—","No customers exist","","",""});
    }

    private static class ReportData {
        List<Customer> customers;
        List<Account>  accounts;
        List<Transfer> transfers;
    }
}
