# On-phone agent — 0.2.0

Pocket Harness runs real commands on your Android phone using the official [Termux RUN_COMMAND contract](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). No root, remote server, plugin or companion APK is required. This is a debug-signed personal preview; live Android/Termux execution remains unverified in this development environment.

## Set up once

1. Install **com.termux** from [F-Droid](https://f-droid.org/en/packages/com.termux/), following [Termux installation guidance](https://github.com/termux/termux-app#installation). Open it once. Callbacks require Termux 0.109 or newer; use a current build. Avoid mixing differently signed installation sources.
2. In Pocket Harness **Agent → Termux setup & connection**, tap **Copy setup script**, then paste/run it in Termux. [The script](app/src/main/assets/setup_termux.sh) installs Python, Git, OpenSSH, ripgrep and coreutils; creates `~/projects`; and sets `allow-external-apps=true` in `~/.termux/termux.properties`, preserving other settings. It then reloads Termux settings.
3. Return and tap **Grant command permission**. If Android does not show a prompt, use **App permissions in Android settings**, then **Permissions → Additional permissions → Run commands in Termux environment**. Both Android's `com.termux.permission.RUN_COMMAND` grant and Termux's `allow-external-apps=true` are necessary.
4. Leave the workspace at `~/projects` or select another existing absolute directory accessible to Termux. Tap **Run connection check**. It actually invokes Termux, checks Python/Git/ripgrep/SSH and verifies the directory. Missing permission, external access, tools or directory produce actionable errors.
5. In Settings, configure your Chat Completions provider/model/key. Agent requires streamed function tools. DeepSeek's default is `https://api.deepseek.com`, `deepseek-flash`; other providers must accept the standard tool format. Chat works separately without Termux or search/GitHub credentials.

Use Termux's private home for projects, especially native builds. Shared Android storage has filesystem/executable restrictions and needs permissions configured inside Termux. Pocket Harness cannot grant access to protected Android app data. Keep the app visible when starting tasks. Notification permission is optional, but allowing it makes progress and Stop visible in the notification tray; Android also exposes foreground work in its task manager.

## Start a task

A useful first task is: “List the projects here, inspect the README and Git status of the project I name, and explain how to run its tests.” For a code change, choose that project's existing directory as the workspace and describe the change and validation you want.

| Authority | Automatic actions | Additional approval |
| --- | --- | --- |
| Ask before edits & terminal | File reads/search, Git status/diff/remote, web tools | File writes, local commits, terminal, clone, fetch, push |
| Edit workspace automatically | Also structured edits, Git init and local commits | Terminal, clone, fetch, push |
| Full terminal authority | All tools, including destructive commands and network/Git writes | Full-access acknowledgement before this task |

Approvals show the actual command/arguments. **Allow once** covers that call. A task file-edit grant covers structured edits within the workspace. A Git grant binds the operation, repository, remote URL and branch. An **all-terminal task grant** also permits other file/destructive/network/Git actions. Grants expire when the task ends. Denial returns an error to the model with an instruction to respect it; review subsequent requests when restricting authority.

**The workspace is not an OS sandbox.** Structured file tools enforce its boundary and exclude symlinks, Git metadata and common credential stores. Approved arbitrary programs can use all file/network access available to Termux. Build/test scripts execute code too. Model instructions and redaction are not security boundaries.

Complete streamed function calls are durably saved before execution. Tools run serially, actual results return as matching `tool` messages, and the model continues until it answers or reaches a limit. Partial/malformed calls and incomplete streams are rejected. Suggested commands in ordinary prose are never automatically executed.

## Files and terminal

**Files & editor** lists directories and opens native text previews/editors. Save checks the full-file SHA-256 and refuses intervening changes. Creation requires a missing destination. UTF-8 files up to 80 KiB are supported. Reads use 10,000-character slices with full-file hashes and next offsets. The manual editor disables whole-file replacement for partial or redacted previews; the agent can read further slices and use a unique, SHA-checked partial edit. Binary files are excluded. Structured edits do not delete/rename files; explicitly approved terminal commands can.

**Terminal** runs the exact acknowledged Bash command with a 60-second timeout. Agent tool calls can choose 1–300 seconds. Results show stdout, stderr, exit code, working directory, truncation, timeout and redaction status. This is non-interactive command execution, not a PTY. Use Termux for interactive login, SSH setup and long-running work.

## Git authentication and writes

Configure Git identity and authentication **inside Termux**, for example your normal `git config --global user.name` / `user.email` and SSH setup. Test authentication manually first, including accepting the correct SSH host fingerprint. The worker disables password/passphrase prompts and uses SSH BatchMode with strict host checking. Encrypted keys need a working unlocked SSH agent in Termux. The app's Code-tab GitHub token is never copied into commands, the environment or Termux storage.

Structured tools implement clone/init/status/diff/fetch/commit/push. They check the exact repository root to avoid operating on a parent/home repository. Commits require explicit eligible text files, matching full-file hashes and no unrelated staged files. Hooks, signing, external diff and textconv are disabled for structured Git. Failed commits can leave selected files staged; inspect status before retrying. Deletions/binary files/larger commits need explicitly approved terminal commands.

Fetch/push read the actual remote URL and HEAD before approval and recheck both immediately before execution. Fetch updates remote-tracking refs without merging. Push explicitly sends `HEAD:refs/heads/<branch>` without force, tags or submodules. Remote policies/races still apply. Full terminal authority can run other Git commands, including destructive ones. Embedded URL credentials are rejected; configure SSH or a credential helper privately in Termux.

## Real web research

**Read a URL** retrieves public HTTPS HTML/text/JSON without a search key, returning source URL/title and bounded readable text. Redirects are validated; credentials, private/local IPs and nonstandard ports are blocked, including DNS targets. Scripts are not executed. PDF/media and JavaScript-only pages are unsupported.

**General web search** requires a separate **Brave Search API key** in Settings. The agent then gets a real `web_search` tool backed by the [Brave Search API](https://api-dashboard.search.brave.com/app/documentation/web-search/get-started), returning up to five titles, source URLs and snippets. Without the key, this tool is omitted; fetching known URLs is still possible and is not called a general search. No provider-built-in search is assumed.

Queries go to Brave. Page text and other tool results may go to your configured AI provider. The model is instructed to treat websites, files and tool output as untrusted data, cite source URLs and distinguish evidence from inference. Prompt injection remains possible; review authority accordingly.

## Stop and recovery

An explicit user start launches an Android `dataSync` foreground service with an ongoing notification and Stop action, following [Android's service rules](https://developer.android.com/develop/background-work/services/fgs/service-types). No boot/callback/task auto-resume is used. Android limits, battery policy, process death or Termux termination can interrupt work.

Stop cancels the provider request and asks the runner to terminate its tracked process group. This is **best-effort**: writes/pushes may already have happened, cancellation can race with a short file operation, and escaped background processes may survive. There is no rollback. PID/start-time checks reduce PID reuse risk. Never assume that a cancelled request means no side effects.

Encrypted journals retain the last five tasks, complete calls, actual results and outstanding command identities. After process death, active tasks become **interrupted** and pending commands become **unknown**. They are never automatically replayed. Late callbacks record outcomes without restarting the agent. **Check execution metadata** does not rerun a command; Termux retains no raw stdout, so lost output cannot be recovered. Inspect actual files/Git/Termux state and explicitly acknowledge possible side effects before starting further writes. Running the connection check does not acknowledge unknown commands.

**Use checkpoint for a new task** supplies a bounded summary as untrusted data, with fresh authority. It never resumes/replays the old tool transcript. Every app callback uses a unique explicit one-shot PendingIntent. A mutable callback is required to receive Termux's fill-in result; the result receiver is not exported and validates the known command ID.

| Bound | Limit |
| --- | --- |
| Task | 20 minutes, 24 model rounds, 48 tool calls |
| Model context | 120,000 characters; explicit error instead of silent dropping |
| Terminal | 1–300 seconds; manual panel 60s |
| Process output | About 12 KB stdout / 8 KB stderr, head/tail retained, truncation flagged |
| Callback envelope | 40 KB serialized JSON cap, below Termux's transport cap |
| Text file | 80 KiB, reads sliced at 10,000 characters |
| Web page | 1 MiB download; 16,000 readable characters |

No guaranteed job completion, interactive session, daemon or unlimited autonomous run is promised. Actual tools/tests that ran are visible in task history; a model answer without tools is not evidence of execution.

## Credentials and privacy

App settings, conversations and task journals use Android Keystore-backed AES-256-GCM, fresh IVs, authenticated filenames, AtomicFile and private `noBackupFilesDir`. Backup is disabled. Pending unknown job identities are retained separately. Termux job files contain private identity/PID/timing/status metadata, not raw commands/stdout. Source files, Git state and programs' own logs stay in Termux normally.

Saved app keys are never injected into commands/environment. Saved values, common encodings and recognizable token/private-key patterns are blocked in tool inputs and redacted from outputs before history/model use. Common credential paths are excluded from structured tools. **Redaction is best-effort:** arbitrary commands can read Termux credentials; unknown formats, transformed/split values and programs' own logs may evade detection. Keep credentials out of project files and avoid printing them. Source/tool output goes to the chosen AI provider. Settings blocks screenshots and cannot change during an active agent task; other screens can show sensitive code. Removing credentials clears AI/GitHub/Brave keys without deleting history.

## Verification

The portable Python suite uses actual files, subprocesses and temporary local Git repositories. Android unit tests use local TLS fixtures for streamed tools/search/web behavior and cover policy, recovery and redaction. Instrumentation tests compile intent, Keystore and navigation checks. Device execution, POSIX process-group behavior, Termux permission/setup/callbacks, real provider accounts and phone Git authentication still require a working test phone. See [VERIFICATION.md](VERIFICATION.md) for actual results; no paid AI request or real application Git push was used during local tests.
