# Plugin System — Testing and Build Notes (API v2)

Status: local v2 verification recorded below; x86_64 CI results for the v2 rewrite are
recorded in §6 as runs execute. Audience: anyone reproducing the work or reviewing its
claims. A test is only complete when its observation is recorded; commands are not
evidence by themselves.

## 1. Development environment (current host)

| Item | Value |
| --- | --- |
| Host | aarch64 Linux under Termux PRoot (Android) |
| JDK for Gradle | Temurin 17 at `/opt/jdks/jdk-17.0.21+9` |
| Gradle | 9.2.1 (wrapper) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.4.21 (+ Kotlin-bundled Compose compiler plugin) |
| Compose | BOM 2025.10.00 (ui 1.9.3, material3 1.4.0) — newest line with minSdk 21 |
| Android SDK | `/root/android-sdk` (`platforms;android-35` + `android-36`, `build-tools;36.0.0`, NDK 29.0.14206865) |
| aapt2 override | `/usr/bin/aapt2` (Debian package, **2.19 — too old for API 35/36 resource tables**) |

`local.properties` contains `sdk.dir=/root/android-sdk` (git-ignored).

**Host limitation (changed from v1 notes):** the old Debian aapt2 cannot load the
resource tables of `android-35`/`android-36` (`RES_TABLE_TYPE_TYPE entry offsets overlap
actual entry data`), so `processDebugResources` — and therefore full unit-test/APK tasks —
fail locally regardless of the override. Google ships no aarch64 Linux aapt2. The v1-era
Termux-prefix aapt2 (`/data/data/com.termux/files/usr/bin/aapt2`) is not present on this
host. Local verification therefore uses the two paths in §2; the full build runs in CI (§6).

## 2. Local v2 verification (executed)

### 2.1 Pure-JVM core compile + unit tests (standalone Kotlin compiler)

The plugin core is pure JVM by design (org.json + java.io + java.security + Guava
BaseEncoding; only `PluginManager`/`PluginExporter` touch Android). It is compiled and
tested with `kotlin-compiler-embeddable` 2.4.21 + JUnit 4.13.2 + org.json 20240303 +
Guava 24.1-jre from Maven Central / the Gradle cache:

```bash
# compile core (excludes the two Android-only files)
java -cp "kc.jar:stdlib.jar:reflect.jar:coroutines.jar:trove.jar:annotations.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 1.8 \
  -cp "stdlib.jar:json.jar:guava.jar" -d core-out <core files>
# compile tests (friend paths expose `internal` members, as Gradle does)
java ... K2JVMCompiler -no-stdlib -jvm-target 1.8 -Xfriend-paths=core-out \
  -cp "stdlib.jar:json.jar:guava.jar:junit.jar:hamcrest.jar:core-out" -d test-out <test files>
# run from the app module dir so BundledPluginsTest finds src/main/assets/plugins
cd app && java -cp "test-out:core-out:stdlib.jar:json.jar:guava.jar:junit.jar:hamcrest.jar" \
  org.junit.runner.JUnitCore com.termux.app.plugins.<each test class>
```

Result (this revision): `KOTLIN_COMPILE_EXIT=0`, `TEST_COMPILE_EXIT=0`,
**`OK (85 tests)`** in ~4 s.

