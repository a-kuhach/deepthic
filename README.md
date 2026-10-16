# Free MAF Code

**Version: beta v0.2.18**

A Windows automation bridge that drives a manually opened Chrome window with the
DeepSeek Web chat and uses it as a remote LLM agent for your project.

The bridge does not use CDP, Selenium, Playwright, browser extensions, remote
debugging, the DeepSeek API or JavaScript injection. All control is through
WinAPI and OS-level virtual input (SendInput).

## What it does

- Finds or launches a Chrome window on https://chat.deepseek.com/.
- Sends a prompt into the DeepSeek chat input.
- Waits for the response, copies the last code block through the DeepSeek copy button.
- Either executes the response as a PowerShell script, or shows it in the chat
  window as a user message (if the agent chose the USER_CHAT format).
- Feeds stdout/stderr/exit code back to the agent as the next prompt.
- Repeats until the agent emits AGENT_DONE / AGENT_STOP or the user presses Stop.

## Requirements

- Windows 10 or 11, display scale must be **100%**. Other scales (125%, 150%, 175%, 200%, 225%) are not supported yet.
  - A future release will add scale-independent calibration; the codebase already contains the growth loop and tolerance-based matching, they are disabled until the real Copy-button colour at those scales is measured (see `agent/project/PROJECT_STATE.md` -> "Deferred — scale-independent calibration").
- Monitor working area at least 1920x1080.
- DeepSeek page zoom in Chrome must be 100% at all times (not modified programmatically).
- JDK 21 or newer (`java` in PATH).
- Maven 3.8 or newer (`mvn` in PATH) for the initial build.
- Google Chrome installed.
- The DeepSeek web chat reachable at https://chat.deepseek.com/.

## Install

1. Download the framework zip from the GitHub releases page.
2. Extract the contents into an `agent/` folder in the root of your project:

       <your-project>/agent/

3. From your project root, run:

       agent\run.bat

   On the first run the script compiles the framework with Maven and starts the
   GUI. Subsequent runs reuse the compiled jar and start immediately.
4. Chrome opens (or is focused) on the DeepSeek chat. Log in if needed.
5. Click Start and wait for the automatic calibration to finish.

## Calibration

The framework calibrates automatically at startup:

- detects the system scale (100-225%);
- grows the Chrome window step by step from the minimum size (673x473) until all required markers are visible;
- for each candidate size runs an in-window color-search (input field, DeepThink, Search, Send), scrolls the chat to the bottom, and verifies that the code-block Copy button is visible.

Calibration succeeds only when all required markers are found AND visible (including the Copy button of the code block). The final CalibrationResult is handed to the input simulator and stays active for the rest of the session.

If the elements cannot fit even at the maximum window size allowed by the workspace, the framework reports that it is incompatible with this PC (see Requirements) and stops. No automatic retries and no page-zoom changes are performed.

## UI overview

The application window is split into two panes (Chat on the left, PS Console on
the right). The bottom bar contains the input area and the controls.

### Speed Mode button

The old speed dropdown is replaced by a single toggle button labelled
`Speed Mode: <current>`. Each click advances the mode in the order
Instant -> Fast -> Normal -> Slow -> Very Slow -> Instant. The selection is
persisted in `agent/config.properties` under the `speed.mode` key and is restored
on the next launch. The delays applied by the agent are unchanged.

### Attach Files

Use the **Attach Files** button next to the input field, or drag-and-drop files
directly onto the Chat pane, to attach files to the next message. Limits:

- up to 20 files per message,
- each file up to 50 MB.

Attached file names are shown above the input field. On send, the files are
placed on the system clipboard as a Windows file-drop payload (CF_HDROP) and
pasted into the DeepSeek chat input with a single Ctrl+V; the text of the
message is pasted with a second Ctrl+V. Both go into the same chat submission,
so the agent receives files and text together. After submission the local
attachment list is cleared.

### SEND button

The **SEND** button to the right of the input field duplicates the Enter key
behaviour: it sends the current message. The button is disabled when the input
field is empty AND no files are attached; otherwise it is enabled.

### Upload detection

After pasting files, the framework waits for the DeepSeek send button to appear
grey (upload in progress) and then to return to its active colour (upload
finished). If the active colour does not appear within two minutes, a dialog
asks whether to keep waiting; confirming restarts the same two-minute window.

## Files created at runtime

- `agent/config.properties` - window position, marker coordinates and colors, timeouts.
- `script.ps1` - last PowerShell script received from the agent.
- `agent/app.log` - application log.
- `agent/project/WIP.md`, `PROJECT_STATE.md`, `TASK.md`, `PLAN.md` - files the agent
  may create and update to keep track of project state.
- `agent/history/` - prompts, responses, scripts and execution logs.

## Agent protocol

The agent replies in one of two formats only:

- `USER_CHAT: <text>` - a message for the user. The program shows it in the chat
  and waits for the next user input.
- One fenced PowerShell code block - executed as `script.ps1` from the project root.

Inside the script output the agent can emit the markers `USER_MSG`, `AGENT_PAUSE`,
`AGENT_STOP`, `AGENT_DONE`.

## License

MIT. See `LICENSE`.
