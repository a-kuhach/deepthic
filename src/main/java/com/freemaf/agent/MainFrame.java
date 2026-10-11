
package com.freemaf.agent;

import javax.swing.AbstractAction;

import javax.swing.ActionMap;

import javax.swing.BorderFactory;

import javax.swing.Box;

import javax.swing.BoxLayout;

import javax.swing.InputMap;

import javax.swing.JButton;

import javax.swing.JFrame;

import javax.swing.JLabel;

import javax.swing.JOptionPane;

import javax.swing.JPanel;

import javax.swing.JScrollPane;

import javax.swing.JTextArea;

import javax.swing.KeyStroke;

import javax.swing.SwingUtilities;

import java.awt.BorderLayout;

import java.awt.Color;

import java.awt.Dimension;

import java.awt.Font;

import java.awt.GridLayout;

import java.awt.event.ActionEvent;

import java.awt.event.InputEvent;

import java.awt.event.KeyEvent;

import java.io.IOException;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.List;

public final class MainFrame extends JFrame implements WindowRecoveryHandler {

    private static final int[] FONT_SIZES = {10, 12, 18};

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

    private volatile boolean calibrationRunning;

    private volatile AutomationLoop currentLoop;

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

        updateStatusIndicator();
        updateStartButton();

        new Thread(hotkeyListener, "GlobalHotkey").start();

    }

    private static String resolveWindowTitle() {

        try {

            String name = Path.of(".").toAbsolutePath().normalize().getFileName().toString();

            if (name == null || name.isBlank()) name = "Project";

            return name + " - Free MAF Code";

        } catch (RuntimeException e) { return "Project - Free MAF Code"; }

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

        JPanel bottom = new JPanel(new BorderLayout());

        JPanel topRow = new JPanel();

        topRow.setLayout(new BoxLayout(topRow, BoxLayout.X_AXIS));

        statusIndicator.setPreferredSize(new Dimension(15, 15));

        statusIndicator.setMinimumSize(new Dimension(15, 15));

        statusIndicator.setMaximumSize(new Dimension(15, 15));

        statusIndicator.setOpaque(true);

        statusIndicator.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

        topRow.add(statusIndicator);

        topRow.add(Box.createHorizontalStrut(6));

        topRow.add(statusLabel);

        topRow.add(Box.createHorizontalStrut(12));

        JButton fontDown = new JButton("a-");

        JButton fontUp = new JButton("A+");

        fontDown.setMargin(new java.awt.Insets(2, 6, 2, 6));

        fontUp.setMargin(new java.awt.Insets(2, 6, 2, 6));

        topRow.add(fontDown);

        topRow.add(fontUp);

        topRow.add(Box.createHorizontalGlue());

        topRow.add(Box.createHorizontalStrut(12));

        JButton speedButton = new JButton();

        updateSpeedButtonText(speedButton);

        speedButton.addActionListener(ev -> {

            SpeedMode next = inputSimulator.getSpeedMode().next();

            inputSimulator.setSpeedMode(next);

            updateSpeedButtonText(speedButton);

            appendSystem("Speed mode: " + next.getLabel());

            try { config.set("speed.mode", next.name()); config.save(); }

            catch (IOException ex) { AppLogger.warn("save speed.mode failed: " + ex.getMessage()); }

        });

        topRow.add(speedButton);

        topRow.add(Box.createHorizontalStrut(12));
        startButton = new JButton("\u25B6");

        JButton restartButton = new JButton("Restart Plan");

        JButton calibrationButton = new JButton("Calibrate");

        JButton clearChatButton = new JButton("Clear Chat");
        startButton.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 18));
        JLabel hotkeyLabel = new JLabel("Shift+F12");
        JPanel startColumn = new JPanel();
        startColumn.setLayout(new BoxLayout(startColumn, BoxLayout.Y_AXIS));
        startColumn.setBackground(DarkTheme.BG);
        startColumn.add(startButton);
        startColumn.add(hotkeyLabel);
        topRow.add(startColumn);


        topRow.add(restartButton);

        topRow.add(calibrationButton);

        topRow.add(clearChatButton);

        bottom.add(topRow, BorderLayout.NORTH);

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

        JButton attachFilesButton = new JButton("Attach Files");

        attachFilesButton.addActionListener(e -> attachFilesAction());

        sendButton = new JButton("SEND");

        sendButton.addActionListener(e -> submitPrompt());

        attachmentsListPanel.setLayout(new BoxLayout(attachmentsListPanel, BoxLayout.Y_AXIS));

        attachmentsListPanel.setBackground(DarkTheme.BG);

        attachmentsScroll = new JScrollPane(attachmentsListPanel);

        attachmentsScroll.setBorder(null);

        attachmentsScroll.getViewport().setBackground(DarkTheme.BG);

        attachmentsScroll.setVisible(false);

        attachmentsScroll.setPreferredSize(new Dimension(200, 0));

        JPanel attachmentsRow = new JPanel(new BorderLayout());

        attachmentsRow.setBackground(DarkTheme.BG);

        attachmentsRow.add(attachmentsScroll, BorderLayout.CENTER);

        attachmentsRow.add(attachFilesButton, BorderLayout.EAST);

        JPanel sendRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 2));

        sendRow.setBackground(DarkTheme.BG);

        sendRow.add(sendButton);

        JPanel inputWrap = new JPanel(new BorderLayout());

        inputWrap.setBackground(DarkTheme.BG);

        inputWrap.add(attachmentsRow, BorderLayout.NORTH);

        inputWrap.add(inputScroll, BorderLayout.CENTER);

        inputWrap.add(sendRow, BorderLayout.SOUTH);

        bottom.add(inputWrap, BorderLayout.CENTER);

        
