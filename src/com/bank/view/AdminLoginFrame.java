package com.bank.view;

import com.bank.admin.AdminCompositionRoot;
import com.bank.security.AdminLockedOutException;
import com.bank.security.AdminPrincipal;
import com.bank.view.admin.AdminFrame;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Optional;

/**
 * AdminLoginFrame — administrator sign-in screen (English, LTR).
 */
public class AdminLoginFrame extends JFrame {

    private JTextField     usernameField;
    private JPasswordField passwordField;
    private JButton        loginButton;
    private JLabel         statusLabel;

    public AdminLoginFrame() {
        initComponents();
        UIUtils.applyLTR(this);
    }

    private void initComponents() {
        setTitle(UITheme.APP_NAME + " — Administrator Sign In");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setLayout(new BorderLayout());
        getContentPane().setBackground(UITheme.SURFACE);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildLoginCard(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(420, 460));
        setLocationRelativeTo(null);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new GridBagLayout());
        header.setBackground(UITheme.PRIMARY_DARK);
        header.setBorder(new EmptyBorder(28, 30, 22, 30));

        JLabel title = new JLabel(UITheme.APP_NAME + " Admin Console");
        title.setFont(UITheme.FONT_HEADING);
        title.setForeground(UITheme.TEXT_ON_DARK);

        JLabel tagLine = new JLabel("Authorized personnel only");
        tagLine.setFont(UITheme.FONT_SMALL);
        tagLine.setForeground(new Color(0x9A, 0xB4, 0xCC));

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        tagLine.setAlignmentX(Component.CENTER_ALIGNMENT);
        inner.add(title);
        inner.add(Box.createVerticalStrut(4));
        inner.add(tagLine);
        header.add(inner);
        return header;
    }

    private JPanel buildLoginCard() {
        JPanel outer = UIUtils.surfacePanel();
        outer.setLayout(new GridBagLayout());

        JPanel card = UIUtils.cardPanel();
        card.setLayout(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridx = 0;
        gc.insets = new Insets(6, 0, 6, 0);

        gc.gridy = 0;
        card.add(UIUtils.secondaryLabel("Sign in with your administrator credentials."), gc);

        gc.gridy = 1;
        gc.insets = new Insets(2, 0, 12, 0);
        card.add(new JSeparator(), gc);
        gc.insets = new Insets(6, 0, 6, 0);

        gc.gridy = 2;
        card.add(UIUtils.bodyLabel("Username"), gc);

        gc.gridy = 3;
        usernameField = UIUtils.styledField(20);
        usernameField.setToolTipText("Enter the administrator username");
        usernameField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) passwordField.requestFocus();
            }
        });
        card.add(usernameField, gc);

        gc.gridy = 4;
        card.add(UIUtils.bodyLabel("Password"), gc);

        gc.gridy = 5;
        passwordField = UIUtils.styledPasswordField(20);
        passwordField.setToolTipText("Enter the administrator password");
        passwordField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) handleAdminLogin();
            }
        });
        card.add(passwordField, gc);

        gc.gridy = 6;
        gc.insets = new Insets(8, 0, 4, 0);
        statusLabel = new JLabel(" ");
        statusLabel.setFont(UITheme.FONT_SMALL);
        statusLabel.setForeground(UITheme.DANGER);
        card.add(statusLabel, gc);

        gc.gridy = 7;
        gc.insets = new Insets(6, 0, 4, 0);
        loginButton = UIUtils.primaryButton("Sign In");
        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, UITheme.BUTTON_HEIGHT));
        loginButton.addActionListener((ActionEvent e) -> handleAdminLogin());
        card.add(loginButton, gc);

        GridBagConstraints outerGc = new GridBagConstraints();
        outerGc.weightx = 1.0;
        outer.add(card, outerGc);
        return outer;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBackground(UITheme.SURFACE);
        footer.setBorder(new EmptyBorder(4, 0, 16, 0));

        JPanel cpRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
        cpRow.setOpaque(false);
        cpRow.add(UIUtils.secondaryLabel("\u00A9 2026 " + UITheme.APP_NAME + ". All rights reserved."));
        footer.add(cpRow);
        return footer;
    }

    private void handleAdminLogin() {
        if (!loginButton.isEnabled()) return;
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (username.isEmpty()) {
            setStatus("Please enter the username.");
            usernameField.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            setStatus("Please enter the password.");
            passwordField.requestFocus();
            return;
        }

        setBusy(true);
        clearStatus();

        new SwingWorker<Optional<AdminPrincipal>, Void>() {
            @Override protected Optional<AdminPrincipal> doInBackground() {
                return AdminCompositionRoot.getInstance().authenticationService().authenticate(username, password);
            }
            @Override protected void done() {
                setBusy(false);
                passwordField.setText("");
                if (!isDisplayable()) return;
                try {
                    Optional<AdminPrincipal> principal = get();
                    if (principal.isPresent()) {
                        clearStatus();
                        AdminFrame frame = new AdminFrame(principal.get());
                        AdminCompositionRoot.getInstance().startFraudOutboxWorker();
                        frame.setVisible(true);
                        dispose();
                    } else {
                        passwordField.requestFocus();
                        setStatus("Incorrect username or password.");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setStatus("Administrator sign-in interrupted.");
                } catch (java.util.concurrent.ExecutionException ex) {
                    if (ex.getCause() instanceof AdminLockedOutException locked) setStatus(locked.getMessage());
                    else setStatus("Administrator authentication unavailable. Check the MySQL connection.");
                } catch (RuntimeException ex) {
                    setStatus("Administrator console could not be opened.");
                }
            }
        }.execute();
    }

    private void setStatus(String msg) { statusLabel.setText(msg); }
    private void clearStatus()         { statusLabel.setText(" "); }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        usernameField.setEnabled(!busy);
        passwordField.setEnabled(!busy);
    }
}
