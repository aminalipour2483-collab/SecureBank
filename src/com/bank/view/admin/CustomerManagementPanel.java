package com.bank.view.admin;

import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.service.AccountService;
import com.bank.service.CustomerService;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * CustomerManagementPanel — admin tab for customer records (English, LTR).
 * v3.0: customer status column (ACTIVE / BANNED) and the Ban / Unban action.
 */
public class CustomerManagementPanel extends JPanel implements AdminFrame.Refreshable {

    private final AdminPrincipal admin;
    private final CustomerService customerService;
    private final AccountService  accountService;

    private DefaultTableModel customerTableModel;
    private JTable            customerTable;
    private List<Customer>    allCustomers;

    private JLabel detailName;
    private JLabel detailNationalId;
    private JLabel detailBirthDate;
    private JLabel detailPhone;
    private JLabel detailEmail;
    private JLabel detailAddress;
    private JLabel detailPostalCode;
    private JLabel detailOccupation;
    private JLabel detailCreatedAt;
    private JLabel detailStatus;        // v3.0 — ACTIVE / BANNED
    private JLabel detailBanReason;     // v3.0 — shown while banned

    private DefaultTableModel accountTableModel;
    private JTable            accountSubTable;

    private JTextField searchField;

    /** v3.0 — toggles between "Ban User" and "Unban User". */
    private JButton banBtn;
    private JButton lifecycleBtn;
    private JButton restoreAccountBtn;

    public CustomerManagementPanel(AdminPrincipal admin,
            CustomerService customerService,
                                   AccountService  accountService) {
        this.admin = AdminGuard.require(admin);
        this.customerService = customerService;
        this.accountService  = accountService;
        setLayout(new BorderLayout(0, 8));
        setBackground(UITheme.SURFACE);
        setBorder(new EmptyBorder(12, 12, 12, 12));

        add(buildToolbar(),   BorderLayout.NORTH);
        add(buildSplitPane(), BorderLayout.CENTER);

        refresh();
    }

    /**
     * Two-row toolbar: search controls on the first row, action buttons on the
     * second. This guarantees every button keeps its full label (the old
     * single-row layout truncated "Ban User" to "Ban Use").
     */
    private JPanel buildToolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setOpaque(false);

        // ── Row 1: search ────────────────────────────────────────────────────
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        searchRow.setOpaque(false);
        searchRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        searchRow.add(UIUtils.bodyLabel("Search:"));
        searchField = UIUtils.styledField(24);
        searchField.setToolTipText("Search by name, national ID or e-mail");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filterTable(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filterTable(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
        });
        searchRow.add(searchField);

        JButton clearBtn = UIUtils.ghostButton("Clear");
        clearBtn.addActionListener(e -> { searchField.setText(""); filterTable(); });
        searchRow.add(clearBtn);

        // ── Row 2: actions ───────────────────────────────────────────────────
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        JButton profileBtn = UIUtils.primaryButton("View Profile");
        JButton editBtn    = UIUtils.ghostButton("Edit Contact");
        lifecycleBtn       = UIUtils.warningButton("Archive Customer");
        banBtn             = UIUtils.warningButton("Ban User");   // v3.0 — fraud module
        JButton refreshBtn = UIUtils.ghostButton("Refresh");

        profileBtn.addActionListener(e -> handleShowProfile());
        editBtn.addActionListener(e    -> handleEditContact());
        lifecycleBtn.addActionListener(e -> handleArchiveRestoreCustomer());
        banBtn.addActionListener(e     -> handleBanUnban());       // v3.0
        refreshBtn.addActionListener(e -> refresh());

        btnRow.add(profileBtn);
        btnRow.add(editBtn);
        btnRow.add(lifecycleBtn);
        btnRow.add(banBtn);
        btnRow.add(refreshBtn);

