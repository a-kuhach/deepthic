
package com.freemaf.agent;

import com.sun.jna.Native;

import com.sun.jna.win32.StdCallLibrary;

import javax.swing.SwingUtilities;

public final class Main {

    private interface Shcore extends StdCallLibrary {

        Shcore INSTANCE = Native.load("Shcore", Shcore.class);

        int SetProcessDpiAwareness(int value);

    }

    private Main() {}

    public static void main(String[] args) {

        setDpiAwareness();

        AppLogger.info("Application starting");

        EmergencyStop.reset();

        Config config = new Config();

        DarkTheme.apply();

        java.util.List<String> missingRules = SystemInstructionProvider.missingRequiredFiles();
        if (!missingRules.isEmpty()) {
            String msg = "Отсутствуют обязательные файлы фреймворка:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), missingRules) + System.lineSeparator()
                    + System.lineSeparator()
                    + "Закройте программу и запустите run.bat для восстановления.";
            try {
                SwingUtilities.invokeAndWait(() -> javax.swing.JOptionPane.showMessageDialog(
                        null, msg, "Free MAF Code", javax.swing.JOptionPane.ERROR_MESSAGE));
            } catch (Exception e) {
                AppLogger.warn("Startup validation dialog failed: " + e.getMessage());
            }
            System.exit(1);
        }
        int cleaned = ProcessRegistry.cleanupStale();
        if (cleaned > 0) AppLogger.info("ProcessRegistry: cleaned " + cleaned + " stale entries");

        SwingUtilities.invokeLater(() -> new MainFrame(config).setVisible(true));

    }

    private static void setDpiAwareness() {

        try {

            Shcore.INSTANCE.SetProcessDpiAwareness(2);

            AppLogger.info("DPI awareness set to PerMonitorV2");

        } catch (Throwable t) {

            AppLogger.warn("Failed to set DPI awareness: " + t.getMessage());

        }

    }

}
