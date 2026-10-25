# Final review for 0.2.0

The Agent tab adds actual on-phone execution to the original chat/GitHub workflow. Review covered the official Termux intent and callback contract, streamed function assembly, model/tool orchestration, file conflict checks, Git destinations, permissions, interruption recovery, credentials and bounded foreground work. This is source/local-test review; live phone/Termux behavior remains unverified.

## Agent findings addressed

- Complete tool calls and pending command identities are saved before dispatch. Incomplete streams, malformed calls and unknown callbacks cannot trigger tools. Cold starts mark work interrupted/unknown without replay; late callbacks only record results. A new task uses a bounded checkpoint and fresh grants.
- Dispatch failures distinguish commands that were not sent from outcomes unknown after dispatch. Unknown writes block subsequent writes until checked or explicitly acknowledged. Connection checks and read-only manual actions do not silently acknowledge unknown writes. Duplicate callback acceptance is checked again under the journal mutex.
- Explicit task authority gates shell/destructive/network actions; task grants expire on completion. Git network approvals display the resolved URL and HEAD and recheck them before execution. The working directory is explicitly described as a structured-tool boundary, not an OS sandbox.
- File replacement checks full SHA-256, uses atomic replacement and preserves mode. Partial previews carry full hashes; the manual editor cannot replace a file using a partial/redacted preview. Unique partial edits fail on stale hashes or ambiguous text. Symlink components and common credential/Git metadata paths are excluded from structured file access.
- Structured Git verifies the exact repository root, rejects embedded credentials and unrelated staged paths, scans explicit commit files and rechecks hashes. Hooks/signing/external diff/textconv are disabled; push is non-force and explicitly scoped. Empty repositories support diff/fetch preflight. Failed commits can leave selected files staged; no automatic undo is claimed.
- Runner stdout/stderr, files, web downloads, callback payloads, model turns/tools/context and task duration are bounded. A process closing stdout/stderr is still allowed to finish until its deadline. Cancellation uses tracked process groups and PID start-time checks on Linux; escaped processes can survive and side effects are not rolled back.
- App API/GitHub/Brave keys are never injected into Termux. Input checks/output redaction cover saved values, common encodings, JSON escaping and recognizable credentials. JSON structure survives redaction. Redaction is explicitly best-effort for unknown/transformed Termux secrets.
- Public URL fetch validates HTTPS redirects and DNS targets and extracts real readable text. General search uses a separate Brave API key; its tool is absent without that key. Provider/GitHub/search requests refuse credential-bearing redirects. No fake search/terminal success or provider-built-in search is assumed.
- A shared Application Vault serializes Keystore creation and file writes across chat, service and receiver. User-started work owns a non-sticky foreground service; non-exported unique one-shot callbacks cannot restart the agent. Rapid repeated start gestures cannot prepare concurrent tasks.

See [TERMUX.md](TERMUX.md) for setup and execution tradeoffs and [VERIFICATION.md](VERIFICATION.md) for actual checks/artifacts. Real device permissions/callbacks, foreground lifecycle, POSIX process groups, Android Keystore and account authentication still need a working phone. No paid AI call or real application Git push was used in verification.

## Prior chat/GitHub review (0.1.1)

Reviewed the actual Kotlin/Compose implementation, first-use setup, streaming and cancellation, persistence and credential handling, selected repository context, proposal validation, and publication to new branches.

## Findings fixed

- Cancellation while saving a conversation was caught as a generic storage error. Cancellation now keeps its status, and initial-save failures cannot leave a reply stuck as streaming. The initial write and final checkpoint share the same error/cancellation handling.
- Failed, cancelled, or interrupted replies lacked a direct retry action. Retry now regenerates only the last incomplete reply from its original message and pinned file context, without adding another user message or reusing a partial assistant answer as model history.
- A stream event carrying both content and a token-limit finish reason discarded that final content. The content is now delivered and retained before the incomplete-response error is raised.
- Per-token scrolling jumped to the start of the last chat item, which fights attempts to read a long reply. Following now uses the measured bottom of the reply and pauses while the user drags to read earlier text. This layout change still needs device verification.
- File-preview sizing was applied inside the selection wrapper, where a parent Column's weight can be lost. Sizing now belongs to the outer selection container so long code previews stay bounded and leave their action buttons accessible; this also needs device verification.
- The diff renderer counted phantom lines for empty files and ambiguously labeled missing final newlines. Hunk counts and per-line newline markers now match the actual replacement.
- HTTP 422 errors from an AI provider were labeled as GitHub failures. The message now covers provider model/request validation as well as repository validation.
- Setup could suggest GitHub was required for chat, and its version label was hard-coded. GitHub is explicitly optional, Android Back returns from Settings to Chat, and the label uses the packaged version. DeepSeek uses its documented fast/non-thinking mode; other provider payloads keep standard fields.
- Settings now redact credentials from their string representation as an additional guard against accidental diagnostic logging.

## Publication integrity reviewed

Only selected existing text files are eligible for edits. Duplicate, unselected, no-op, binary, oversized, or mixed-snapshot edits are rejected. Original Git file modes and the reviewed parent/tree are preserved. An explicit review acknowledgement and commit action gate writes. The client checks the base before creating objects and again before creating a fresh branch, never force-updates an existing branch, and reports a base advancing immediately afterward. A regression test checks recovery of a lost create-ref response without retrying the write.

## Remaining limits

This remains a debug-signed personal preview. The APK is not presented as production-ready or available on the Play Store. Android navigation, Keystore execution and device persistence remain unverified because the local emulator's ADB authorization failed; no further emulator repair was attempted. Provider compatibility and actual token/organization permissions still require the user's account and device. The original Code/Review flow still modifies selected existing GitHub files; the new Agent flow separately adds local files, shell execution, builds and Git through Termux.

See [VERIFICATION.md](VERIFICATION.md) for the checks and release artifact details. The small storage/chat interfaces added for regression testing exercise orchestration failures without keys or network access; they do not replace the Keystore implementation in the installed app.
