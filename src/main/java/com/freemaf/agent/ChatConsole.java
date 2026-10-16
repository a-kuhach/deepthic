
package com.freemaf.agent;

import javax.swing.JPanel;

import javax.swing.JScrollPane;

import javax.swing.JTextPane;

import javax.swing.text.BadLocationException;

import javax.swing.text.SimpleAttributeSet;

import javax.swing.text.StyleConstants;

import javax.swing.text.StyledDocument;

import java.awt.BorderLayout;

import java.awt.Color;

public final class ChatConsole extends JPanel {

    public static final Color DEBUG_COLOR = new Color(180, 180, 180);

    public static final Color USER_COLOR = new Color(120, 220, 120);

    public static final Color AGENT_COLOR = new Color(255, 165, 60);

    public static final Color SYSTEM_COLOR = new Color(120, 190, 255);

    private final JTextPane textPane = new JTextPane() {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
    };

    private final StyledDocument doc;

    private int fontSize = 12;

    public ChatConsole() {

        setLayout(new BorderLayout());

        textPane.setEditable(false);

        textPane.setBackground(DarkTheme.FIELD);

        textPane.setForeground(DarkTheme.FG);

        this.doc = textPane.getStyledDocument();

        JScrollPane sp = new JScrollPane(textPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        sp.setBackground(DarkTheme.BG);

        sp.getViewport().setBackground(DarkTheme.FIELD);

        add(sp, BorderLayout.CENTER);

    }

    public void appendDebug(String line) {

        append("debug", "[debug] " + line, DEBUG_COLOR);

    }

    public void appendUser(String line) {

        append("user", "You: " + line, USER_COLOR);

    }

    public void appendAgent(String line) {

        append("agent", "Agent: " + line, AGENT_COLOR);

    }

    public void appendSystem(String line) {

        append("system", "System: " + line, SYSTEM_COLOR);

    }

    public void restoreEntry(ChatHistory.Entry entry) {

        Color color = colorForKind(entry.kind());

        String prefix = prefixForKind(entry.kind());

        appendRaw(prefix + entry.text(), color);

    }

    public void clear() { textPane.setText(""); }

    public void setFontSize(int size) {

        this.fontSize = size;

        textPane.setText("");

        for (ChatHistory.Entry e : ChatHistory.loadAll()) restoreEntry(e);

    }

    private static Color colorForKind(ChatHistory.Kind kind) {

        return switch (kind) {

            case USER -> USER_COLOR;

            case AGENT -> AGENT_COLOR;

            case SYSTEM -> SYSTEM_COLOR;

            case DEBUG -> DEBUG_COLOR;

        };

    }

    private static String prefixForKind(ChatHistory.Kind kind) {

        return switch (kind) {

            case USER -> "You: ";

            case AGENT -> "Agent: ";

            case SYSTEM -> "System: ";

            case DEBUG -> "[debug] ";

        };

    }

    private void append(String rawText, String lineWithPrefix, Color color) {

        ChatHistory.Kind kind = switch (rawText) {

            case "user" -> ChatHistory.Kind.USER;

            case "agent" -> ChatHistory.Kind.AGENT;

            case "system" -> ChatHistory.Kind.SYSTEM;

            default -> ChatHistory.Kind.DEBUG;

        };

        ChatHistory.append(new ChatHistory.Entry(kind, rawText.isEmpty() ? "" : lineWithPrefix));

        appendRaw(lineWithPrefix, color);

    }

    private void appendRaw(String line, Color color) {

        SimpleAttributeSet attrs = new SimpleAttributeSet();

        StyleConstants.setForeground(attrs, color);

        StyleConstants.setFontFamily(attrs, "Monospaced");

        StyleConstants.setFontSize(attrs, fontSize);

        try {

            doc.insertString(doc.getLength(), line + System.lineSeparator(), attrs);

            textPane.setCaretPosition(doc.getLength());

        } catch (BadLocationException e) {

            AppLogger.warn("ChatConsole append failed: " + e.getMessage());

        }

    }

}
