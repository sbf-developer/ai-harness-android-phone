# Pocket Harness

A native Android AI coding and research agent that works **on your phone** through Termux. Bring your own AI provider, inspect and edit local files, execute commands, run tests, use Git and read the web. Kotlin, Jetpack Compose, coroutines and OkHttp. Android 8.0+ (API 26); targets Android 16 (API 36).

**New in 0.2.0:** the Agent tab performs real on-phone work using the official Termux RUN_COMMAND service. It includes streamed function-tool rounds, task approvals, a native file editor and command runner, real Brave search/public URL reading, encrypted task history, foreground execution and interruption recovery without replay. Follow [TERMUX.md](TERMUX.md) for the one-time setup, permissions, Git authentication and important execution limits. Chat and the original Code/Review workflow below remain independent of Termux.

## Install on your Android phone

**Requires Android 8.0 or newer.** This is a personal preview, distributed as a **debug-signed APK**, not a Play Store release.

1. On your phone, open the [0.2.0 release](https://github.com/sbf-developer/ai-harness-android-phone/releases/tag/v0.2.0) and download **PocketHarness-0.2.0-debug.apk** from Assets. You can also use the [direct APK download](https://github.com/sbf-developer/ai-harness-android-phone/releases/download/v0.2.0/PocketHarness-0.2.0-debug.apk). The repository is public, so GitHub sign-in is not required.
2. Open the downloaded APK from your browser's downloads or the Files app. When Android asks, open **Settings**, enable **Allow from this source** for that browser or Files app, then return and tap **Install**. Device wording can vary. You can turn the installation permission off afterward. See [Google's phone-install instructions](https://support.google.com/pixelphone/answer/7391672?hl=en-GB).
3. Open **Pocket Harness → Settings**. For DeepSeek, keep `https://api.deepseek.com` and `deepseek-flash`, paste your own API key into **AI API key**, then tap **Save settings**. These values are based on [DeepSeek's current API guide](https://api-docs.deepseek.com/). API access and usage belong to your provider account; no credentials or credits are included in this APK.
4. Return to **Chat**, type a message, and send. **A GitHub token is not needed for ordinary chat.** The GitHub section is optional and only needed for repository features.
5. For local coding, open **Agent → Termux setup & connection**, install official Termux, copy/run the setup script there, grant command permission, and run the connection check. Then choose your workspace and task authority. [Full setup and first-task instructions](TERMUX.md).

The APK and its SHA-256 checksum are release assets, excluded from the source repository. Updates must use the same signing key; the existing local debug key stays private. Uninstalling or clearing app storage removes local settings and conversations. The personal-preview limitations and exact checks are recorded in [VERIFICATION.md](VERIFICATION.md).

## Build from source

1. Open this folder in Android Studio. Use JDK 17 or 21, Android SDK platform 36, and build tools 35.0.0 or newer. The pinned Gradle wrapper downloads Gradle 8.13.
2. Let Studio create `local.properties`, or set `ANDROID_HOME` to your SDK directory. Do not commit `local.properties`.
3. Run the `app` configuration on an emulator or a connected Android phone.

PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n dev.pocketharness/.MainActivity
```

macOS / Linux:

```sh
sh gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This is a development build, signed with the local Android debug key. No signing credentials, AI keys, or GitHub tokens are included.

Chat sends streamed text Chat Completions requests; Agent additionally requires streamed function-tool support. Required custom headers, other model-specific request formats, and other API families are not configurable. This personal preview still needs live Termux/device testing; see VERIFICATION.md.

## Use

- **Settings:** enter an HTTPS Chat Completions base URL, exact model ID, and your API key. The app appends `/chat/completions`; for DeepSeek use `https://api.deepseek.com`. The initial model is `deepseek-flash`; all fields are editable. Direct DeepSeek requests disable thinking mode using its [documented mode switch](https://api-docs.deepseek.com/guides/thinking_mode/). That extension is omitted for other providers. The optional Brave Search key enables actual general web search in Agent; URL reading needs no search key. Anthropic Messages, OAuth providers and images are unsupported. Settings cannot change during an active agent task.
- **Agent:** choose an existing Termux workspace, describe a task and select authority. File reads, SHA-checked edits, real Bash execution, Git and web tools are available. Approvals display the exact operation. The workspace is not an OS sandbox: approved commands use Termux's full file/network access. [Setup, authority modes, recovery and limits](TERMUX.md).
- **Chat:** send messages, watch the response stream, or cancel. Partial responses remain visible with their status. **Retry response** replaces an incomplete last reply using its original user message and file context, without duplicating that message; retrying is a new provider request. Conversation history, deletion, and new conversations are in the top bar. Failed or cancelled assistant responses are excluded from subsequent model requests. A long conversation shows an explicit size error rather than silently dropping context.
- **Code:** add a fine-grained GitHub personal access token, load repositories, and open a branch. Tap folders to browse, then a regular UTF-8 file to preview and select it. Up to six files / 120 KiB total can be selected from one repository snapshot. Refreshing a branch clears selection and pending review. Symlinks, submodules, binary files, and large files are rejected.
- **Propose edits:** select files, describe the desired change in the Chat composer, and tap **Propose edits**. This request generates a separate structured proposal using the instruction and selected files. No repository writes happen during generation. This version modifies selected existing files; it does not create, rename, or delete files.
- **Review:** inspect Diff, Before, and After for each changed file. Enter a fresh branch name and commit message, check the review acknowledgement, then explicitly tap **Commit to new branch**. The app creates one commit containing all reviewed edits. It does not merge, update the base branch, create a pull request, or overwrite an existing branch. Open the resulting commit in GitHub to continue your normal review process.

For optional GitHub access, create a [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new), choose only the repositories you need, and paste it into the app's Settings. Grant **Metadata: read** and **Contents: read** for browsing; grant **Contents: read and write** for publication. Workflow files may require additional Workflows permission; repository and organization policies still apply. These permissions follow the [GitHub Git tree API requirements](https://docs.github.com/en/rest/git/trees). Empty repositories without a commit cannot be browsed. Enterprise hosts and OAuth sign-in are outside this version.

## Review and concurrency

File reads use immutable commit/tree/blob SHAs. The review holds that exact base and original file modes (including executable files). Proposal validation rejects unselected paths, duplicate paths, mixed snapshots, no-op edits, binary content, and oversized output.

Publication checks the base head before any write, verifies the destination branch does not exist, creates a tree based on the original tree and a commit with the original parent, and checks the base head again immediately before creating the new branch. A changed base blocks publication and requires refreshing and regenerating the proposal. Intermediate tree/commit objects may be left unreferenced if a later check fails.

GitHub does not offer an atomic transaction across a base ref and creation of another ref. The base can advance in the final interval; publication still only creates a fresh branch from the reviewed base, and a post-publication check warns the user to compare before merging. No force update or existing-ref update API is used. A lost create-ref response triggers a read-only recovery check; the app never blindly retries a write. Publishing is intentionally not user-cancellable once started. If the process is killed or confirmation is lost, check the named branch in GitHub before trying again.

## Storage and privacy

Settings (including credentials), messages, attached source snapshots and agent journals are encrypted using AES-256-GCM with a fresh IV for each save, backed by a non-exportable Android Keystore key. Authenticated data binds each encrypted envelope to its storage filename. AtomicFile protects against interrupted writes. Files are in the app's private `noBackupFilesDir`; backup is disabled. Settings blocks screenshots and recent-app capture while displayed. Other screens can contain sensitive source code, so handle captures accordingly. Termux source files, Git state and programs' own logs live normally in Termux; only private job identity/PID/status metadata is retained by our runner. App keys are never injected into Termux. Output redaction is best-effort; see TERMUX.md.

No analytics, HTTP logging interceptor, hard-coded credentials, or crash reporting is installed. HTTP error bodies are not shown or logged. HTTPS is required for AI endpoints and cleartext traffic is disabled. Provider, GitHub and Brave requests refuse redirects to avoid forwarding credentials. Keyless web fetch follows only validated public HTTPS redirects. The GitHub token only goes to `api.github.com`; AI keys only go to the configured provider. Messages, selected file contents and agent tool results go to that provider. Repository contents are delimited as data, and local validation gates proposed writes.

Streaming text is refreshed at most every 40 ms and checkpointed to encrypted storage about every two seconds. Stopping a stream closes the underlying network call. In-flight messages are marked interrupted on the next launch. Selection, file previews, and pending proposals are session state; proposals must be regenerated after process death. Clearing Android app storage or uninstalling removes access to local encrypted data; keep your credentials elsewhere if you need them again.

## Checks

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
python -m unittest discover -s tests -v
# On a running emulator / connected test device:
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

Unit tests use a local MockWebServer (including TLS for AI requests), with no real credentials or external repository writes. They exercise SSE parsing, partial responses, cancellation, redirect refusal, sanitized errors, proposal/path/size checks, byte-preserving replacements, Git tree parent/mode preservation, existing-branch refusal, and changing-base scenarios. Instrumentation tests cover native navigation, missing-key states, Keystore encryption, tamper detection, and conversation persistence.

See `VERIFICATION.md` for the actual results and machine-specific limits of this implementation run.

## Project layout

- `data/`: immutable serializable models and Keystore-backed encrypted storage.
- `network/`: bounded/cancellable HTTP, streaming Chat Completions, and GitHub Git database operations.
- `review/`: deterministic edit validation and readable diff generation.
- `ui/`: native Compose screens, light/dark theme, file preview, history, and explicit review approval.
- `HarnessViewModel.kt`: screen state, persistence, task lifecycle, and orchestration.

## Primary references

- [Android Gradle plugin 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Compose BOM and compiler setup](https://developer.android.com/develop/ui/compose/bom)
- [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography)
- [DeepSeek Chat Completions / streaming](https://api-docs.deepseek.com/api/create-chat-completion/)
- [GitHub Git trees](https://docs.github.com/en/rest/git/trees), [commits](https://docs.github.com/en/rest/git/commits), [references](https://docs.github.com/en/rest/git/refs), and [API version support](https://docs.github.com/en/rest/about-the-rest-api/api-versions)

The GitHub client pins the supported `2022-11-28` REST contract. Dependencies are pinned deliberately; this project does not rely on dynamic version resolution.
