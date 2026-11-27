package com.bank.view.admin;

import com.bank.model.Transfer;
import com.bank.model.Transfer.TransferStatus;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.service.TransferService;
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
 * TransferManagementPanel — admin tab for transfer records (English, LTR).
 */
public class TransferManagementPanel extends JPanel implements AdminFrame.Refreshable {

    private final AdminPrincipal admin;
    private final TransferService transferService;

    private DefaultTableModel tableModel;
    private JTable            table;
    private List<Transfer>    allTransfers;

    private JComboBox<String> statusFilter;
    private JTextField        refSearchField;
    private JLabel            summaryLabel;

    public TransferManagementPanel(AdminPrincipal admin,
            TransferService transferService) {
        this.admin = AdminGuard.require(admin);
        this.transferService = transferService;
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

        // ── Row 1: filters ───────────────────────────────────────────────────
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.setOpaque(false);
        filters.setBorder(new EmptyBorder(0, 0, 8, 0));

        filters.add(UIUtils.bodyLabel("Status:"));
        statusFilter = new JComboBox<>(new String[]{
                "All", "Pending", "Completed", "Failed", "Reversed"});
        statusFilter.setFont(UITheme.FONT_BODY);
        statusFilter.setPreferredSize(new Dimension(120, 30));
        statusFilter.addActionListener(e -> applyFilter());
        filters.add(statusFilter);

        filters.add(Box.createHorizontalStrut(10));
        filters.add(UIUtils.bodyLabel("Reference #:"));
        refSearchField = UIUtils.styledField(12);
        refSearchField.setPreferredSize(new Dimension(150, 32));
        refSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        filters.add(refSearchField);

        JButton clearBtn = UIUtils.ghostButton("Clear");
        clearBtn.addActionListener(e -> {
            refSearchField.setText("");
            statusFilter.setSelectedIndex(0);
            applyFilter();
        });
        filters.add(clearBtn);

        // ── Row 2: operations ────────────────────────────────────────────────
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.setBorder(new EmptyBorder(0, 0, 8, 0));

        JButton failBtn    = UIUtils.dangerButton("Mark Failed");
        JButton reverseBtn = UIUtils.warningButton("Reverse");
        JButton refreshBtn = UIUtils.ghostButton("Refresh");

        failBtn.setToolTipText("Mark a pending transfer as failed");
        reverseBtn.setToolTipText("Mark a completed transfer as reversed");

        failBtn.addActionListener(e    -> handleUpdateStatus(TransferStatus.FAILED));
        reverseBtn.addActionListener(e -> handleUpdateStatus(TransferStatus.REVERSED));
        refreshBtn.addActionListener(e -> refresh());

        actions.add(failBtn);
        // Reversal UI disabled: the current data model has no posting reversal operation.
        actions.add(refreshBtn);

        bar.add(filters);
        bar.add(actions);
        return bar;
    }

