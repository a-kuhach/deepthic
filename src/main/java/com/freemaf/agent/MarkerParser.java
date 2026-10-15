
package com.freemaf.agent;

import java.util.List;

import java.util.Optional;

public final class MarkerParser {

    private MarkerParser() {}

    public static List<MarkerEvent> parse(String stdout) {

        if (stdout == null || stdout.isBlank()) return List.of();

        return stdout.lines()

                .map(String::strip)

                .filter(line -> !line.isEmpty())

                .map(MarkerParser::toEvent)

                .filter(Optional::isPresent)

                .map(Optional::get)

                .toList();

    }

    private static Optional<MarkerEvent> toEvent(String line) {

        if (line.startsWith("ROLE:")) {

            return Optional.of(new MarkerEvent(MarkerType.ROLE, afterPrefix(line, "ROLE:")));

        }

        if (line.startsWith("USER_MSG:")) {

            return Optional.of(new MarkerEvent(MarkerType.USER_MSG, afterPrefix(line, "USER_MSG:")));

        }

        if (line.startsWith("AGENT_PAUSE:")) {

            return Optional.of(new MarkerEvent(MarkerType.AGENT_PAUSE, afterPrefix(line, "AGENT_PAUSE:")));

        }

        if (line.startsWith("AGENT_STOP:")) {

            return Optional.of(new MarkerEvent(MarkerType.AGENT_STOP, afterPrefix(line, "AGENT_STOP:")));

        }

        if (line.startsWith("AGENT_DONE:")) {

            return Optional.of(new MarkerEvent(MarkerType.AGENT_DONE, afterPrefix(line, "AGENT_DONE:")));

        }

        if (line.startsWith("AGENT_SESSION_MIGRATE_START:") || line.equals("AGENT_SESSION_MIGRATE_START")) {

            String startMsg = line.startsWith("AGENT_SESSION_MIGRATE_START:")

                    ? afterPrefix(line, "AGENT_SESSION_MIGRATE_START:")

                    : "";

            return Optional.of(new MarkerEvent(MarkerType.AGENT_SESSION_MIGRATE_START, startMsg));

        }

        if (line.startsWith("AGENT_SESSION_MIGRATE_CONFIRM:") || line.equals("AGENT_SESSION_MIGRATE_CONFIRM")) {

            String confirmMsg = line.startsWith("AGENT_SESSION_MIGRATE_CONFIRM:")

                    ? afterPrefix(line, "AGENT_SESSION_MIGRATE_CONFIRM:")

                    : "";

            return Optional.of(new MarkerEvent(MarkerType.AGENT_SESSION_MIGRATE_CONFIRM, confirmMsg));

        }

        return Optional.empty();

    }

    private static String afterPrefix(String line, String prefix) {

        return line.substring(prefix.length()).strip();

    }

}
