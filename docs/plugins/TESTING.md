# Plugin System — Testing and Build Notes

Status: local test evidence from 2026-10-22; x86_64 CI verification was added on
2026-10-24 but has not been executed because all changes are uncommitted.
Audience: anyone reproducing the work or reviewing its claims.

Sections 2–8 are results from actual local runs. Section 9 describes the CI workflow
that must produce the missing APK, lint, and runtime evidence; it is **not** a result.

## 1. Development environment

| Item | Value |
| --- | --- |
| Host | aarch64 Linux under Termux PRoot (Android) |
| JDK | OpenJDK 17 at `/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk` |
| Gradle | 9.2.1 (wrapper) |
| Android Gradle Plugin | 8.13.2 |
| Android SDK | `/root/android-sdk` (`platforms;android-36`, `build-tools;36.0.0`, `platform-tools`) |
| NDK | `29.0.14206865` at `/root/android-sdk/ndk/29.0.14206865` |
| aapt2 override | `/data/data/com.termux/files/usr/bin/aapt2` (Termux package) |

`local.properties` contains `sdk.dir=/root/android-sdk` and is git-ignored. The aapt2
override is required because Google's Linux `aapt2` binary is x86_64-only and does not
run on this host. Gradle prints an "experimental option" warning for the override; it
is expected.

Unit-test command used for every result:

```bash
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk
./gradlew :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  --console=plain
```

## 2. Baseline before plugin changes

A detached worktree at upstream `8629e632` was created at `/tmp/opencode/baseline`
with the same `local.properties` and toolchain.

### 2.1 Terminal emulator

```bash
./gradlew :terminal-emulator:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  --console=plain
```

Result: `BUILD SUCCESSFUL in 2m 52s`; **145 tests passed**. Log:
`/tmp/opencode/test-emulator.log`.

The same command on the current tree also passed **145 tests** (`BUILD SUCCESSFUL in
1m 29s`, `/tmp/opencode/test-emulator-current.log`); the module was not modified.

### 2.2 App module

```bash
./gradlew :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  --console=plain
```

Result: `2 tests completed, 1 failed`; `BUILD FAILED in 1m 30s`. Log:
`/tmp/opencode/test-app-baseline-clean.log`.

The only failure is pre-existing:

```
FileReceiverActivityTest > testIsSharedTextAnUrl FAILED
java.lang.NullPointerException: ShadowActivityThread.reset: ActivityThread not set
  at org.robolectric.shadows.ShadowActivityThread.reset(ShadowActivityThread.java:284)
  ...
```

`TermuxActivityTest > testExtractUrls` passes. The failure occurs in Robolectric's
teardown/reset on JDK 17, not in the URL assertions. It is unrelated to the plugin
system and is **not** counted as a regression.

## 3. Plugin tests

