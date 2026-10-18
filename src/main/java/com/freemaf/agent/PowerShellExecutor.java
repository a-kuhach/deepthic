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
                    wd.shutdown();
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
        long elapsedSeconds = (System.currentTimeMillis() - processStartMs) / 1000L;
        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();
        int exitCode = process.exitValue();
        ExecutionResult result = new ExecutionResult(stdout, stderr, exitCode, timedOutFlag, elapsedSeconds);
        ExecutionLogger.save(result);
        return result;
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
