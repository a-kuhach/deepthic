package com.freemaf.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptQueueTest {

    @Test void drainReturnsNullWhenEmpty() { PromptQueue pq = new PromptQueue(); assertEquals(null, pq.drainPendingUserDuringWorking()); }

    @Test void pendingIsMergedIntoNextAdd() throws Exception { PromptQueue pq = new PromptQueue(); pq.addPendingUserDuringWorking("hello"); pq.add("followup"); Prompt p = pq.take(); assertTrue(p.text().startsWith("hello")); assertTrue(p.text().contains("followup")); }

    @Test void pendingAccumulatesAcrossMultipleCalls() throws Exception { PromptQueue pq = new PromptQueue(); pq.addPendingUserDuringWorking("a"); pq.addPendingUserDuringWorking("b"); pq.add("x"); Prompt p = pq.take(); assertTrue(p.text().contains("a")); assertTrue(p.text().contains("b")); assertTrue(p.text().contains("x")); }

    @Test void addDirectIgnoresPending() throws Exception { PromptQueue pq = new PromptQueue(); pq.addPendingUserDuringWorking("hello"); pq.addDirect("followup"); Prompt p = pq.take(); assertEquals("followup", p.text()); assertTrue(pq.hasPendingUserDuringWorking()); }

    @Test void pendingDrainedOnce() throws Exception { PromptQueue pq = new PromptQueue(); pq.addPendingUserDuringWorking("hello"); pq.add("first"); pq.add("second"); Prompt p1 = pq.take(); Prompt p2 = pq.take(); assertTrue(p1.text().startsWith("hello")); assertEquals("second", p2.text()); }

    @Test void addUserBypassesPending() throws Exception { PromptQueue pq = new PromptQueue(); pq.addPendingUserDuringWorking("pending"); pq.addUser("direct", List.of()); Prompt p = pq.take(); assertEquals("direct", p.text()); assertTrue(pq.hasPendingUserDuringWorking()); }



    @Test void pendingWithFilesMergesIntoNextAdd() throws Exception {

        PromptQueue pq = new PromptQueue();

        java.io.File f1 = new java.io.File("a.txt");

        pq.addPendingUserDuringWorking("hello", java.util.List.of(f1));

        pq.add("followup");

        Prompt p = pq.take();

        assertTrue(p.text().contains("hello"));

        assertTrue(p.text().contains("followup"));

        assertEquals(1, p.files().size());

        assertEquals("a.txt", p.files().get(0).getName());

    }



    @Test void pendingFilesAccumulateAcrossMultipleCalls() throws Exception {

        PromptQueue pq = new PromptQueue();

        java.io.File f1 = new java.io.File("a.txt");

        java.io.File f2 = new java.io.File("b.txt");

        pq.addPendingUserDuringWorking("first", java.util.List.of(f1));

        pq.addPendingUserDuringWorking("second", java.util.List.of(f2));

        pq.add("followup");

        Prompt p = pq.take();

        assertTrue(p.text().contains("first"));

        assertTrue(p.text().contains("second"));

        assertEquals(2, p.files().size());

    }



    @Test void mixedPendingMessagesMergeTextAndFiles() throws Exception {

        PromptQueue pq = new PromptQueue();

        java.io.File f1 = new java.io.File("a.txt");

        java.io.File f2 = new java.io.File("b.txt");

        pq.addPendingUserDuringWorking("first", java.util.List.of(f1));

        pq.addPendingUserDuringWorking("second-no-files");

        pq.addPendingUserDuringWorking("third", java.util.List.of(f2));

        pq.add("followup");

        Prompt p = pq.take();

        assertTrue(p.text().contains("first"));

        assertTrue(p.text().contains("second-no-files"));

        assertTrue(p.text().contains("third"));

        assertTrue(p.text().contains("followup"));

        assertEquals(2, p.files().size());

        assertEquals("a.txt", p.files().get(0).getName());

        assertEquals("b.txt", p.files().get(1).getName());

    }



    @Test void drainPendingUserReturnsTextAndFiles() {

        PromptQueue pq = new PromptQueue();

        java.io.File f1 = new java.io.File("a.txt");

        pq.addPendingUserDuringWorking("hello", java.util.List.of(f1));

        PromptQueue.PendingUser pu = pq.drainPendingUser();

        assertEquals("hello", pu.text());

        assertEquals(1, pu.files().size());

        assertEquals(null, pq.drainPendingUser());

    }



    @Test void drainStringApiStillReturnsTextOnly() {

        PromptQueue pq = new PromptQueue();

        java.io.File f1 = new java.io.File("a.txt");

        pq.addPendingUserDuringWorking("hello", java.util.List.of(f1));

        assertEquals("hello", pq.drainPendingUserDuringWorking());

        assertEquals(null, pq.drainPendingUserDuringWorking());

    }
}
