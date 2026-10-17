
package com.freemaf.agent;



import javax.swing.AbstractAction;

import javax.swing.ActionMap;

import javax.swing.BorderFactory;

import javax.swing.BoxLayout;

import javax.swing.InputMap;

import javax.swing.JButton;

import javax.swing.JComponent;

import javax.swing.JFrame;

import javax.swing.JLabel;

import javax.swing.JOptionPane;

import javax.swing.JPanel;

import javax.swing.JScrollPane;

import javax.swing.JTextArea;

import javax.swing.KeyStroke;

import javax.swing.SwingConstants;

import javax.swing.SwingUtilities;

import java.awt.BorderLayout;

import java.awt.Color;

import java.awt.Dimension;

import java.awt.Font;

import java.awt.GridBagConstraints;

import java.awt.GridBagLayout;

import java.awt.GridLayout;

import java.awt.Insets;

import java.awt.event.ActionEvent;

import java.awt.event.InputEvent;

import java.awt.event.KeyEvent;

import java.awt.event.MouseAdapter;

import java.awt.event.MouseEvent;

import java.io.IOException;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.List;



public final class MainFrame extends JFrame implements WindowRecoveryHandler {



    private static final int[] FONT_SIZES = {10, 12, 18};

    private static final int STATUS_RECT_WIDTH_PX = 170;



    private final ChatConsole chatConsole = new ChatConsole();

    private final ConsolePanel psConsolePanel = new ConsolePanel();

    private final JTextArea inputArea = new JTextArea(5, 40);

    private final java.util.List<java.io.File> attachedFiles = new java.util.ArrayList<>();

    private final JPanel attachmentsListPanel = new JPanel();



    private javax.swing.JScrollPane attachmentsScroll;

    private javax.swing.JButton sendButton;

    private javax.swing.JButton startButton;



    private final JLabel statusLabel = new JLabel("Stopped");

    private final JLabel statusIndicator = new JLabel();



    private final PromptQueue promptQueue = new PromptQueue();

    private final ProcessManager processManager = new ProcessManager();

    private final Config config;

    private final ClipboardService clipboardService;

    private final InputSimulator inputSimulator;

    private final PixelColorService pixelColorService;

    private final WindowManager windowManager;

    private final DeepSeekSession deepSeekSession;

    private final PowerShellExecutor powerShellExecutor;

    private final ContextPackager contextPackager;

    private final GlobalHotkeyListener hotkeyListener;



    private int fontLevel = 1;



    private volatile boolean startSequenceRunning;

    private volatile Thread startSequenceThread;

    private volatile long lastStopAtMs;

    private volatile boolean userMessageQueuedForStart;

    private volatile boolean calibrationRunning;

    private volatile AutomationLoop currentLoop;

    private final com.freemaf.agent.cdp.CdpBootstrap cdpBootstrap;



    private StatusMarker.State currentState = StatusMarker.State.STOPPED;



    public MainFrame(Config config) {

        super(resolveWindowTitle());

        this.config = config;

        this.clipboardService = new ClipboardService();

        this.inputSimulator = new InputSimulator(config, clipboardService);

        this.pixelColorService = new PixelColorService();

        this.windowManager = new WindowManager();

        this.inputSimulator.setWindowManager(this.windowManager);

        this.inputSimulator.setPixelColorService(this.pixelColorService);

        this.deepSeekSession = new DeepSeekSession(config, inputSimulator, clipboardService, pixelColorService, windowManager);

        this.deepSeekSession.setDebugSink(this::appendDebug);

        this.deepSeekSession.setWindowRecoveryHandler(this);
        this.cdpBootstrap = config.isCdpEnabled() ? new com.freemaf.agent.cdp.CdpBootstrap(config) : null;
        if (this.cdpBootstrap != null) {
            this.deepSeekSession.setCdpBootstrap(this.cdpBootstrap);
            AppLogger.info("MainFrame: CDP mode enabled");
        }

        this.powerShellExecutor = new PowerShellExecutor(processManager, config);

        this.contextPackager = new ContextPackager();

        this.hotkeyListener = new GlobalHotkeyListener(this::stopAction);

        this.inputSimulator.setSpeedMode(SpeedMode.fromName(config.get("speed.mode", "INSTANT")));

        this.inputSimulator.setWaitRetryCallback(reason -> {

            final boolean[] ok = {false};

            try {

                SwingUtilities.invokeAndWait(() -> {

                    int r = JOptionPane.showConfirmDialog(MainFrame.this,

                            reason + " Продолжить ожидание ещё 2 минуты?",

                            "Ожидание загрузки файлов",

                            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

                    ok[0] = r == JOptionPane.YES_OPTION;

                });

            } catch (Exception e) {

                AppLogger.warn("waitRetry dialog failed: " + e.getMessage());

                return false;

            }

            return ok[0];

        });

        setDefaultCloseOperation(EXIT_ON_CLOSE);

        setSize(1200, 800);

        setLocationRelativeTo(null);

        setLayout(new BorderLayout());

        add(buildCenterPanel(), BorderLayout.CENTER);

        add(buildBottomPanel(), BorderLayout.SOUTH);

        restoreChatHistory();

        applyWhiteText(getContentPane());

        installPressFeedbackRecursive(getContentPane());

        updateStatusIndicator();

        updateStartButton();

        new Thread(hotkeyListener, "GlobalHotkey").start();

    }



    private static String resolveWindowTitle() {

        try {

            String name = Path.of(".").toAbsolutePath().normalize().getFileName().toString();

            if (name == null || name.isBlank()) name = "Project";

            return name + " - Deepthic";

        } catch (RuntimeException e) { return "Project - Deepthic"; }

    }



    private static void applyWhiteText(java.awt.Container c) {

        for (java.awt.Component comp : c.getComponents()) {

            if (comp instanceof JButton btn) {

                btn.setForeground(DarkTheme.BUTTON_TEXT);

                btn.setBackground(DarkTheme.BUTTON);

                btn.setOpaque(true);

                btn.setContentAreaFilled(true);

                btn.setBorderPainted(true);

                btn.setFocusPainted(false);

            } else if (comp instanceof JLabel lbl) {

                lbl.setForeground(DarkTheme.FG);

            }

            if (comp instanceof java.awt.Container) applyWhiteText((java.awt.Container) comp);

        }

    }



    private static void installPressFeedbackRecursive(java.awt.Container c) {

        for (java.awt.Component comp : c.getComponents()) {

            if (comp instanceof JButton btn) installPressFeedback(btn);

            if (comp instanceof java.awt.Container) installPressFeedbackRecursive((java.awt.Container) comp);

        }

    }



    private static void installPressFeedback(JButton b) {

        b.addMouseListener(new MouseAdapter() {

            @Override public void mousePressed(MouseEvent e) {

                if (b.isEnabled()) b.setBackground(DarkTheme.BUTTON.darker());

            }

            @Override public void mouseReleased(MouseEvent e) {

                b.setBackground(DarkTheme.BUTTON);

            }

            @Override public void mouseExited(MouseEvent e) {

                b.setBackground(DarkTheme.BUTTON);

            }

        });

    }



    private JComponent createDivider() {

        JPanel divider = new JPanel();

        divider.setBackground(DarkTheme.DIVIDER);

        divider.setPreferredSize(new Dimension(AgentConstants.DIVIDER_THICKNESS_PX, 1));

        divider.setMinimumSize(new Dimension(AgentConstants.DIVIDER_THICKNESS_PX, 1));

        return divider;

    }



    private JButton createTwoLineButton(String topText, String bottomText) {

        JButton btn = new JButton("<html><center>" + topText + "<br>" + bottomText + "</center></html>");

        btn.setContentAreaFilled(true);

        return btn;

    }



    private JPanel buildCenterPanel() {

        JPanel center = new JPanel(new GridLayout(1, 2));

        JPanel left = new JPanel(new BorderLayout());

        left.add(new JLabel("Chat"), BorderLayout.NORTH);

        left.add(chatConsole, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout());

        right.add(new JLabel("PS Console"), BorderLayout.NORTH);

        right.add(psConsolePanel, BorderLayout.CENTER);

        center.add(left);

        center.add(right);

        return center;

    }



    private JPanel buildBottomPanel() {

        JPanel bottom = new JPanel(new GridBagLayout());

        bottom.setBackground(DarkTheme.BG);

        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0;

        gbc.weightx = 1.0;

        gbc.weighty = AgentConstants.BOTTOM_ROW_WEIGHT;

        gbc.fill = GridBagConstraints.BOTH;

        gbc.gridy = 0; bottom.add(buildRow1(), gbc);

        gbc.gridy = 1; bottom.add(buildRow2(), gbc);

        gbc.gridy = 2; bottom.add(buildRow3(), gbc);

        gbc.gridy = 3; bottom.add(buildRow4(), gbc);

        return bottom;

    }



    /** Row 1 - status rectangle first (left), then 7 buttons, in a single horizontal line. */

    private JPanel buildRow1() {

        JPanel row1 = new JPanel(new GridBagLayout());

        row1.setBackground(DarkTheme.BG);



        // Status rectangle: indicator top-left corner, state label centered inside.

        statusIndicator.setPreferredSize(new Dimension(AgentConstants.STATUS_INDICATOR_SIZE_PX, AgentConstants.STATUS_INDICATOR_SIZE_PX));

        statusIndicator.setMinimumSize(new Dimension(AgentConstants.STATUS_INDICATOR_SIZE_PX, AgentConstants.STATUS_INDICATOR_SIZE_PX));

        statusIndicator.setMaximumSize(new Dimension(AgentConstants.STATUS_INDICATOR_SIZE_PX, AgentConstants.STATUS_INDICATOR_SIZE_PX));

        statusIndicator.setOpaque(true);

        statusIndicator.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);



        JPanel statusRect = new JPanel(new GridBagLayout());

        statusRect.setBackground(DarkTheme.BG);

        statusRect.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

        GridBagConstraints si = new GridBagConstraints();

        si.gridx = 0; si.gridy = 0;

        si.anchor = GridBagConstraints.NORTHWEST;

        si.insets = new Insets(2, 4, 0, 0);

        statusRect.add(statusIndicator, si);

        GridBagConstraints st = new GridBagConstraints();

        st.gridx = 0; st.gridy = 0;

        st.weightx = 1.0; st.weighty = 1.0;

        st.fill = GridBagConstraints.BOTH;

        statusRect.add(statusLabel, st);

        statusRect.setPreferredSize(new Dimension(STATUS_RECT_WIDTH_PX, AgentConstants.ROW1_BUTTON_MIN_HEIGHT_PX));

        statusRect.setMinimumSize(new Dimension(STATUS_RECT_WIDTH_PX, AgentConstants.ROW1_BUTTON_MIN_HEIGHT_PX));



        JButton fontDown = new JButton("a-");

        JButton fontUp = new JButton("A+");

        JButton speedButton = new JButton();

        updateSpeedButtonText(speedButton);

        startButton = createTwoLineButton(StartButtonGlyph.glyph(currentState, false), "Shift+F12");

        JButton restartButton = new JButton("Restart Plan");

        JButton calibrationButton = new JButton("Reconnect");

        JButton clearChatButton = new JButton("Clear Chat");



        JButton[] buttons = {fontDown, fontUp, speedButton, startButton, restartButton, calibrationButton, clearChatButton};

        int btnHeight = AgentConstants.ROW1_BUTTON_MIN_HEIGHT_PX;

        for (JButton b : buttons) {

            Dimension pref = b.getPreferredSize();

            b.setPreferredSize(new Dimension(pref.width, btnHeight));

            b.setMinimumSize(new Dimension(0, btnHeight));

        }



        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridy = 0;

        gbc.fill = GridBagConstraints.BOTH;

        gbc.weighty = 1.0;

        int col = 0;



        gbc.gridx = col++; gbc.weightx = 0; gbc.insets = new Insets(2, 2, 2, 2);

        row1.add(statusRect, gbc);



        for (JButton b : buttons) {

            gbc.gridx = col++; gbc.weightx = 0; gbc.insets = new Insets(2, 0, 2, 0);

            row1.add(createDivider(), gbc);

            gbc.gridx = col++; gbc.weightx = 1.0; gbc.insets = new Insets(2, 2, 2, 2);

            row1.add(b, gbc);

        }



        startButton.addActionListener(e -> handleStartButtonClick());

        restartButton.addActionListener(e -> restartAction());

        calibrationButton.addActionListener(e -> reconnectAction());

        clearChatButton.addActionListener(e -> clearChatAction());

        fontDown.addActionListener(e -> adjustFont(-1));

        fontUp.addActionListener(e -> adjustFont(+1));

        speedButton.addActionListener(ev -> {

            SpeedMode next = inputSimulator.getSpeedMode().next();

            inputSimulator.setSpeedMode(next);

            updateSpeedButtonText(speedButton);

            appendSystem("Speed mode: " + next.getLabel());

            try { config.set("speed.mode", next.name()); config.save(); }

            catch (IOException ex) { AppLogger.warn("save speed.mode failed: " + ex.getMessage()); }

        });



        return row1;

    }