Command:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.termux.app.plugins.*' \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  --console=plain
```

Result: `BUILD SUCCESSFUL in 2m 15s`; **46 tests passed**. Log:
`/tmp/opencode/test-plugins-final4.log`.

| Test class | Tests | Covers |
| --- | ---: | --- |
| `PluginApiTest` | 4 | Semver validation/comparison, ID rules, capability/type mapping |
| `PluginArchiveTest` | 4 | In-memory read, traversal/duplicate/unexpected entries, missing files, size limits |
| `PluginInstallerTest` | 13 | Install/enable/disable/uninstall, exclusive types, dependencies, cycles, updates, downgrade rejection, checksum/size, tamper detection, uninstall leaving unrelated files, bundled samples |
| `PluginManifestTest` | 8 | Valid manifest, unknown fields, unsupported type/capability/API, newer core, malformed/duplicate/self dependencies, strict JSON types, out-of-range integers |
| `PluginPayloadTest` | 6 | Theme colors and limits, required foreground/background, preset path escapes, extra-keys JSON shape, payload type mismatch |
| `PluginRegistryTest` | 3 | Save/load, corrupt registry quarantine, invalid record isolation |
| `PluginRepositoryClientTest` | 4 | Signed index parsing, compatibility flag, HTTPS enforcement, unknown fields/duplicates |
| `PluginSignatureVerifierTest` | 4 | RSA signature verification, wrong data, undersized key, Base64 validation |

The bundled samples are parsed and installed through `PluginArchive`,
`PluginManifest`, and `PluginPayload` in `PluginInstallerTest.installBundledSamplesOnce`.
`AssetManager` is unavailable in a plain JVM test, so that test reads the three ZIPs
from `app/src/main/assets/plugins` directly; the asset-listing/install loop itself
still needs a device check.

## 4. Full app test run

Command: the unit-test command above.

Result: `48 tests completed, 1 failed`; `BUILD FAILED in 1m 18s`. Log:
`/tmp/opencode/test-app-9.log`. (The suite recompiled after a source edit; an incremental
run of the same suite took 20s.)

- 46 plugin tests pass.
- `TermuxActivityTest` passes.
- `FileReceiverActivityTest` fails with the same pre-existing Robolectric teardown
  NPE recorded in the clean baseline.

So the change introduces **zero new test failures**. The build is red only because the
baseline is red for that one test on this host.

The manifest declaration and Java sources were also checked without the native build:

```bash
./gradlew :app:compileDebugJavaWithJavac :app:processDebugMainManifest \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 14s`. The merged debug manifest resolves
`PluginManagerActivity` to `com.termux.app.plugins.PluginManagerActivity` with
`android:exported="false"`. This does not execute the UI.

## 5. Lint

```bash
./gradlew :app:lintDebug \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  --console=plain
```

Result: `BUILD FAILED in 45s`. Log: `/tmp/opencode/lint-app.log`.

Lint did not reach the Java sources. `:termux-shared` invokes NDK `ndk-build` first,
which exits with:

```
ERROR: Unknown host CPU architecture: aarch64
process .../ndk/29.0.14206865/ndk-build finished with non-zero exit value 1
```

This is the same x86_64-only NDK limitation that blocks the APK build (§6.1), not a
lint finding. Lint must be rerun on a supported host; it is not a substitute for unit
tests and cannot validate device styling behavior.

## 6. APK build and installation

### 6.1 Known host blocker

`./gradlew :app:assembleDebug` cannot produce an APK on this aarch64 host. The
Android Gradle Plugin downloads Google's x86_64 AAPT2 and the configured NDK contains
x86_64 toolchains; there is no QEMU/binfmt or x86_64 compat layer available. The
aapt2 override lets resource processing and unit tests run, but native compilation
still needs a matching NDK toolchain.

This is a tooling limitation, not a source-code failure. The supported build path is
the x86_64 workflow in §9. The same commands work on any x86_64 Linux host with the
SDK/NDK versions above:

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
./gradlew :app:assembleDebug
```

Expected output path (debug, universal):

```
app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

The exact name depends on `packageVariant` / `apkVersionTag`.

### 6.2 Install and smoke test on a device

```bash
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

This is a `com.termux` package. It cannot coexist with official Termux and cannot
replace it unless signed with the official key. Use a clean device/emulator or back up
and uninstall the existing app first. See `AUDIT.md` §2.

Manual checklist. **Status: pending on a physical device.** The CI emulator job in §9
covers steps 1–2 and part of step 10 when it runs. It does not cover theme/extra-keys/
preset behavior, uninstall, or invalid-state recovery. A test is only complete when
its observation is recorded; the commands below are not evidence by themselves.

1. Install the APK; open Termux; confirm the terminal and bootstrap still work and
   **zero** plugins are installed.
2. Settings → Plugins → Install bundled samples. Three entries appear, disabled.
3. Enable Midnight. With no `~/.termux/colors.properties`, colors change after the
   activity resumes. Disable it; the built-in colors return.
4. Create `~/.termux/colors.properties`; reopen styling; the user file wins and the
   plugin is inert.
5. Enable the extra-keys sample. With no explicit `extra-keys` in
   `termux.properties`, the bar changes. Disable it; the built-in bar returns.
