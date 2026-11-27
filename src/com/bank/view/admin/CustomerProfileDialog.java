package com.bank.view.admin;

import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * CustomerProfileDialog — read-only customer profile with accounts (English, LTR).
 * v3.0: shows ban status and reason.
 */
public class CustomerProfileDialog extends JDialog {

    public CustomerProfileDialog(Frame parent, Customer customer, List<Account> accounts) {
        super(parent, "Customer Profile: " + customer.getFullName(), true);
        setLayout(new BorderLayout());
        getContentPane().setBackground(UITheme.SURFACE);
        UIUtils.applyLTR(this);

        add(buildHeader(customer),        BorderLayout.NORTH);
        add(buildDetailPanel(customer),   BorderLayout.CENTER);
        add(buildAccountTable(accounts),  BorderLayout.SOUTH);

        setSize(760, 560);
        setMinimumSize(new Dimension(660, 430));
        setLocationRelativeTo(parent);
    }

    private JPanel buildHeader(Customer c) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(UITheme.PRIMARY);
        header.setBorder(new EmptyBorder(16, 20, 16, 20));

        JLabel name = new JLabel(c.getFullName());
        name.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 18));
        name.setForeground(UITheme.TEXT_ON_DARK);

        JLabel id = new JLabel("ID: " + c.getCustomerId() + "  |  National ID: " + c.getNationalId());
        id.setFont(UITheme.FONT_SMALL);
        id.setForeground(new Color(0xB8, 0xCC, 0xE0));

        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(name, BorderLayout.NORTH);
        left.add(id,   BorderLayout.SOUTH);

        header.add(left, BorderLayout.LINE_START);

        JButton closeBtn = UIUtils.ghostButton("Close");
        closeBtn.setForeground(UITheme.TEXT_ON_DARK);
        closeBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x8A, 0xA4, 0xC0), 1),
                new EmptyBorder(5, 14, 5, 14)));
        closeBtn.addActionListener(e -> dispose());
        header.add(closeBtn, BorderLayout.LINE_END);

        return header;
    }

    private JPanel buildDetailPanel(Customer c) {
        JPanel panel = UIUtils.cardPanel();
        panel.setLayout(new GridBagLayout());
        panel.setBorder(new EmptyBorder(12, 16, 12, 16));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 8, 4, 8);

        int row = 0;
        row = addDetailRow(panel, gc, row, "Full Name:",   c.getFullName());
        row = addDetailRow(panel, gc, row, "National ID:", c.getNationalId());
        row = addDetailRow(panel, gc, row, "Birth Date:",
                c.getBirthDate() != null ? DateUtil.formatDate(c.getBirthDate()) : "—");
        row = addDetailRow(panel, gc, row, "Phone:",       c.getPhone());
        row = addDetailRow(panel, gc, row, "E-mail:",      c.getEmail());
        row = addDetailRow(panel, gc, row, "Address:",     c.getAddress() != null ? c.getAddress() : "—");
        row = addDetailRow(panel, gc, row, "Postal Code:", c.getPostalCode() != null ? c.getPostalCode() : "—");
        row = addDetailRow(panel, gc, row, "Occupation:",  c.getOccupation() != null ? c.getOccupation() : "—");
        row = addDetailRow(panel, gc, row, "Registered:",
                c.getCreatedAt() != null ? DateUtil.formatDateTime(c.getCreatedAt()) : "—");

        // v3.0 — fraud module: ban status (red when banned)
        JLabel statusLbl = UIUtils.bodyLabel(c.getStatus().name());
        statusLbl.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 13));
        statusLbl.setForeground(c.isBanned() ? UITheme.DANGER
                : (c.isArchived() ? UITheme.TEXT_SECONDARY : UITheme.ACCENT_DARK));
        gc.gridy = row; gc.gridx = 0; gc.weightx = 0.35;
        gc.insets = new Insets(4, 8, 4, 8);
        JLabel statusCap = UIUtils.secondaryLabel("Status:");
        statusCap.setHorizontalAlignment(SwingConstants.LEFT);
        panel.add(statusCap, gc);
        gc.gridx = 1; gc.weightx = 0.65;
        panel.add(statusLbl, gc);
        row++;

        if (c.isBanned() && c.getBannedReason() != null) {
            row = addDetailRow(panel, gc, row, "Ban Reason:", c.getBannedReason());
        }

        return panel;
    }

    private int addDetailRow(JPanel panel, GridBagConstraints gc, int row, String label, String value) {
        gc.gridy = row;
        gc.gridx = 0;
        gc.weightx = 0.35;
        gc.insets = new Insets(4, 8, 4, 8);
        JLabel lbl = UIUtils.secondaryLabel(label);
        lbl.setHorizontalAlignment(SwingConstants.LEFT);
        panel.add(lbl, gc);

        gc.gridx = 1;
        gc.weightx = 0.65;
        JLabel valLbl = UIUtils.bodyLabel(value != null ? value : "—");
        valLbl.setFont(new Font(UITheme.FONT_FAMILY, Font.PLAIN, 13));
        panel.add(valLbl, gc);
        return row + 1;
    }

    private JPanel buildAccountTable(List<Account> accounts) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(UITheme.SURFACE);
        panel.setBorder(new EmptyBorder(0, 16, 16, 16));

        JLabel title = UIUtils.subheading("Customer Accounts");
        panel.add(title, BorderLayout.NORTH);

        String[] cols = {"Account Number", "Card Number", "Type", "Balance (" + DateUtil.CURRENCY + ")", "Status", "Opened"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        if (accounts != null) {
            for (Account a : accounts) {
                String typeEn = a.getAccountType().name().equals("SAVINGS") ? "Savings" : "Checking";
                String statusEn = a.getStatus().name();
                model.addRow(new Object[]{
                    a.getAccountNumber(),
                    maskCardNumber(a.getCardNumber()),
                    typeEn,
                    DateUtil.formatAmount(a.getBalance()),
                    statusEn,
                    a.getCreatedAt() != null ? DateUtil.formatDate(a.getCreatedAt().toLocalDate()) : "—"
                });
            }
        }

        if (model.getRowCount() == 0) {
            model.addRow(new Object[]{"—", "No accounts exist", "", "", "", ""});
        }

        JTable table = new JTable(model);
        UIUtils.styleTable(table);
        table.setFont(UITheme.FONT_BODY);
        table.setRowHeight(26);
        table.setGridColor(UITheme.BORDER);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(UITheme.SELECTION_BG);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(700, 150));
        panel.add(scroll, BorderLayout.CENTER);

        UIUtils.adjustColumnWidths(table, 60, 200);

        return panel;
    }

    private String maskCardNumber(String card) {
        if (card == null || card.length() < 12) return card != null ? card : "—";
        return card.substring(0, 4) + " .... " + card.substring(card.length() - 4);
    }
}
