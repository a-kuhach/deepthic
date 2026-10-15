
package com.freemaf.agent;

public final class PromptBuilder {

    public static final String FORMAT_REMINDER =

            "### FORMAT_REMINDER" + System.lineSeparator()

            + "Первая строка ответа: [ROLE]. Первая строка внутри PS-блока: Write-Output \"ROLE: ТЕГ\"." + System.lineSeparator()

            + "Ровно один fenced-блок PowerShell. После закрывающих тройных кавычек — ничего." + System.lineSeparator();

    private PromptBuilder() {}

    public static String build(String systemInstruction, String context, String userMessage, String scriptLog) {

        StringBuilder sb = new StringBuilder();

        if (systemInstruction != null && !systemInstruction.isBlank()) {

            sb.append("### SYSTEM_INSTRUCTION").append(System.lineSeparator());

            sb.append(systemInstruction).append(System.lineSeparator());

        }

        if (context != null && !context.isBlank()) {

            sb.append("### CONTEXT").append(System.lineSeparator());

            sb.append(context).append(System.lineSeparator());

        }

        if (userMessage != null && !userMessage.isBlank()) {

            sb.append("### USER_MESSAGE").append(System.lineSeparator());

            sb.append(userMessage).append(System.lineSeparator());

        }

        if (scriptLog != null && !scriptLog.isBlank()) {

            sb.append("### SCRIPT_EXECUTION_LOG").append(System.lineSeparator());

            sb.append(scriptLog).append(System.lineSeparator());

        }

        sb.append(FORMAT_REMINDER);

        return sb.toString();

    }

    public static String buildFollowUp(String formatReminder, String payload) {

        StringBuilder sb = new StringBuilder();

        if (payload != null && !payload.isBlank()) {

            sb.append(payload).append(System.lineSeparator());

        }

        if (formatReminder != null && !formatReminder.isBlank()) {

            sb.append(formatReminder);

        }

        return sb.toString();

    }

}