6. Add an explicit `extra-keys` value; the user value wins.
7. Create a directory under `$HOME`; enable the preset sample; long-press the terminal
   and select its menu entry. A normal session opens in that directory.
8. Disable each plugin; the built-in theme/keys/preset entries disappear.
9. Uninstall each plugin; confirm no `$HOME` or `~/.termux` file changed and the
   entries are gone after restart. Unrelated user configuration must remain.
10. Reinstall the APK (or force-stop and relaunch); plugin registrations and enabled
    state persist. The CI emulator job checks installed-sample persistence only.
11. Corrupt one installed `manifest.json`; reopen Plugins; the entry is shown invalid
    and contributes nothing. Restore it or uninstall it through the manager.
12. Confirm the manager shows "No plugin repository is configured in this build."
    Do **not** configure a production repository URL or key in this phase; remote
    catalog behavior stays disabled until a real repository and trust configuration
    exist.

## 7. What was not tested

- **CI verification.** The x86_64 workflow and emulator smoke job in §9 have not run.
  GitHub Actions only runs on a pushed ref; this worktree is uncommitted and no
  x86_64 host was available.
- **Device/emulator execution.** No APK was built in this environment, so runtime
  styling, menu behavior, persistence across process death, and TalkBack/accessibility
  were not exercised. The physical-device checklist in §6.2 is still pending.
- **Real repository.** No signed repository or pinned key is configured in this build;
  only the parser/verifier unit tests and the bundled samples were exercised. Remote
  catalog behavior stays disabled until a real repository and production trust
  configuration exist.
- **Release build/R8.** The release pipeline was not run; the activity is
  manifest-declared and there is no reflection.
- **Android 21–35 matrix.** Unit tests run on the JVM. Production code avoids
  API-26-only `java.util.Base64` and `java.nio.file`; this is enforced by review, not by
  a device matrix.
- **Termux companion apps.** They were not installed or tested with the new APK.
- **Concurrent operations.** The UI serializes actions on one executor, but no
  multi-process stress test was run.

## 8. Reproducing without the host override

On a normal x86_64 development machine with Android SDK/NDK installed, omit
`-Pandroid.aapt2FromMavenOverride=...` and run the same Gradle tasks. The `:app`
module still needs the SDK matching `compileSdkVersion 36` and the NDK version pinned
in `app/build.gradle`. The same tasks are wired into §9 for CI.

## 9. Phase 6 — x86_64 CI verification

Workflow: `.github/workflows/plugin-verification.yml`.
Trigger: pushes to `master` and `plugins-system`, pull requests targeting `master`,
and manual `workflow_dispatch`. GitHub only exposes `workflow_dispatch` once the
workflow exists on the default branch, so before this lands the supported first run
is a push to `plugins-system` (the branch name is hardcoded in the trigger) or a pull
request targeting `master`. A push to any other branch name does not trigger it
unless the trigger list is updated.

### 9.1 `build-and-test` job

Runner: `ubuntu-24.04` (x86_64), JDK 17 (Temurin), Android SDK platform 36,
build-tools 36.0.0, NDK 29.0.14206865, Gradle caching via
`gradle/actions/setup-gradle`.

Tasks, in order, all required:

| Step | Command |
| --- | --- |
| Plugin tests | `./gradlew :app:testDebugUnitTest --tests 'com.termux.app.plugins.*'` |
| Full app suite | `./gradlew :app:testDebugUnitTest` |
| Baseline gate | `python3 scripts/check-unit-tests.py app/build/test-results/testDebugUnitTest --allow-baseline --min-tests 48 --gradle-exit "$EXIT"` |
| Terminal emulator | `./gradlew :terminal-emulator:testDebugUnitTest` |
| APK | `./gradlew :app:assembleDebug` |
| APK verification | require the universal APK and write `sha256sums.txt` |
| Lint | `./gradlew :app:lintDebug` |

