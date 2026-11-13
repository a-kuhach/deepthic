
package com.freemaf.agent;

public final class PromptBuilder {

    public static final String FORMAT_REMINDER =

            "### FORMAT_REMINDER" + System.lineSeparator()

            + "Первая строка ответа: [ROLE]. Первая строка внутри PS-блока: Write-Output \"ROLE: ТЕГ\"." + System.lineSeparator()

            
+ "REQUIRED: первая строка ответа = [ROLE], где ROLE one of: ANALYST, PLANNER, EXECUTOR, JUDGE, CODE_REVIEWER." + System.lineSeparator()
            + "REQUIRED: ровно ОДИН fenced PowerShell-блок (```powershell ... ```)." + System.lineSeparator()
            + "REQUIRED: первая строка внутри блока = Write-Output \"ROLE: <ROLE>\" в двойных кавычках." + System.lineSeparator()
            + "Ничего вне fenced-блока после закрывающих кавычек." + System.lineSeparator()
            + System.lineSeparator()
            + "WRONG examples (will be rejected, regeneration costs a turn):" + System.lineSeparator()
            + "  X ответ без fenced-блока" + System.lineSeparator()
            + "  X два fenced-блока в одном ответе" + System.lineSeparator()
            + "  X [ROLE] отсутствует или вне fenced-блока" + System.lineSeparator()
            + "  X Write-Output ROLE: EXECUTOR без кавычек (нужно Write-Output \"ROLE: EXECUTOR\")" + System.lineSeparator()
            + "  X текст после закрывающих ``` (только внутри блока)" + System.lineSeparator()
            + System.lineSeparator()
            + "CORRECT skeleton:" + System.lineSeparator()
            + "  [EXECUTOR]" + System.lineSeparator()
            + "  ```powershell" + System.lineSeparator()
            + "  Write-Output \"ROLE: EXECUTOR\"" + System.lineSeparator()
            + "  Write-Output \"USER_MSG: <status>\"" + System.lineSeparator()
            + "  <тело скрипта>" + System.lineSeparator()
            + "  ```" + System.lineSeparator();

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
