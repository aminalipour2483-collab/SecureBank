package com.bank.view;

import com.bank.exception.AccountAutoLockedException;
import com.bank.exception.AccountLockedException;
import com.bank.exception.AdminLockedException;
import com.bank.exception.BankException;
import com.bank.exception.CustomerBannedException;
import com.bank.exception.InvalidPinException;
import com.bank.exception.InvalidPinWithAttemptsException;
import com.bank.model.Account;
import com.bank.service.AccountService;
import com.bank.service.ServiceFactory;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerSessionService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.concurrent.ExecutionException;

/** Customer login. The PIN-recovery tab is revealed only after an automatic PIN lock. */
public class LoginFrame extends JFrame {
    private final CustomerSessionService customerSessionService = ServiceFactory.getInstance().getCustomerSessionService();
    private final AccountService accountService = ServiceFactory.getInstance().getAccountService();
    private JTabbedPane tabs;

    private JTextField cardField;
    private JPasswordField pinField;
    private JButton loginButton;
    private JLabel loginStatus;
    private JLabel attemptLabel;

    private JPanel recoveryPanel;
    private JTextField recoveryCardField;
    private JTextField nationalIdField;
    private JTextField registeredPhoneField;
    private JPasswordField newPinField;
    private JPasswordField confirmPinField;
    private JButton recoverButton;
    private JLabel recoveryStatus;
    private boolean cardLocked;
    private boolean authenticationBusy;
    private String lockedCardNumber;

    public LoginFrame() {
        initComponents();
        UIUtils.applyLTR(this);
    }

