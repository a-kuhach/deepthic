package com.freemaf.agent;

import java.util.List;

public final class MarkerProcessor {

    private MarkerProcessor() {}

    public static MarkerProcessResult process(List<MarkerEvent> events, ChatConsole chatConsole, StatusMarker statusMarker) {
        MarkerProcessResult result = MarkerProcessResult.CONTINUE;
        for (MarkerEvent event : events) {
            switch (event.type()) {
                case ROLE -> { }
                case USER_MSG -> chatConsole.appendAgent(event.message());
                case AGENT_PAUSE -> {
                    if (result != MarkerProcessResult.HALT) {
                        result = MarkerProcessResult.PAUSE;
                    }
                }
                case AGENT_STOP -> {
                    chatConsole.appendSystem("Agent stopped: " + event.message());
                    statusMarker.setState(StatusMarker.State.STOPPED);
                    result = MarkerProcessResult.HALT;
                }
                case AGENT_DONE -> {
                    chatConsole.appendSystem("Agent done: " + event.message());
                    statusMarker.setState(StatusMarker.State.STOPPED);
                    result = MarkerProcessResult.HALT;
                }
                case AGENT_SESSION_MIGRATE_START -> { }
                case AGENT_SESSION_MIGRATE_CONFIRM -> { }
            }
        }
        return result;
    }

    public static String extractRole(List<MarkerEvent> events) {
        for (MarkerEvent event : events) {
            if (event.type() == MarkerType.ROLE) return event.message().toUpperCase();
        }
        return "";
    }
}

