
package com.freemaf.agent;

import java.io.File;

import java.util.List;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.LinkedBlockingQueue;

public final class PromptQueue {

    private final BlockingQueue<Prompt> queue = new LinkedBlockingQueue<>();
    private final AtomicReference<String> pendingUserDuringWorking = new AtomicReference<>(null);

        public void add(String prompt) {
        String p = drainPendingUserDuringWorking();
        if (p != null && !p.isBlank()) {
            queue.add(Prompt.of(p + System.lineSeparator() + System.lineSeparator() + prompt));
        } else {
            queue.add(Prompt.of(prompt));
        }
    }

        public void add(Prompt prompt) {
        String p = drainPendingUserDuringWorking();
        if (p != null && !p.isBlank()) {
            String merged = p + System.lineSeparator() + System.lineSeparator() + prompt.text();
            queue.add(new Prompt(merged, prompt.files(), prompt.userInitiated()));
        } else {
            queue.add(prompt);
        }
    }

    public void addUser(String text, List<File> files) {

        queue.add(Prompt.user(text, files));

    }

    public void addDirect(String prompt) { queue.add(Prompt.of(prompt)); }
    public void addDirect(Prompt prompt) { queue.add(prompt); }

    public void addPendingUserDuringWorking(String text) {
        if (text == null || text.isBlank()) return;
        pendingUserDuringWorking.updateAndGet(prev ->
                (prev == null || prev.isBlank())
                        ? text
                        : prev + System.lineSeparator() + System.lineSeparator() + text);
    }

    public String drainPendingUserDuringWorking() {
        return pendingUserDuringWorking.getAndSet(null);
    }

    public boolean hasPendingUserDuringWorking() {
        String v = pendingUserDuringWorking.get();
        return v != null && !v.isBlank();
    }

    public Prompt take() throws InterruptedException {

        return queue.take();

    }

}
