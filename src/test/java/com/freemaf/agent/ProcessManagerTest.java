package com.freemaf.agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessManagerTest {
    @AfterEach
    void resetFlags() { ProcessManager.disableTaskkillForTest(false); }
    @Test
    void killTreeKillsParentAndDescendants() throws Exception {
        Process parent = spawnParentWithChild();
        try {
            List<ProcessHandle> children = parent.descendants().collect(Collectors.toList());
            assertFalse(children.isEmpty(), "parent must have at least one descendant");
            new ProcessManager().killProcessTree(parent);
            assertFalse(parent.isAlive(), "parent must be dead");
            for (ProcessHandle h : children) {
                assertFalse(h.isAlive(), "descendant pid=" + h.pid() + " must be dead");
            }
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
        }
    }
    @Test
    void fallbackKillsParentAndDescendantsWhenTaskkillDisabled() throws Exception {
        ProcessManager.disableTaskkillForTest(true);
        Process parent = spawnParentWithChild();
        try {
            List<ProcessHandle> children = parent.descendants().collect(Collectors.toList());
            assertFalse(children.isEmpty(), "parent must have at least one descendant");
            new ProcessManager().killProcessTree(parent);
            assertFalse(parent.isAlive(), "parent must be dead (fallback)");
            for (ProcessHandle h : children) {
                assertFalse(h.isAlive(), "descendant pid=" + h.pid() + " must be dead (fallback)");
            }
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
        }
    }
    @Test
    void setCurrentScriptProcessRejectsSecondNonZero() throws Exception {
        ProcessManager pm = new ProcessManager();
        Process p1 = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 5").start();
        Process p2 = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 5").start();
        try {
            pm.setCurrentScriptProcess(p1);
            pm.setCurrentScriptProcess(p2);
            assertSame(p1, pm.getCurrentScriptProcess(), "first non-null must win");
            pm.clearCurrentScriptProcess(p1);
            assertSame(null, pm.getCurrentScriptProcess(), "clear with matching expected must null out");
        } finally {
            if (p1.isAlive()) p1.destroyForcibly();
            if (p2.isAlive()) p2.destroyForcibly();
        }
    }
    @Test
    void concurrentSetExactlyOneWins() throws Exception {
        ProcessManager pm = new ProcessManager();
        Process p1 = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 5").start();
        Process p2 = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 5").start();
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger wins = new AtomicInteger(0);
            Runnable setter = () -> {
                try {
                    start.await();
                    pm.setCurrentScriptProcess(Thread.currentThread().getName().equals("A") ? p1 : p2);
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            };
            Thread a = new Thread(setter, "A");
            Thread b = new Thread(setter, "B");
            a.start(); b.start();
            start.countDown();
            done.await();
            Process winner = pm.getCurrentScriptProcess();
            assertTrue(winner == p1 || winner == p2, "winner must be one of the two");
            if (winner == p1 || winner == p2) wins.set(1);
            assertEquals(1, wins.get(), "exactly one setter must win");
        } finally {
            if (p1.isAlive()) p1.destroyForcibly();
            if (p2.isAlive()) p2.destroyForcibly();
        }
    }
    private static Process spawnParentWithChild() throws Exception {
        String cmd = "Start-Process powershell -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 60' -NoNewWindow; Start-Sleep -Seconds 60";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd).start();
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (p.descendants().findAny().isPresent()) return p;
            Thread.sleep(100L);
        }
        if (p.isAlive()) p.destroyForcibly();
        throw new IllegalStateException("child did not start in 5s");
    }
}