| Test class | Tests | Covers |
| --- | ---: | --- |
| `SemverTest` | 5 | Strict Semver 2.0.0 parse/reject, precedence incl. prerelease ordering, build-metadata equality |
| `PluginParserTest` | 16 | Full document, round-trip serialization, unknown fields, schema/API versions, id grammar, bounds, duplicate exclusives, theme color forms, extra-keys shape/popups, preset path grammar, terminal-settings whitelist + shared editor validator, settings schema per type, tokens, dependencies |
| `PluginSettingsTest` | 8 | Effective values + fallbacks, per-type validation, token resolution, fail-closed resolved colors/paths, host JSON shape, Android-ICU-safe token regex |
| `PluginArchiveTest` | 9 | Round-trip, LICENSE, missing/unknown/traversal/duplicate entries (raw duplicate local headers), oversize archive/document, garbage magic |
| `PluginStoreTest` | 10 | Registry round-trip, quarantine-not-overwrite, v1 detection, hash tamper detection, move-aside commit, deletion containment, unsafe segments, orphan cleanup, staging |
| `PluginRuntimeTest` | 12 | Active/disabled themes, generation, tamper fail-closed, exclusive ordering, missing/disabled deps, cycles, preset canonicalization/existence, terminal-settings merge, future-core, settings tokens into active theme |
| `PluginInstallerTest` | 15 | Fresh install states, update preserves enabled+settings, downgrade rejection, dependency gates, exclusive swap, cascades, uninstall, settings validation/reset, duplicate, in-place edit re-labeling, catalog identity, artifact validation, idempotent enable |
| `PluginMigrationTest` | 3 | All three v1 types migrate (enabled state, backup, runtime loads), unconvertible skipped, no-op for v2/empty |
| `PluginRepositoryTest` | 4 | Catalog parse/rejects, RSA-2048 signature verify + tamper failure, weak-key rejection |
| `BundledPluginsTest` | 3 | Shipped v2 ZIPs parse + resolve through the real parser; accent/paper-tone/all-caps settings resolve |

### 2.2 Full Kotlin + Compose compile (Gradle, resource tasks skipped)

No plugin Kotlin code references `R`, so the Kotlin compile can run without resource
linking on this host:

```bash
export JAVA_HOME=/opt/jdks/jdk-17.0.21+9
./gradlew --no-daemon -PcompileSdkVersion=35 \
  -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2 \
  :app:compileDebugKotlin \
  -x processDebugResources -x mergeDebugResources -x packageDebugResources \
  -x parseDebugLocalResources -x generateDebugRFile -x generateDebugResValues
```

Result (this revision): **`BUILD SUCCESSFUL in 2m 44s`**; all core and
`com.termux.app.plugins.ui` classes present in
`app/build/tmp/kotlin-classes/debug/`. `-PcompileSdkVersion=35` satisfies every Compose
dependency's `minCompileSdk=35` (verified from the AAR `aar-metadata.properties`); CI
compiles against 36. This compiles the Compose UI but does not execute it; Java hook
compilation, resources, dexing and runtime behavior are verified in CI.

### 2.3 Startup crash fix and re-verification (Termux/aarch64 host)

A device crash report showed `ExceptionInInitializerError` at `ZenApi.<clinit>` during
`TermuxApplication.onCreate` → `PluginManager.init` → `PluginMigration.migrateIfNeeded` →
`PluginParser.parse` → `PluginJson.requireString`: `SETTING_TOKEN_REGEX` ended with a bare
`}` (index 31), which `java.util.regex` accepts as a literal but Android's ICU regex
rejects (`PatternSyntaxException: Syntax error in regex pattern near index 32`). The
closing brace is now escaped and `PluginSettingsTest.settingTokenRegexIsAndroidIcuSafe`
guards the escaped pattern, since JVM-only tests cannot catch this engine difference.

Re-verified on a real Termux/aarch64 host (not the PRoot host of §1) with `openjdk-17`,
aapt2 2.20, SDK platform 36 + build-tools 36.0.0 and
`-Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`:
85 pure-JVM tests, `:app:compileDebugKotlin` BUILD SUCCESSFUL,
`:app:testDebugUnitTest` 89 tests / 0 failures, `:terminal-emulator:testDebugUnitTest`
145 tests / 0 failures. Without the override, AGP's Maven aapt2 cannot execute on
aarch64. APK assembly and lint remain CI-only here: `ndk-build` reports
`Unknown host CPU architecture: aarch64` (NDK r29 and r30 ship only `linux-x86_64`
prebuilts).

### 2.4 Plugin editor/hub UX fixes and re-verification

From device screenshots: the editor let users change metadata but not the contribution
values they actually want to tune, a tap on a key chip deleted the key, a redundant key
preview repeated the rows, and installed plugins were not marked as installed in Browse.

