package com.bank.view.dialog;

import com.bank.view.DateUtil;
import com.bank.view.UITheme;
import com.bank.view.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;

/**
 * AmountDialog — numeric amount entry dialog for deposit / withdraw (English).
 */
public class AmountDialog extends JDialog {

    private JTextField  amountField;
    private JLabel      statusLabel;
    private BigDecimal  result = null;

    public AmountDialog(Frame parent, String title, String prompt) {
        super(parent, title, true);
        buildUI(prompt);
        UIUtils.applyLTR(this);
    }

    private void buildUI(String prompt) {
        setLayout(new BorderLayout());
        getContentPane().setBackground(UITheme.SURFACE);
        setResizable(false);

        JPanel body = UIUtils.cardPanel();
        body.setLayout(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridx   = 0;
        gc.insets  = new Insets(6, 0, 6, 0);

        gc.gridy = 0;
        JLabel promptLabel = UIUtils.bodyLabel(prompt);
        body.add(promptLabel, gc);

        gc.gridy = 1;
        amountField = UIUtils.styledField(18);
        amountField.setFont(UITheme.FONT_MONO);
        amountField.setToolTipText("Example: 500000 or 1200000 (" + DateUtil.CURRENCY + ")");
        amountField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) confirm();
            }
        });
        body.add(amountField, gc);

        gc.gridy  = 2;
        gc.insets = new Insets(2, 0, 4, 0);
        statusLabel = new JLabel(" ");
        statusLabel.setFont(UITheme.FONT_SMALL);
        statusLabel.setForeground(UITheme.DANGER);
        body.add(statusLabel, gc);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setOpaque(false);

        JButton cancelBtn  = UIUtils.ghostButton("Cancel");
        JButton confirmBtn = UIUtils.primaryButton("Confirm");

        cancelBtn.addActionListener(e  -> { result = null; dispose(); });
        confirmBtn.addActionListener(e -> confirm());

        btnRow.add(cancelBtn);
        btnRow.add(confirmBtn);

        gc.gridy  = 3;
        gc.insets = new Insets(6, 0, 0, 0);
        body.add(btnRow, gc);

        add(body, BorderLayout.CENTER);

        pack();
        setMinimumSize(new Dimension(360, 220));
        setLocationRelativeTo(getOwner());
    }

    private void confirm() {
        String text = amountField.getText().trim()
                .replace(",", "")
                .replace("٬", "")
                .replace("۰", "0").replace("۱", "1").replace("۲", "2")
                .replace("۳", "3").replace("۴", "4").replace("۵", "5")
                .replace("۶", "6").replace("۷", "7").replace("۸", "8").replace("۹", "9");

        if (text.isEmpty()) {
            statusLabel.setText("Please enter the amount.");
            amountField.requestFocus();
            return;
        }
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                statusLabel.setText("The amount must be greater than zero");
                return;
            }
            result = value.setScale(0, java.math.RoundingMode.HALF_UP);
            dispose();
        } catch (NumberFormatException ex) {
            statusLabel.setText("Invalid amount — numbers only");
            amountField.selectAll();
            amountField.requestFocus();
        }
    }

    public BigDecimal showAndGetAmount() {
        setVisible(true);
        return result;
    }
}
