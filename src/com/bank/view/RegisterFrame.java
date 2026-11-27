package com.bank.view;

import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.service.RegistrationService;
import com.bank.service.ServiceFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * RegisterFrame — public registration for a brand-new customer and their first account (English, LTR).
 */
public class RegisterFrame extends JFrame {

    private final RegistrationService registrationService;

    // ── Personal information ──────────────────────────────────────────────────
    private JTextField    firstNameField;
    private JTextField    lastNameField;
    private JTextField    nationalIdField;
    private JTextField    birthDateField;     // format: yyyy/MM/dd (Gregorian)
    private JTextField    phoneField;
    private JTextField    emailField;

    // ── Address ───────────────────────────────────────────────────────────────
    private JTextArea     addressArea;
    private JTextField    postalCodeField;
    private JTextField    occupationField;

    // ── Account settings ──────────────────────────────────────────────────────
    private JComboBox<String> accountTypeCombo;
    private JTextField        initialDepositField;
    private JPasswordField    pinField;
    private JPasswordField    confirmPinField;

    private JLabel statusLabel;
    private JButton cancelBtn, registerBtn;
    private boolean registrationInProgress;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    public RegisterFrame() {
        this.registrationService = ServiceFactory.getInstance().getRegistrationService();
        initComponents();
        UIUtils.applyLTR(this);
    }

    private void initComponents() {
        setTitle(UITheme.APP_NAME + " — New Customer Registration");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(UITheme.SURFACE);

        add(buildHeader(),  BorderLayout.NORTH);
        add(buildForm(),    BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(540, 700));
        setLocationRelativeTo(null);
    }

    // ─── Header ──────────────────────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel header = new JPanel(new GridBagLayout());
        header.setBackground(UITheme.PRIMARY);
        header.setBorder(new EmptyBorder(20, 24, 18, 24));

