package com.bank.view;

import com.bank.exception.BankException;
import com.bank.exception.InsufficientFundsException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.model.Transaction;
import com.bank.model.Transfer;
import com.bank.service.AccountService;
import com.bank.service.CustomerService;
import com.bank.service.ServiceFactory;
import com.bank.service.TransferService;
import com.bank.service.MessageService;
import com.bank.security.CustomerPrincipal;
import com.bank.security.CustomerSessionService;
import com.bank.view.dialog.AmountDialog;
import com.bank.view.dialog.TransactionHistoryDialog;
import com.bank.view.dialog.TransferDialog;
import com.bank.view.dialog.CustomerInboxDialog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * DashboardFrame — customer online-banking dashboard (English, LTR).
 */
public class DashboardFrame extends JFrame {

    private final AccountService  accountService;
    private final CustomerService customerService;
    private final TransferService transferService;
    private final MessageService messageService;
    private final CustomerSessionService customerSessionService;
    private final CustomerPrincipal principal;

    private Account  account;
    private Customer customer;

    private JLabel welcomeLabel;
    private JLabel balanceLabel;
    private JLabel accountTypeLabel;
    private JLabel accountNumberLabel;
    private JLabel accountStatusLabel;

    private JButton depositButton;
    private JButton withdrawButton;
    private JButton transferButton;
    private JButton inboxButton, openAccountButton;
    private boolean moneyMovementInProgress;

