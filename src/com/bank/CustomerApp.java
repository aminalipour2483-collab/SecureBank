package com.bank;

import com.bank.view.LoginFrame;
import com.bank.view.UIUtils;

/**
 * CustomerApp — entry point for the customer online-banking portal.
 */
public class CustomerApp {
    public static void main(String[] args) {
        // Fail fast, with instructions, if the database settings are missing.
        com.bank.config.StartupCheck.requireDatabaseConfig();
        UIUtils.applyLookAndFeel();
        javax.swing.SwingUtilities.invokeLater(() -> {
            LoginFrame loginFrame = new LoginFrame();
            loginFrame.setVisible(true);
        });
    }
}
