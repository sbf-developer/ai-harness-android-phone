# Verification — 24 October 2026

## Build and checks

The final source was checked with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Result: **BUILD SUCCESSFUL**. The final build log is `artifacts/build-verification.log`.

| Check | Result |
| --- | --- |
| AI/TLS/SSE/cancellation/error tests | 5 passed |
| GitHub publication/concurrency/file tests | 6 passed |
| Edit policy/context/endpoint/diff tests | 7 passed |
| Total local unit tests | **18 passed, 0 failed** |
| Android lint | **0 errors, 14 warnings** |
| Debug application APK | Built |
| Instrumentation APK | Built |
| APK signature | Verified, APK Signature Scheme v2 |
| On-device instrumentation execution | Unverified — emulator ADB authorization unavailable |
| Representative app screenshot | Unavailable |

The lint warnings are version-update notices for deliberately pinned dependencies and Gradle. No lint baseline or error suppression was added. Reports are under `app/build/reports/`; XML unit results are under `app/build/test-results/testDebugUnitTest/`.

## Installable artifact

- Stable copy: `artifacts/PocketHarness-debug.apk`
- Gradle output: `app/build/outputs/apk/debug/app-debug.apk`
- Instrumentation output: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Application ID: `dev.pocketharness`
- Version: `0.1.0` (1)
- Minimum Android API: 26; target / compile API: 36.
- SHA-256: `74606530606c7b196a87be1196fd48ffcb0e44362fe9f126c20a60b946bb16aa`

Signature details are in `artifacts/apk-signature-verification.log`. The Gradle distribution has a checksum pinned in the wrapper properties, obtained from the official Gradle distribution service.

## Runtime verification

The instrumentation APK compiled successfully, but its tests **were not run**. The existing Android API 36 AVD was started in read-only mode with snapshots disabled. After boot it showed a system Bluetooth crash dialog and an ADB authorization prompt. One targeted attempt to clear the blocker did not produce an authorized connection. Emulator repair was stopped, and the task-owned emulator was shut down without changing the original AVD data.

Consequently, native navigation, actual Android Keystore execution, fresh-IV/tamper checks on Android, and on-device conversation persistence remain **unverified**. The compiled instrumentation suite is ready to run on a working emulator or connected test phone using the command in README.md. No representative app screenshot is available. The ignored `artifacts/Screenshot_*.png` files are emulator troubleshooting captures, not screenshots of Pocket Harness.

## Scope of verification

No real AI keys or GitHub tokens were requested or used. Provider and GitHub network behavior was tested against a local mock server, including TLS, cancellation, changed-base checks before publication, a base changing while tree/commit objects are created, existing-branch rejection, original Git parent/file mode preservation, and a base advancing immediately after publication.

Live provider authentication, live GitHub permissions/organization policies, and an in-app commit to a real repository remain unverified. This application verification used local mocks and did not exercise external repository writes. A later, explicitly authorized push of this app's source code is separate from these application checks. The app's publication path is implemented and requires explicit in-app review approval.

The project folder was empty. No applicable AGENTS.md was found. Git initially resolved to the Windows user home directory, which was left untouched. For the subsequently authorized source publication, a dedicated Git repository was initialized inside this Android project and its boundary verified before staging. All implementation files are inside this project. Generated APKs, verification logs, screenshots, local SDK configuration, build caches, credentials, and temporary task files are excluded from source control; artifact paths above refer to the original local verification workspace.

The first extended check hit host disk and memory limits. The final build succeeded after limiting Gradle to one worker and compiling Kotlin in-process with a 1.5 GiB heap. No existing user files or AVD data were deleted. Temporary bootstrap and compiler diagnostic files remain ignored in the project because automatic approval review rejected their cleanup with the reason "blocked by policy".
