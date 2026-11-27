package com.bank.view.dialog;

import com.bank.model.Transaction;
import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * TransactionHistoryDialog — full transaction history of one account (English, LTR).
 */
public class TransactionHistoryDialog extends JDialog {

    public TransactionHistoryDialog(Frame parent, List<Transaction> transactions) {
        super(parent, "Transaction History", true);
        buildUI(transactions);
        UIUtils.applyLTR(this);
    }

    private void buildUI(List<Transaction> transactions) {
        setLayout(new BorderLayout());
        getContentPane().setBackground(UITheme.SURFACE);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(UITheme.PRIMARY);
        header.setBorder(new EmptyBorder(14, 18, 14, 18));

        JLabel title = new JLabel("Complete Transaction History");
        title.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 16));
        title.setForeground(UITheme.TEXT_ON_DARK);

        JLabel count = new JLabel(transactions.size() + " records");
        count.setFont(UITheme.FONT_SMALL);
        count.setForeground(new Color(0xB8, 0xCC, 0xE0));

        header.add(title, BorderLayout.LINE_START);
        header.add(count, BorderLayout.LINE_END);
        add(header, BorderLayout.NORTH);

        String[] columns = {"#", "Date & Time", "Type", "Amount (" + DateUtil.CURRENCY + ")", "Balance After (" + DateUtil.CURRENCY + ")"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        for (int i = 0; i < transactions.size(); i++) {
            Transaction tx = transactions.get(i);
            String sign = tx.isCredit() ? "+" : "-";
            String typeEn = formatType(tx.getTransactionType().name());
            model.addRow(new Object[]{
                i + 1,
                tx.getCreatedAt() != null ? DateUtil.formatDateTimeFull(tx.getCreatedAt()) : "—",
                typeEn,
                sign + DateUtil.formatAmount(tx.getAmount()),
                DateUtil.formatAmount(tx.getBalanceAfterTransaction())
            });
        }

        if (transactions.isEmpty()) {
            model.addRow(new Object[]{"—", "No transactions found", "", "", ""});
        }

        JTable table = new JTable(model);
        UIUtils.styleTable(table);

        table.setFont(UITheme.FONT_BODY);
        table.setRowHeight(28);
        table.setGridColor(UITheme.BORDER);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(new Color(0xE3, 0xEE, 0xF8));

        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getColumnModel().getColumn(4).setPreferredWidth(140);

        table.setDefaultRenderer(Object.class, new RowColorRenderer());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(new EmptyBorder(12, 12, 4, 12));
        add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.setBackground(UITheme.SURFACE);
        footer.setBorder(new EmptyBorder(4, 12, 12, 12));
        JButton closeBtn = UIUtils.primaryButton("Close");
        closeBtn.addActionListener(e -> dispose());
        footer.add(closeBtn);
        add(footer, BorderLayout.SOUTH);

        setSize(740, 500);
        setMinimumSize(new Dimension(620, 400));
        setLocationRelativeTo(getOwner());
    }

    private String formatType(String raw) {
        if (raw == null) return "";
        return switch (raw.toUpperCase()) {
            case "DEPOSIT" -> "Deposit";
            case "WITHDRAWAL", "WITHDRAW" -> "Withdrawal";
            case "TRANSFER_IN" -> "Transfer In";
            case "TRANSFER_OUT" -> "Transfer Out";
            default -> raw;
        };
    }

    private static class RowColorRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (!isSelected) {
                String type = String.valueOf(table.getValueAt(row, 2));
                boolean isCredit = type.contains("Deposit") || type.contains("In");
                setBackground(isCredit ? UITheme.ROW_CREDIT
                        : (row % 2 == 0 ? UITheme.ROW_DEBIT : UITheme.ROW_ALTERNATE));
            }
            setBorder(new EmptyBorder(0, 8, 0, 8));
            setHorizontalAlignment(SwingConstants.LEFT);
            return this;
        }
    }
}
