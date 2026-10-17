# Deepthic

**Version: beta v0.3.41**

A Windows bridge that drives a Chrome tab on the DeepSeek Web chat and uses it as a remote LLM agent for your project.

The active transport is CDP (Chrome DevTools Protocol) over WebSocket + HTTP against headless Chrome with a persistent profile. The legacy WinAPI path (SendInput, pixel markers, calibration, CF_HDROP clipboard) remains in the codebase but is marked `@Deprecated` and is not used when `cdp.enabled=true`, which is the default since v0.3.0.

No Selenium, no Playwright, no browser extensions, no official DeepSeek API, and no JavaScript injection: only the in-house CDP client built on `java.net.http`.

## What it does

- Launches (or reuses) headless Chrome on https://chat.deepseek.com/ with the persistent profile `agent/cache/cdp-profile`.
- Sends a prompt into the DeepSeek chat input via `Runtime.evaluate` + `execCommand('insertText')` + synthetic Enter.
- Attaches files via `DOM.setFileInputFiles` (with `Page.fileChooserOpened` as fallback).
- Reads the agent response through `innerText` from the assistant message node.
- Either executes the response as a PowerShell script or shows it in the chat window as a user message (if the agent chose the `USER_CHAT:` format).
- Feeds stdout/stderr/exit code back to the agent as the next prompt.
- Repeats until the agent emits `AGENT_DONE` / `AGENT_STOP` or the user presses Stop / `Shift+F12`.

## Requirements

- Windows 10 or 11 (any display scale; the CDP path is not scale-sensitive).
- JDK 21 or newer (`java` in PATH).
- Maven 3.8 or newer (`mvn` in PATH) for the initial build.
- Google Chrome installed. The framework launches its own Chrome instance with `--remote-debugging-port=9222` and its own `--user-data-dir`; your default Chrome profile is not touched.
- Internet connection to reach https://chat.deepseek.com/.
- A DeepSeek account, logged in once into the persistent profile `agent/cache/cdp-profile`. If the login session drops, the framework temporarily relaunches Chrome in headful mode so you can sign in, then returns to headless.

## Install

1. Download the framework zip from the GitHub releases page.
2. Extract the contents into an `agent/` folder in the root of your project:

        <your-project>/agent/

3. From your project root, run:

        agent\run.bat

   On the first run the script compiles the framework with Maven and starts the GUI. Subsequent runs reuse the compiled jar and start immediately.
4. On the very first CDP run the framework launches headless Chrome with the persistent profile `agent/cache/cdp-profile` and navigates to DeepSeek. If the session is not logged in yet, the framework relaunches Chrome in headful mode for the login; when the session becomes active it stops headful Chrome and returns to headless.
5. Click Start in the GUI and enter a task.

## Configuration

All runtime configuration lives in `agent/config.properties`. The relevant keys:

- `cdp.enabled` (default `true` since v0.3.0) - selects the CDP transport. Set to `false` to fall back to the deprecated WinAPI path.
- The Chrome profile is fixed at `agent/cache/cdp-profile`; the debug port is fixed at `9222`.
- `session.*`, `timeout.*`, `watchdog.*` keys apply to both transports.

On upgrade from an older version, schema migration v7 promotes an existing `cdp.enabled=false` to `true` once. After that, `cdp.enabled` is preserved as-is; to keep the legacy path, set it back to `false` after the first launch of v0.3.0.

## Browser control (CDP)

The framework controls Chrome exclusively through the Chrome DevTools Protocol:

- `ChromeProcessLauncher` starts its own Chrome with `--headless=new`, `--remote-debugging-port=9222`, `--remote-allow-origins=*`, `--user-data-dir=agent/cache/cdp-profile`, `--no-startup-window`, and anti-throttling flags.
- `CdpHttpClient` wraps HTTP endpoints under `/json/*`.
- `CdpWebSocket` implements id-correlated request/response over `java.net.http.WebSocket`.
- `CdpSession` manages tabs: create, attach (`Target.attachToTarget`), activate (`Target.activateTarget`), close.
- `CdpDeepSeek` implements the DeepSeek-specific operations: `sendMessage`, `readLastResponse`, `waitForResponse`, `checkLoggedIn`, `attachFile`.

The WinAPI path (SendInput, pixel markers, calibration, clipboard-based attach) is marked `@Deprecated` and is inactive when `cdp.enabled=true`. It is retained in the codebase as a legacy fallback.

