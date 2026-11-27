package com.bank;

import com.bank.view.AdminLoginFrame;
import com.bank.view.UIUtils;

/**
 * AdminApp — entry point for the administrator console.
 * Administrator sign-in: BCrypt credential records in the admin_users database table.
 * Database settings: BANK_DB_USER / BANK_DB_PASSWORD (see DatabaseConfig); throttled after repeated failures.
 */
public class AdminApp {
    public static void main(String[] args) {
        // Fail fast, with instructions, if the database settings are missing.
        com.bank.config.StartupCheck.requireDatabaseConfig();
        // Also validate administrator credentials before displaying the login form.
        try {
            com.bank.admin.AdminCompositionRoot.getInstance();
        } catch (IllegalStateException e) {
            System.err.println("[Startup] " + e.getMessage());
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(null, e.getMessage(),
                        "Administrator configuration required", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
            System.exit(com.bank.config.StartupCheck.EXIT_CONFIG_ERROR);
        }
        UIUtils.applyLookAndFeel();
        javax.swing.SwingUtilities.invokeLater(() -> {
            AdminLoginFrame adminLoginFrame = new AdminLoginFrame();
            adminLoginFrame.setVisible(true);
        });
    }
}
