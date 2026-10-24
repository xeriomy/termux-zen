# Plugin System — Repository Audit (Phase 0)

Date: 2026-10-22
Repository: Termux app fork, branch `plugins-system`
Upstream baseline: `8629e632` (`master` at time of audit)

This document records what was inspected before source changes. Commands are listed with
their real output in [TESTING.md](TESTING.md). Nothing in this audit changed application
behavior.

## 1. Repository shape

Four Gradle modules (`settings.gradle:1`):

| Module | Role |
| --- | --- |
| `:app` | Android application: activity, service, terminal clients, settings UI |
| `:termux-shared` | Shared constants and utilities, also consumed by Termux companion apps |
| `:terminal-emulator` | Terminal emulator core and JNI |
| `:terminal-view` | Terminal `View` implementation |

- `:app` depends on `:terminal-view` and `:termux-shared` (`app/build.gradle:28-42`).
- Java 8 source/target, core-library desugaring, `minSdkVersion=21`, `targetSdkVersion=28`,
  `compileSdkVersion=36` (`gradle.properties`, `app/build.gradle`).
- Release builds run R8 (`app/build.gradle:89-94`). `proguard-rules.pro` disables obfuscation
  but not shrinking; XML-referenced fragments are annotated `@Keep`.
- There is no JSON library, dependency-injection framework, plugin framework, Kotlin, Compose,
  or JavaScript runtime. `org.json` is platform-provided.

## 2. Identity, prefix, and bootstrap

The application identity is deliberately hard-coded across several layers:

- `namespace "com.termux"` and no separate `applicationId` in `app/build.gradle:16`.
- `android:sharedUserId="${TERMUX_PACKAGE_NAME}"` (`app/src/main/AndroidManifest.xml:5`),
  with `TERMUX_PACKAGE_NAME = "com.termux"` (`app/build.gradle:54`).
- `TermuxConstants.TERMUX_PACKAGE_NAME = "com.termux"`
  (`termux-shared/.../TermuxConstants.java:352`).
- `$PREFIX` is `/data/data/com.termux/files/usr` and `$HOME` is
  `/data/data/com.termux/files/home` (`TermuxConstants.java:581-659`).
- `TermuxInstaller` rejects installation when `getFilesDir()` is not
  `/data/data/com.termux/files` (`app/.../TermuxInstaller.java:92`), because bootstrap
  binaries have the prefix compiled in.
- The bootstrap zip and its SHA-256 checksums are pinned in `app/build.gradle` for the
  `apt-android-7` and `apt-android-5` variants.

Consequences, with evidence:

1. **Replacing official Termux** requires the official signing key. A fork signed with its own
   key cannot update or replace an installed official `com.termux`. Uninstalling the official
   app deletes `/data/data/com.termux`, including `$HOME` and `$PREFIX`, unless the user has a
   backup.
2. **Coexistence with official Termux** is not possible while keeping the identity: the
   package name, `sharedUserId`, prefix, provider authority, permission names, and companion-app
   UID all collide. Changing `applicationId` alone is not enough: the data directory and
   compiled-in `$PREFIX` still point at `com.termux`, and the companion apps share the UID.
3. **Changing prefix or package name** requires rebuilding the bootstrap and all Termux
   packages, as documented in `README.md` ("Forking") and in `TermuxConstants.java:294-323`.
   That is out of scope and would break compatibility.

**Decision for this fork:** keep `com.termux`, keep the existing signing configuration, and
document that the APK is installed on a device/emulator without the official app (or after an
explicit backup and uninstall). The plugin system does not touch identity, prefix, bootstrap,
or package repositories.

## 3. Startup and lifecycle insertion points

`TermuxApplication.onCreate` (`app/.../TermuxApplication.java:24-74`) is synchronous and runs
before any activity. It initializes crash handling, preferences, the shell manager, bootstrap
variant checks, the AM socket, and the shell environment.

`TermuxActivity.onCreate` (`app/.../TermuxActivity.java:197-280`) loads properties, applies
the activity theme, builds terminal clients, loads colors/font, builds extra keys, and binds
`TermuxService`. `TermuxActivity.onStop` unregisters the styling broadcast receiver
(`TermuxActivity.java:343`), so a "reload styling" broadcast sent while Settings or the plugin
manager is on top is **lost**. The system must re-check plugin state in `onResume`.

Terminal styling is loaded in `TermuxTerminalSessionActivityClient.checkForFontAndColors`
(`app/.../TermuxTerminalSessionActivityClient.java:494-518`) from fixed files
`~/.termux/colors.properties` and `~/.termux/font.ttf`. `TerminalColorScheme.updateWith`
resets, then applies properties and can throw mid-apply on malformed input
(`terminal-emulator/.../TerminalColorScheme.java:72-105`).