    private JPanel buildTableCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new BorderLayout(0, 8));
        card.add(UIUtils.subheading("Transfer Records"), BorderLayout.NORTH);

        String[] cols = {
                "ID", "Reference #", "From Account",
                "To Account", "Amount (" + DateUtil.CURRENCY + ")", "Status", "Date"
        };

        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        table = new JTable(tableModel);
        UIUtils.styleTable(table);

        table.setFont(UITheme.FONT_BODY);
        table.setRowHeight(28);
        table.setGridColor(UITheme.BORDER);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(UITheme.SELECTION_BG);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                                                           boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) {
                    String status = String.valueOf(t.getValueAt(r, 5));
                    switch (status) {
                        case "Completed" -> setBackground(UITheme.ROW_CREDIT);
                        case "Pending"   -> setBackground(UITheme.ROW_WARNING);
                        case "Failed", "Reversed" -> setBackground(UITheme.ROW_DEBIT);
                        default -> setBackground(r % 2 == 0 ? UITheme.CARD : UITheme.ROW_ALTERNATE);
                    }
                }
                setBorder(new EmptyBorder(4, 8, 4, 8));
                setHorizontalAlignment(SwingConstants.LEFT);
                return this;
            }
        });

        card.add(new JScrollPane(table), BorderLayout.CENTER);
        UIUtils.adjustColumnWidths(table, 70, 200);   // fit headers even before data loads
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
        new SwingWorker<List<Transfer>, Void>() {
            @Override protected List<Transfer> doInBackground() {
                return transferService.findAll(admin);
            }
            @Override protected void done() {
                try {
                    allTransfers = get();
                    applyFilter();
                } catch (InterruptedException | ExecutionException e) {
                    UIUtils.showError(TransferManagementPanel.this,
                            "Failed to load transfers: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void applyFilter() {
        if (allTransfers == null) return;

        String statusSel = (String) statusFilter.getSelectedItem();
        String refSearch = refSearchField.getText().trim().toLowerCase();

        List<Transfer> filtered = allTransfers.stream()
                .filter(t -> {
                    String statusEn = statusToEn(t.getStatus());
                    return "All".equals(statusSel) || statusEn.equals(statusSel);
                })
                .filter(t -> refSearch.isEmpty()
                        || t.getReferenceNumber().toLowerCase().contains(refSearch))
                .toList();

        tableModel.setRowCount(0);

        long completed = 0, pending = 0, failed = 0, reversed = 0;
        for (Transfer t : filtered) {
            String statusEn = statusToEn(t.getStatus());
            tableModel.addRow(new Object[]{
                    t.getTransferId(),
                    t.getReferenceNumber(),
                    t.getSenderAccountId(),
                    t.getReceiverAccountId(),
                    DateUtil.formatAmount(t.getAmount()),
                    statusEn,
                    t.getCreatedAt() != null ? DateUtil.formatDateTime(t.getCreatedAt()) : "—"
            });
            switch (t.getStatus()) {
                case COMPLETED -> completed++;
                case PENDING   -> pending++;
                case FAILED    -> failed++;
                case REVERSED  -> reversed++;
            }
        }

        if (filtered.isEmpty()) {
            tableModel.addRow(new Object[]{"—", "No transfers match this filter", "", "", "", "", ""});
        }

        summaryLabel.setText(String.format(
                "Showing %d   |   Completed: %d   |   Pending: %d   |   Failed: %d   |   Reversed: %d",
                filtered.size(), completed, pending, failed, reversed));

        UIUtils.adjustColumnWidths(table, 70, 200);
    }

    private String statusToEn(TransferStatus status) {
        return switch (status) {
            case PENDING   -> "Pending";
            case COMPLETED -> "Completed";
            case FAILED    -> "Failed";
            case REVERSED  -> "Reversed";
        };
    }

    private void handleUpdateStatus(TransferStatus newStatus) {
        int row = table.getSelectedRow();
        if (row < 0) {
            UIUtils.showWarning(this, "Please select a transfer record first.");
            return;
        }

        Object idObj = tableModel.getValueAt(row, 0);
        if (idObj == null || idObj.toString().trim().isEmpty() || idObj.toString().equals("—")) {
            UIUtils.showWarning(this, "Please select a valid record.");
            return;
        }

        long transferId;
        try {
            transferId = Long.parseLong(idObj.toString().trim());
        } catch (NumberFormatException e) {
            UIUtils.showWarning(this, "Invalid transfer ID.");
            return;
        }

        String currentStatus = tableModel.getValueAt(row, 5).toString();
        String refNum = tableModel.getValueAt(row, 1).toString();

        if (newStatus == TransferStatus.FAILED && !"Pending".equals(currentStatus)) {
            UIUtils.showError(this,
                    "Only \u201CPending\u201D transfers can be marked as failed.\nCurrent status: " + currentStatus);
            return;
        }
        if (newStatus == TransferStatus.REVERSED && !"Completed".equals(currentStatus)) {
            UIUtils.showError(this,
                    "Only \u201CCompleted\u201D transfers can be marked as reversed.\nCurrent status: " + currentStatus);
            return;
        }

        boolean ok = UIUtils.confirmDialog(this,
                "Change transfer " + refNum + " to \u201C" + statusToEn(newStatus) + "\u201D?\n\n" +
                        "Note: only the record status changes.\nAccount balances are NOT adjusted automatically.");
        if (!ok) return;

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                transferService.updateTransferStatus(admin, transferId, newStatus);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(TransferManagementPanel.this,
                            "Transfer " + refNum + " was changed to \u201C" + statusToEn(newStatus) + "\u201D.");
                    refresh();
                } catch (ExecutionException ex) {
                    UIUtils.showError(TransferManagementPanel.this,
                            "Changing the status failed: " + ex.getCause().getMessage());
                } catch (InterruptedException ex) {
                    UIUtils.showError(TransferManagementPanel.this, "Operation interrupted.");
                }
            }
        }.execute();
    }
}
