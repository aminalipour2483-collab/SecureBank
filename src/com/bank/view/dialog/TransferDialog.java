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
import java.math.RoundingMode;

/**
 * TransferDialog — transfer funds dialog (English, LTR).
 * Recipient can be identified by 12-digit account number or card number.
 */
public class TransferDialog extends JDialog {

    public enum LookupMode { ACCOUNT_NUMBER, CARD_NUMBER }

    private JRadioButton acctRadio;
    private JRadioButton cardRadio;
    private JLabel       identifierLabel;
    private JTextField   identifierField;
    private JTextField   amountField;
    private JLabel       statusLabel;

    private boolean    confirmed = false;
    private String     recipientIdentifier;
    private LookupMode lookupMode;
    private BigDecimal amount;

    public TransferDialog(Frame parent) {
        super(parent, "Transfer Funds", true);
        buildUI();
        UIUtils.applyLTR(this);
    }

    private void buildUI() {
        setLayout(new BorderLayout());
        getContentPane().setBackground(UITheme.SURFACE);
        setResizable(false);

        JPanel body = UIUtils.cardPanel();
        body.setLayout(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridx   = 0;
        gc.insets  = new Insets(5, 0, 5, 0);

        gc.gridy = 0;
        body.add(UIUtils.subheading("Transfer Funds"), gc);

        gc.gridy = 1;
        gc.insets = new Insets(2, 0, 10, 0);
        body.add(new JSeparator(), gc);
        gc.insets = new Insets(5, 0, 5, 0);

        gc.gridy = 2;
        body.add(UIUtils.bodyLabel("Identify the recipient by:"), gc);

        gc.gridy = 3;
        body.add(buildRadioPanel(), gc);

        gc.gridy = 4;
        gc.insets = new Insets(4, 0, 10, 0);
        body.add(new JSeparator(), gc);
        gc.insets = new Insets(5, 0, 5, 0);

        gc.gridy = 5;
        identifierLabel = UIUtils.bodyLabel("Account Number *");
        body.add(identifierLabel, gc);

        gc.gridy = 6;
        identifierField = UIUtils.styledField(22);
        identifierField.setFont(UITheme.FONT_MONO);
        identifierField.setDocument(new FixedLengthDocument(12));
        identifierField.setToolTipText("Enter the recipient's 12-digit account number");
        identifierField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) amountField.requestFocus();
            }
        });
        body.add(identifierField, gc);

        gc.gridy = 7;
        body.add(UIUtils.bodyLabel("Transfer Amount (" + DateUtil.CURRENCY + ") *"), gc);

        gc.gridy = 8;
        amountField = UIUtils.styledField(22);
        amountField.setFont(UITheme.FONT_MONO);
        amountField.setToolTipText("Example: 250000");
        amountField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) confirm();
            }
        });
        body.add(amountField, gc);

        gc.gridy  = 9;
        gc.insets = new Insets(6, 0, 2, 0);
        statusLabel = new JLabel(" ");
        statusLabel.setFont(UITheme.FONT_SMALL);
        statusLabel.setForeground(UITheme.DANGER);
        body.add(statusLabel, gc);

        gc.gridy  = 10;
        gc.insets = new Insets(8, 0, 0, 0);
        body.add(buildButtonRow(), gc);

        add(body, BorderLayout.CENTER);
        pack();
        setMinimumSize(new Dimension(400, 420));
        setLocationRelativeTo(getOwner());
    }

    private JPanel buildRadioPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setOpaque(false);

        ButtonGroup group = new ButtonGroup();
        acctRadio = new JRadioButton("Account Number");
        cardRadio = new JRadioButton("Card Number");

        for (JRadioButton rb : new JRadioButton[]{acctRadio, cardRadio}) {
            rb.setFont(UITheme.FONT_BODY);
            rb.setBackground(UITheme.CARD);
            rb.setFocusPainted(false);
            group.add(rb);
        }

        acctRadio.setForeground(UITheme.PRIMARY);
        cardRadio.setForeground(UITheme.TEXT_SECONDARY);
        acctRadio.setSelected(true);

        acctRadio.addActionListener(e -> onModeChange());
        cardRadio.addActionListener(e -> onModeChange());

        JPanel acctCard = radioCard(acctRadio, true);
        JPanel cardCard = radioCard(cardRadio, false);

        acctRadio.putClientProperty("card", acctCard);
        cardRadio.putClientProperty("card", cardCard);

        panel.add(acctCard);
        panel.add(Box.createHorizontalStrut(8));
        panel.add(cardCard);
        return panel;
    }

    private JPanel radioCard(JRadioButton radio, boolean selected) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(selected ? new Color(0xE8, 0xF0, 0xF8) : UITheme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        selected ? UITheme.PRIMARY : UITheme.BORDER, selected ? 2 : 1),
                new EmptyBorder(6, 12, 6, 12)));
        card.add(radio, BorderLayout.CENTER);
        return card;
    }

    private void onModeChange() {
        boolean isAcct = acctRadio.isSelected();
        identifierLabel.setText(isAcct ? "Account Number *" : "Card Number *");
        identifierField.setText("");
        identifierField.setToolTipText(isAcct
                ? "Enter the recipient's 12-digit account number"
                : "Enter the recipient's 12-digit card number");
        updateRadioCardBorder(acctRadio, isAcct);
        updateRadioCardBorder(cardRadio, !isAcct);
        statusLabel.setText(" ");
        identifierField.requestFocus();
    }

    private void updateRadioCardBorder(JRadioButton radio, boolean active) {
        JPanel card = (JPanel) radio.getClientProperty("card");
        if (card == null) return;
        card.setBackground(active ? new Color(0xE8, 0xF0, 0xF8) : UITheme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        active ? UITheme.PRIMARY : UITheme.BORDER, active ? 2 : 1),
                new EmptyBorder(6, 12, 6, 12)));
        radio.setForeground(active ? UITheme.PRIMARY : UITheme.TEXT_SECONDARY);
        card.repaint();
    }

    private JPanel buildButtonRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);

        JButton cancelBtn  = UIUtils.ghostButton("Cancel");
        JButton confirmBtn = UIUtils.primaryButton("Transfer");

        cancelBtn.addActionListener(e  -> dispose());
        confirmBtn.addActionListener(e -> confirm());

        row.add(cancelBtn);
        row.add(confirmBtn);
        return row;
    }

    private void confirm() {
        String identifier = identifierField.getText().trim();
        String amtStr     = amountField.getText().trim();

        if (identifier.isEmpty()) {
            statusLabel.setText(acctRadio.isSelected()
                    ? "The account number is required." : "The card number is required.");
            identifierField.requestFocus();
            return;
        }
        if (!identifier.matches("\\d{12}")) {
            statusLabel.setText("Must be exactly 12 digits   e.g. 402345678901");
            identifierField.selectAll();
            identifierField.requestFocus();
            return;
        }

        if (amtStr.isEmpty()) {
            statusLabel.setText("The transfer amount is required.");
            amountField.requestFocus();
            return;
        }
        try {
            BigDecimal value = new BigDecimal(amtStr.replace(",", "").replace("٬", ""));
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                statusLabel.setText("The amount must be greater than zero");
                amountField.selectAll();
                amountField.requestFocus();
                return;
            }
            recipientIdentifier = identifier;
            lookupMode = acctRadio.isSelected() ? LookupMode.ACCOUNT_NUMBER : LookupMode.CARD_NUMBER;
            amount = value.setScale(0, RoundingMode.HALF_UP);
            confirmed = true;
            dispose();
        } catch (NumberFormatException ex) {
            statusLabel.setText("Invalid amount — numbers only");
            amountField.selectAll();
            amountField.requestFocus();
        }
    }

    public boolean showAndGet() {
        setVisible(true);
        return confirmed;
    }

    public String getRecipientIdentifier() { return recipientIdentifier; }
    public LookupMode getLookupMode() { return lookupMode; }
    public BigDecimal getAmount() { return amount; }

    private static class FixedLengthDocument extends javax.swing.text.PlainDocument {
        private static final long serialVersionUID = 1L;
        private final int maxLength;
        FixedLengthDocument(int maxLength) { this.maxLength = maxLength; }

        @Override
        public void insertString(int offset, String str, javax.swing.text.AttributeSet attr)
                throws javax.swing.text.BadLocationException {
            if (str == null) return;
            if (getLength() + str.length() <= maxLength)
                super.insertString(offset, str, attr);
        }
    }
}