    public DashboardFrame(Account account, CustomerPrincipal principal) {
        this.account         = account;
        this.principal       = java.util.Objects.requireNonNull(principal, "principal");
        this.accountService  = ServiceFactory.getInstance().getAccountService();
        this.customerService = ServiceFactory.getInstance().getCustomerService();
        this.transferService = ServiceFactory.getInstance().getTransferService();
        this.messageService = ServiceFactory.getInstance().getMessageService();
        this.customerSessionService = ServiceFactory.getInstance().getCustomerSessionService();
        initComponents();
        UIUtils.applyLTR(this);
        refreshBalance();
        refreshInboxUnreadCount();
        new SwingWorker<Customer, Void>() {
            @Override protected Customer doInBackground() {
                customerSessionService.requireActiveAccount(principal);
                return customerService.findCustomerById(principal);
            }
            @Override protected void done() {
                if (!isDisplayable()) return;
                try { customer = get(); if (customer != null) welcomeLabel.setText("Welcome back, " + customer.getFirstName()); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (ExecutionException ex) { /* generic welcome remains; balance refresh reports errors */ }
            }
        }.execute();
    }

    private void initComponents() {
        setTitle(UITheme.APP_NAME + " — Dashboard");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                handleApplicationExit();
            }
        });

        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(UITheme.SURFACE);

        add(buildHeader(),      BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildFooter(),      BorderLayout.SOUTH);

        setMinimumSize(new Dimension(700, 500));
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(UITheme.PRIMARY);
        header.setBorder(new EmptyBorder(18, 24, 18, 24));

        JPanel leftSide = new JPanel();
        leftSide.setOpaque(false);
        leftSide.setLayout(new BoxLayout(leftSide, BoxLayout.Y_AXIS));

        String firstName = (customer != null) ? customer.getFirstName() : "Customer";
        welcomeLabel = new JLabel("Welcome back, " + firstName);
        welcomeLabel.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 18));
        welcomeLabel.setForeground(UITheme.TEXT_ON_DARK);
        welcomeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel balanceCaption = new JLabel("Available Balance");
        balanceCaption.setFont(UITheme.FONT_SMALL);
        balanceCaption.setForeground(new Color(0xB8, 0xCC, 0xE0));
        balanceCaption.setAlignmentX(Component.LEFT_ALIGNMENT);

        balanceLabel = new JLabel("Loading...");
        balanceLabel.setFont(UITheme.FONT_BALANCE);
        balanceLabel.setForeground(Color.WHITE);
        balanceLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        leftSide.add(welcomeLabel);
        leftSide.add(Box.createVerticalStrut(8));
        leftSide.add(balanceCaption);
        leftSide.add(balanceLabel);

        JLabel logo = new JLabel(UITheme.APP_NAME);
        logo.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 15));
        logo.setForeground(new Color(0xB8, 0xCC, 0xE0));
        logo.setHorizontalAlignment(SwingConstants.RIGHT);

        header.add(leftSide, BorderLayout.LINE_START);
        header.add(logo,     BorderLayout.LINE_END);
        return header;
    }

    private JPanel buildCenterPanel() {
        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(UITheme.SURFACE);
        center.setBorder(new EmptyBorder(20, 24, 10, 24));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill   = GridBagConstraints.BOTH;
        gc.weighty = 1.0;
        gc.insets = new Insets(0, 0, 0, 12);

        gc.gridx   = 0;
        gc.weightx = 0.32;
        center.add(buildAccountSummaryCard(), gc);

        gc.gridx   = 1;
        gc.weightx = 0.68;
        gc.insets  = new Insets(0, 0, 0, 0);
        center.add(buildActionCard(), gc);

        return center;
    }

    private JPanel buildAccountSummaryCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel title = UIUtils.subheading("Account Summary");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalStrut(14));
        card.add(makeSeparator());
        card.add(Box.createVerticalStrut(12));

        String typeEn = account.getAccountType().name().equals("SAVINGS") ? "Savings" : "Checking";
        accountTypeLabel = summaryValueLabel(typeEn);
        card.add(summaryRowPanel("Account Type", accountTypeLabel));
        card.add(Box.createVerticalStrut(10));

        String masked = maskAccountNumber(account.getAccountNumber());
        accountNumberLabel = summaryValueLabel(masked);
        accountNumberLabel.setFont(UITheme.FONT_MONO);
        card.add(summaryRowPanel("Account Number", accountNumberLabel));
        card.add(Box.createVerticalStrut(10));

        boolean isActive = account.isActive();
        accountStatusLabel = new JLabel(isActive ? "\u25CF Active" : "\u25CF Locked");
        accountStatusLabel.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 12));
        accountStatusLabel.setForeground(isActive ? UITheme.ACCENT : UITheme.DANGER);
        card.add(summaryRowPanel("Status", accountStatusLabel));

        card.add(Box.createVerticalGlue());
        return card;
    }

    private JPanel buildActionCard() {
        JPanel card = UIUtils.cardPanel();
        card.setLayout(new GridBagLayout());

        JLabel title = UIUtils.subheading("Banking Operations");
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = 0;
        gc.gridwidth = 2;
        gc.fill  = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 10, 0);
        card.add(title, gc);

        gc.gridy = 1;
        gc.insets = new Insets(0, 0, 14, 0);
        card.add(makeSeparator(), gc);

        depositButton  = UIUtils.successButton("Deposit");
        withdrawButton = UIUtils.dangerButton("Withdraw");
        transferButton = UIUtils.primaryButton("Transfer");
        JButton historyBtn  = UIUtils.ghostButton("Transaction History");
        JButton pinBtn      = UIUtils.warningButton("Change PIN");
        inboxButton         = UIUtils.ghostButton("Inbox");
        openAccountButton = UIUtils.ghostButton("Open Additional Account");

        depositButton.addActionListener(e  -> handleDeposit());
        withdrawButton.addActionListener(e -> handleWithdraw());
        transferButton.addActionListener(e -> handleTransfer());
        historyBtn.addActionListener(e  -> handleTransactionHistory());
        pinBtn.addActionListener(e      -> handleChangePin());
        inboxButton.addActionListener(e -> handleInbox());
        openAccountButton.addActionListener(e -> handleOpenAccount());

        Dimension btnSize = new Dimension(Integer.MAX_VALUE, 44);
        for (JButton b : new JButton[]{depositButton, withdrawButton, transferButton, historyBtn, pinBtn, inboxButton, openAccountButton}) {
            b.setMaximumSize(btnSize);
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setBorder(BorderFactory.createCompoundBorder(
                    b.getBorder(),
                    new EmptyBorder(0, 8, 0, 0)));
        }

        gc.gridwidth = 1;
        gc.weightx   = 0.5;
        gc.insets    = new Insets(5, 0, 5, 6);

        gc.gridy = 2; gc.gridx = 0; card.add(depositButton,  gc);
        gc.gridx = 1; gc.insets = new Insets(5, 6, 5, 0); card.add(withdrawButton, gc);

        gc.gridy = 3; gc.gridx = 0; gc.insets = new Insets(5, 0, 5, 6); card.add(transferButton, gc);
        gc.gridx = 1; gc.insets = new Insets(5, 6, 5, 0); card.add(historyBtn,  gc);

        gc.gridy = 4; gc.gridx = 0; gc.insets = new Insets(5, 0, 5, 6);
        card.add(pinBtn, gc);
        gc.gridx = 1; gc.insets = new Insets(5, 6, 5, 0); card.add(openAccountButton, gc);

        gc.gridy = 5; gc.gridx = 0; gc.gridwidth = 2;
        gc.weighty = 0; gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(5, 0, 5, 0);
        card.add(inboxButton, gc);

        gc.gridy = 6; gc.weighty = 1.0; gc.fill = GridBagConstraints.BOTH;
        gc.insets = new Insets(0, 0, 0, 0);
        card.add(Box.createVerticalGlue(), gc);

        return card;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(UITheme.SURFACE);
        footer.setBorder(new EmptyBorder(8, 24, 16, 24));

        JButton logoutBtn = UIUtils.ghostButton("Sign Out");
        logoutBtn.addActionListener(e -> handleLogout());

        footer.add(logoutBtn, BorderLayout.LINE_START);

        JLabel hint = UIUtils.secondaryLabel("All transactions are encrypted and logged.");
        footer.add(hint, BorderLayout.LINE_END);

        return footer;
    }

    private void refreshBalance() {
        new SwingWorker<BigDecimal, Void>() {
            @Override
            protected BigDecimal doInBackground() {
                customerSessionService.requireActiveAccount(principal);
                return accountService.getAccountBalance(principal, account.getAccountId());
            }
            @Override
            protected void done() {
                try {
                    BigDecimal balance = get();
                    balanceLabel.setText(DateUtil.formatMoney(balance));
                } catch (InterruptedException | ExecutionException ex) {
                    balanceLabel.setText("Error loading balance");
                }
            }
        }.execute();
    }

    private void handleDeposit() {
        AmountDialog dlg = new AmountDialog(this, "Deposit", "Enter the amount to deposit (" + DateUtil.CURRENCY + "):");
        BigDecimal amount = dlg.showAndGetAmount();
        if (amount == null) return;

        boolean ok = UIUtils.confirmDialog(this,
                String.format("Deposit %s to your account?", DateUtil.formatMoney(amount)));
        if (!ok || !beginMoneyMovement()) return;

        final String requestIdentity = com.bank.util.IdempotencyUtil.fingerprint(
                "DEPOSIT", Long.toString(account.getAccountId()),
                com.bank.util.IdempotencyUtil.money(amount));
        final String idempotencyKey = com.bank.util.IdempotencyUtil.getOrCreateClientKey(requestIdentity);

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                customerSessionService.requireActiveAccount(principal);
                accountService.deposit(principal, account.getAccountId(), amount, idempotencyKey);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    com.bank.util.IdempotencyUtil.markClientRequestCompleted(requestIdentity);
                    refreshBalance();
                    UIUtils.showSuccess(DashboardFrame.this,
                            String.format("%s was deposited successfully.",
                                    DateUtil.formatMoney(amount)));
                } catch (ExecutionException ex) {
                    handleServiceException(ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    UIUtils.showError(DashboardFrame.this, "The deposit was interrupted.");
                } finally {
                    endMoneyMovement();
                }
            }
        }.execute();
    }

    private void handleWithdraw() {
        AmountDialog dlg = new AmountDialog(this, "Withdraw", "Enter the amount to withdraw (" + DateUtil.CURRENCY + "):");
        BigDecimal amount = dlg.showAndGetAmount();
        if (amount == null) return;

        boolean ok = UIUtils.confirmDialog(this,
                String.format("Withdraw %s from your account?", DateUtil.formatMoney(amount)));
        if (!ok || !beginMoneyMovement()) return;

        final String requestIdentity = com.bank.util.IdempotencyUtil.fingerprint(
                "WITHDRAW", Long.toString(account.getAccountId()),
                com.bank.util.IdempotencyUtil.money(amount));
        final String idempotencyKey = com.bank.util.IdempotencyUtil.getOrCreateClientKey(requestIdentity);

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                customerSessionService.requireActiveAccount(principal);
                accountService.withdraw(principal, account.getAccountId(), amount, idempotencyKey);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    com.bank.util.IdempotencyUtil.markClientRequestCompleted(requestIdentity);
                    refreshBalance();
                    UIUtils.showSuccess(DashboardFrame.this,
                            String.format("%s was withdrawn successfully.",
                                    DateUtil.formatMoney(amount)));
                } catch (ExecutionException ex) {
                    handleServiceException(ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    UIUtils.showError(DashboardFrame.this, "The withdrawal was interrupted.");
                } finally {
                    endMoneyMovement();
                }
            }
        }.execute();
    }

    private void handleTransfer() {
        TransferDialog dlg = new TransferDialog(this);
        if (!dlg.showAndGet()) return;

        String identifier = dlg.getRecipientIdentifier();
        BigDecimal amount = dlg.getAmount();
        TransferDialog.LookupMode mode = dlg.getLookupMode();

        String modeLabel = (mode == TransferDialog.LookupMode.CARD_NUMBER) ? "Card Number" : "Account Number";
        boolean ok = UIUtils.confirmDialog(this,
                String.format(
                    "Confirm Transfer\n\n" +
                    "Recipient : %s  %s\n" +
                    "Amount    : %s\n\n" +
                    "This operation cannot be undone.",
                    modeLabel, identifier, DateUtil.formatMoney(amount)));
        if (!ok || !beginMoneyMovement()) return;

        new SwingWorker<Transfer, Void>() {
            private String requestIdentity;
            @Override protected Transfer doInBackground() throws Exception {
                customerSessionService.requireActiveAccount(principal);
                long receiverAccountId = mode == TransferDialog.LookupMode.CARD_NUMBER
                    ? transferService.resolveRecipientAccountIdByCardNumber(principal, identifier)
                    : transferService.resolveRecipientAccountIdByAccountNumber(principal, identifier);
                if (receiverAccountId == account.getAccountId()) throw new BankException("You cannot transfer to your own account.");
                requestIdentity = com.bank.util.IdempotencyUtil.fingerprint(
                    "TRANSFER", Long.toString(account.getAccountId()), Long.toString(receiverAccountId),
                    com.bank.util.IdempotencyUtil.money(amount));
                String idempotencyKey = com.bank.util.IdempotencyUtil.getOrCreateClientKey(requestIdentity);
                return transferService.processTransfer(principal, account.getAccountId(), receiverAccountId, amount, idempotencyKey);
            }
            @Override protected void done() {
                try {
                    Transfer transfer = get();
                    com.bank.util.IdempotencyUtil.markClientRequestCompleted(requestIdentity);
                    refreshBalance();
                    String modeStr = (mode == TransferDialog.LookupMode.CARD_NUMBER) ? "Card Number" : "Account Number";
                    UIUtils.showSuccess(DashboardFrame.this,
                            String.format(
                                "Transfer completed successfully!\n\n" +
                                "Recipient   :  %s  %s\n" +
                                "Amount      :  %s\n" +
                                "Reference # :  %s\n\n" +
                                "Your balance has been updated.",
                                modeStr, identifier, DateUtil.formatMoney(amount),
                                transfer.getReferenceNumber()));
                } catch (ExecutionException ex) {
                    handleServiceException(ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    UIUtils.showError(DashboardFrame.this, "The transfer was interrupted.");
                } finally {
                    endMoneyMovement();
                }
            }
        }.execute();
    }

    private void handleOpenAccount() {
        if (moneyMovementInProgress) return;
        JComboBox<Account.AccountType> type = new JComboBox<>(Account.AccountType.values());
        JTextField amountField = new JTextField("0", 14);
        JPasswordField pinField = new JPasswordField(14);
        JPasswordField confirmField = new JPasswordField(14);
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Account type")); form.add(type);
        form.add(new JLabel("Initial simulated deposit")); form.add(amountField);
        form.add(new JLabel("New PIN (4–6 digits)")); form.add(pinField);
        form.add(new JLabel("Confirm PIN")); form.add(confirmField);
        int answer = JOptionPane.showConfirmDialog(this, form, "Open Additional Account", JOptionPane.OK_CANCEL_OPTION);
        char[] pinChars = pinField.getPassword(), confirmation = confirmField.getPassword();
        String pin;
        try {
            if (answer != JOptionPane.OK_OPTION) return;
            pin = new String(pinChars);
            if (!pin.matches("\\d{4,6}") || !java.util.Arrays.equals(pinChars, confirmation)) {
                UIUtils.showError(this, "Enter matching 4–6 digit PINs."); return;
            }
        } finally {
            java.util.Arrays.fill(pinChars, '\0'); java.util.Arrays.fill(confirmation, '\0');
            pinField.setText(""); confirmField.setText("");
        }
        final BigDecimal amount;
        try { amount = new BigDecimal(amountField.getText().trim()); }
        catch (NumberFormatException ex) { UIUtils.showError(this, "Enter a valid amount."); return; }
        Account.AccountType accountType = (Account.AccountType) type.getSelectedItem();
        if (!beginMoneyMovement()) return;
        new SwingWorker<Account, Void>() {
            @Override protected Account doInBackground() {
                return accountService.openAccount(principal, accountType, pin, amount);
            }
            @Override protected void done() {
                try {
                    Account opened = get();
                    UIUtils.showSuccess(DashboardFrame.this, "Account opened. Save these details and sign in with its card and PIN to use it.\nAccount: " + opened.getAccountNumber() + "\nCard: " + opened.getCardNumber());
                } catch (ExecutionException ex) { handleServiceException(ex.getCause()); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); UIUtils.showError(DashboardFrame.this, "Account opening interrupted. Verify the account before retrying."); }
                finally { endMoneyMovement(); }
            }
        }.execute();
    }

    private boolean beginMoneyMovement() {
        if (moneyMovementInProgress) return false;
        moneyMovementInProgress = true;
        depositButton.setEnabled(false);
        withdrawButton.setEnabled(false);
        transferButton.setEnabled(false);
        openAccountButton.setEnabled(false);
        return true;
    }

    private void endMoneyMovement() {
        moneyMovementInProgress = false;
        depositButton.setEnabled(true);
        withdrawButton.setEnabled(true);
        transferButton.setEnabled(true);
        openAccountButton.setEnabled(true);
    }

    private void handleTransactionHistory() {
        new SwingWorker<List<Transaction>, Void>() {
            @Override protected List<Transaction> doInBackground() {
                customerSessionService.requireActiveAccount(principal);
                return accountService.getTransactionHistory(principal, account.getAccountId());
            }
            @Override protected void done() {
                try {
                    List<Transaction> history = get();
                    TransactionHistoryDialog dlg = new TransactionHistoryDialog(DashboardFrame.this, history);
                    dlg.setVisible(true);
                } catch (ExecutionException ex) {
                    handleServiceException(ex.getCause());
                } catch (InterruptedException ex) {
                    UIUtils.showError(DashboardFrame.this, "Could not load the transaction history.");
                }
            }
        }.execute();
    }

    private void handleInbox() {
        CustomerInboxDialog dialog = new CustomerInboxDialog(this, messageService, customerSessionService, principal);
        dialog.setVisible(true);
        refreshInboxUnreadCount();
    }

    private void refreshInboxUnreadCount() {
        if (inboxButton == null) return;
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() {
                customerSessionService.requireActiveAccount(principal);
                return messageService.getUnreadCountForCustomer(principal);
            }
            @Override protected void done() {
                try {
                    int unread = get();
                    inboxButton.setText(unread > 0 ? "Inbox (" + unread + " unread)" : "Inbox");
                } catch (Exception ex) {
                    inboxButton.setText("Inbox");
                }
            }
        }.execute();
    }

    private void handleChangePin() {
        JPasswordField currentPinFld = UIUtils.styledPasswordField(12);
        JPasswordField newPinFld     = UIUtils.styledPasswordField(12);
        JPasswordField confirmPinFld = UIUtils.styledPasswordField(12);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(UITheme.CARD);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(5, 6, 5, 6);

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0.4;
        form.add(UIUtils.bodyLabel("Current PIN:"), gc);
        gc.gridx = 1; gc.weightx = 0.6;
        form.add(currentPinFld, gc);

        gc.gridx = 0; gc.gridy = 1;
        form.add(UIUtils.bodyLabel("New PIN (4\u20136 digits):"), gc);
        gc.gridx = 1;
        form.add(newPinFld, gc);

        gc.gridx = 0; gc.gridy = 2;
        form.add(UIUtils.bodyLabel("Confirm new PIN:"), gc);
        gc.gridx = 1;
        form.add(confirmPinFld, gc);

        int result = JOptionPane.showConfirmDialog(
                this, form, "Change PIN",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        String currentPin = new String(currentPinFld.getPassword());
        String newPin     = new String(newPinFld.getPassword());
        String confirmPin = new String(confirmPinFld.getPassword());

        if (!newPin.equals(confirmPin)) {
            UIUtils.showError(this, "The new PIN and its confirmation do not match.");
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                customerSessionService.requireActiveAccount(principal);
                accountService.changePin(principal, account.getAccountId(), currentPin, newPin);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UIUtils.showSuccess(DashboardFrame.this, "Your PIN was changed successfully.");
                } catch (ExecutionException ex) {
                    handleServiceException(ex.getCause());
                } catch (InterruptedException ex) {
                    UIUtils.showError(DashboardFrame.this, "The PIN change was interrupted.");
                }
            }
        }.execute();
    }

    private void handleLogout() {
        if (moneyMovementInProgress) { UIUtils.showError(this, "Wait for the current banking operation to finish."); return; }
        boolean confirmed = UIUtils.confirmDialog(this, "Are you sure you want to sign out?");
        if (!confirmed) return;

        customerSessionService.logout(principal);
        LoginFrame login = new LoginFrame();
        login.setVisible(true);
        dispose();
    }

    private void handleApplicationExit() {
        if (moneyMovementInProgress) { UIUtils.showError(this, "Wait for the current banking operation to finish."); return; }
        boolean confirmed = UIUtils.confirmDialog(this, "Close SecureBank and end your customer session?");
        if (!confirmed) return;
        customerSessionService.logout(principal);
        dispose();
        System.exit(0);
    }

    private void handleServiceException(Throwable cause) {
        if (cause instanceof com.bank.exception.AccountAutoLockedException) {
            UIUtils.showError(this,
                    "This account was locked after three failed PIN attempts. Sign out and use the Recover PIN tab on the login screen.");
        } else if (cause instanceof InsufficientFundsException) {
            InsufficientFundsException ife = (InsufficientFundsException) cause;
            UIUtils.showError(this,
                    String.format("Insufficient funds.\n\nAvailable  : %s\nRequested  : %s",
                            DateUtil.formatMoney(ife.getAvailable().longValue()),
                            DateUtil.formatMoney(ife.getRequested().longValue())));
        } else if (cause instanceof com.bank.exception.AccountLockedException) {
            UIUtils.showError(this, "This account is locked. Please contact support.");
        } else if (cause instanceof com.bank.exception.InvalidPinException) {
            UIUtils.showError(this, "Incorrect PIN. Please try again.");
        } else if (cause instanceof com.bank.exception.CustomerBannedException) {
            UIUtils.showError(this, cause.getMessage());
        } else if (cause instanceof BankException) {
            UIUtils.showError(this, cause.getMessage());
        } else {
            UIUtils.showError(this, "An unexpected error occurred. Please try again.");
        }
    }

    private JLabel summaryValueLabel(String text) {
        JLabel lbl = UIUtils.bodyLabel(text);
        lbl.setForeground(UITheme.TEXT_PRIMARY);
        lbl.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 12));
        return lbl;
    }

    private JPanel summaryRowPanel(String caption, JLabel valueLabel) {
        JPanel row = new JPanel(new GridLayout(2, 1, 0, 2));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel captionLbl = UIUtils.secondaryLabel(caption);
        captionLbl.setFont(new Font(UITheme.FONT_FAMILY, Font.PLAIN, 10));
        row.add(captionLbl);
        row.add(valueLabel);
        return row;
    }

    private JSeparator makeSeparator() {
        JSeparator sep = new JSeparator();
        sep.setForeground(UITheme.BORDER);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        return sep;
    }

    private String maskAccountNumber(String acctNum) {
        if (acctNum == null || acctNum.length() < 8) return acctNum;
        int len  = acctNum.length();
        int show = 4;
        return acctNum.substring(0, show) + "\u25CF\u25CF\u25CF\u25CF" + acctNum.substring(len - show);
    }
}
