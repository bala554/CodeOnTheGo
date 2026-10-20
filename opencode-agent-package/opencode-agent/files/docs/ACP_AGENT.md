# OpenCode agent over ACP (Agent Client Protocol)

Replaces the simulated installer and the `opencode serve` HTTP client with a real install
and a native ACP client. **Status: written and reviewed, not yet compiled or run on a device.**

## What changed

| Area | Change |
|---|---|
| Install | Real HTTPS download, SHA-256 verification, safe tar.gz extraction (zip-slip protected). Previously a `delay()` simulation. |
| Manifest | `assets/agents/opencode-manifest.json` pinned to the ACP Registry entry for OpenCode 1.18.35 (`linux-aarch64`, `linux-x86_64`, `acp` args). |
| Transport | `acp/AcpConnection.kt`: newline-delimited JSON-RPC 2.0 over the agent's stdin/stdout. |
| Client | `acp/AcpAgentClient.kt`: `initialize`, `session/new`, `session/prompt`, `session/cancel`, `session/set_config_option`; handles `fs/read_text_file`, `fs/write_text_file`, `session/request_permission`. Terminal capability is not advertised. |
| Chat UI | Build/Plan and model selectors now use the agent's real config options (`mode`, `model`). |
| Status dialog | `ui/OpenCodeAgentStatusDialog.kt` (Status / Version / Connection, CHAT / STOP / HIDE). The editor toolbar action opens it first. |
| Safety | `OpenCodeFileBridge`: real path containment, `.git` write protection, stale-read conflict detection, undo stack, LCS diff. |
| Bug fixes | Tool approvals never reached the server and the raw diff text was written over the file; `startServer` reported Running with no server; the health check accepted HTTP 404; wildcard CORS. |

## Permission model

- Reads are free (inside the project root only).
- Edits need approval: the agent is launched with `OPENCODE_PERMISSION={"edit":"ask","bash":"deny"}`, and
  `session/request_permission` is shown as an approval card. Approval is `allow_once` only.
- The agent has no shell.
- If the agent writes files itself (instead of `fs/write_text_file`), the path/conflict/undo protections in
  `OpenCodeFileBridge` do not apply to those writes; the permission request is then the only gate.

## First run checklist

1. Build, open a project, tap the OpenCode toolbar icon, press START in the dialog.
2. The dialog shows the exact launch command while starting. Failures to look for:
   `Permission denied` / `not found` (Android may block running binaries from app storage, or the
   Linux build may not run on bionic).
3. In logcat, filter `AcpAgentClient`: `initialize ->` and `session/new ->` show the agent's
   capabilities and available models/modes. Use `opencode --print-logs acp` (add to `acp_arguments`)
   to get agent-side logs on stderr.
4. Open the model list; expect OpenCode's models (e.g. MiMo-V2.6-Flash Free). Whether free models
   need an OpenCode API key is unverified.

## Known gaps

- Not compiled or run (written without an Android/Kotlin toolchain).
- Not done: `+` attach and `@` file context (ACP `resource_link`), session history
  (`session/list` + `session/load`), effort selector, Undo button, unsaved editor buffers for
  `fs/read_text_file`, ACP `authenticate` / terminal login.
- `OpenCodeProcessManager` still contains the old HTTP `startServer` code (unused).
- `registry/DefaultAgentRegistry` still has its own simulated install (no longer on the install path).
- `OpenCodeFreeProviders.MODELS` is no longer used by the input card; its ids look invented.
- `OpenCodeSidebarAction` still opens the chat directly (not the status dialog).

## Suggested commit message

    Add ACP-based OpenCode agent: real install, native client, Build/Plan + model selectors

    - Real download/verify/extract installer pinned to the ACP Registry entry
    - AcpConnection/AcpAgentClient (stdio JSON-RPC) with fs and permission handlers
    - Safer OpenCodeFileBridge (containment, conflict detection, undo)
    - Chat selectors backed by agent config options; status dialog
    - Fix: tool approvals now reach the agent; no false "Running" state