        bar.add(searchRow);
        bar.add(btnRow);
        return bar;
    }

    private JSplitPane buildSplitPane() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildCustomerTablePanel(), buildDetailPanel());
        split.setDividerLocation(560);
        split.setDividerSize(6);
        split.setResizeWeight(0.58);
        split.setBorder(null);
        return split;
    }

    private JPanel buildCustomerTablePanel() {
        JPanel panel = UIUtils.cardPanel();
        panel.setLayout(new BorderLayout(0, 8));

        JLabel title = UIUtils.subheading("All Customers");
        panel.add(title, BorderLayout.NORTH);

        String[] cols = {"ID", "Full Name", "National ID", "Phone", "E-mail", "Registered", "Status"};
        customerTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        customerTable = buildStyledTable(customerTableModel);
        UIUtils.styleTable(customerTable);
        customerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) populateDetailPanel();
        });

        // v3.0 — banned customers get a red row.
        customerTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) {
                    String status = String.valueOf(t.getValueAt(r, 6));
                    setBackground("BANNED".equals(status) ? UITheme.ROW_DEBIT
                            : ("ARCHIVED".equals(status) ? UITheme.SURFACE
                            : (r % 2 == 0 ? UITheme.CARD : UITheme.ROW_ALTERNATE)));
                }
                setBorder(new EmptyBorder(4, 8, 4, 8));
                setHorizontalAlignment(SwingConstants.LEFT);
                return this;
            }
        });

        panel.add(new JScrollPane(customerTable), BorderLayout.CENTER);
        UIUtils.adjustColumnWidths(customerTable, 60, 250);   // fit headers even before data loads
        return panel;
    }

    private JPanel buildDetailPanel() {
        JPanel outer = new JPanel(new BorderLayout(0, 10));
        outer.setOpaque(false);
        outer.setBorder(new EmptyBorder(0, 0, 0, 8));

        JPanel detailCard = UIUtils.cardPanel();
        detailCard.setLayout(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(3, 4, 3, 4);

        JLabel detailTitle = UIUtils.subheading("Customer Details");
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2; gc.weightx = 1.0;
        detailCard.add(detailTitle, gc);
        gc.gridy = 1;
        detailCard.add(new JSeparator(), gc);
        gc.gridwidth = 1;

        detailName       = detailValueLabel("—");
        detailNationalId = detailValueLabel("—");
        detailBirthDate  = detailValueLabel("—");
        detailPhone      = detailValueLabel("—");
        detailEmail      = detailValueLabel("—");
        detailAddress    = detailValueLabel("—");
        detailPostalCode = detailValueLabel("—");
        detailOccupation = detailValueLabel("—");
        detailCreatedAt  = detailValueLabel("—");
        detailStatus     = detailValueLabel("ACTIVE");   // v3.0
        detailBanReason  = detailValueLabel("—");        // v3.0

        int row = 2;
        row = addDetailRow(detailCard, gc, row, "Full Name:",   detailName);
        row = addDetailRow(detailCard, gc, row, "National ID:", detailNationalId);
        row = addDetailRow(detailCard, gc, row, "Birth Date:",  detailBirthDate);
        row = addDetailRow(detailCard, gc, row, "Phone:",       detailPhone);
        row = addDetailRow(detailCard, gc, row, "E-mail:",      detailEmail);
        row = addDetailRow(detailCard, gc, row, "Address:",     detailAddress);
        row = addDetailRow(detailCard, gc, row, "Postal Code:", detailPostalCode);
        row = addDetailRow(detailCard, gc, row, "Occupation:",  detailOccupation);
        row = addDetailRow(detailCard, gc, row, "Registered:",  detailCreatedAt);
        addDetailRow(detailCard, gc, row, "Status:",        detailStatus);      // v3.0
        addDetailRow(detailCard, gc, row + 1, "Ban Reason:", detailBanReason);  // v3.0

        JPanel accountCard = UIUtils.cardPanel();
        accountCard.setLayout(new BorderLayout(0, 8));

        JPanel acctHeader = new JPanel(new BorderLayout());
        acctHeader.setOpaque(false);
        acctHeader.add(UIUtils.subheading("Customer Accounts"), BorderLayout.LINE_START);

        JPanel accountActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        accountActions.setOpaque(false);
        JButton viewAcctsBtn = UIUtils.primaryButton("Load Accounts");
        viewAcctsBtn.addActionListener(e -> handleViewAccounts());
        restoreAccountBtn = UIUtils.warningButton("Approve Account Restore");
        restoreAccountBtn.addActionListener(e -> handleRestoreArchivedAccount());
        accountActions.add(viewAcctsBtn);
        accountActions.add(restoreAccountBtn);
        acctHeader.add(accountActions, BorderLayout.LINE_END);
        accountCard.add(acctHeader, BorderLayout.NORTH);

        String[] acctCols = {"Account ID", "Account Number", "Type", "Balance", "Status", "Closure Reason"};
        accountTableModel = new DefaultTableModel(acctCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        accountSubTable = buildStyledTable(accountTableModel);
        UIUtils.styleTable(accountSubTable);

        accountSubTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) {
                    String status = String.valueOf(t.getValueAt(r, 4));
                    setBackground("LOCKED".equals(status) ? UITheme.ROW_DEBIT
                            : (r % 2 == 0 ? UITheme.CARD : UITheme.ROW_ALTERNATE));
                }
                setBorder(new EmptyBorder(4, 8, 4, 8));
                setHorizontalAlignment(SwingConstants.LEFT);
                return this;
            }
        });

        accountCard.add(new JScrollPane(accountSubTable), BorderLayout.CENTER);
        UIUtils.adjustColumnWidths(accountSubTable, 50, 200);

        outer.add(detailCard,  BorderLayout.NORTH);
        outer.add(accountCard, BorderLayout.CENTER);
        return outer;
    }

    @Override
    public void refresh() {
        new SwingWorker<List<Customer>, Void>() {
            @Override protected List<Customer> doInBackground() {
                return customerService.getAllCustomers(admin);
            }
            @Override protected void done() {
                try {
                    allCustomers = get();
                    populateCustomerTable(allCustomers);
                    UIUtils.adjustColumnWidths(customerTable, 60, 250);
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(CustomerManagementPanel.this,
                            "Failed to load customers: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void populateCustomerTable(List<Customer> customers) {
        customerTableModel.setRowCount(0);
        for (Customer c : customers) {
            // keep the ID as a plain English number so parseLong never breaks
            customerTableModel.addRow(new Object[]{
                c.getCustomerId(),
                c.getFullName(),
                c.getNationalId(),
                c.getPhone(),
                c.getEmail(),
                c.getCreatedAt() != null
                    ? DateUtil.formatDate(c.getCreatedAt().toLocalDate()) : "—",
                c.getStatus().name()
            });
        }
        if (customers.isEmpty()) {
            customerTableModel.addRow(new Object[]{"—", "No customers found", "", "", "", "", ""});
        }
    }

    private void populateDetailPanel() {
        int row = customerTable.getSelectedRow();
        if (row < 0 || allCustomers == null) return;

        Object idObj = customerTableModel.getValueAt(row, 0);
        if (idObj == null || idObj.toString().trim().isEmpty() || idObj.toString().equals("—")) {
            return;
        }

        long selectedId;
        try {
            selectedId = Long.parseLong(idObj.toString().trim());
        } catch (NumberFormatException e) {
            return;
        }

        Customer c = allCustomers.stream()
                .filter(x -> x.getCustomerId() == selectedId)
                .findFirst().orElse(null);
        if (c == null) return;

        detailName.setText(c.getFullName());
        detailNationalId.setText(c.getNationalId());
        detailBirthDate.setText(c.getBirthDate() != null ? DateUtil.formatDate(c.getBirthDate()) : "—");
        detailPhone.setText(c.getPhone());
        detailEmail.setText(c.getEmail());
        detailAddress.setText(c.getAddress() != null ? c.getAddress() : "—");
        detailPostalCode.setText(c.getPostalCode() != null ? c.getPostalCode() : "—");
        detailOccupation.setText(c.getOccupation() != null ? c.getOccupation() : "—");
        detailCreatedAt.setText(c.getCreatedAt() != null ? DateUtil.formatDate(c.getCreatedAt().toLocalDate()) : "—");

        // v3.0 — fraud module: ban status + reason + toggle the action button
        detailStatus.setText(c.getStatus().name());
        detailStatus.setForeground(c.isBanned() ? UITheme.DANGER
                : (c.isArchived() ? UITheme.TEXT_SECONDARY : UITheme.ACCENT_DARK));
        detailBanReason.setText(c.isBanned() && c.getBannedReason() != null
                ? c.getBannedReason() : "—");
        updateBanButton(c);
        updateLifecycleButton(c);

        accountTableModel.setRowCount(0);
    }

    /** Ban is unavailable while archived; an archived customer must be restored first. */
    private void updateBanButton(Customer c) {
        banBtn.setEnabled(!c.isArchived());
        if (c.isArchived()) {
            banBtn.setText("Ban / Unban unavailable");
        } else if (c.isBanned()) {
            banBtn.setText("Unban User");
            banBtn.setBackground(UITheme.ACCENT);
        } else {
            banBtn.setText("Ban User");
            banBtn.setBackground(UITheme.DANGER);
        }
    }

    private void updateLifecycleButton(Customer c) {
        lifecycleBtn.setText(c.isArchived() ? "Restore Customer" : "Archive Customer");
        lifecycleBtn.setBackground(c.isArchived() ? UITheme.ACCENT : UITheme.WARNING);
    }

    private void filterTable() {
        if (allCustomers == null) return;
        String keyword = searchField.getText().trim().toLowerCase();
        List<Customer> filtered = allCustomers.stream()
                .filter(c -> keyword.isEmpty()
                        || c.getFullName().toLowerCase().contains(keyword)
                        || c.getNationalId().toLowerCase().contains(keyword)
                        || c.getEmail().toLowerCase().contains(keyword))
                .toList();
        populateCustomerTable(filtered);
        UIUtils.adjustColumnWidths(customerTable, 60, 250);
    }

    private void handleShowProfile() {
        Customer selected = getSelectedCustomer();
        if (selected == null) {
            UIUtils.showWarning(this, "Please select a customer first.");
            return;
        }

        new SwingWorker<List<Account>, Void>() {
            @Override protected List<Account> doInBackground() {
                return accountService.getAccountsByCustomer(admin, selected.getCustomerId());
            }
            @Override protected void done() {
                try {
                    List<Account> accounts = get();
                    CustomerProfileDialog dlg = new CustomerProfileDialog(
                            (Frame) SwingUtilities.getWindowAncestor(CustomerManagementPanel.this),
                            selected, accounts);
                    dlg.setVisible(true);
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(CustomerManagementPanel.this,
                            "Failed to load account details: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void handleViewAccounts() {
        Customer selected = getSelectedCustomer();
        if (selected == null) {
            UIUtils.showWarning(this, "Please select a customer first.");
            return;
        }

        new SwingWorker<List<Account>, Void>() {
            @Override protected List<Account> doInBackground() {
                return accountService.getAccountsByCustomer(admin, selected.getCustomerId());
            }
            @Override protected void done() {
                try {
                    List<Account> accounts = get();
                    accountTableModel.setRowCount(0);
                    for (Account a : accounts) {
                        String typeEn = a.getAccountType().name().equals("SAVINGS") ? "Savings" : "Checking";
                        String statusEn = a.getStatus().name();
                        accountTableModel.addRow(new Object[]{
                            a.getAccountId(),
                            a.getAccountNumber(),
                            typeEn,
                            DateUtil.formatAmount(a.getBalance()),
                            statusEn,
                            a.getClosureReason().name()
                        });
                    }
                    if (accounts.isEmpty()) {
                        accountTableModel.addRow(new Object[]{"—", "No accounts found", "", "", "", ""});
                    }
                    UIUtils.adjustColumnWidths(accountSubTable, 50, 200);
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(CustomerManagementPanel.this,
                            "Failed to load accounts: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void handleEditContact() {
        Customer selected = getSelectedCustomer();
        if (selected == null) {
            UIUtils.showWarning(this, "Please select a customer first.");
            return;
        }

        JTextField phoneField = UIUtils.styledField(20);
        JTextField emailField = UIUtils.styledField(20);
        phoneField.setText(selected.getPhone());
        emailField.setText(selected.getEmail());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(UITheme.CARD);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(5, 6, 5, 6);

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0.35;
        form.add(UIUtils.bodyLabel("Phone:"), gc);
        gc.gridx = 1; gc.weightx = 0.65;
        form.add(phoneField, gc);

        gc.gridx = 0; gc.gridy = 1;
        form.add(UIUtils.bodyLabel("E-mail:"), gc);
        gc.gridx = 1;
        form.add(emailField, gc);

        int result = JOptionPane.showConfirmDialog(
                this, form,
                "Edit Contact — " + selected.getFullName(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        String phone = phoneField.getText().trim();
        String email = emailField.getText().trim();

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                customerService.updateCustomerInfo(admin, selected.getCustomerId(), phone, email);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(CustomerManagementPanel.this,
                            "Contact details of " + selected.getFullName() + " were updated.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(CustomerManagementPanel.this, ex.getCause().getMessage());
                } catch (InterruptedException ex) {
                    UIUtils.showError(CustomerManagementPanel.this, "The update was interrupted.");
                }
            }
        }.execute();
    }

    private void handleArchiveRestoreCustomer() {
        Customer selected = getSelectedCustomer();
        if (selected == null) {
            UIUtils.showWarning(this, "Please select a customer first.");
            return;
        }
        boolean restoring = selected.isArchived();
        String message = restoring
                ? "Restore " + selected.getFullName() + "?\n\n"
                  + "The customer returns to the status held before archive.\n"
                  + "Accounts remain CLOSED until each is separately approved."
                : "Archive " + selected.getFullName() + "?\n\n"
                  + "All ACTIVE/LOCKED accounts will be CLOSED.\n"
                  + "Balances and financial history will be preserved, but funds cannot be used or received.\n"
                  + "Previously CLOSED accounts remain CLOSED. This action is reversible by an administrator.";
        if (!UIUtils.confirmDialog(this, message)) return;

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                if (restoring) customerService.restoreArchivedCustomer(admin, selected.getCustomerId());
                else customerService.archiveCustomer(admin, selected.getCustomerId());
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(CustomerManagementPanel.this, restoring
                            ? "Customer restored. Review and approve each account separately."
                            : "Customer archived. Account balances and financial history were preserved.");
                    accountTableModel.setRowCount(0);
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(CustomerManagementPanel.this,
                            "Customer lifecycle operation failed: " + rootMessage(ex));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    UIUtils.showError(CustomerManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    private void handleRestoreArchivedAccount() {
        Customer selected = getSelectedCustomer();
        if (selected == null) {
            UIUtils.showWarning(this, "Select a customer first.");
            return;
        }
        int row = accountSubTable.getSelectedRow();
        if (row < 0) {
            UIUtils.showWarning(this, "Load accounts and select an account first.");
            return;
        }
        Object idValue = accountTableModel.getValueAt(row, 0);
        if (idValue == null || "—".equals(idValue.toString())) return;
        long accountId;
        try { accountId = Long.parseLong(idValue.toString()); }
        catch (NumberFormatException ex) { UIUtils.showWarning(this, "Invalid account selection."); return; }
        if (!UIUtils.confirmDialog(this,
                "Approve restoration of account " + accountId + "?\n\n"
                + "The account will return to its exact pre-archive state.\n"
                + "An account that was LOCKED before archive will remain LOCKED.")) return;

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                customerService.restoreArchivedAccount(admin, selected.getCustomerId(), accountId);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(CustomerManagementPanel.this, "Account restoration approved.");
                    handleViewAccounts();
                } catch (ExecutionException ex) {
                    UIUtils.showError(CustomerManagementPanel.this, rootMessage(ex));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    UIUtils.showError(CustomerManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    // =========================================================================
    // v3.0 — FRAUD MODULE: ban / unban the selected customer
    // =========================================================================
    private void handleBanUnban() {
        Customer selected = getSelectedCustomer();
        if (selected == null) {
            UIUtils.showWarning(this, "Please select a customer first.");
            return;
        }

        if (selected.isBanned()) {
            handleUnban(selected);
        } else {
            handleBan(selected);
        }
    }

    /** Ban flow: reason dialog (required) → confirmation → service call. */
    private void handleBan(Customer selected) {
        JTextArea reasonArea = new JTextArea(3, 26);
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
                "Banning blocks login and LOCKS every account of this customer."),
                BorderLayout.SOUTH);

        int result = JOptionPane.showConfirmDialog(this, form,
                "Ban User — " + selected.getFullName(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        final String reason = reasonArea.getText().trim();
        if (reason.isEmpty()) {
            UIUtils.showWarning(this, "A ban reason is required.");
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                customerService.banCustomer(admin, selected.getCustomerId(), reason);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(CustomerManagementPanel.this,
                            selected.getFullName() + " has been BANNED.\n"
                            + "Login is now rejected and all their accounts are LOCKED.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(CustomerManagementPanel.this,
                            rootMessage(ex));
                } catch (InterruptedException ex) {
                    UIUtils.showError(CustomerManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    /** Unban flow: confirmation → service call → refresh. */
    private void handleUnban(Customer selected) {
        boolean confirmed = UIUtils.confirmDialog(this,
                "Lift the ban on " + selected.getFullName() + "?\n\n"
                + "The customer returns to ACTIVE, their accounts are unlocked\n"
                + "and they can log in again.");
        if (!confirmed) return;

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                customerService.unbanCustomer(admin, selected.getCustomerId());
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(CustomerManagementPanel.this,
                            selected.getFullName() + " has been unbanned.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(CustomerManagementPanel.this,
                            rootMessage(ex));
                } catch (InterruptedException ex) {
                    UIUtils.showError(CustomerManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }

    private String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    private Customer getSelectedCustomer() {
        int row = customerTable.getSelectedRow();
        if (row < 0 || allCustomers == null) return null;

        Object idObj = customerTableModel.getValueAt(row, 0);
        if (idObj == null || idObj.toString().trim().isEmpty() || idObj.toString().equals("—")) {
            return null;
        }

        try {
            long id = Long.parseLong(idObj.toString().trim());
            return allCustomers.stream()
                    .filter(c -> c.getCustomerId() == id)
                    .findFirst().orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

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

    private JLabel detailValueLabel(String text) {
        JLabel lbl = UIUtils.bodyLabel(text);
        lbl.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 12));
        return lbl;
    }

    private int addDetailRow(JPanel panel, GridBagConstraints gc,
                              int row, String caption, JLabel value) {
        gc.gridy = row; gc.gridx = 0; gc.weightx = 0.4;
        gc.insets = new Insets(3, 4, 3, 8);
        JLabel cap = UIUtils.secondaryLabel(caption);
        panel.add(cap, gc);
        gc.gridx = 1; gc.weightx = 0.6;
        gc.insets = new Insets(3, 0, 3, 4);
        panel.add(value, gc);
        return row + 1;
    }
}
