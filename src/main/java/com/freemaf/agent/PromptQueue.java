
package com.freemaf.agent;

import java.io.File;
import java.util.ArrayList;

import java.util.List;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.LinkedBlockingQueue;


public final class PromptQueue {



    private final BlockingQueue<Prompt> queue = new LinkedBlockingQueue<>();

    private final AtomicReference<PendingUser> pendingUserDuringWorking = new AtomicReference<>(null);



    public static final class PendingUser {

        private final String text;

        private final List<File> files;



        public PendingUser(String text, List<File> files) {

            this.text = text == null ? "" : text;

            this.files = files == null ? List.of() : List.copyOf(files);

        }



        public String text() { return text; }

        public List<File> files() { return files; }

    }



    public void add(String prompt) {

        PendingUser p = drainPendingUser();

        if (p != null && !p.text().isBlank()) {

            String merged = p.text() + System.lineSeparator() + System.lineSeparator() + prompt;

            queue.add(new Prompt(merged, p.files(), false));

        } else {

            queue.add(Prompt.of(prompt));

        }

    }



    public void add(Prompt prompt) {

        PendingUser p = drainPendingUser();

        if (p != null && !p.text().isBlank()) {

            String merged = p.text() + System.lineSeparator() + System.lineSeparator() + prompt.text();

            List<File> mergedFiles = new ArrayList<>(p.files());

            mergedFiles.addAll(prompt.files());

            queue.add(new Prompt(merged, mergedFiles, prompt.userInitiated()));

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

        addPendingUserDuringWorking(text, List.of());

    }



    public void addPendingUserDuringWorking(String text, List<File> files) {

        if (text == null || text.isBlank()) return;

        List<File> filesCopy = files == null ? List.of() : List.copyOf(files);

        pendingUserDuringWorking.updateAndGet(prev -> {

            if (prev == null) {

                return new PendingUser(text, filesCopy);

            }

            String mergedText = prev.text().isBlank()

                    ? text

                    : prev.text() + System.lineSeparator() + System.lineSeparator() + text;

            List<File> mergedFiles = new ArrayList<>(prev.files());

            mergedFiles.addAll(filesCopy);

            return new PendingUser(mergedText, mergedFiles);

        });

    }



    public PendingUser drainPendingUser() {

        return pendingUserDuringWorking.getAndSet(null);

    }



    public String drainPendingUserDuringWorking() {

        PendingUser p = drainPendingUser();

        return p == null ? null : p.text();

    }



    public boolean hasPendingUserDuringWorking() {

        PendingUser v = pendingUserDuringWorking.get();

        return v != null && !v.text().isBlank();

    }



    public Prompt take() throws InterruptedException {

        return queue.take();

    }



}