    private JPanel buildRow2() {

        JPanel row2 = new JPanel(new BorderLayout());

        row2.setBackground(DarkTheme.BG);

        JButton attachFilesButton = new JButton("Attach Files");

        attachFilesButton.addActionListener(e -> attachFilesAction());

        attachmentsListPanel.setLayout(new BoxLayout(attachmentsListPanel, BoxLayout.Y_AXIS));

        attachmentsListPanel.setBackground(DarkTheme.BG);

        attachmentsScroll = new JScrollPane(attachmentsListPanel);

        attachmentsScroll.setBorder(null);

        attachmentsScroll.getViewport().setBackground(DarkTheme.BG);

        attachmentsScroll.setVisible(false);

        row2.add(attachmentsScroll, BorderLayout.CENTER);

        JPanel attachButtons = new JPanel(new java.awt.GridLayout(1, 1, 4, 0));
        attachButtons.setBackground(DarkTheme.BG);
        attachButtons.add(attachFilesButton);
        row2.add(attachButtons, BorderLayout.EAST);

        return row2;

    }



    private JPanel buildRow3() {

        JPanel row3 = new JPanel(new BorderLayout());

        row3.setBackground(DarkTheme.BG);

        inputArea.setLineWrap(true);

        inputArea.setWrapStyleWord(true);

        inputArea.setBackground(DarkTheme.FIELD);

        inputArea.setForeground(DarkTheme.FG);

        inputArea.setCaretColor(DarkTheme.FG);

        inputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        InputMap im = inputArea.getInputMap();

        ActionMap am = inputArea.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit");

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");

        am.put("submit", new AbstractAction() {

            @Override public void actionPerformed(ActionEvent e) { submitPrompt(); }

        });

        JScrollPane inputScroll = new JScrollPane(inputArea);

        inputScroll.setBackground(DarkTheme.BG);

        inputScroll.getViewport().setBackground(DarkTheme.FIELD);

        javax.swing.TransferHandler defaultTH = inputArea.getTransferHandler();

        javax.swing.TransferHandler attachHandler = buildAttachmentTransferHandler(defaultTH);

        inputArea.setTransferHandler(attachHandler);

        inputArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {

            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateSendButtonState(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateSendButtonState(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateSendButtonState(); }

        });

        row3.add(inputScroll, BorderLayout.CENTER);

        return row3;

    }



    /** Row 4 - full-width SEND button: SEND label followed by spaced "[Enter]" hint. */

    private JPanel buildRow4() {

        JPanel row4 = new JPanel(new BorderLayout());

        row4.setBackground(DarkTheme.BG);

        sendButton = new JButton("<html>SEND&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<font color='#C0C0C0'>[Enter]</font></html>");

        sendButton.setHorizontalAlignment(SwingConstants.CENTER);

        sendButton.addActionListener(e -> submitPrompt());

        row4.add(sendButton, BorderLayout.CENTER);

        return row4;

    }



    private void updateSpeedButtonText(JButton btn) {

        btn.setText("Speed Mode: " + inputSimulator.getSpeedMode().getLabel());

    }