Extra keys are built once per activity in `TermuxTerminalExtraKeys.setExtraKeys`
(`app/.../TermuxTerminalExtraKeys.java:49-78`). `reloadActivityStyling` reuses the existing
`ExtraKeysInfo`; changing the key set requires activity recreation or an explicit reload method.

Session creation is centralized in `TermuxTerminalSessionActivityClient.addNewSession`
(`...:364-389`) and `TermuxService.createTermuxSession` (`...:565-575`). A preset that only
chooses a working directory can reuse that path without adding shell execution.

## 4. Existing "plugin" terminology

`termux-shared/.../termux/plugins/TermuxPluginUtils.java` is companion-app and `RUN_COMMAND`
plumbing. Termux:API, Termux:Boot, Termux:Float, Termux:Styling, Termux:Tasker, and
Termux:Widget are separate APKs sharing `sharedUserId com.termux`. The new system is unrelated:
it is declarative data hosted by the app, not an APK, IPC, or `RUN_COMMAND` client.

The new package is therefore `com.termux.app.plugins`, and documentation explicitly
distinguishes "declarative plugins" from "Termux companion apps".

## 5. Reusable infrastructure

- `org.json` for manifest/catalog parsing.
- `java.util.zip.ZipInputStream` for bounded, in-memory artifact reading.
- `java.security.Signature` / `KeyFactory` for RSA `SHA256withRSA`.
- `TermuxConstants` for home/prefix paths.
- JUnit 4.13.2 and Robolectric 4.10 in `:app` (`app/build.gradle:148-150`).
- `org.json:json:20240303` is test-only, because JVM unit tests do not get Android's
  `org.json` implementation; production still uses the platform class.
- `FileUtils` exists but its bootstrap zip extraction (`TermuxInstaller.java:156-205`) does not
  validate archive paths and must **not** be reused for untrusted plugin artifacts.

`android.util.AtomicFile` was considered for the registry but not used: a temporary file
plus `fsync` plus `rename` is smaller, works on a plain JVM for tests, and lets the
registry quarantine an unreadable file before the next save. The implementation does not
reuse bootstrap extraction for plugin archives.

No downloader, signature verifier, archive validator, semver comparator, or plugin storage
code exists. These are new, small, testable classes.

## 6. Baseline build and test status

See [TESTING.md](TESTING.md) for the exact commands and outputs. At audit time the machine had
no Android SDK, NDK, or Gradle cache; `./gradlew` could not be used until the toolchain was
installed. The audit itself changed no source files.

A clean detached worktree at `8629e632` was tested after the toolchain was installed:

- `:terminal-emulator:testDebugUnitTest`: **145 tests passed**, `BUILD SUCCESSFUL in 2m 52s`.
- `:app:testDebugUnitTest`: **2 tests, 1 failed**, `BUILD FAILED in 1m 30s`. The failure is
  `FileReceiverActivityTest.testIsSharedTextAnUrl`, a Robolectric teardown NPE:
  `ShadowActivityThread.reset: ActivityThread not set` on JDK 17. `TermuxActivityTest`
  passes.

The current tree has 46 plugin tests; all pass. The full `:app` suite has 48 tests with the
same single pre-existing failure. No new test failures were introduced. A release APK could
not be built on this aarch64 host because Google's AAPT2 and NDK toolchains are x86_64-only;
the aapt2 override makes resource processing and unit tests run, but native compilation fails
with `ERROR: Unknown host CPU architecture: aarch64`. This is a toolchain limitation, not a
source regression. Device-level tests are still unavailable.

The plugin work does not modify `TermuxActivityTest` or `FileReceiverActivityTest`.

## 7. Risks carried into the design

1. Styling broadcasts are lost while another activity is on top → `onResume` generation check
   calls `reloadActivityStyling(false)`.
2. Terminal color application is not atomic → validate all colors before applying.
3. Extra keys are built once per activity → `TermuxTerminalExtraKeys.reload()` rebuilds them
   during styling reload; no activity recreation is needed.
4. Plugin storage is under app-private `files/plugins`, not `~/.termux`, so Termux companion
   apps and Termux:Styling do not see plugin contributions. Documented limitation.
5. The app shares a UID with its companion apps; plugin files are app-private but not a
   security boundary against a compromised same-UID app.
6. R8 shrinking → manifest-declared activity and XML-referenced classes must be kept. No
   reflection or dynamic loading is used, so other plugin classes are reachable normally.
7. A process kill between an update's directory rename and the registry commit leaves the
   installed hash mismatched; the plugin fails closed and the old copy is quarantined as
   `<version>.old-<time>`. Normal exceptions roll back.
8. The repository signature provides no revocation, expiry, or replay protection; a key
   rotation needs an app release.
9. The development host cannot build the APK or run lint to completion; device verification
   remains the largest unclosed validation gap.