javax.swing.TransferHandler defaultTH = inputArea.getTransferHandler();
        javax.swing.TransferHandler attachHandler = buildAttachmentTransferHandler(defaultTH);

        inputArea.setTransferHandler(attachHandler);

        inputScroll.setTransferHandler(attachHandler);

        inputWrap.setTransferHandler(attachHandler);

        inputArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {

            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateSendButtonState(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateSendButtonState(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateSendButtonState(); }

        });

        updateSendButtonState();

        rebuildAttachmentsList();
        startButton.addActionListener(e -> handleStartButtonClick());

        restartButton.addActionListener(e -> restartAction());

        calibrationButton.addActionListener(e -> calibrationAction());

        clearChatButton.addActionListener(e -> clearChatAction());

        fontDown.addActionListener(e -> adjustFont(-1));

        fontUp.addActionListener(e -> adjustFont(+1));

        return bottom;

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
            config.setResumeAfterStop(false); // user gave fresh prompt
            appendSystem("Auto-start (STOPPED → Start).");
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
        new Thread(this::startSequence, "StartSequence").start();
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
        startButton.setText(StartButtonGlyph.glyph(currentState, userPaused));
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

    private void runCalibrationAction() {

        try {

            WindowInfo window = ensureChromeWindow();

            if (window == null) return;

            windowManager.select(window);

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

            calibrationRunning = false;

        }

    }
    private void startSequence() {
        try {
            AutomationLoop loop = new AutomationLoop(chatConsole, psConsolePanel, statusMarkerProxy(), promptQueue,
                    deepSeekSession, powerShellExecutor, config, windowManager);
            SessionDetector.SessionDecision decision = loop.bootstrapSession();
            WindowInfo activeWindow = decision.activeWindow() != null
                    ? decision.activeWindow()
                    : (decision.executorWindow() != null ? decision.executorWindow() : decision.reviewerWindow());
            if (activeWindow == null) {
                appendSystem("Failed to determine active window.");
                setStatus(StatusMarker.State.STOPPED);
                return;
            }
            if (decision.decision() == SessionDetector.Decision.OPEN_NEW_CHAT
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
            if (!setupWorkRect()) return;
            boolean skipCalibration = decision.decision() == SessionDetector.Decision.RESUME_REVIEWER_SESSION;
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
            appendSystem("Waiting for a user message in the input field below.");
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

        int wh = config.getInt("window.work.height", 473);

        if (!windowManager.setRect(wx, wy, ww, wh)) {

            appendSystem("Failed to set window size.");

            setStatus(StatusMarker.State.STOPPED);

            return false;

        }

        appendSystem("Window set to " + wx + "," + wy + " " + ww + "x" + wh);

        Thread.sleep(2000L);

        return true;

    }

    private boolean runCalibrationSequence() throws Exception {

        appendSystem("Static calibration (input field, DeepThink/Search toggles)...");

        AutoCalibrator.CalibrationResult staticResult = AutoCalibrator.calibrateStatic(config);

        appendSystem("Calibration complete:" + System.lineSeparator() + staticResult.summary());

        appendSystem("Calibrating SEND button (disabled state)...");

        AutoCalibrator.CalibrationResult sendDisabled = AutoCalibrator.calibrateSendDisabled(config);

        appendSystem("SEND disabled: " + sendDisabled.summary());

        String calPrompt = config.get("calibration.prompt",

                "Reply with exactly one fenced code block and no text outside it. The block must contain a single line: OK");

        appendSystem("Sending calibration prompt: " + calPrompt);

        deepSeekSession.ensureWindow();

        inputSimulator.typeTextWithoutEnter(calPrompt);

        Thread.sleep(500L);

        appendSystem("Calibrating SEND button (active state)...");

        AutoCalibrator.CalibrationResult sendActive = AutoCalibrator.calibrateSendActive(config);

        appendSystem("SEND active: " + sendActive.summary());

        inputSimulator.enter();

        if (!showConfirmDialog(

                "Wait until the agent answer is fully generated in the chat, then click OK."

                        + System.lineSeparator()

                        + "The app will then read the regenerate and copy button colors.",

                "Waiting for generation")) {

            appendSystem("Calibration cancelled by user.");

            return false;

        }

        windowManager.activate();

        Thread.sleep(500L);

        try { inputSimulator.scrollDown(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }

        Thread.sleep(700L);

        appendSystem("Dynamic calibration (regenerate, copy_last)...");

        AutoCalibrator.CalibrationResult dynamicResult = AutoCalibrator.calibrateDynamic(config);

        appendSystem("Calibration complete:" + System.lineSeparator() + dynamicResult.summary());

        return true;

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

            case WORKING, PLANNING -> new Color(150, 255, 80);

            case PAUSED -> new Color(255, 165, 0);

        };

        statusIndicator.setBackground(c);

    }

    private void stopAction() {

        EmergencyStop.stop();
        if (currentLoop != null) {
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


