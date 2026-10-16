package com.freemaf.agent;

import javax.swing.UIManager;
import java.awt.Color;

public final class DarkTheme {

    public static final Color BG = new Color(43, 43, 43);
    public static final Color FG = Color.WHITE;
    public static final Color FIELD = new Color(30, 30, 30);
    public static final Color BUTTON = new Color(45, 48, 50);
    public static final Color BUTTON_TEXT = new Color(235, 235, 235);
    public static final Color DIVIDER = new Color(26, 26, 26);

    private DarkTheme() {}

    public static void apply() {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        } catch (Exception e) {
            AppLogger.warn("Metal L&F not available: " + e.getMessage());
        }
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.messageForeground", FG);
        UIManager.put("Label.foreground", FG);
        UIManager.put("Label.background", BG);
        UIManager.put("Button.background", BUTTON);
        UIManager.put("Button.foreground", BUTTON_TEXT);
        UIManager.put("Button.select", BUTTON);
        UIManager.put("Button.focus", BUTTON);
        UIManager.put("Button.disabledText", new Color(160, 160, 160));
        UIManager.put("Button.disabledShadow", BUTTON);
        UIManager.put("TextField.background", FIELD);
        UIManager.put("TextField.foreground", FG);
        UIManager.put("TextField.caretForeground", FG);
        UIManager.put("TextArea.background", FIELD);
        UIManager.put("TextArea.foreground", FG);
        UIManager.put("TextArea.caretForeground", FG);
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("Viewport.background", FIELD);
        UIManager.put("ComboBox.background", FIELD);
        UIManager.put("ComboBox.foreground", FG);
        UIManager.put("ComboBox.selectionBackground", BUTTON);
        UIManager.put("ComboBox.selectionForeground", FG);
        UIManager.put("Frame.background", BG);
        UIManager.put("CheckBox.foreground", FG);
        UIManager.put("RadioButton.foreground", FG);
        UIManager.put("Menu.foreground", FG);
        UIManager.put("MenuItem.foreground", FG);
        UIManager.put("MenuBar.background", BG);
        UIManager.put("ToolTip.background", FIELD);
        UIManager.put("ToolTip.foreground", FG);
        UIManager.put("PopupMenu.background", FIELD);
        UIManager.put("PopupMenu.foreground", FG);
        UIManager.put("SplitPane.background", BG);
        UIManager.put("SplitPane.dividerSize", 3);
        UIManager.put("ScrollBar.thumb", BUTTON);
        UIManager.put("ScrollBar.track", BG);
        UIManager.put("ScrollBar.background", BG);
    }

}