    private void initComponents() {
        setTitle(UITheme.APP_NAME + " — Customer Login");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setLayout(new BorderLayout());
        getContentPane().setBackground(UITheme.SURFACE);
        add(buildHeader(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        setMinimumSize(new Dimension(480, 620));
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new GridBagLayout());
        header.setBackground(UITheme.PRIMARY);
        header.setBorder(new EmptyBorder(26, 30, 22, 30));
        JLabel name = new JLabel(UITheme.APP_NAME);
        name.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 24));
        name.setForeground(UITheme.TEXT_ON_DARK);
        JLabel tagline = new JLabel(UITheme.APP_TAGLINE);
        tagline.setFont(UITheme.FONT_SMALL);
        tagline.setForeground(new Color(0xB8, 0xCC, 0xE0));
        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        name.setAlignmentX(Component.CENTER_ALIGNMENT);
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
        stack.add(name);
        stack.add(Box.createVerticalStrut(4));
        stack.add(tagline);
        header.add(stack);
        return header;
    }

    private JTabbedPane buildTabs() {
        tabs = new JTabbedPane();
        tabs.setFont(UITheme.FONT_BUTTON);
        tabs.setBackground(UITheme.CARD);
        tabs.addTab("Card + PIN", buildLoginCard());
        tabs.addChangeListener(e -> {
            if (loginStatus != null) loginStatus.setText(" ");
            if (recoveryStatus != null) recoveryStatus.setText(" ");
        });
        return tabs;
    }

    private JPanel buildLoginCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.insets = new Insets(6, 0, 6, 0);

        gc.gridy = 0;
        card.add(UIUtils.secondaryLabel("Sign in with your 12-digit card number and PIN."), gc);
        gc.gridy = 1;
        gc.insets = new Insets(2, 0, 12, 0);
        card.add(new JSeparator(), gc);
        gc.insets = new Insets(6, 0, 6, 0);

        gc.gridy = 2;
        card.add(UIUtils.bodyLabel("Card Number (12 digits)"), gc);
        gc.gridy = 3;
        cardField = UIUtils.styledField(22);
        cardField.setFont(UITheme.FONT_MONO);
        cardField.setDocument(new FixedLengthDocument(12));
        cardField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { onCardChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { onCardChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onCardChanged(); }
        });
        cardField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) pinField.requestFocus();
            }
        });
        card.add(cardField, gc);

        gc.gridy = 4;
        card.add(UIUtils.bodyLabel("PIN"), gc);
        gc.gridy = 5;
        pinField = UIUtils.styledPasswordField(22);
        pinField.setDocument(new FixedLengthDocument(6));
        pinField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) handleLogin();
            }
        });
        card.add(pinField, gc);

        gc.gridy = 6;
        gc.insets = new Insets(8, 0, 2, 0);
        loginStatus = makeStatusLabel();
        card.add(loginStatus, gc);
        gc.gridy = 7;
        gc.insets = new Insets(0, 0, 4, 0);
        attemptLabel = makeAttemptLabel();
        card.add(attemptLabel, gc);
        gc.gridy = 8;
        gc.insets = new Insets(6, 0, 4, 0);
        loginButton = UIUtils.primaryButton("Sign In");
        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, UITheme.BUTTON_HEIGHT));
        loginButton.addActionListener(e -> handleLogin());
        card.add(loginButton, gc);
        return card;
    }

    private JPanel buildRecoveryCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.insets = new Insets(5, 0, 5, 0);

        gc.gridy = 0;
        card.add(UIUtils.secondaryLabel("Verify all three registered details to reset a PIN locked after three failed attempts."), gc);
        gc.gridy = 1;
        gc.insets = new Insets(2, 0, 10, 0);
        card.add(new JSeparator(), gc);
        gc.insets = new Insets(5, 0, 5, 0);

        gc.gridy = 2; card.add(UIUtils.bodyLabel("Card Number (12 digits)"), gc);
        gc.gridy = 3;
        recoveryCardField = UIUtils.styledField(22);
        recoveryCardField.setFont(UITheme.FONT_MONO);
        recoveryCardField.setDocument(new FixedLengthDocument(12));
        card.add(recoveryCardField, gc);

        gc.gridy = 4; card.add(UIUtils.bodyLabel("National ID"), gc);
        gc.gridy = 5;
        nationalIdField = UIUtils.styledField(22);
        card.add(nationalIdField, gc);

        gc.gridy = 6; card.add(UIUtils.bodyLabel("Registered Phone Number"), gc);
        gc.gridy = 7;
        registeredPhoneField = UIUtils.styledField(22);
        card.add(registeredPhoneField, gc);

        gc.gridy = 8; card.add(UIUtils.bodyLabel("New PIN (4–6 digits)"), gc);
        gc.gridy = 9;
        newPinField = UIUtils.styledPasswordField(22);
        newPinField.setDocument(new FixedLengthDocument(6));
        card.add(newPinField, gc);

        gc.gridy = 10; card.add(UIUtils.bodyLabel("Confirm New PIN"), gc);
        gc.gridy = 11;
        confirmPinField = UIUtils.styledPasswordField(22);
        confirmPinField.setDocument(new FixedLengthDocument(6));
        card.add(confirmPinField, gc);

        gc.gridy = 12;
        gc.insets = new Insets(8, 0, 2, 0);
        recoveryStatus = makeStatusLabel();
        card.add(recoveryStatus, gc);

        gc.gridy = 13;
        gc.insets = new Insets(6, 0, 4, 0);
        recoverButton = UIUtils.primaryButton("Verify Details & Reset PIN");
        recoverButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, UITheme.BUTTON_HEIGHT));
        recoverButton.addActionListener(e -> handleRecovery());
        card.add(recoverButton, gc);
        return card;
    }

    private void revealRecoveryTab(String cardNumber) {
        lockedCardNumber = cardNumber;
        if (recoveryPanel == null) recoveryPanel = buildRecoveryCard();
        if (tabs.indexOfComponent(recoveryPanel) < 0) tabs.addTab("Recover PIN", recoveryPanel);
        recoveryCardField.setText(cardNumber);
        nationalIdField.setText("");
        registeredPhoneField.setText("");
        newPinField.setText("");
        confirmPinField.setText("");
        recoveryStatus.setText(" ");
        tabs.setSelectedComponent(recoveryPanel);
        setMinimumSize(new Dimension(500, 760));
        pack();
    }

    private void removeRecoveryTab() {
        if (recoveryPanel != null && tabs.indexOfComponent(recoveryPanel) >= 0) {
            tabs.remove(recoveryPanel);
        }
    }

    private void onCardChanged() {
        String current = cardField.getText().trim();
        if (cardLocked && !current.equals(lockedCardNumber)) {
            cardLocked = false;
            pinField.setEnabled(true);
            loginButton.setEnabled(true);
            loginButton.setBackground(UITheme.PRIMARY);
            loginButton.setText("Sign In");
            pinField.setText("");
            loginStatus.setText(" ");
            attemptLabel.setText(" ");
            removeRecoveryTab();
            setMinimumSize(new Dimension(480, 620));
            pack();
        }
    }

    private void handleLogin() {
        if (authenticationBusy || !loginButton.isEnabled()) return;
        String card = cardField.getText().trim();
        String pin = new String(pinField.getPassword()).trim();
        if (!card.matches("\\d{12}")) {
            setStatus(loginStatus, "Enter a valid 12-digit card number.");
            cardField.requestFocus();
            return;
        }
        if (!pin.matches("\\d{4,6}")) {
            setStatus(loginStatus, "PIN must contain 4 to 6 digits.");
            pinField.requestFocus();
            return;
        }
        setBusy(true, loginButton, cardField, pinField);
        setStatus(loginStatus, " ");
        record LoginResult(CustomerPrincipal principal, Account account) {}
        new SwingWorker<LoginResult, Void>() {
            @Override protected LoginResult doInBackground() {
                CustomerPrincipal principal = customerSessionService.authenticate(card, pin);
                try {
                    return new LoginResult(principal, customerSessionService.requireActiveAccount(principal));
                } catch (RuntimeException ex) {
                    customerSessionService.logout(principal);
                    throw ex;
                }
            }
            @Override protected void done() {
                setBusy(false, loginButton, cardField, pinField);
                try {
                    LoginResult result = get();
                    CustomerPrincipal principal = result.principal();
                    Account account = result.account();
                    cardLocked = false;
                    removeRecoveryTab();
                    clearAttemptLabel();
                    openDashboard(account, principal);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    pinField.setText("");
                    pinField.requestFocus();
                    if (cause instanceof CustomerBannedException cbe) {
                        cardLocked = true;
                        pinField.setEnabled(false);
                        loginButton.setEnabled(false);
                        loginButton.setBackground(UITheme.DANGER);
                        loginButton.setText("Blocked");
                        String reason = cbe.getReason() == null || cbe.getReason().isBlank() ? "" : " Reason: " + cbe.getReason();
                        setStatus(loginStatus, "Access is blocked by bank administration." + reason);
                        showLockoutDialog(false, true);
                    } else if (cause instanceof AccountAutoLockedException) {
                        cardLocked = true;
                        lockedCardNumber = card;
                        pinField.setEnabled(false);
                        loginButton.setEnabled(false);
                        loginButton.setBackground(UITheme.DANGER);
                        loginButton.setText("Locked");
                        setStatus(loginStatus, "Locked after three incorrect PIN attempts. Use the Recover PIN tab.");
                        revealRecoveryTab(card);
                        showLockoutDialog(true, false);
                    } else if (cause instanceof AdminLockedException || cause instanceof AccountLockedException) {
                        cardLocked = true;
                        removeRecoveryTab();
                        pinField.setEnabled(false);
                        loginButton.setEnabled(false);
                        loginButton.setBackground(UITheme.DANGER);
                        loginButton.setText("Locked");
                        setStatus(loginStatus, "This account was locked by bank administration. Contact support.");
                        showLockoutDialog(false, false);
                    } else if (cause instanceof InvalidPinWithAttemptsException attempts) {
                        setStatus(loginStatus, "Incorrect PIN.");
                        updateAttemptLabel(attempts);
                        if (attempts.isLastAttempt()) {
                            loginButton.setBackground(UITheme.DANGER);
                            loginButton.setText("Sign In — Last Try");
                        }
                    } else if (cause instanceof InvalidPinException) {
                        setStatus(loginStatus, "Incorrect PIN. Please try again.");
                    } else if (cause instanceof BankException) {
                        setStatus(loginStatus, cause.getMessage());
                    } else {
                        setStatus(loginStatus, "Connection error. Please try again.");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    customerSessionService.context().invalidateCurrent();
                    setStatus(loginStatus, "Sign-in interrupted.");
                } catch (RuntimeException ex) {
                    customerSessionService.context().invalidateCurrent();
                    pinField.setText("");
                    setStatus(loginStatus, "The customer session could not be established. Please sign in again.");
                }
            }
        }.execute();
    }

    private void handleRecovery() {
        if (authenticationBusy || !recoverButton.isEnabled()) return;
        String card = recoveryCardField.getText().trim();
        String nationalId = nationalIdField.getText().trim();
        String phone = registeredPhoneField.getText().trim();
        String newPin = new String(newPinField.getPassword());
        String confirm = new String(confirmPinField.getPassword());
        if (!card.matches("\\d{12}")) { setStatus(recoveryStatus, "Enter a valid 12-digit card number."); recoveryCardField.requestFocus(); return; }
        if (nationalId.isBlank() || phone.isBlank()) { setStatus(recoveryStatus, "National ID and registered phone are required."); return; }
        if (!newPin.matches("\\d{4,6}")) { setStatus(recoveryStatus, "New PIN must contain 4 to 6 digits."); newPinField.requestFocus(); return; }
        if (!newPin.equals(confirm)) { setStatus(recoveryStatus, "The new PIN entries do not match."); confirmPinField.setText(""); confirmPinField.requestFocus(); return; }

        setBusy(true, recoverButton, recoveryCardField, nationalIdField, registeredPhoneField, newPinField, confirmPinField);
        setStatus(recoveryStatus, "Verifying registered details...");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() { accountService.recoverPin(card, nationalId, phone, newPin); return null; }
            @Override protected void done() {
                setBusy(false, recoverButton, recoveryCardField, nationalIdField, registeredPhoneField, newPinField, confirmPinField);
                try {
                    get();
                    pinField.setText("");
                    cardField.setText(card);
                    cardLocked = false;
                    lockedCardNumber = null;
                    pinField.setEnabled(true);
                    loginButton.setEnabled(true);
                    loginButton.setBackground(UITheme.PRIMARY);
                    loginButton.setText("Sign In");
                    removeRecoveryTab();
                    tabs.setSelectedIndex(0);
                    setMinimumSize(new Dimension(480, 620));
                    pack();
                    UIUtils.showSuccess(LoginFrame.this, "Your PIN has been reset and the failed-PIN lock has been cleared. Sign in using your new PIN.");
                    pinField.requestFocus();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    setStatus(recoveryStatus, cause instanceof BankException ? cause.getMessage() : "Recovery failed. Please try again.");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setStatus(recoveryStatus, "Recovery was interrupted.");
                }
            }
        }.execute();
    }

    private void showLockoutDialog(boolean autoLocked, boolean banned) {
        String title = banned ? "Access Blocked" : autoLocked ? "Account Locked" : "Account Locked by Administrator";
        String message;
        if (banned) {
            message = "Your access has been blocked by bank administration. Please contact the support team.";
        } else if (autoLocked) {
            message = "Your account was automatically locked after three consecutive incorrect PIN attempts.\n\n"
                    + "The Recover PIN tab is now available. Enter your national ID, card number, and registered phone number, then choose a new PIN.\n\n"
                    + "Five failed recovery checks trigger a 15-minute recovery cooldown.";
        } else {
            message = "Your account was locked by bank administration. Self-service PIN recovery cannot remove an administrator lock. Please contact support.";
        }
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private void openDashboard(Account account, CustomerPrincipal principal) {
        DashboardFrame dashboard = new DashboardFrame(account, principal);
        dashboard.setVisible(true);
        dispose();
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 10));
        footer.setBackground(UITheme.SURFACE);
        footer.add(UIUtils.secondaryLabel("Need help?"));
        JButton contact = linkButton("Contact Support", UITheme.ACCENT);
        contact.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Please contact your bank administrator for assistance.", "SecureBank Support", JOptionPane.INFORMATION_MESSAGE));
        footer.add(contact);
        footer.add(UIUtils.secondaryLabel("New customer?"));
        JButton register = linkButton("Open an Account", UITheme.PRIMARY);
        register.addActionListener(e -> {
            if (authenticationBusy) return;
            RegisterFrame frame = new RegisterFrame();
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) {
                    if (LoginFrame.this.isDisplayable()) {
                        pinField.setText("");
                        LoginFrame.this.setVisible(true);
                        LoginFrame.this.toFront();
                    }
                }
            });
            setVisible(false);
            frame.setVisible(true);
        });
        footer.add(register);
        footer.add(UIUtils.secondaryLabel("© 2026 " + UITheme.APP_NAME + ". All rights reserved."));
        return footer;
    }

    private JButton linkButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setFont(UITheme.FONT_SMALL);
        button.setForeground(color);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JLabel makeStatusLabel() {
        JLabel label = new JLabel(" ");
        label.setFont(UITheme.FONT_SMALL);
        label.setForeground(UITheme.DANGER);
        return label;
    }

    private JLabel makeAttemptLabel() {
        JLabel label = new JLabel(" ");
        label.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 11));
        label.setForeground(UITheme.WARNING);
        return label;
    }

    private void updateAttemptLabel(InvalidPinWithAttemptsException ex) {
        StringBuilder dots = new StringBuilder();
        for (int i = 1; i <= ex.getMaxAttempts(); i++) {
            dots.append(i <= ex.getAttemptsMade() ? "●" : "○");
            if (i < ex.getMaxAttempts()) dots.append(" ");
        }
        attemptLabel.setText(dots + "  Attempt " + ex.getAttemptsMade() + " of " + ex.getMaxAttempts()
                + " — " + ex.getAttemptsLeft() + " left");
        attemptLabel.setForeground(ex.isLastAttempt() ? UITheme.DANGER : UITheme.WARNING);
    }

    private void clearAttemptLabel() {
        if (attemptLabel != null) attemptLabel.setText(" ");
        if (loginButton != null) { loginButton.setBackground(UITheme.PRIMARY); loginButton.setText("Sign In"); }
    }

    private void setStatus(JLabel label, String message) { if (label != null) label.setText(message == null ? " " : message); }

    private void setBusy(boolean busy, JButton button, JComponent... fields) {
        authenticationBusy = busy;
        button.setEnabled(!busy);
        for (JComponent field : fields) field.setEnabled(!busy);
    }

    private static final class FixedLengthDocument extends PlainDocument {
        private static final long serialVersionUID = 1L;
        private final int maxLength;
        FixedLengthDocument(int maxLength) { this.maxLength = maxLength; }
        @Override public void insertString(int offset, String text, AttributeSet attr) throws BadLocationException {
            if (text != null && getLength() + text.length() <= maxLength) super.insertString(offset, text, attr);
        }
    }
}
