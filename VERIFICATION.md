# Verification — 0.2.0 personal preview (25 October 2026)

## Build and local checks

The completed source was checked with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
python -m unittest discover -s tests -v
```

Result: **BUILD SUCCESSFUL**. The main build completed in 9m 36s; the final source/asset synchronization build completed in 2m 39s with unchanged Kotlin/test outputs reused. Logs are `artifacts/release-v0.2.0-build.log`, `artifacts/release-v0.2.0-final-build.log`, and `artifacts/release-v0.2.0-worker-tests.log` in the original workspace. See [REVIEW.md](REVIEW.md) for review findings and [TERMUX.md](TERMUX.md) for setup and execution limits.

| Check | Result |
| --- | --- |
| Agent loop/protocol/policy/recovery/redaction/decoder tests | 10 passed |
| Agent TLS streaming/cancel/search/web tests | 6 passed |
| Existing chat/provider/GitHub/review regression tests | 30 passed |
| Total Android local unit tests | **46 passed, 0 failed** |
| Portable Python worker tests | **13 passed, 1 skipped, 0 failed** |
| Android lint | **0 errors, 17 warnings** |
| Debug application APK | Built |
| Instrumentation APK | Built; execution unverified |
| APK signature | Verified, Signature Scheme v2; same certificate as 0.1.1 |
| APK worker/setup assets | Byte-for-byte match with tested source |
| Real Android/Termux/provider workflow | **Unverified** |

Lint warnings comprise 14 pinned dependency/Gradle version notices, two intentional official Termux path warnings, and one optional Android KTX style suggestion. No lint baseline/error suppression was added. The compiler also reports existing-compatible notification/clipboard deprecations and a redundant exhaustive `when` branch. Reports are under `app/build/reports/` and unit XML under `app/build/test-results/testDebugUnitTest/`.

The Python suite executes actual child processes and temporary local Git repositories: stdout/stderr/exit status, bounded output, timeout, closed-pipe completion, pre-start cancellation, command-line JSON envelopes, Unicode file slices, conflict-safe edits, credential/path rejection, exact repository boundaries, commits, unrelated staged-file refusal and changed-remote refusal. The symlink-creation test is skipped because this Windows account cannot create symlinks. Linux `/proc` identity checks/process-group cancellation are not exercised on Windows.

The Android network fixtures use local TLS with explicit loopback hostnames and matching certificates, retaining certificate validation. They exercise real streamed request parsing, function schemas, tool results/IDs, unsupported models, cancellation, separate Brave authentication, HTML extraction and private redirects. No paid provider call, real key, actual Termux command or external application Git push was used in these tests.

## Installable artifact

- Release copy: `artifacts/PocketHarness-0.2.0-debug.apk`
- Checksum: `artifacts/PocketHarness-0.2.0-debug.apk.sha256`
- Gradle output: `app/build/outputs/apk/debug/app-debug.apk`
- Instrumentation output: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Application ID: `dev.pocketharness`
- Version: **0.2.0 (3)**
- Minimum / target / compile API: **26 / 36 / 36**
- APK size: **19,472,456 bytes**
- Architectures: arm64-v8a, armeabi-v7a, x86, x86_64
- SHA-256: `6a62e99bd16d423e2f2c7820b61ce5bd35f4a2d61f5ee7e29b9a8db0103a372d`
- Signing certificate SHA-256: `3f770003c22a1dcf52f797ede1aee4942927be089e2df8f9efaa5c8b0e42a298`

The certificate matches the 0.1.1 build, preserving eligibility for an in-place update. Signature details are in `artifacts/release-v0.2.0-signature.log`. Packaged `harness_runner.py` and `setup_termux.sh` were compared against the final source; the jsoup license is retained in the APK. This is a **debug-signed personal preview**, not a production/Play Store release. The signing keystore stays private and is not a release asset.

## Phone delivery

- Source branch: `codex/initial-android-app`; tag: `v0.2.0`.
- [Release page](https://github.com/sbf-developer/ai-harness-android-phone/releases/tag/v0.2.0).
- [Direct APK](https://github.com/sbf-developer/ai-harness-android-phone/releases/download/v0.2.0/PocketHarness-0.2.0-debug.apk).
- [SHA-256 checksum](https://github.com/sbf-developer/ai-harness-android-phone/releases/download/v0.2.0/PocketHarness-0.2.0-debug.apk.sha256).
- [Termux setup and authority guide](TERMUX.md).

The public release distributes APK/checksum assets separately from source. Draft asset size/digest and anonymous published download checks are performed after source/tag publication; the resulting local delivery record is `artifacts/release-v0.2.0-download-verification.json`. Source uploads and this app's own release publication are separate from the application's unverified phone-side Git/provider workflows.

## Device limitations and next checks

No working authorized Android device was available. The existing emulator had previously failed ADB authorization and shown a Bluetooth system crash. Further emulator repair was not attempted. Instrumentation tests compile but **were not run**. Native navigation/layout, Android Keystore execution and persistence, Termux installation/custom permission/external-command setting, mutable callback delivery, foreground/background lifecycle, Stop/process groups, real provider function compatibility, phone Git SSH credentials and real Brave account access remain unverified. No representative app screenshot is supplied.

On a working test phone, install this APK, follow TERMUX.md, run the actual connection check, inspect a disposable project, edit a file, execute a harmless command/test, and exercise approval/deny/Stop/late-result recovery before granting full task authority. Verify Git authentication manually in Termux and inspect the exact push destination. Interrupted/unknown commands must never be assumed to have had no side effects.

The application uses a dedicated Git repository inside this project; the Windows home repository was left untouched. APKs, caches, SDK paths, keystores, credentials, logs and temporary files are ignored. The 8 GB development PC uses one Gradle worker and a 1.5 GiB in-process compiler heap. Previously ignored bootstrap/crash/troubleshooting files remain local; no cleanup of unrelated user data was attempted.