APK assembly deliberately precedes lint: if lint fails, the debug APK and its
checksums are still uploaded. The job and workflow still fail.

The full app suite is allowed to fail only through
`scripts/check-unit-tests.py`. That script permits the single known
`FileReceiverActivityTest.testIsSharedTextAnUrl` failure **and only when its message
still matches** `ShadowActivityThread.reset: ActivityThread not set`. Any other
failure, a changed message, a missing or skipped baseline test, fewer than 48 executed
tests, missing XML, or a Gradle failure without the baseline exits non-zero. If the
baseline test executes and passes, the gate passes and notes that the quarantine can be
removed. The raw Gradle output and XML are kept, so the red baseline is visible rather
than hidden. This is a quarantine, not a fix; see `ROADMAP.md` M2.

Static validation: `actionlint` 1.7.12 accepts the workflow, ShellCheck 0.11.0
accepts `scripts/emulator-smoke.sh`, `python3 -m py_compile` accepts the JUnit gate,
and `git diff --check` reports no whitespace errors. The JUnit gate was exercised
locally against the real baseline XML and against synthetic unexpected-failure,
changed-message, passing-baseline, skipped-baseline, missing-baseline, too-few-tests,
and missing-XML cases; only the real baseline and passing-baseline cases passed.

Artifacts (retained 14 days):

- `termux-debug-apk`: all debug APKs, `output-metadata.json`, and `sha256sums.txt`.
  It is uploaded whenever at least one debug APK exists, including after a lint
  failure. If assembly produced no APK, the job has already failed and the upload step
  is skipped rather than adding a second failure. The universal APK is
  `app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk`
  unless `TERMUX_PACKAGE_VARIANT` / `TERMUX_APK_VERSION_TAG` change the name.
- `plugin-verification-reports`: JUnit XML/HTML for `:app` and `:terminal-emulator`,
  plus `app/build/reports/lint-results-debug.*`.

### 9.2 `emulator-smoke` job

Runner: `ubuntu-24.04` with the GitHub-hosted KVM-enabled Android emulator, API 34,
x86_64 AOSP image, Pixel 2 profile. It downloads `termux-debug-apk` and runs
`scripts/emulator-smoke.sh`.

The job runs whenever assembly and APK verification both succeeded, even if a later
step such as lint failed; the run uses
`needs.build-and-test.outputs.apk_built == 'true'` and does not require the whole
build job to be green. The workflow remains failed in that case, so the emulator is
extra evidence, not a pass. If assembly or verification failed, or the run was
cancelled, the emulator job is skipped because no APK artifact exists.

The script performs a fresh install (uninstall first), first launch, bootstrap wait,
one terminal command typed into the terminal with zero plugins, Settings → Plugins,
install of all three bundled samples, and a force-stop/relaunch check that the
registrations persist. It saves screenshots, UI dumps, `registry.json`, and logcat to
the `emulator-smoke-evidence` artifact.

It deliberately does **not** claim coverage of theme application/fallback, extra-keys
application/fallback, preset use, uninstall safety, invalid-state recovery, or
accessibility. Those remain steps 3–9 and 11 of §6.2 on a physical device.

### 9.3 Current status

**Not executed.** No workflow run, APK, lint result, or emulator result exists yet.
This environment is aarch64, the worktree has no upstream branch, and the changes are
intentionally uncommitted. Do not cite §9 as passing evidence.

For the first run, commit the work and push the branch under the name hardcoded in
the trigger:

```bash
git push -u origin plugins-system
```

Then open the "Plugin verification (x86_64)" run for that push. Opening a pull request
from `plugins-system` to `master` is the alternative and triggers the same workflow.
`workflow_dispatch` is not listed until the workflow reaches the default branch; after
it is merged, manual runs can use:

```bash
gh workflow run plugin-verification.yml --ref plugins-system
gh run watch
```

Record the run URL, APK SHA-256, JUnit counts, lint result, and emulator artifact. If
the emulator job fails for infrastructure reasons, keep that failure; it is not a
passing runtime check.