    private void adjustFont(int delta) {

        int newLevel = Math.max(0, Math.min(FONT_SIZES.length - 1, fontLevel + delta));

        if (newLevel == fontLevel) return;

        fontLevel = newLevel;

        int size = FONT_SIZES[fontLevel];

        chatConsole.setFontSize(size);

        psConsolePanel.setFontSize(size);

        inputArea.setFont(new Font("Monospaced", Font.PLAIN, size));

    }



    private void attachFilesAction() {

        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();

        chooser.setMultiSelectionEnabled(true);

        int result = chooser.showOpenDialog(this);

        if (result != javax.swing.JFileChooser.APPROVE_OPTION) return;

        java.io.File[] selected = chooser.getSelectedFiles();

        if (selected == null || selected.length == 0) return;

        addAttachedFiles(java.util.Arrays.asList(selected));

    }



    private void addAttachedFiles(java.util.List<java.io.File> files) {

        java.util.List<java.io.File> candidate = new java.util.ArrayList<>(attachedFiles);

        candidate.addAll(files);

        AttachmentValidator.ValidationResult r = AttachmentValidator.validate(candidate);

        if (!r.valid()) {

            javax.swing.JOptionPane.showMessageDialog(this, r.error(),

                    "Attachment error", javax.swing.JOptionPane.ERROR_MESSAGE);

            return;

        }

        attachedFiles.clear();

        attachedFiles.addAll(candidate);

        rebuildAttachmentsList();

        updateSendButtonState();

    }