## Agent protocol

The agent replies in one of two formats only:

- `USER_CHAT: <text>` - a message for the user. The framework shows it in the chat and waits for the next user input.
- One fenced PowerShell code block - executed as `script.ps1` from the project root.

Inside the script output the agent can emit the markers `USER_MSG`, `AGENT_PAUSE`, `AGENT_STOP`, `AGENT_DONE`, `AGENT_SESSION_MIGRATE_START`, `AGENT_SESSION_MIGRATE_CONFIRM`.

In CDP mode the assistant response is read directly from the DOM (`innerText` of the assistant message node), not from the system clipboard. The user-visible format (`USER_CHAT:`) is unchanged.

## Attach Files

Use the **Attach Files** button next to the input field, or drag-and-drop files directly onto the Chat pane. Limits:

- up to 20 files per message,
- each file up to 50 MB.

In CDP mode the files are uploaded via `DOM.setFileInputFiles` (with `Page.fileChooserOpened` as fallback), and the framework waits for the attachment to appear in the DOM before sending the prompt. The legacy WinAPI path used `CF_HDROP` + Ctrl+V and is only reachable when `cdp.enabled=false`.

## CDP vs legacy (cdp.enabled)

| Aspect             | CDP path (`cdp.enabled=true`, default) | Legacy WinAPI path (`cdp.enabled=false`) |
|--------------------|----------------------------------------|------------------------------------------|
| Transport          | CDP over `java.net.http.WebSocket`     | SendInput + clipboard + pixel reads      |
| Chrome launch      | Own ProcessBuilder, headless           | Finds/focuses a manual Chrome window     |
| Response reading   | `innerText` from DOM                   | Copy button + clipboard sentinel         |
| Attach files       | `DOM.setFileInputFiles`                | `CF_HDROP` + Ctrl+V                      |
| Marker calibration | Not required (hardcoded selectors)     | Required (pixel anchors + calibration)   |
| Display scale      | Any                                    | Only 100% supported                      |
| Deprecation status | Active default                         | `@Deprecated`, kept as fallback          |

Deprecated components (still compile, not used when `cdp.enabled=true`): `InputSimulator`, `AutoCalibrator`, `MarkerSpec`, `CalibrationResult`, `CalibrateAndVerifyProbe`, `PixelColorService`, `ClipboardService`, `WinApiChatScroller`, `ChatScroller`, `WindowSizer`, `CompatibilityChecker`, `SizingProbe`, `ProbeResult`, `SizeApplier`, `WindowSizeResult`, `CalibrationPanel`, `CalibrationOrchestrator`.

## Files created at runtime

- `agent/config.properties` - CDP flag, session state, timeouts, watchdog thresholds.
- `agent/cache/cdp-profile/` - persistent Chrome profile holding the DeepSeek login session.
- `agent/cache/_/` - backups created by the framework and by PS-script mutations.
- `agent/app.log` - application log.
- `agent/history/` - prompts, responses, scripts and execution logs.
- `agent/project/WIP.md`, `PLAN.md`, `PROJECT_STATE.md` - agent-maintained project state files.
- `script.ps1` - last PowerShell script received from the agent (deleted immediately after execution).

## UI overview

The application window is split into two panes (Chat left, PS Console right). The bottom bar is organized as four stacked rows; each row stretches with the window height:

1. Status row - a rectangular status panel (indicator + state label) at the left, followed by a row of seven buttons: `a-`, `A+`, `Speed Mode: <current>`, Play/Stop (glyph with `Shift+F12` underneath), `Restart Plan`, `Calibrate`, `Clear Chat`. All seven buttons share the same height; thin dark dividers separate adjacent buttons.
2. Attach row - attachment chips on the left (name + x remove button) and the always-visible `Attach Files` button on the right, stretched to the row height.
3. Input row - the chat input `JTextArea` inside a vertical scroll pane. This row absorbs the extra vertical space on window resize. Files can be drag-and-dropped directly onto the input area.
4. SEND row - a full-width `SEND` button with the `Enter` hint on the right.

### Speed Mode button

The speed control is a single toggle button labelled `Speed Mode: <current>`. Each click advances the mode in the order Instant -> Fast -> Normal -> Slow -> Very Slow -> Instant. The selection is persisted in `agent/config.properties` under the `speed.mode` key and is restored on the next launch.

## License

MIT. See `LICENSE`.
