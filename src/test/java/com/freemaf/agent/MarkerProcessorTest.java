package com.freemaf.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MarkerProcessorTest {

    private static MarkerEvent pause() { return new MarkerEvent(MarkerType.AGENT_PAUSE, "waiting"); }
    private static MarkerEvent stop()  { return new MarkerEvent(MarkerType.AGENT_STOP,  "done"); }
    private static MarkerEvent userMsg() { return new MarkerEvent(MarkerType.USER_MSG,  "hello"); }

    @Test
    void process_pauseAndStopInThisOrder_returnsHalt() {
        ChatConsole chatConsole = mock(ChatConsole.class);
        StatusMarker statusMarker = mock(StatusMarker.class);
        MarkerProcessResult result = MarkerProcessor.process(List.of(pause(), stop()), chatConsole, statusMarker);
        assertEquals(MarkerProcessResult.HALT, result);
        verify(statusMarker, never()).setState(StatusMarker.State.PAUSED);
    }

    @Test
    void process_stopAndPauseInThisOrder_returnsHalt() {
        ChatConsole chatConsole = mock(ChatConsole.class);
        StatusMarker statusMarker = mock(StatusMarker.class);
        MarkerProcessResult result = MarkerProcessor.process(List.of(stop(), pause()), chatConsole, statusMarker);
        assertEquals(MarkerProcessResult.HALT, result);
        verify(statusMarker, never()).setState(StatusMarker.State.PAUSED);
    }

    @Test
    void process_pauseOnly_returnsPause() {
        ChatConsole chatConsole = mock(ChatConsole.class);
        StatusMarker statusMarker = mock(StatusMarker.class);
        MarkerProcessResult result = MarkerProcessor.process(List.of(pause()), chatConsole, statusMarker);
        assertEquals(MarkerProcessResult.PAUSE, result);
    }

    @Test
    void process_stopOnly_returnsHalt() {
        ChatConsole chatConsole = mock(ChatConsole.class);
        StatusMarker statusMarker = mock(StatusMarker.class);
        MarkerProcessResult result = MarkerProcessor.process(List.of(stop()), chatConsole, statusMarker);
        assertEquals(MarkerProcessResult.HALT, result);
    }

    @Test
    void process_emptyEvents_returnsContinue() {
        ChatConsole chatConsole = mock(ChatConsole.class);
        StatusMarker statusMarker = mock(StatusMarker.class);
        MarkerProcessResult result = MarkerProcessor.process(List.of(), chatConsole, statusMarker);
        assertEquals(MarkerProcessResult.CONTINUE, result);
    }

    @Test
    void process_userMsgAndPause_returnsPause() {
        ChatConsole chatConsole = mock(ChatConsole.class);
        StatusMarker statusMarker = mock(StatusMarker.class);
        MarkerProcessResult result = MarkerProcessor.process(List.of(userMsg(), pause()), chatConsole, statusMarker);
        assertEquals(MarkerProcessResult.PAUSE, result);
    }
}