Changes: key chips now open a cell editor on the label and delete through a separate
button (with a second, deliberate "Remove key" action inside the dialog); the duplicate
key preview was removed; terminal-settings values are editable (including `${settings.id}`
tokens) using one validator shared with the strict parser
(`TerminalSettingsSchema.isValidDocumentValue`); Browse cards show an "Installed" badge
and a disabled "Installed" button for installed plugins; Edit is available for any
installed plugin, with a confirmation for bundled/repository artifacts and a `local`
re-label on save. New tests cover the shared validator and the in-place re-label.

Re-verified on the same aarch64 host: 85 pure-JVM tests,
`:app:compileDebugKotlin` BUILD SUCCESSFUL, `:app:testDebugUnitTest` 89 tests / 0 failures.
Emulator/device interaction with the new controls still needs a CI APK and the §5 checklist.

### 2.5 Bundled plugin set and expanded Coding Keys

The two theme samples (Paper Light, Solarized Dark) were replaced by a single dark theme
sample, **Midnight Dark** (`com.xeriomy.zen.midnightdark`), with a configurable cursor
accent. **Coding Keys** was expanded to 1.1.0 with navigation, F1–F12, KEYBOARD, and macro
rows alongside the original modifiers and PWD key, so the bundled sample exercises more of
the extra-keys grammar. Both bundled artifacts were re-parsed and resolved by
`BundledPluginsTest`; the emulator smoke now looks for Midnight Dark instead of the removed
themes. Device interaction with the new key rows remains on the §5 checklist.

### 2.6 Author-defined metadata and settings schema

The user clarified that plugin identity, version and the list of user-editable settings are
authored by the plugin developer in `plugin.json`, not changed by a user from the in-app
editor. Drafts loaded from an installed artifact (including duplicates) are now
schema-locked: name, id, version, author and description are read-only, the settings list is
read-only (no add/edit/delete), and raw-JSON mode is hidden so the lock cannot be bypassed.
Only contribution values stay editable; setting **values** continue to be changed on the
detail screen's generated form. New-plugin authoring keeps all fields available.
`EditorDraftTest` covers both locks. Re-verified: 85 pure-JVM tests,
`:app:compileDebugKotlin` BUILD SUCCESSFUL, `:app:testDebugUnitTest` 89 tests / 0 failures.

## 3. What local verification does not cover

- Java sources (host hooks, termux-shared overlay) — compiled in CI.
- Resource linking, manifest merge, dexing, APK assembly — CI.
- Robolectric app tests and the emulator suite — CI (they need resource processing).
- Runtime UI behavior, styling application, accessibility — CI emulator smoke + device
  checklist (§5).

## 4. Reproducing on a normal x86_64 host

With SDK platform 36 + build-tools 36.0.0 + NDK 29.0.14206865 installed, no overrides:

```bash
./gradlew :app:testDebugUnitTest          # full app suite incl. 85 plugin tests
./gradlew :terminal-emulator:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

## 5. Device checklist (pending on physical devices)

The CI emulator job covers install → hub → bundled install → generated settings →
restart persistence. It does not cover styling application/fallback, presets, uninstall
safety, invalid-state recovery or accessibility.

1. Install the APK; terminal + bootstrap work; **zero** plugins installed.
2. Settings → Plugins: empty state; Get plugins shows the two bundled plugins and the
   "no repository configured" notice.
3. Install Midnight Dark (confirmed, active). With no `colors.properties`, colors change
   on resume. Change its **Cursor color** setting; cursor color updates. Disable; built-in
   colors return.
4. Create `~/.termux/colors.properties`; the plugin theme is inert/overridden; user file wins.
5. Install Coding Keys; extra-keys bar changes (no explicit `extra-keys`): check the
   navigation (HOME/PGUP), F1–F12, KEYBOARD and macro/PWD rows. Toggle **ALL-CAPS key
   labels**; terminal cursor becomes a blinking bar; scrollback grows. Set
   `terminal-cursor-style` in `termux.properties`; the user value wins.
6. Add an explicit `extra-keys`; plugin keys become overridden.
7. Create a plugin in the editor (theme + a color setting), save, enable, verify; edit its
   contribution colors only — name/ID/version/author/description and the settings list must
   be read-only, with no **Advanced: edit JSON** button; duplicate a bundled plugin and edit
   the copy's contributions; export and re-import the ZIP. Setting values are changed on the
   detail screen, not in the editor.
8. Duplicate Midnight Dark and enable the local copy while the original is enabled:
   confirm the exclusive-swap warning; the previous theme provider is disabled.
9. Dependencies: install a preset-only base + a dependent plugin; disable the base and
   confirm the cascade warning and dependent disable; uninstall the base.
10. Uninstall everything; confirm no `$HOME`/`.termux` file changed; entries gone after
    restart; unrelated configuration intact.
11. Corrupt an installed `plugin.json` (run-as); the entry shows Invalid + reason and
    contributes nothing; uninstall works.
12. v1 migration: install a v1-era build, add a v1 plugin, upgrade; state survives.
13. Search, rotation, TalkBack, large font: descriptive labels, ≥48 dp targets.

## 6. x86_64 CI

Workflow: `.github/workflows/plugin-verification.yml` (push to `master`/`plugins-system`,
PRs to `master`, `workflow_dispatch` once on the default branch).

`build-and-test` (ubuntu-24.04, Temurin 17, SDK 36/build-tools 36/NDK r29): plugin tests →
full app suite → gate `scripts/check-unit-tests.py --min-tests 89` (85 plugin + 4 app
tests; the historical `FileReceiverActivityTest` Robolectric-teardown quarantine is still
encoded and only matches its exact message) → emulator unit tests → `assembleDebug` →
APK checksums → `lintDebug`. Artifacts: `termux-debug-apk`, `plugin-verification-reports`.

`emulator-smoke` (API 34 x86_64, KVM enabled via `sudo chmod 0666 /dev/kvm`): fresh
install → first launch/bootstrap → terminal command with zero plugins → Settings →
Plugins → hub empty state → Get plugins (bundled + not-configured catalog) → install
Coding Keys via the confirmation dialog → registry check → detail screen shows the
generated **ALL-CAPS key labels** setting and contribution previews → force-stop/relaunch
persistence. Evidence artifact: screenshots, UI dumps, registry, logcat.

### 6.1 v2 executed runs

Recorded as runs complete (run id, commit, jobs, APK SHA-256):

| Run | Commit | Build job | Emulator job |
| --- | --- | --- | --- |
| pending | — | — | — |

### 6.2 v1 run history (historical, pre-rewrite)

The v1 MVP reached green end-to-end runs on `plugins-system` (build + lint + emulator),
e.g. runs 40733083297 (`0e6f3cbf`) and 40736015071 (`f409b5d4`): 57 plugin tests, 59 app
tests, 145 emulator tests, lint 0 errors, 5-ABI + universal APKs, ~45 s emulator smoke.
Earlier failures were diagnosed as: no usable KVM (fixed with the chmod step), a
`grep -o | wc -l` off-by-one in the script, case-sensitive tab/button matching (Material
uppercases; helpers now match text or content-desc case-insensitively), and one transient
Maven Central 403 wall at dependency resolution (re-run green; treat as infrastructure).
The v1 APK installed by the final v1 run hashed
`6bd5a42ae26ad1664c44e5c386423e12a23d43f85da65c74444b00d82d57c0af`. These runs verify the
v1 code only; they are not evidence for the v2 rewrite.

## 7. Not tested (v2)

- Physical devices and the Android 21–35 matrix (Compose line chosen keeps minSdk 21;
  core avoids API-26-only `java.util.Base64`/`java.nio.file` — Guava BaseEncoding is used).
- Real signed repository (build fields empty; parser/verifier unit-tested only).
- Release build/R8 (activity + FileProvider are manifest-declared; no reflection).
- Companion apps alongside the new APK.
- Multi-process stress (UI serializes on one executor).