package com.bank.config;

import java.awt.GraphicsEnvironment;

/** Start-up validation shared by both client entry points. */
public final class StartupCheck {

    /** Exit code used when the database settings are missing or invalid. */
    public static final int EXIT_CONFIG_ERROR = 2;

    private StartupCheck() { }

    /**
     * Verifies the database settings before any window opens. On failure it reports the problem
     * (dialog when a display exists, stderr always) and terminates the process with
     * {@link #EXIT_CONFIG_ERROR}.
     */
    public static void requireDatabaseConfig() {
        try {
            DatabaseConfig.load();
        } catch (DatabaseConfigException e) {
            System.err.println("[Startup] " + e.getMessage());
            if (!GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(null, e.getMessage(),
                        "Database configuration required", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
            System.exit(EXIT_CONFIG_ERROR);
        }
    }
}