        JLabel title = new JLabel("Open a New Account");
        title.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 18));
        title.setForeground(UITheme.TEXT_ON_DARK);

        JLabel sub = new JLabel("Please fill in all fields carefully to open your account.");
        sub.setFont(UITheme.FONT_SMALL);
        sub.setForeground(new Color(0xB8, 0xCC, 0xE0));

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        inner.add(title);
        inner.add(Box.createVerticalStrut(4));
        inner.add(sub);
        header.add(inner);
        return header;
    }

    // ─── Main form ───────────────────────────────────────────────────────────
    private JScrollPane buildForm() {
        JPanel form = UIUtils.cardPanel();
        form.setLayout(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridx   = 0;
        gc.gridwidth = 2;

        int row = 0;

        // ── Personal information ──────────────────────────────────────────────
        row = addSectionHeader(form, gc, row, "Personal Information");

        firstNameField  = UIUtils.styledField(22);
        lastNameField   = UIUtils.styledField(22);
        nationalIdField = UIUtils.styledField(22);
        birthDateField  = UIUtils.styledField(22);
        birthDateField.setToolTipText("Format: yyyy/MM/dd   e.g. 1995/07/22");
        phoneField      = UIUtils.styledField(22);
        emailField      = UIUtils.styledField(22);

        row = addFormRow(form, gc, row, "First Name *",          firstNameField);
        row = addFormRow(form, gc, row, "Last Name *",           lastNameField);
        row = addFormRow(form, gc, row, "National ID *",         nationalIdField);
        row = addFormRow(form, gc, row, "Birth Date *",          birthDateField);
        row = addFormRow(form, gc, row, "Phone Number *",        phoneField);
        row = addFormRow(form, gc, row, "E-mail *",              emailField);

        // ── Address ────────────────────────────────────────────────────────────
        row = addSectionHeader(form, gc, row, "Address");

        addressArea = new JTextArea(3, 20);
        addressArea.setFont(UITheme.FONT_BODY);
        addressArea.setLineWrap(true);
        addressArea.setWrapStyleWord(true);

        JScrollPane addressScroll = new JScrollPane(addressArea);
        addressScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER, 1),
                new EmptyBorder(2, 2, 2, 2)));
        addressScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        addressScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        row = addFormRow(form, gc, row, "Full Address *", addressScroll);

        postalCodeField = UIUtils.styledField(22);
        occupationField = UIUtils.styledField(22);
        row = addFormRow(form, gc, row, "Postal Code *", postalCodeField);
        row = addFormRow(form, gc, row, "Occupation *",  occupationField);

        // ── Account settings ───────────────────────────────────────────────────
        row = addSectionHeader(form, gc, row, "Account Settings");

        accountTypeCombo = new JComboBox<>(new String[]{"Savings", "Checking"});
        accountTypeCombo.setFont(UITheme.FONT_BODY);
        accountTypeCombo.setBackground(UITheme.CARD);

        initialDepositField = UIUtils.styledField(22);
        initialDepositField.setToolTipText("Minimum amount: 0 " + DateUtil.CURRENCY);
        pinField        = UIUtils.styledPasswordField(22);
        pinField.setToolTipText("4 to 6 digits");
        confirmPinField = UIUtils.styledPasswordField(22);

        row = addFormRow(form, gc, row, "Account Type *",       accountTypeCombo);
        row = addFormRow(form, gc, row, "Initial Deposit",       initialDepositField);
        row = addFormRow(form, gc, row, "PIN (4\u20136 digits) *",     pinField);
        row = addFormRow(form, gc, row, "Confirm PIN *",               confirmPinField);

        // ── Status label ───────────────────────────────────────────────────────
        gc.gridy   = row++;
        gc.insets  = new Insets(10, 0, 2, 0);
        statusLabel = new JLabel(" ");
        statusLabel.setFont(UITheme.FONT_SMALL);
        statusLabel.setForeground(UITheme.DANGER);
        form.add(statusLabel, gc);

        // ── Buttons ────────────────────────────────────────────────────────────
        gc.gridy  = row;
        gc.insets = new Insets(6, 0, 4, 0);
        form.add(buildButtonRow(), gc);

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(new EmptyBorder(16, 20, 10, 20));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    // ─── Footer ──────────────────────────────────────────────────────────────
    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER));
        footer.setBackground(UITheme.SURFACE);
        footer.setBorder(new EmptyBorder(0, 0, 10, 0));
        footer.add(UIUtils.secondaryLabel("Fields marked with * are required"));
        return footer;
    }

    // ─── Button row ──────────────────────────────────────────────────────────
    private JPanel buildButtonRow() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        panel.setOpaque(false);

        cancelBtn   = UIUtils.ghostButton("Cancel");
        registerBtn = UIUtils.primaryButton("Register");

        cancelBtn.addActionListener(e -> dispose());
        registerBtn.addActionListener(e -> handleRegister());

        panel.add(cancelBtn);
        panel.add(registerBtn);
        return panel;
    }

    // ─── Form helpers ────────────────────────────────────────────────────────
    private int addFormRow(JPanel form, GridBagConstraints gc,
                           int row, String labelText, JComponent field) {
        gc.gridwidth = 1;
        gc.weightx   = 0.35;
        gc.gridx     = 0;
        gc.gridy     = row;
        gc.insets    = new Insets(5, 0, 5, 8);
        form.add(UIUtils.bodyLabel(labelText), gc);

        gc.gridx     = 1;
        gc.weightx   = 0.65;
        gc.insets    = new Insets(5, 0, 5, 0);
        form.add(field, gc);

        gc.gridwidth = 2;
        return row + 1;
    }

    private int addSectionHeader(JPanel form, GridBagConstraints gc,
                                 int row, String title) {
        gc.gridy   = row;
        gc.gridx   = 0;
        gc.gridwidth = 2;
        gc.weightx = 1.0;
        gc.insets  = new Insets(row == 0 ? 0 : 14, 0, 6, 0);

        JLabel lbl = new JLabel("  " + title);
        lbl.setFont(UITheme.FONT_BUTTON);
        lbl.setForeground(UITheme.TEXT_ON_DARK);
        lbl.setBackground(UITheme.PRIMARY);
        lbl.setOpaque(true);
        lbl.setBorder(new EmptyBorder(5, 8, 5, 8));
        lbl.setHorizontalAlignment(SwingConstants.LEFT);
        form.add(lbl, gc);
        return row + 1;
    }

    // =========================================================================
    // Registration action
    // =========================================================================
    private void handleRegister() {
        if (registrationInProgress) return;

        String firstName      = firstNameField.getText().trim();
        String lastName       = lastNameField.getText().trim();
        String nationalId     = nationalIdField.getText().trim();
        String birthDateStr   = birthDateField.getText().trim();
        String phone          = phoneField.getText().trim();
        String email          = emailField.getText().trim();
        String address        = addressArea.getText().trim();
        String postalCode     = postalCodeField.getText().trim();
        String occupation     = occupationField.getText().trim();
        String accountTypeSel = (String) accountTypeCombo.getSelectedItem();
        String depositStr     = initialDepositField.getText().trim();
        char[] pinChars       = pinField.getPassword();
        char[] confirmChars   = confirmPinField.getPassword();
        String pin            = new String(pinChars);
        String confirmPin     = new String(confirmChars);

        // Client-side validation
        if (pin.isEmpty()) {
            setStatus("The PIN is required.");
            pinField.requestFocus();
            clearPinArrays(pinChars, confirmChars);
            return;
        }
        if (!pin.equals(confirmPin)) {
            setStatus("The PIN and its confirmation do not match.");
            pinField.setText("");
            confirmPinField.setText("");
            pinField.requestFocus();
            clearPinArrays(pinChars, confirmChars);
            return;
        }

        // Birth date is stored as a Gregorian date
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(birthDateStr.replace('۰','0').replace('۱','1')
                    .replace('۲','2').replace('۳','3').replace('۴','4')
                    .replace('۵','5').replace('۶','6').replace('۷','7')
                    .replace('۸','8').replace('۹','9'), DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            setStatus("Birth date must use the format yyyy/MM/dd   e.g. 1995/07/22");
            birthDateField.requestFocus();
            clearPinArrays(pinChars, confirmChars);
            return;
        }

        BigDecimal initialDeposit;
        try {
            initialDeposit = depositStr.isEmpty()
                    ? BigDecimal.ZERO
                    : new BigDecimal(depositStr.replace(",", "").replace("٬", ""));
        } catch (NumberFormatException ex) {
            setStatus("The initial deposit must be a number   e.g. 500000 or 1000000");
            initialDepositField.requestFocus();
            clearPinArrays(pinChars, confirmChars);
            return;
        }

        String accountType = accountTypeSel.equals("Savings") ? "SAVINGS" : "CHECKING";

        String plainTextPin = pin; // AccountService hashes before persistence.

        java.util.Arrays.fill(pinChars,    '\0');
        java.util.Arrays.fill(confirmChars,'\0');
        pin        = null;
        confirmPin = null;

        final String fFirstName  = firstName;
        final String fLastName   = lastName;
        final String fNationalId = nationalId;
        final String fPhone      = phone;
        final String fEmail      = email;
        final String fAddress    = address;
        final String fPostalCode = postalCode;
        final String fOccupation = occupation;
        final LocalDate fBirthDate = birthDate;
        final BigDecimal fInitialDeposit = initialDeposit;
        final String fPlainTextPin = plainTextPin;
        final Account.AccountType fType = Account.AccountType.from(accountType);

        clearStatus();
        setFormEnabled(false);

        SwingWorker<Account, Void> worker = new SwingWorker<>() {
            @Override
            protected Account doInBackground() throws Exception {
                return registrationService.registerNewCustomer(
                    fNationalId, fFirstName, fLastName, fBirthDate,
                    fPhone, fEmail, fAddress, fPostalCode, fOccupation,
                    fType, fPlainTextPin, fInitialDeposit);
            }

            @Override
            protected void done() {
                setFormEnabled(true);
                try {
                    Account newAccount = get();
                    showSuccessCard(newAccount);
                    dispose();
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof BankException) {
                        setStatus(cause.getMessage());
                    } else {
                        setStatus("Registration failed. Please try again.");
                        System.err.println("[RegisterFrame] " + cause);
                    }
                } catch (Exception ex) {
                    setStatus("An unexpected error occurred.");
                    System.err.println("[RegisterFrame] " + ex.getMessage());
                }
            }
        };

        worker.execute();
    }

    // =========================================================================
    // UI helpers
    // =========================================================================
    private void setStatus(String msg) { statusLabel.setText(msg); }
    private void clearStatus()         { statusLabel.setText(" "); }

    private void setFormEnabled(boolean enabled) {
        registrationInProgress = !enabled;
        cancelBtn.setEnabled(enabled);
        registerBtn.setEnabled(enabled);
        setDefaultCloseOperation(enabled ? JFrame.DISPOSE_ON_CLOSE : JFrame.DO_NOTHING_ON_CLOSE);
        firstNameField.setEnabled(enabled);
        lastNameField.setEnabled(enabled);
        nationalIdField.setEnabled(enabled);
        birthDateField.setEnabled(enabled);
        phoneField.setEnabled(enabled);
        emailField.setEnabled(enabled);
        addressArea.setEnabled(enabled);
        postalCodeField.setEnabled(enabled);
        occupationField.setEnabled(enabled);
        accountTypeCombo.setEnabled(enabled);
        initialDepositField.setEnabled(enabled);
        pinField.setEnabled(enabled);
        confirmPinField.setEnabled(enabled);
    }

    private void clearPinArrays(char[]... arrays) {
        for (char[] arr : arrays) java.util.Arrays.fill(arr, '\0');
    }

    // =========================================================================
    // Success dialog
    // =========================================================================
    private void showSuccessCard(Account account) {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(UITheme.CARD);
        card.setBorder(new EmptyBorder(8, 16, 8, 16));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill      = GridBagConstraints.HORIZONTAL;
        gc.weightx   = 1.0;
        gc.gridx     = 0;
        gc.gridwidth = 2;
        gc.insets    = new Insets(4, 0, 4, 0);

        gc.gridy = 0;
        JLabel title = UIUtils.subheading("Your account has been opened successfully!");
        title.setForeground(UITheme.ACCENT);
        card.add(title, gc);

        gc.gridy = 1;
        card.add(new JSeparator(), gc);

        gc.gridy = 2;
        card.add(UIUtils.secondaryLabel("Your new account is ready to use."), gc);

        gc.gridwidth = 1;
        gc.insets    = new Insets(8, 12, 6, 0);

        gc.gridy = 3; gc.gridx = 0; gc.weightx = 0.4;
        card.add(UIUtils.bodyLabel("Card Number:"), gc);
        gc.gridx = 1; gc.weightx = 0.6;
        JLabel cardNumLabel = new JLabel(formatCardNumber(account.getCardNumber()));
        cardNumLabel.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 16));
        cardNumLabel.setForeground(UITheme.PRIMARY);
        card.add(cardNumLabel, gc);

        gc.gridy = 4; gc.gridx = 0;
        card.add(UIUtils.bodyLabel("Account Number:"), gc);
        gc.gridx = 1;
        JLabel acctNumLabel = new JLabel(account.getAccountNumber());
        acctNumLabel.setFont(UITheme.FONT_MONO);
        acctNumLabel.setForeground(UITheme.TEXT_PRIMARY);
        card.add(acctNumLabel, gc);

        gc.gridy = 5; gc.gridx = 0;
        card.add(UIUtils.bodyLabel("Account Type:"), gc);
        gc.gridx = 1;
        String typeEn = account.getAccountType().name().equals("SAVINGS") ? "Savings" : "Checking";
        card.add(UIUtils.bodyLabel(typeEn), gc);

        gc.gridy = 6; gc.gridx = 0;
        card.add(UIUtils.bodyLabel("Initial Balance:"), gc);
        gc.gridx = 1;
        JLabel balLabel = new JLabel(DateUtil.formatMoney(account.getBalance()));
        balLabel.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 13));
        balLabel.setForeground(UITheme.ACCENT);
        card.add(balLabel, gc);

        gc.gridy = 7; gc.gridx = 0; gc.gridwidth = 2;
        gc.insets = new Insets(14, 0, 4, 0);
        JLabel pinNote = new JLabel("Note: use your card number and PIN to sign in.");
        pinNote.setFont(UITheme.FONT_SMALL);
        pinNote.setForeground(UITheme.WARNING.darker());
        card.add(pinNote, gc);

        gc.gridy = 8;
        gc.insets = new Insets(2, 0, 4, 0);
        JLabel secNote = new JLabel("Keep your card number confidential and never share it with anyone.");
        secNote.setFont(UITheme.FONT_SMALL);
        secNote.setForeground(UITheme.TEXT_SECONDARY);
        card.add(secNote, gc);

        JOptionPane.showMessageDialog(
                this,
                card,
                "Account Opened",
                JOptionPane.PLAIN_MESSAGE);
    }

    private String formatCardNumber(String raw) {
        if (raw == null || raw.length() != 12) return raw;
        return raw.substring(0, 4) + " "
             + raw.substring(4, 8) + " "
             + raw.substring(8, 12);
    }
}