    private void rebuildAttachmentsList() {

        if (attachmentsListPanel == null) return;

        attachmentsListPanel.removeAll();

        if (attachedFiles.isEmpty()) {

            if (attachmentsScroll != null) attachmentsScroll.setVisible(false);

        } else {

            if (attachmentsScroll != null) {

                int rows = attachedFiles.size();

                int height = Math.min(rows * 24, 96);

                attachmentsScroll.setPreferredSize(new Dimension(200, height));

                attachmentsScroll.setVisible(true);

            }

            for (int i = 0; i < attachedFiles.size(); i++) {

                final int idx = i;

                java.io.File f = attachedFiles.get(i);

                JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));

                row.setBackground(DarkTheme.BG);

                JLabel name = new JLabel(f.getName());

                name.setForeground(DarkTheme.FG);

                JButton removeBtn = new JButton("\u2715");

                removeBtn.setMargin(new java.awt.Insets(0, 6, 0, 6));

                removeBtn.setForeground(DarkTheme.BUTTON_TEXT);

                removeBtn.setBackground(DarkTheme.BUTTON);

                removeBtn.setFocusPainted(false);

                removeBtn.addActionListener(e -> {

                    attachedFiles.remove(idx);

                    rebuildAttachmentsList();

                    updateSendButtonState();

                });

                row.add(name);

                row.add(removeBtn);

                attachmentsListPanel.add(row);

            }

        }

        attachmentsListPanel.revalidate();

        attachmentsListPanel.repaint();

        java.awt.Container parent = attachmentsListPanel.getParent();

        if (parent != null) { parent.revalidate(); parent.repaint(); }

        java.awt.Container content = getContentPane();

        if (content != null) { content.revalidate(); content.repaint(); }

    }



    private void updateSendButtonState() {

        if (sendButton == null) return;

        sendButton.setEnabled(SendButtonState.isEnabled(inputArea.getText(), attachedFiles.size()));

    }



    private javax.swing.TransferHandler buildAttachmentTransferHandler(javax.swing.TransferHandler delegate) {

        return new javax.swing.TransferHandler() {

            @Override public boolean canImport(TransferSupport support) {

                if (support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) return true;

                return delegate != null && delegate.canImport(support);

            }

            @Override public boolean importData(TransferSupport support) {

                if (support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {

                    try {

                        @SuppressWarnings("unchecked")

                        java.util.List<java.io.File> files = (java.util.List<java.io.File>)

                                support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);

                        addAttachedFiles(files);

                        return true;

                    } catch (Exception ex) {

                        AppLogger.warn("drag-and-drop import failed: " + ex.getMessage());

                        return false;

                    }

                }

                return delegate != null && delegate.importData(support);

            }

            @Override public int getSourceActions(javax.swing.JComponent c) {

                return delegate != null ? delegate.getSourceActions(c) : javax.swing.TransferHandler.COPY_OR_MOVE;

            }

            @Override public void exportToClipboard(javax.swing.JComponent comp, java.awt.datatransfer.Clipboard clip, int action) throws IllegalStateException {

                if (delegate != null) delegate.exportToClipboard(comp, clip, action);

            }

        };

    }




    private void restoreChatHistory() {

        List<ChatHistory.Entry> entries = ChatHistory.loadAll();

        for (ChatHistory.Entry e : entries) chatConsole.restoreEntry(e);

        if (!entries.isEmpty()) appendSystem("Chat history restored (" + entries.size() + " entries).");

    }



    private void clearChatAction() {

        ChatHistory.clear();

        chatConsole.clear();

        chatConsole.appendSystem("Chat history cleared.");

    }



    private void submitPrompt() {

        String text = inputArea.getText().trim();

        java.util.List<java.io.File> files = new java.util.ArrayList<>(attachedFiles);

        if (!SendButtonState.isEnabled(text, files.size())) return;

        if (files.isEmpty()) {

            chatConsole.appendUser(text);

        } else {

            StringBuilder sb = new StringBuilder();

            if (!text.isEmpty()) sb.append(text).append(System.lineSeparator());

            sb.append("Attached files:");

            for (java.io.File f : files) sb.append(System.lineSeparator()).append("  - ").append(f.getName());

            chatConsole.appendUser(sb.toString());

        }

        promptQueue.addUser(text, files);

        inputArea.setText("");

        attachedFiles.clear();

        rebuildAttachmentsList();

        updateSendButtonState();

        if (currentState == StatusMarker.State.STOPPED) {

            long sinceStop = System.currentTimeMillis() - lastStopAtMs;

            if (lastStopAtMs > 0L && sinceStop < 3000L) {

                appendSystem("Auto-start suppressed (STOP was " + sinceStop + " ms ago).");

                return;

            }

            config.setResumeAfterStop(false);

            appendSystem("Auto-start (STOPPED -> Start).");

            userMessageQueuedForStart = true;

            startAction();

        } else if (currentState == StatusMarker.State.PAUSED && currentLoop != null) {

            appendSystem("Resuming paused loop to deliver user message.");

            currentLoop.resumeAfterUserInput();

        }

    }



    private void startAction() {

        EmergencyStop.reset();

        if (startSequenceRunning) { appendSystem("Start already running."); return; }

        startSequenceRunning = true;

        setStatus(StatusMarker.State.WORKING);

        updateStartButton();

        Thread t = new Thread(this::startSequence, "StartSequence");

        this.startSequenceThread = t;

        t.start();

    }



    private void handleStartButtonClick() {

        if (currentLoop != null && currentLoop.isPaused()) {

            appendSystem("Resuming session...");

            currentLoop.resumeFromPause();

            return;

        }

        if (currentLoop != null && (currentState == StatusMarker.State.WORKING

                || currentState == StatusMarker.State.PLANNING)) {

            stopAction();

            return;

        }

        if (startSequenceRunning) {

            appendSystem("Start already running.");

            return;

        }

        startAction();

    }



    private void updateStartButton() {

        if (startButton == null) return;

        boolean userPaused = currentLoop != null && currentLoop.isPaused();

        String glyph = StartButtonGlyph.glyph(currentState, userPaused);

        startButton.setText("<html><center>" + glyph + "<br>Shift+F12</center></html>");

    }



    private void reconnectAction() {
        if (cdpBootstrap != null) {
            appendSystem("Reconnecting CDP tabs: closing reviewer + executor, then reopening executor...");
            new Thread(() -> {
                try {
                    cdpBootstrap.closeReviewer();
                    cdpBootstrap.closeExecutor();
                    cdpBootstrap.openExecutor();
                    cdpBootstrap.activateExecutor();
                    appendSystem("Reconnected in CDP mode.");
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(MainFrame.this,
                            "Reconnected in CDP mode.", "Reconnect", JOptionPane.INFORMATION_MESSAGE));
                } catch (Exception ex) {
                    AppLogger.warn("reconnectAction failed: " + ex.getMessage());
                    appendSystem("Reconnect failed: " + ex.getMessage());
                }
            }, "ReconnectSequence").start();
            return;
        }
        appendSystem("Calibration is deprecated since S5; use Reconnect in CDP mode.");
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(MainFrame.this,
                "Calibration is deprecated since S5; it is kept only for cdp.enabled=false compatibility." + System.lineSeparator()
                        + "Switch cdp.enabled=true and use Reconnect.",
                "Reconnect", JOptionPane.INFORMATION_MESSAGE));
    }

    private void calibrationAction() {

        EmergencyStop.reset();

        if (calibrationRunning) { appendSystem("Calibration already running."); return; }

        calibrationRunning = true;

        appendSystem("Manual calibration started.");

        new Thread(this::runCalibrationAction, "CalibrationSequence").start();

    }



    @Override

    public boolean recover() {
        if (cdpBootstrap != null) {
            appendSystem("recover() ignored in CDP mode");
            return false;
        }

        final int[] choice = {JOptionPane.NO_OPTION};

        try {

            SwingUtilities.invokeAndWait(() -> {

                int r = JOptionPane.showOptionDialog(MainFrame.this,

                        "The active Chrome window was closed or lost." + System.lineSeparator()

                                + "Open a new window and continue?",

                        "Chrome window lost", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,

                        null, null, JOptionPane.YES_OPTION);

                choice[0] = r;

            });

        } catch (Exception e) {

            AppLogger.warn("recover: dialog failed: " + e.getMessage());

            return false;

        }

        if (choice[0] != JOptionPane.YES_OPTION) {

            appendSystem("User declined to recover window.");

            return false;

        }

        try {

            WindowInfo window = ensureChromeWindow();

            if (window == null) return false;

            windowManager.select(window);

            if (!setupWorkRect()) return false;

            appendSystem("New DeepSeek window selected and configured.");

            AutomationLoop l = currentLoop;

            if (l != null) l.resetExecutorContext();

            return true;

        } catch (Exception e) {

            appendSystem("Failed to recover window - " + e.getMessage());

            return false;

        }

    }



    @Deprecated
    private void runCalibrationAction() {
        if (cdpBootstrap != null) {
            appendSystem("Calibration is not required in CDP mode.");
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(MainFrame.this,
                    "Calibration is not required in CDP mode.", "CDP mode", JOptionPane.INFORMATION_MESSAGE));
            setStatus(StatusMarker.State.STOPPED);
            return;
        }

        WindowInfo calibrationWindow = null;
        try {
            if (currentLoop != null || startSequenceRunning) {
                appendSystem("Stopping active session before calibration...");
                stopAction();
                Thread.sleep(500L);
                EmergencyStop.reset();
            }

            java.util.List<com.sun.jna.Pointer> excludes = new java.util.ArrayList<>();
            for (WindowInfo w : ChromeLauncher.findChromeWindows()) {
                if (w.title().toLowerCase().contains("deepseek")) {
                    excludes.add(w.hwnd());
                }
            }

            AppLogger.info("runCalibrationAction: excluding " + excludes.size() + " existing DeepSeek window(s)");
            appendSystem("Opening fresh Chrome window for calibration...");

            WindowInfo window = ChromeLauncher.launchNewWindow(config, excludes);
            if (window == null) {
                setStatus(StatusMarker.State.STOPPED);
                return;
            }
            calibrationWindow = window;

            windowManager.select(window);
            appendSystem("Calibration window: " + window.title());

            if (!setupWorkRect()) return;
            if (!runCalibrationSequence()) { setStatus(StatusMarker.State.STOPPED); return; }

            config.setCalibrated(true);
            config.save();
            appendSystem("Calibration saved to config.properties.");
            setStatus(StatusMarker.State.STOPPED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            appendSystem("Calibration interrupted.");
            setStatus(StatusMarker.State.STOPPED);
        } catch (Exception e) {
            appendSystem("Calibration error - " + e.getMessage());
            setStatus(StatusMarker.State.STOPPED);
        } finally {
            if (calibrationWindow != null) {
                try {
                    long hwndLong = calibrationWindow.hwndValue();
                    if (hwndLong != 0L && com.freemaf.agent.winapi.WinApiService.isWindow(hwndLong)) {
                        com.freemaf.agent.winapi.WinApiService.closeWindow(hwndLong);
                        AppLogger.info("runCalibrationAction: closed calibration window hwnd=" + hwndLong);
                    }
                } catch (Exception closeEx) {
                    AppLogger.warn("runCalibrationAction: close calibration window failed: " + closeEx.getMessage());
                }
            }
            calibrationRunning = false;
        }
    }

    private void startSequence() {

        boolean userAlreadyQueued = userMessageQueuedForStart;

        userMessageQueuedForStart = false;

        try {

            if (Thread.currentThread().isInterrupted() || EmergencyStop.isStopped()) {

                AppLogger.info("startSequence: aborted at entry");

                setStatus(StatusMarker.State.STOPPED);

                return;

            }

            if (cdpBootstrap != null) {
                AppLogger.info("startSequence: CDP mode, starting bootstrap");
                try {
                    cdpBootstrap.start(true);
                    cdpBootstrap.openExecutor();
                } catch (Exception cex) {
                    appendSystem("CDP bootstrap failed: " + cex.getMessage());
                    setStatus(StatusMarker.State.STOPPED);
                    return;
                }
            }

            AutomationLoop loop = new AutomationLoop(chatConsole, psConsolePanel, statusMarkerProxy(), promptQueue,

                    deepSeekSession, powerShellExecutor, config, windowManager, cdpBootstrap);

            SessionDetector.SessionDecision decision = loop.bootstrapSession();

            WindowInfo activeWindow = decision.activeWindow() != null

                    ? decision.activeWindow()

                    : (decision.executorWindow() != null ? decision.executorWindow() : decision.reviewerWindow());

            if (activeWindow == null) {

                appendSystem("Failed to determine active window.");

                setStatus(StatusMarker.State.STOPPED);

                return;

            }

            if (cdpBootstrap == null && decision.decision() == SessionDetector.Decision.OPEN_NEW_CHAT

                    || decision.decision() == SessionDetector.Decision.RECOVER_NEW_EXECUTOR) {

                if (LoginChecker.isSignInPage(activeWindow, config)) {

                    appendSystem("Sign-in page detected. Log in in the open Chrome window.");

                    if (!showConfirmDialog("Log in in the open Chrome window, then click OK.", "Waiting for login")) {

                        appendSystem("Login cancelled by user.");

                        setStatus(StatusMarker.State.STOPPED);

                        return;

                    }

                }

            }

            if (cdpBootstrap == null && !setupWorkRect()) return;

            boolean skipCalibration = cdpBootstrap != null || decision.decision() == SessionDetector.Decision.RESUME_REVIEWER_SESSION;

            if (!skipCalibration && !config.isCalibrated()) {

                appendSystem("Initial calibration (one-time).");

                if (!runCalibrationSequence()) { setStatus(StatusMarker.State.STOPPED); return; }

                config.setCalibrated(true);

                config.save();

                appendSystem("Calibration saved. It will not run on next launches.");

            } else if (skipCalibration) {

                appendSystem("Reviewer session resumed, skipping calibration.");

            } else {

                appendSystem("Calibration already done, skipping.");

            }

            this.currentLoop = loop;

            updateStartButton();

            if (config.getResumeAfterStop()) {

                config.setResumeAfterStop(false);

                if (decision.decision() == SessionDetector.Decision.CONTINUE_OLD_SESSION

                        || decision.decision() == SessionDetector.Decision.OPEN_NEW_CHAT) {

                    String resumeMsg = "### ROLE_TO_ACT: EXECUTOR" + System.lineSeparator()

                            + "Работа остановлена, последний выполнявшийся PS-скрипт убит в процессе, его лог утерян. Продолжи задачу с учётом того, что скрипт мог не выполниться, выполниться частично или полностью.";

                    promptQueue.addUser(resumeMsg, java.util.List.of());

                    appendSystem("Resume-after-stop: queued continuation prompt.");

                }

                setStatus(StatusMarker.State.WORKING);

            } else {

                boolean hasPriorSession = decision.decision() != SessionDetector.Decision.OPEN_NEW_CHAT;

                if (hasPriorSession && decision.decision() == SessionDetector.Decision.CONTINUE_OLD_SESSION) {

                    if (userAlreadyQueued) {

                        appendSystem("Continuing prior session with user-supplied message.");

                    } else {

                        String continueMsg = "### ROLE_TO_ACT: EXECUTOR" + System.lineSeparator()

                                + "Пользователь нажал Play без нового сообщения. Если у тебя остался незакрытый вопрос к пользователю — задай его заново через USER_MSG + AGENT_PAUSE. Иначе продолжи работу с того места, где остановился, либо пришли краткий статус.";

                        promptQueue.addUser(continueMsg, java.util.List.of());

                        appendSystem("Continuing prior session without a user message.");

                    }

                } else if (hasPriorSession) {

                    appendSystem("Resuming prior session; starting loop.");

                } else {

                    appendSystem("Waiting for a user message in the input field below.");

                }

                setStatus(hasPriorSession ? StatusMarker.State.WORKING : StatusMarker.State.PAUSED);

            }

            loop.run();

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            appendSystem("Cycle interrupted.");

            setStatus(StatusMarker.State.STOPPED);

        } catch (IOException e) {

            appendSystem("Error - " + e.getMessage());

            setStatus(StatusMarker.State.STOPPED);

        } catch (Exception e) {

            appendSystem("Error - " + e.getMessage());

            setStatus(StatusMarker.State.STOPPED);

        } finally {

            this.currentLoop = null;

            startSequenceRunning = false;

            updateStartButton();

        }

    }



    private StatusMarker statusMarkerProxy() {

        return new StatusMarker() {

            @Override public void setState(State state) {

                SwingUtilities.invokeLater(() -> MainFrame.this.setStatus(state));

            }

        };

    }



    private WindowInfo ensureChromeWindow() throws Exception {

        if (cdpBootstrap != null) {
            cdpBootstrap.openExecutor();
            return new WindowInfo(null, "cdp-executor", 0, 0, 1, 1);
        }
        WindowInfo window = ChromeLauncher.findDeepSeekWindow();

        if (window == null) {

            appendSystem("DeepSeek window not found, launching Chrome...");

            window = ChromeLauncher.launchAndWait(config);

        } else {

            appendSystem("Found DeepSeek window: " + window.title());

        }

        if (LoginChecker.isSignInPage(window, config)) {

            appendSystem("Sign-in page detected. Log in in the open Chrome window.");

            if (!showConfirmDialog("Log in in the open Chrome window, then click OK.", "Waiting for login")) {

                appendSystem("Login cancelled by user.");

                setStatus(StatusMarker.State.STOPPED);

                return null;

            }

            window = ChromeLauncher.findDeepSeekWindow();

            if (window == null) {

                appendSystem("DeepSeek window disappeared after login.");

                setStatus(StatusMarker.State.STOPPED);

                return null;

            }

        }

        return window;

    }



    private boolean setupWorkRect() throws InterruptedException {

        int wx = config.getInt("window.work.x", 50);

        int wy = config.getInt("window.work.y", 50);

        int ww = config.getInt("window.work.width", 673);

        int wh = config.getInt("window.work.height", 508);

        if (!windowManager.setRect(wx, wy, ww, wh)) {

            appendSystem("Failed to set window size.");

            setStatus(StatusMarker.State.STOPPED);

            return false;

        }

        appendSystem("Window set to " + wx + "," + wy + " " + ww + "x" + wh);

        Thread.sleep(2000L);

        return true;

    }



    @Deprecated
    private boolean runCalibrationSequence() throws Exception {
        AppLogger.warn("runCalibrationSequence is deprecated; CDP mode (cdp.enabled=true) does not require calibration");

        long tStartAll = System.currentTimeMillis();

        if (windowManager.hwnd() == null || !windowManager.isWindowValid()) {

            appendSystem("No managed Chrome window for calibration.");

            AppLogger.warn("runCalibrationSequence: no managed window");

            return false;

        }

        long hLong = com.sun.jna.Pointer.nativeValue(windowManager.hwnd());

        WindowInfo window = WindowFinder.findByHwnd(hLong, config.getWindowTitle()).orElse(null);

        if (window == null) {

            window = ChromeLauncher.findDeepSeekWindow();

        }

        if (window == null) {

            appendSystem("DeepSeek window not found before calibration.");

            AppLogger.warn("runCalibrationSequence: DeepSeek window not found");

            return false;

        }

        int scale = com.freemaf.agent.winapi.WinApiService.systemScalePercent();

        boolean shiftActive = config.getShiftActiveForHwnd(hLong);

        AppLogger.info("runCalibrationSequence: window=" + window.left() + "," + window.top()

                + " " + (window.right() - window.left()) + "x" + (window.bottom() - window.top())

                + " scale=" + scale + "% shiftActive=" + shiftActive);

        appendSystem("Running calibration (scale=" + scale + "%, shift=" + shiftActive + ")...");

        setStatus(StatusMarker.State.CALIBRATION);



        String calMode = config.get("calibration.mode", "legacy");

        AppLogger.info("runCalibrationSequence: calibration.mode=" + calMode);

        if ("legacy".equalsIgnoreCase(calMode)) {

            return runLegacyCalibrationSequence(window, scale);

        }



        try (PixelColorService svc = new PixelColorService()) {

            appendDebug("[Calib 1/6] searching input_field (up to 10 s)...");

            long t1 = System.currentTimeMillis();

            AutoCalibrator.MarkerHit inputHit = null;

            long deadline1 = t1 + 10_000L;

            int polls1 = 0;

            while (System.currentTimeMillis() < deadline1 && !EmergencyStop.isStopped()) {

                polls1++;

                long tPollStart = System.currentTimeMillis();

                inputHit = AutoCalibrator.findInputField(window, svc);

                AppLogger.info("runCalibrationSequence: findInputField poll#" + polls1

                        + " took=" + (System.currentTimeMillis() - tPollStart) + " ms result="

                        + (inputHit == null ? "null" : inputHit.x() + "," + inputHit.y() + " #" + inputHit.observedColorHex()));

                if (inputHit != null) break;

                try { Thread.sleep(500L); }

                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }

            }

            AppLogger.info("runCalibrationSequence: STEP1 INPUT_FIELD done total=" + (System.currentTimeMillis() - t1)

                    + " ms polls=" + polls1 + " result=" + (inputHit == null ? "NOT FOUND" : "FOUND"));

            if (inputHit == null) {

                return failCalibration(scale, "STEP1: INPUT_FIELD not found within 10s");

            }

            appendDebug("[Calib 1/6] input_field found at " + inputHit.x() + "," + inputHit.y() + " #" + inputHit.observedColorHex());



            appendDebug("[Calib 2/6] searching send (disabled)...");

            long t2 = System.currentTimeMillis();

            AutoCalibrator.MarkerHit sendDisabledHit = AutoCalibrator.findSend(window, svc);

            AppLogger.info("runCalibrationSequence: STEP2 SEND(disabled) took=" + (System.currentTimeMillis() - t2)

                    + " ms result=" + (sendDisabledHit == null ? "NOT FOUND"

                            : sendDisabledHit.x() + "," + sendDisabledHit.y() + " #" + sendDisabledHit.observedColorHex()

                                    + " disabled=" + sendDisabledHit.disabledObserved()));

            if (sendDisabledHit == null) {

                return failCalibration(scale, "STEP2: SEND not found in disabled state");

            }

            appendDebug("[Calib 2/6] send(disabled) at " + sendDisabledHit.x() + "," + sendDisabledHit.y());



            appendDebug("[Calib 3/6] inserting calibration prompt...");

            String calPrompt = config.get("calibration.prompt",

                    "Reply with exactly one fenced code block and no text outside it. The block must contain a single line: OK");

            long t3 = System.currentTimeMillis();

            windowManager.activate();

            Thread.sleep(200L);

            clipboardService.setText(calPrompt);

            AppLogger.info("runCalibrationSequence: STEP3 clicking input at " + inputHit.x() + "," + inputHit.y());

            inputSimulator.click(inputHit.x(), inputHit.y());

            Thread.sleep(300L);

            AppLogger.info("runCalibrationSequence: STEP3 sending Ctrl+A");

            inputSimulator.ctrlA();

            Thread.sleep(150L);

            AppLogger.info("runCalibrationSequence: STEP3 sending Ctrl+V");

            inputSimulator.ctrlV();

            Thread.sleep(200L);

            AppLogger.info("runCalibrationSequence: STEP3 prompt inserted in " + (System.currentTimeMillis() - t3) + " ms");



            appendDebug("[Calib 4/6] waiting for send-active (up to 5 s)...");

            long t4 = System.currentTimeMillis();

            AutoCalibrator.MarkerHit sendActiveHit = null;

            long deadline4 = t4 + 5_000L;

            int polls4 = 0;

            while (System.currentTimeMillis() < deadline4 && !EmergencyStop.isStopped()) {

                polls4++;

                long tp = System.currentTimeMillis();

                AutoCalibrator.MarkerHit h = AutoCalibrator.findSend(window, svc);

                AppLogger.info("runCalibrationSequence: findSend(afterText) poll#" + polls4

                        + " took=" + (System.currentTimeMillis() - tp) + " ms result="

                        + (h == null ? "null" : h.x() + "," + h.y() + " #" + h.observedColorHex() + " disabled=" + h.disabledObserved()));

                if (h != null && !h.disabledObserved()) { sendActiveHit = h; break; }

                try { Thread.sleep(250L); }

                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }

            }

            AppLogger.info("runCalibrationSequence: STEP4 SEND(active) done total=" + (System.currentTimeMillis() - t4)

                    + " ms polls=" + polls4 + " result=" + (sendActiveHit == null ? "NOT FOUND" : "FOUND"));

            if (sendActiveHit == null) {

                return failCalibration(scale, "STEP4: SEND did not switch to active within 5s after text insertion");

            }

            appendDebug("[Calib 4/6] send(active) at " + sendActiveHit.x() + "," + sendActiveHit.y() + " #" + sendActiveHit.observedColorHex());



            appendDebug("[Calib 5/6] submitting prompt; then poll code_copy up to 20 s...");

            clipboardService.flushClipboard();

            Thread.sleep(80L);

            inputSimulator.enter();

            AppLogger.info("runCalibrationSequence: STEP5 submitted, awaiting render 1 s");

            Thread.sleep(1000L);



            AutoCalibrator.MarkerHit inputHit2 = AutoCalibrator.findInputField(window, svc);

            AutoCalibrator.MarkerHit sendHit2  = AutoCalibrator.findSend(window, svc);

            AppLogger.info("runCalibrationSequence: STEP6 re-scan after shift-clear input="

                    + (inputHit2 == null ? "NOT FOUND" : inputHit2.x() + "," + inputHit2.y() + " #" + inputHit2.observedColorHex())

                    + " send="

                    + (sendHit2 == null ? "NOT FOUND" : sendHit2.x() + "," + sendHit2.y() + " #" + sendHit2.observedColorHex()

                            + " disabled=" + sendHit2.disabledObserved()));



            WinApiChatScroller scroller = new WinApiChatScroller();

            AutoCalibrator.MarkerHit copyHit = null;

            long hwndLong7 = window.hwndValue();

            int startW = window.right() - window.left();

            int startH = window.bottom() - window.top();

            com.freemaf.agent.winapi.WinApiService.WorkspaceBounds wb7 =

                    com.freemaf.agent.winapi.WinApiService.getWorkspaceBounds(window.hwnd());

            int workspaceW7 = wb7 == null ? 1920 : wb7.width();

            int workspaceH7 = wb7 == null ? 1080 : wb7.height();

            boolean growEnabled = config.getBoolean("window.calibration.growth.enabled", false);

            int maxW7 = growEnabled ? Math.min(workspaceW7 > 0 ? workspaceW7 : 1920, 1920) : startW;

            int maxH7 = growEnabled ? Math.min(workspaceH7 > 0 ? workspaceH7 : 1080, 1080) : startH;

            final int GROW_STEP = 20;

            final int POLLS_PER_STEP = 4;

            final int FINAL_STABILITY_POLLS = 3;

            WindowInfo current7 = window;

            int acceptedW = startW;

            int acceptedH = startH;

            AppLogger.info("runCalibrationSequence: STEP7 grow loop start=" + startW + "x" + startH

                    + " max=" + maxW7 + "x" + maxH7 + " step=" + GROW_STEP

                    + " pollsPerStep=" + POLLS_PER_STEP);

            int growIter = 0;

            for (int curW = startW, curH = startH; ; curW += GROW_STEP, curH += GROW_STEP, growIter++) {

                if (curW > maxW7 && curH > maxH7) break;

                int nextW = Math.min(curW, maxW7);

                int nextH = Math.min(curH, maxH7);

                if (growIter > 0) {

                    boolean applied = windowManager.applySize(nextW, nextH);

                    try { Thread.sleep(500L); }

                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }

                    current7 = WindowFinder.findByHwnd(hwndLong7, config.getWindowTitle()).orElse(current7);

                    int actualW = current7.right() - current7.left();

                    int actualH = current7.bottom() - current7.top();

                    AppLogger.info("runCalibrationSequence: STEP7 grow it=" + growIter

                            + " applied=" + applied + " requested=" + nextW + "x" + nextH

                            + " actual=" + actualW + "x" + actualH);

                    if (!applied) break;

                }

                long t7 = System.currentTimeMillis();

                int scrolls = 0;

                for (int p = 0; p < POLLS_PER_STEP && !EmergencyStop.isStopped(); p++) {

                    try { scroller.scrollToBottom(current7); scrolls++; } catch (Throwable ignored) { }

                    try { Thread.sleep(500L); }

                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }

                    java.util.Optional<AutoCalibrator.MarkerHit> opt = AutoCalibrator.findCodeCopy(current7, windowManager, svc);

                    if (opt.isPresent()) { copyHit = opt.get(); break; }

                }

                AppLogger.info("runCalibrationSequence: STEP7 size=" + nextW + "x" + nextH

                        + " iterMs=" + (System.currentTimeMillis() - t7)

                        + " scrolls=" + scrolls

                        + " result=" + (copyHit == null ? "NOT FOUND" : "FOUND"));

                if (copyHit != null) {

                    acceptedW = nextW;

                    acceptedH = nextH;

                    int prevY = copyHit.y();

                    int stable = 0;

                    for (int s = 0; s < FINAL_STABILITY_POLLS && !EmergencyStop.isStopped(); s++) {

                        try { scroller.scrollToBottom(current7); } catch (Throwable ignored) { }

                        try { Thread.sleep(500L); }

                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }

                        java.util.Optional<AutoCalibrator.MarkerHit> opt = AutoCalibrator.findCodeCopy(current7, windowManager, svc);

                        if (opt.isPresent()) {

                            AutoCalibrator.MarkerHit h = opt.get();

                            if (h.y() == prevY) stable++;

                            else { stable = 0; prevY = h.y(); }

                            copyHit = h;

                        } else { stable = 0; }

                    }

                    AppLogger.info("runCalibrationSequence: STEP7 stability=" + stable

                            + " final=" + copyHit.x() + "," + copyHit.y() + " #" + copyHit.observedColorHex());

                    break;

                }

            }

            if (copyHit == null) {

                return failCalibration(scale, "STEP7: code_copy not found at any window size up to "

                        + maxW7 + "x" + maxH7 + " (step=" + GROW_STEP

                        + ", start=" + startW + "x" + startH + ")");

            }

            if (acceptedW != startW || acceptedH != startH) {

                config.set("window.work.width", String.valueOf(acceptedW));

                config.set("window.work.height", String.valueOf(acceptedH));

                config.save();

                AppLogger.info("runCalibrationSequence: window.work size persisted as " + acceptedW + "x" + acceptedH);

            }



            WindowInfo finalWindow = WindowFinder.findByHwnd(hwndLong7, config.getWindowTitle()).orElse(current7);

            AutoCalibrator.MarkerHit inputHitFinal = AutoCalibrator.findInputField(finalWindow, svc);

            AutoCalibrator.MarkerHit sendHitFinal  = AutoCalibrator.findSend(finalWindow, svc);

            if (inputHitFinal == null || sendHitFinal == null) {

                return failCalibration(scale, "STEP7.5: input/send re-scan failed on final size "

                        + acceptedW + "x" + acceptedH

                        + " (input=" + (inputHitFinal == null ? "NOT FOUND" : "ok")

                        + ", send=" + (sendHitFinal == null ? "NOT FOUND" : "ok") + ")");

            }

            inputHit2 = inputHitFinal;

            sendHit2 = sendHitFinal;

            AppLogger.info("runCalibrationSequence: STEP7.5 final size=" + acceptedW + "x" + acceptedH

                    + " input=" + inputHitFinal.x() + "," + inputHitFinal.y() + " #" + inputHitFinal.observedColorHex()

                    + " send=" + sendHitFinal.x() + "," + sendHitFinal.y() + " #" + sendHitFinal.observedColorHex()

                    + " disabled=" + sendHitFinal.disabledObserved());

            appendDebug("[Calib 5/6] code_copy at " + copyHit.x() + "," + copyHit.y() + " #" + copyHit.observedColorHex()

                    + " (window " + acceptedW + "x" + acceptedH + ")");



            appendDebug("[Calib 6/6] searching regenerate (xR = code_copy.x - const)...");

            int xOffset = config.getRegenerateXOffsetFromCodeCopy();

            int xR = copyHit.x() - xOffset;

            AppLogger.info("runCalibrationSequence: STEP8 xCopy=" + copyHit.x() + " xOffset=" + xOffset + " xR=" + xR);

            long t8 = System.currentTimeMillis();

            AutoCalibrator.MarkerHit regenHit = AutoCalibrator.findRegenerate(finalWindow, xR, svc);

            AppLogger.info("runCalibrationSequence: STEP8 REGENERATE done total=" + (System.currentTimeMillis() - t8)

                    + " ms result=" + (regenHit == null ? "NOT FOUND"

                            : regenHit.x() + "," + regenHit.y() + " #" + regenHit.observedColorHex()));

            if (regenHit == null) {

                return failCalibration(scale, "STEP8: REGENERATE not found at xR=" + xR

                        + " (xCopy=" + copyHit.x() + " - xOffset=" + xOffset + ")");

            }

            appendDebug("[Calib 6/6] regenerate at " + regenHit.x() + "," + regenHit.y() + " #" + regenHit.observedColorHex());



            AutoCalibrator.MarkerHit finalInput = inputHit2 != null ? inputHit2 : inputHit;

            AutoCalibrator.MarkerHit finalSend  = sendHit2  != null ? sendHit2  : sendActiveHit;

            java.util.Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new java.util.LinkedHashMap<>();

            java.util.Map<String, String> colors = new java.util.LinkedHashMap<>();

            hits.put(CalibrationMarker.INPUT_FIELD, finalInput);

            colors.put(CalibrationMarker.INPUT_FIELD.id(), finalInput.observedColorHex());

            hits.put(CalibrationMarker.SEND, finalSend);

            colors.put(CalibrationMarker.SEND.id(), finalSend.observedColorHex());

            hits.put(CalibrationMarker.REGENERATE, regenHit);

            colors.put(CalibrationMarker.REGENERATE.id(), regenHit.observedColorHex());

            hits.put(CalibrationMarker.CODE_COPY, copyHit);

            colors.put(CalibrationMarker.CODE_COPY.id(), copyHit.observedColorHex());

            AutoCalibrator.CalibrationResult result = new AutoCalibrator.CalibrationResult(colors, hits, copyHit);

            inputSimulator.setCalibration(result);

            long totalMs = System.currentTimeMillis() - tStartAll;

            appendSystem("Calibration completed (scale=" + scale + "%).");

            AppLogger.info("runCalibrationSequence: calibration ready, markers=" + hits.size() + " total=" + totalMs + " ms");

            setStatus(StatusMarker.State.PAUSED);

            return true;

        }

    }



    @Deprecated
    private boolean runLegacyCalibrationSequence(WindowInfo window, int scale) throws Exception {

        long tStartAll = System.currentTimeMillis();

        int dx = window.left() - 50;

        int dy = window.top() - 50;

        int inFreshX = config.getInt("legacy.input.fresh.x", 94) + dx;

        int inFreshY = config.getInt("legacy.input.fresh.y", 333) + dy;

        int inClrX   = config.getInt("legacy.input.cleared.x", 94) + dx;

        int inClrY   = config.getInt("legacy.input.cleared.y", 365) + dy;

        int sendFreshX = config.getInt("legacy.send.fresh.x", 709) + dx;

        int sendFreshY = config.getInt("legacy.send.fresh.y", 372) + dy;

        int sendClrX = config.getInt("legacy.send.cleared.x", 725) + dx;

        int sendClrY = config.getInt("legacy.send.cleared.y", 456) + dy;

        int copyX = config.getInt("legacy.code_copy.x", 605) + dx;

        int copyY = config.getInt("legacy.code_copy.y", 247) + dy;

        int regenX = config.getInt("legacy.regenerate.x", 125) + dx;

        int regenY = config.getInt("legacy.regenerate.y", 308) + dy;

        AppLogger.info("runLegacyCalibrationSequence: window offset dx=" + dx + " dy=" + dy);



        try (PixelColorService svc = new PixelColorService()) {

            appendDebug("[Calib L 1/6] reading input_field.fresh at " + inFreshX + "," + inFreshY);

            String freshInputHex = ColorUtils.toHex(svc.readPixel(inFreshX, inFreshY));

            AppLogger.info("runLegacy: input.fresh=" + inFreshX + "," + inFreshY + " #" + freshInputHex);



            appendDebug("[Calib L 2/6] reading send.fresh(disabled) at " + sendFreshX + "," + sendFreshY);

            String sendFreshDisabledHex = ColorUtils.toHex(svc.readPixel(sendFreshX, sendFreshY));

            AppLogger.info("runLegacy: send.fresh(disabled)=" + sendFreshX + "," + sendFreshY + " #" + sendFreshDisabledHex);



            appendDebug("[Calib L 3/6] inserting calibration prompt");

            String calPrompt = config.get("calibration.prompt",

                    "Reply with exactly one fenced code block and no text outside it. The block must contain a single line: OK");

            windowManager.activate();

            Thread.sleep(200L);

            clipboardService.setText(calPrompt);

            inputSimulator.click(inFreshX, inFreshY);

            Thread.sleep(120L);

            inputSimulator.ctrlA();

            Thread.sleep(80L);

            inputSimulator.ctrlV();

            Thread.sleep(150L);



            appendDebug("[Calib L 4/6] waiting for send.fresh to become active (up to 5 s)");

            long deadline4 = System.currentTimeMillis() + 5000L;

            String sendFreshActiveHex = "";

            while (System.currentTimeMillis() < deadline4 && !EmergencyStop.isStopped()) {

                sendFreshActiveHex = ColorUtils.toHex(svc.readPixel(sendFreshX, sendFreshY));

                if ("5686FE".equalsIgnoreCase(sendFreshActiveHex)) break;

                Thread.sleep(200L);

            }

            AppLogger.info("runLegacy: send.fresh(active)=" + sendFreshX + "," + sendFreshY + " #" + sendFreshActiveHex);



            appendDebug("[Calib L 5/6] submitting prompt");

            clipboardService.flushClipboard();

            Thread.sleep(80L);

            inputSimulator.enter();



            appendSystem("Жду ответа агента. Убедись, что ответ полностью сгенерирован и под ним видны кнопка Copy и ряд иконок (⟲ 👍 👎 🔊 ↗), затем нажми OK.");

            if (!showConfirmDialog(

                    "Подожди, пока агент полностью сгенерирует ответ." + System.lineSeparator()

                            + "Убедись, что под ответом видны кнопка Copy и ряд иконок (⟲ 👍 👎 🔊 ↗)." + System.lineSeparator()

                            + "Затем нажми OK.",

                    "Калибровка: дождись ответа агента")) {

                return failCalibration(scale, "legacy: пользователь отменил ожидание ответа");

            }

            Thread.sleep(300L);



            appendDebug("[Calib L 6/6] reading cleared-state markers");

            String clearedInputHex = ColorUtils.toHex(svc.readPixel(inClrX, inClrY));

            String clearedSendHex = ColorUtils.toHex(svc.readPixel(sendClrX, sendClrY));

            String copyHex = ColorUtils.toHex(svc.readPixel(copyX, copyY));

            String regenHex = ColorUtils.toHex(svc.readPixel(regenX, regenY));

            AppLogger.info("runLegacy: cleared input=" + inClrX + "," + inClrY + " #" + clearedInputHex

                    + " | send=" + sendClrX + "," + sendClrY + " #" + clearedSendHex

                    + " | code_copy=" + copyX + "," + copyY + " #" + copyHex

                    + " | regenerate=" + regenX + "," + regenY + " #" + regenHex);



            java.util.Map<CalibrationMarker, AutoCalibrator.MarkerHit> hits = new java.util.LinkedHashMap<>();

            java.util.Map<String, String> colors = new java.util.LinkedHashMap<>();

            AutoCalibrator.MarkerHit inputHit = new AutoCalibrator.MarkerHit(inClrX, inClrY, clearedInputHex.toUpperCase(), false);

            AutoCalibrator.MarkerHit sendHit = new AutoCalibrator.MarkerHit(sendClrX, sendClrY, clearedSendHex.toUpperCase(), true);

            AutoCalibrator.MarkerHit copyHit = new AutoCalibrator.MarkerHit(copyX, copyY, copyHex.toUpperCase(), false);

            AutoCalibrator.MarkerHit regenHit = new AutoCalibrator.MarkerHit(regenX, regenY, regenHex.toUpperCase(), false);

            hits.put(CalibrationMarker.INPUT_FIELD, inputHit);

            hits.put(CalibrationMarker.SEND, sendHit);

            hits.put(CalibrationMarker.CODE_COPY, copyHit);

            hits.put(CalibrationMarker.REGENERATE, regenHit);

            colors.put(CalibrationMarker.INPUT_FIELD.id(), clearedInputHex.toUpperCase());

            colors.put(CalibrationMarker.SEND.id(), clearedSendHex.toUpperCase());

            colors.put(CalibrationMarker.CODE_COPY.id(), copyHex.toUpperCase());

            colors.put(CalibrationMarker.REGENERATE.id(), regenHex.toUpperCase());

            AutoCalibrator.CalibrationResult result = new AutoCalibrator.CalibrationResult(colors, hits, copyHit);

            inputSimulator.setCalibration(result);

            long totalMs = System.currentTimeMillis() - tStartAll;

            appendSystem("Calibration completed (legacy mode, scale=" + scale + "%).");

            AppLogger.info("runLegacyCalibrationSequence: ready, markers=4, total=" + totalMs + " ms");

            setStatus(StatusMarker.State.PAUSED);

            return true;

        }

    }



    private boolean failCalibration(int scale, String reason) {

        AppLogger.warn("runCalibrationSequence: FAILED - " + reason);

        appendSystem("Calibration FAILED: " + reason);

        showIncompatiblePcDialog(new CompatibilityChecker.CompatibilityResult(

                CompatibilityChecker.CompatibilityResult.Status.INCOMPATIBLE_PC, reason), scale);

        return false;

    }



    private void showIncompatiblePcDialog(CompatibilityChecker.CompatibilityResult result, int scalePercent) {

        String diag = result == null ? "" : result.diagnostic();

        String message = "Требуется минимум " + CompatibilityChecker.MIN_WORKSPACE_WIDTH

                + "x" + CompatibilityChecker.MIN_WORKSPACE_HEIGHT + "." + System.lineSeparator()

                + "Системный масштаб: " + scalePercent + "%." + System.lineSeparator()

                + "Причина: " + diag;

        try {

            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(

                    MainFrame.this, message, "Фреймворк несовместим с этим ПК", JOptionPane.ERROR_MESSAGE));

        } catch (Exception e) {

            AppLogger.warn("showIncompatiblePcDialog failed: " + e.getMessage());

        }

        appendSystem("Framework is incompatible with this PC: " + diag);

        setStatus(StatusMarker.State.STOPPED);

    }



    private boolean showConfirmDialog(String message, String title) throws Exception {

        final boolean[] done = {false};

        SwingUtilities.invokeAndWait(() -> {

            int result = JOptionPane.showConfirmDialog(MainFrame.this, message, title,

                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);

            done[0] = result == JOptionPane.OK_OPTION;

        });

        return done[0];

    }



    private void appendSystem(String line) {

        SwingUtilities.invokeLater(() -> chatConsole.appendSystem(line));

    }



    private void appendDebug(String line) {

        if (!config.getBoolean("debug.show_in_chat", true)) return;

        SwingUtilities.invokeLater(() -> chatConsole.appendDebug(line));

    }



    private void setStatus(StatusMarker.State state) {

        this.currentState = state;

        SwingUtilities.invokeLater(() -> {

            statusLabel.setText(state.name());

            updateStartButton();

            updateStatusIndicator();

        });

    }



    private void updateStatusIndicator() {

        Color c = switch (currentState) {

            case STOPPED -> new Color(220, 50, 50);

            case WORKING, PLANNING, CALIBRATION -> new Color(150, 255, 80);

            case PAUSED -> new Color(255, 165, 0);

        };

        statusIndicator.setBackground(c);

    }



    private void stopAction() {

        EmergencyStop.stop();
        if (cdpBootstrap != null) {
            try { cdpBootstrap.stop(); } catch (Exception cex) { AppLogger.warn("stopAction: cdpBootstrap.stop failed: " + cex.getMessage()); }
        }

        lastStopAtMs = System.currentTimeMillis();

        Thread t = startSequenceThread;

        if (t != null && t.isAlive()) {

            AppLogger.info("stopAction: interrupting StartSequence thread");

            t.interrupt();

        }

        if (currentLoop != null) {

            if (!currentLoop.isReviewerActive()) {

                config.setLastPhase("EXECUTOR");

                config.setLastReviewerHwnd(0);

                config.setLastReviewerTitle("");

                AppLogger.info("stopAction: active role is EXECUTOR, clearing reviewer phase marker");

            }

            config.setResumeAfterStop(true);

        }

        Process currentScriptProcess = processManager.getCurrentScriptProcess();

        if (currentScriptProcess != null) {

            processManager.killProcessTree(currentScriptProcess);

        } else {

            processManager.killCurrentProcess();

        }

        setStatus(StatusMarker.State.STOPPED);

        startSequenceRunning = false;

        currentLoop = null;

        updateStartButton();

        chatConsole.appendSystem("Stopped by user");

    }



    private void restartAction() {

        Path wipPath = Path.of("agent", "project", "WIP.md");

        String wip;

        if (Files.exists(wipPath)) {

            try { wip = Files.readString(wipPath, StandardCharsets.UTF_8); }

            catch (IOException e) {

                chatConsole.appendSystem("Failed to read WIP.md - " + e.getMessage());

                wip = "(не удалось прочитать WIP.md: " + e.getMessage() + ")";

            }

        } else {

            wip = "(файл agent/project/WIP.md не найден — журнал ещё не создан)";

            chatConsole.appendSystem("WIP.md not found, sending restart without journal.");

        }

        String restartPrompt = PromptBuilder.buildFollowUp(SystemInstructionProvider.getFormatReminder(),

                "### WIP.md" + System.lineSeparator() + wip + System.lineSeparator()

                        + "Roll back all changes described in the plan and start the plan from scratch.");

        promptQueue.add(restartPrompt);

        setStatus(StatusMarker.State.PLANNING);

        chatConsole.appendSystem("Restart instruction queued");

    }



}
