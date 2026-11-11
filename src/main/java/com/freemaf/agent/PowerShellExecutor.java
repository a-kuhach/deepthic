package com.freemaf.agent;
import com.freemaf.agent.winapi.JobObjectManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class PowerShellExecutor {
    private final ProcessManager processManager;
    private final Config config;
    private volatile long processStartMs;
    private ScheduledExecutorService watchdog;
    private volatile boolean timedOutFlag;
    public PowerShellExecutor(ProcessManager processManager, Config config) {
        this.processManager = processManager;
        this.config = config;
    }
    public ExecutionResult execute(Path script, Consumer<String> lineConsumer) throws IOException, InterruptedException {
        ExecutionResult result = execute(script);
        if (lineConsumer != null) {
            for (String line : result.stdout().split("\r?\n")) {
                if (!line.isEmpty()) lineConsumer.accept(line);
            }
            for (String line : result.stderr().split("\r?\n")) {
                if (!line.isEmpty()) lineConsumer.accept("[stderr] " + line);
            }
        }
        return result;
    }
    public ExecutionResult execute(Path script) throws IOException, InterruptedException {
        
String scriptContent = Files.readString(script, StandardCharsets.UTF_8);
        // FIX94: reject scripts whose first non-blank line is a UI chrome label or a shell name.
        String firstNonBlank = null;
        for (String ln : scriptContent.split("\\R")) {
            if (!ln.isBlank()) { firstNonBlank = ln.trim(); break; }
        }
        if (firstNonBlank != null && firstNonBlank.length() > 0 && firstNonBlank.charAt(0) == '\uFEFF') {
            firstNonBlank = firstNonBlank.substring(1);
        }
        if (firstNonBlank != null) {
            String lo = firstNonBlank.toLowerCase();
            if (lo.equals("powershell") || lo.equals("pwsh") || lo.equals("bash") || lo.equals("sh")
                    || lo.equals("cmd") || lo.equals("copy") || lo.equals("download")
                    || lo.equals("копировать") || lo.equals("скачать")) {
                ExecutionResult blocked = new ExecutionResult("",
                        "Script rejected: first non-blank line is '" + firstNonBlank
                                + "', which looks like a DeepSeek UI header or shell name. "
                                + "The response was mis-parsed; fix CdpDeepSeek.readLastResponse or the model output. "
                                + "Do not start a nested shell.",
                        -2, false, 0L);
                ExecutionLogger.save(blocked);
                AppLogger.warn("PowerShellExecutor: rejected script, first line = " + firstNonBlank);
                return blocked;
            }
        }
        DirectiveValidator.ValidationResult validation = DirectiveValidator.validate(scriptContent);
        if (!validation.allowed()) {
            ExecutionResult blocked = new ExecutionResult("", validation.reason(), -1, false, 0L);
            ExecutionLogger.save(blocked);
            return blocked;
        }
        ScriptLogger.save(scriptContent);
        String scriptPath = script.toAbsolutePath().toString().replace("'", "''");
        String command = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                + "$OutputEncoding = [System.Text.Encoding]::UTF8; "
                + "& '" + scriptPath + "'";
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command);         pb.directory(Path.of(".").toAbsolutePath().normalize().toFile());         pb.redirectErrorStream(false);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GCM_INTERACTIVE", "never");
        pb.environment().put("credential.interactive", "false");
        pb.environment().put("GIT_ASKPASS", "echo");
        pb.environment().put("SSH_ASKPASS", "echo");         // FIX8b: stdin redirect to an empty file plus NonInteractive flag prevent         // the PS process from hanging on interactive prompts. A bare cmdlet alias         // such as Set (short for Set-Variable) prompts for the mandatory -Name         // parameter and reads stdin forever. With NonInteractive plus EOF stdin         // the cmdlet fails fast with ParameterBindingException instead of blocking.         try {             Path stdinFile = Path.of("agent", "cache", "_", "ps_stdin_empty.txt");             Files.createDirectories(stdinFile.getParent());             if (!Files.exists(stdinFile)) Files.writeString(stdinFile, "");             pb.redirectInput(ProcessBuilder.Redirect.from(stdinFile.toFile()));         } catch (Exception stdinErr) {             AppLogger.warn("PowerShellExecutor: stdin redirect setup failed: " + stdinErr.getMessage());         }
        AppLogger.info("PowerShellExecutor: starting script.ps1 (timeoutSec=" + config.getScriptTimeoutSeconds() + ")");
        Process process = pb.start();
        processStartMs = System.currentTimeMillis();
        if (JobObjectManager.isActive()) {
            boolean assigned = JobObjectManager.assign(process);
            if (!assigned) {
                AppLogger.warn("JobObjectManager.assign failed for pid=" + process.pid());
            }
        }
        timedOutFlag = false;
        processManager.setCurrentScriptProcess(process);
        ProcessRegistry.register(process);
        long checkMs = config.getScriptCheckIntervalMs();
        long timeoutSec = config.getScriptTimeoutSeconds();
        ScheduledExecutorService wd = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ps-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog = wd;
        wd.scheduleWithFixedDelay(() -> {
            try {
                long elapsed = System.currentTimeMillis() - processStartMs;
                if (elapsed > timeoutSec * 1000L) {
                    timedOutFlag = true;
                    processManager.killProcessTree(process);
                    if (!process.isAlive()) { wd.shutdown(); }
                }
            } catch (Throwable t) {
                AppLogger.error("watchdog task failed: " + t.getMessage());
            }
        }, checkMs, checkMs, TimeUnit.MILLISECONDS);
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));
        try {
            long defenceDeadlineMs = System.currentTimeMillis() + 2L * timeoutSec * 1000L;
            while (true) {
                if (process.waitFor(5000L, TimeUnit.MILLISECONDS)) break;
                if (System.currentTimeMillis() >= defenceDeadlineMs) {
                    timedOutFlag = true;
                    processManager.killProcessTree(process);
                    break;
                }
            }
        } finally {
            watchdog.shutdownNow();
            watchdog = null;
            ProcessRegistry.unregister(process);
            processManager.clearCurrentScriptProcess(process);
        }
        if (process.isAlive()) {
            AppLogger.warn("PowerShellExecutor: process still alive after defence loop; killing before stream read");
            processManager.killProcessTree(process);
        }
        long elapsedSeconds = (System.currentTimeMillis() - processStartMs) / 1000L;
        AppLogger.info("PowerShellExecutor: script finished (exit=" + guardedExitCode(process) + ", elapsed=" + elapsedSeconds + "s, timedOut=" + timedOutFlag + ")");
        int exitCode = guardedExitCode(process);
        String stdout;
        try { stdout = stdoutFuture.get(30, TimeUnit.SECONDS); }
        catch (TimeoutException te) { AppLogger.warn("PowerShellExecutor: stdout read timeout after kill; closing stream"); try { process.getInputStream().close(); } catch (Exception ignored) { } try { stdout = stdoutFuture.getNow(""); } catch (Throwable t) { stdout = ""; } }
        catch (ExecutionException ee) { AppLogger.warn("PowerShellExecutor: stdout read failed: " + ee.getMessage()); stdout = ""; }
        String stderr;
        try { stderr = stderrFuture.get(30, TimeUnit.SECONDS); }
        catch (TimeoutException te) { AppLogger.warn("PowerShellExecutor: stderr read timeout after kill; closing stream"); try { process.getErrorStream().close(); } catch (Exception ignored) { } try { stderr = stderrFuture.getNow(""); } catch (Throwable t) { stderr = ""; } }
        catch (ExecutionException ee) { AppLogger.warn("PowerShellExecutor: stderr read failed: " + ee.getMessage()); stderr = ""; }
        ExecutionResult result = new ExecutionResult(stdout, stderr, exitCode, timedOutFlag, elapsedSeconds);
        ExecutionLogger.save(result);
        return result;
    }
    static int guardedExitCode(Process p) {
        if (p == null) return -1;
        try {
            return p.isAlive() ? -1 : p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readStream(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLogger.warn("PowerShell stream read failed: " + e.getMessage());
            return "";
        }
    }
}
