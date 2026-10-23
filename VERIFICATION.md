# Verification — 0.1.1 personal preview (24 October 2026)

## Build and checks

The final source was checked with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Result: **BUILD SUCCESSFUL**. The final build log is `artifacts/release-v0.1.1-build.log`. See [REVIEW.md](REVIEW.md) for findings and fixes.

| Check | Result |
| --- | --- |
| AI/TLS/SSE/cancellation/error tests | 7 passed |
| GitHub publication/concurrency/file tests | 7 passed |
| Chat setup/save/cancel/retry/interruption tests | 7 passed |
| Edit policy/context/endpoint/diff tests | 9 passed |
| Total local unit tests | **30 passed, 0 failed** |
| Android lint | **0 errors, 14 warnings** |
| Debug application APK | Built |
| Instrumentation APK | Built |
| APK signature | Verified, APK Signature Scheme v2 |
| On-device instrumentation execution | Unverified — emulator ADB authorization unavailable |
| Representative app screenshot | Unavailable |

The lint warnings are version-update notices for deliberately pinned dependencies and Gradle. No lint baseline or error suppression was added. Reports are under `app/build/reports/`; XML unit results are under `app/build/test-results/testDebugUnitTest/`.

## Installable artifact

- Release copy: `artifacts/PocketHarness-0.1.1-debug.apk`
- Checksum: `artifacts/PocketHarness-0.1.1-debug.apk.sha256`
- Gradle output: `app/build/outputs/apk/debug/app-debug.apk`
- Instrumentation output: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Application ID: `dev.pocketharness`
- Version: `0.1.1` (2)
- Minimum Android API: 26; target / compile API: 36.
- APK size: 19,012,068 bytes.
- Included native architectures: arm64-v8a, armeabi-v7a, x86, x86_64.
- SHA-256: `ab3257ef769fc65880b20dd4322b615593a743c328fb5b95cef7ac4e86180127`
- Signing certificate SHA-256: `3f770003c22a1dcf52f797ede1aee4942927be089e2df8f9efaa5c8b0e42a298`.
- The signing certificate matches the earlier 0.1.0 APK, preserving eligibility for an in-place update from that build.

Signature details are in `artifacts/release-v0.1.1-signature.log`. This is a **debug-signed personal preview**, not a production or Play Store release. The Gradle distribution has a checksum pinned in the wrapper properties, obtained from the official Gradle distribution service.

## Phone delivery

- Tag: `v0.1.1`.
- [Release page](https://github.com/sbf-developer/ai-harness-android-phone/releases/tag/v0.1.1).
- [Direct APK download](https://github.com/sbf-developer/ai-harness-android-phone/releases/download/v0.1.1/PocketHarness-0.1.1-debug.apk).
- [SHA-256 checksum](https://github.com/sbf-developer/ai-harness-android-phone/releases/download/v0.1.1/PocketHarness-0.1.1-debug.apk.sha256).

The repository is public. APKs and checksums are distributed as GitHub Release assets, with no keys, signing keystores, or binary build outputs committed to the source tree. Phone installation and first-use steps are at the top of README.md. Upload and anonymous download verification are performed after source/tag publication; release asset metadata provides the published size and digest.

## Runtime verification

The instrumentation APK compiled successfully, but its tests **were not run**. The existing Android API 36 AVD was started in read-only mode with snapshots disabled. After boot it showed a system Bluetooth crash dialog and an ADB authorization prompt. One targeted attempt to clear the blocker did not produce an authorized connection. Emulator repair was stopped, and the task-owned emulator was shut down without changing the original AVD data.

Consequently, native navigation, actual Android Keystore execution, fresh-IV/tamper checks on Android, and on-device conversation persistence remain **unverified**. The compiled instrumentation suite is ready to run on a working emulator or connected test phone using the command in README.md. No representative app screenshot is available. The ignored `artifacts/Screenshot_*.png` files are emulator troubleshooting captures, not screenshots of Pocket Harness.

## Scope of verification

No real AI keys or GitHub tokens were requested or used. Provider and GitHub network behavior was tested against a local mock server, including TLS, cancellation, changed-base checks before publication, a base changing while tree/commit objects are created, existing-branch rejection, original Git parent/file mode preservation, and a base advancing immediately after publication.

Live provider authentication, live GitHub permissions/organization policies, and an in-app commit to a real repository remain unverified. This application verification used local mocks and did not exercise external repository writes. A later, explicitly authorized push of this app's source code is separate from these application checks. The app's publication path is implemented and requires explicit in-app review approval.

The project folder was empty. No applicable AGENTS.md was found. Git initially resolved to the Windows user home directory, which was left untouched. For the subsequently authorized source publication, a dedicated Git repository was initialized inside this Android project and its boundary verified before staging. All implementation files are inside this project. Generated APKs, verification logs, screenshots, local SDK configuration, build caches, credentials, and temporary task files are excluded from source control; artifact paths above refer to the original local verification workspace.

The first extended check hit host disk and memory limits. The final build succeeded after limiting Gradle to one worker and compiling Kotlin in-process with a 1.5 GiB heap. No existing user files or AVD data were deleted. Temporary bootstrap and compiler diagnostic files remain ignored in the project because automatic approval review rejected their cleanup with the reason "blocked by policy".
