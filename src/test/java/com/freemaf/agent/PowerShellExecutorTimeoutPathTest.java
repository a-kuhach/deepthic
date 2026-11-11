
package com.freemaf.agent;



import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;



import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.concurrent.atomic.AtomicInteger;



import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.doAnswer;

import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.never;

import static org.mockito.Mockito.spy;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;



class PowerShellExecutorTimeoutPathTest {



    @TempDir

    Path tmp;



    @Test

    void survivesChildHoldingStdoutOpen() throws Exception {

        Config cfg = new Config();

        cfg.set("watchdog.script.timeout.seconds", "2");

        cfg.set("watchdog.script.check.interval.ms", "200");

        PowerShellExecutor ex = new PowerShellExecutor(new ProcessManager(), cfg);

        Path script = tmp.resolve("holder.ps1");

        Files.writeString(script,

                "Start-Job -ScriptBlock { Start-Sleep -Seconds 100 } | Out-Null\r\nStart-Sleep -Seconds 10\r\n",

                StandardCharsets.UTF_8);

        long t0 = System.currentTimeMillis();

        ExecutionResult r = ex.execute(script);

        long wallMs = System.currentTimeMillis() - t0;

        assertTrue(r.isTimedOut(), "long script must be marked timed out");

        assertTrue(wallMs < 60000L, "execute() must return within bounded wall clock; wallMs=" + wallMs);

    }



    @Test

    void exitValueGuardedWhenStillAlive() {

        Process pAlive = mock(Process.class);

        when(pAlive.isAlive()).thenReturn(true);

        assertEquals(-1, PowerShellExecutor.guardedExitCode(pAlive));

        verify(pAlive, never()).exitValue();



        Process pDeadThrows = mock(Process.class);

        when(pDeadThrows.isAlive()).thenReturn(false);

        when(pDeadThrows.exitValue()).thenThrow(new IllegalThreadStateException("still alive"));

        assertEquals(-1, PowerShellExecutor.guardedExitCode(pDeadThrows));



        Process pOk = mock(Process.class);

        when(pOk.isAlive()).thenReturn(false);

        when(pOk.exitValue()).thenReturn(42);

        assertEquals(42, PowerShellExecutor.guardedExitCode(pOk));

    }



    @Test

    void watchdogRetriesKillUntilDead() throws Exception {

        Config cfg = new Config();

        cfg.set("watchdog.script.timeout.seconds", "1");

        cfg.set("watchdog.script.check.interval.ms", "200");



        ProcessManager pm = spy(new ProcessManager());

        AtomicInteger killCalls = new AtomicInteger();

        doAnswer(inv -> {

            int n = killCalls.incrementAndGet();

            if (n >= 2) {

                inv.callRealMethod();

            }

            return null;

        }).when(pm).killProcessTree(any(Process.class));



        PowerShellExecutor ex = new PowerShellExecutor(pm, cfg);

        Path script = tmp.resolve("retry.ps1");

        Files.writeString(script, "Start-Sleep -Seconds 10\r\n", StandardCharsets.UTF_8);



        ExecutionResult r = ex.execute(script);

        assertTrue(r.isTimedOut(), "script must be marked timed out");

        assertTrue(killCalls.get() >= 2, "watchdog must retry kill; calls=" + killCalls.get());

    }

}
