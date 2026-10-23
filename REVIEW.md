# Final review for 0.1.1

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

This is a debug-signed personal preview. The APK is not presented as production-ready or available on the Play Store. Android navigation, Keystore execution, and device persistence remain unverified because the local emulator's ADB authorization failed; no additional emulator repair was attempted for this release. No real paid AI requests or in-app GitHub commits were made. Provider compatibility and actual token/organization permissions still require the user's account and device. Edits modify selected existing files; creation, deletion, shell execution, builds, and remote workspaces are outside scope.

See [VERIFICATION.md](VERIFICATION.md) for the checks and release artifact details. The small storage/chat interfaces added for regression testing exercise orchestration failures without keys or network access; they do not replace the Keystore implementation in the installed app.
