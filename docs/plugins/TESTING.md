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
host. Local verification therefore uses the two paths in §2; the full build runs in CI (§6). On the current Termux/aarch64 host the Termux-prefix aapt2
2.20 **is** available and unit tests can run with the override (see §2.8); APK assembly
still cannot run because the NDK prebuilts are x86_64-only.

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
86 pure-JVM tests, `:app:compileDebugKotlin` BUILD SUCCESSFUL,
`:app:testDebugUnitTest` 90 tests / 0 failures, `:terminal-emulator:testDebugUnitTest`
146 tests / 0 failures. Without the override, AGP's Maven aapt2 cannot execute on
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

### 2.5 Bundled plugin set and expanded key sample

The two theme samples (Paper Light, Solarized Dark) were replaced by a single dark theme
sample, **Midnight Dark** (`com.xeriomy.zen.midnightdark`), with a configurable cursor
accent. The key sample (now named **Dev Terminal**, id still `com.xeriomy.zen.codingkeys`)
was expanded to 1.1.0 with navigation, F1–F12, KEYBOARD, and macro rows, and later slimmed
to two rows in 1.2.0. Both bundled artifacts were re-parsed and resolved by
`BundledPluginsTest`; the emulator smoke now looks for Midnight Dark instead of the removed
themes. Device interaction with the new key rows remains on the §5 checklist.

### 2.6 Author-defined metadata and settings schema

The user clarified that plugin identity, version and the list of user-editable settings are
authored by the plugin developer in `plugin.json`, not changed by a user from the in-app
editor. Drafts loaded from an installed artifact (including duplicates) are now
schema-locked: name, id, version, author and description are read-only, the settings list is
read-only (no add/edit/delete), and raw-JSON mode is hidden so the lock cannot be bypassed.
Only contribution values stay editable; setting **values** are changed in the plugin
editor's generated form. New-plugin authoring keeps all fields available.
`EditorDraftTest` covers both locks. Re-verified: 85 pure-JVM tests,
`:app:compileDebugKotlin` BUILD SUCCESSFUL, `:app:testDebugUnitTest` 89 tests / 0 failures.

### 2.7 Detail/editor simplification and theme removal

From the next round of device screenshots: the **Duplicate & edit** and **Export** buttons
were removed from the detail screen (Edit and Uninstall remain), the enable switch now sits
on the title row of the header card, and the Details block (version/author/source/id) sits
inside that same card, together with the description. For a loaded draft the editor no longer shows the
author-defined metadata block, the settings-schema list, the contribution delete buttons or
**Add contribution**; only the declared contribution and setting values remain editable. The
extra-keys style chips are labelled as the style used when no explicit `extra-keys` is set.

The theme palette also failed to return to the built-in scheme after a theme plugin was
uninstalled: a plugin reload can finish after `TermuxActivity` has already resumed, so the
generation check in `onResume()` missed it. `TermuxActivity` now subscribes to runtime
change notifications while visible and re-applies styling on every generation change;
`PluginRuntimeTest` covers the runtime side and `TerminalColorSchemeTest` covers palette
reset with empty properties.

### 2.8 Dev Terminal rename, compact default rows, macro fixes and editor controls

From the next device screenshots: the bundled key plugin's display name was **Coding Keys**
even though it carried much more than keys, so it is now **Dev Terminal** (id unchanged:
`com.xeriomy.zen.codingkeys`, version 1.2.0). Its default extra keys are the two rows shown
in the screenshot: `ESC CTRL ALT SHIFT BACKSLASH UP KEYBOARD` and
`TAB FN HOME END LEFT DOWN RIGHT`; the F1–F12 and macro rows remain supported by the
editor but are no longer bundled defaults. The terminal-settings contribution is unchanged.

Macros had two real defects. `TerminalExtraKeys` split a macro on spaces and wrote each
text token without a separator, so `echo hello world` was typed as `echohelloworld`;
`MacroKeySequence` now preserves literal spaces between text tokens while keeping the
key-name grammar (`CTRL f d` stays a key sequence with separator-only spaces). A new
macro-only `sendOnPress` boolean in `plugin.json` appends an Enter action when true;
without it the macro only types. Legacy macros ending in the two literal characters `\n`
are migrated to that flag at parse time. Macros authored in the editor default to
send-on-press, while documents without the field keep type-only behavior. The key chooser
dialog now has a **More** button revealing KEYBOARD, F1–F12, BACKSLASH and the rest of the
named keys, then **Less** to collapse back to the compact 18-chip set.

Verification executed on the Termux/aarch64 host:

```bash
./gradlew --no-daemon -PcompileSdkVersion=36 \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  :app:compileDebugKotlin :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest \
  --console=plain
# BUILD SUCCESSFUL
python3 scripts/check-unit-tests.py app/build/test-results/testDebugUnitTest --min-tests 100
python3 scripts/check-unit-tests.py terminal-emulator/build/test-results/testDebugUnitTest
```

Result: `:app:testDebugUnitTest` **102 tests, 0 skipped, 0 failures** (was 90), including
`MacroSendOnPressTest` (5 data-path tests), `MacroKeySequenceTest` (6 pure dispatch tests),
`ExtraKeyButtonTest` (1 host JSON flag test) and an exact two-row `BundledPluginsTest`.
`:terminal-emulator:testDebugUnitTest`
**146 tests, 0 skipped, 0 failures**. The standalone pure-JVM core compile was not re-run
because the temporary jar directory (`.../tmp/opencode/pv`) is no longer present on this
host; the Gradle app suite compiles and runs all of those core classes plus the new tests.
Emulator smoke (including the renamed card/install flow) is CI-only here.

### 2.9 Merged detail card, settings form moved into the editor

From the next device screenshot: the header card, the Details block and the description were
circled together ("I think we can merge those sections in details menu"), and the generated
settings form was requested on the plugin's **Edit** screen — with the contribution and
terminal-settings editors, not on the app's main Settings screen.

Changes: `DetailScreen` now renders the title row (with the enable switch), id, status/type
chips, Details (version/author/source/id) and the description inside **one** card, separated
by a divider. The standalone Details heading, description paragraph and the whole Settings
section were removed; contribution previews, dependencies, Edit and Uninstall are unchanged.
`EditorScreen` renders the generated toggle/number/text/choice/color form plus **Reset to
defaults** next to the contribution editors for installed plugins only. `EditorDraft.pluginId`
points at the installed record (set by the edit/duplicate entry points via `from`); new-plugin
drafts have no values form and keep the settings-schema designer. Effective values come from
`PluginSettings.effectiveValues(installed.manifest, record.settings)`, and each change writes
through `HubController.setSetting` / `resetSettings` immediately, exactly as before.

Verification executed on the Termux/aarch64 host:

```bash
./gradlew --no-daemon -PcompileSdkVersion=36 \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 \
  :app:compileDebugKotlin :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest \
  --console=plain
# BUILD SUCCESSFUL
python3 scripts/check-unit-tests.py app/build/test-results/testDebugUnitTest --min-tests 104
python3 scripts/check-unit-tests.py terminal-emulator/build/test-results/testDebugUnitTest --min-tests 146
```

Result: `:app:testDebugUnitTest` **104 tests, 0 skipped, 0 failures** (was 102; two new
`EditorDraftTest` cases cover the installed-plugin id and the effective-value path used by
the editor). `:terminal-emulator:testDebugUnitTest`
**146 tests, 0 skipped, 0 failures**. `git diff --check` clean. The emulator smoke script now
checks the merged card, then opens Edit and asserts the generated **ALL-CAPS key labels**
setting and **Reset to defaults** there; that smoke and the §5 checklist are CI/device work.

## 3. What local verification does not cover

- APK assembly, resource linking, manifest merge, dexing, lint and NDK builds — CI.
  Java sources are compiled locally by the Gradle command above; they are not executed
  on a device here.
- Resource linking, manifest merge, dexing, APK assembly — CI.
- Robolectric app tests and the emulator suite — CI (they need resource processing).
- Runtime UI behavior, styling application, accessibility — CI emulator smoke + device
  checklist (§5).

## 4. Reproducing on a normal x86_64 host

With SDK platform 36 + build-tools 36.0.0 + NDK 29.0.14206865 installed, no overrides:

```bash
./gradlew :app:testDebugUnitTest          # full app suite (104 tests in this revision)
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
   on resume. Open **Edit**, change its **Cursor color** setting there; the cursor color
   updates immediately. **Reset to defaults** restores the author's default. Disable;
   built-in colors return.
4. Create `~/.termux/colors.properties`; the plugin theme is inert/overridden; user file wins.
5. Install Dev Terminal; extra-keys bar changes (no explicit `extra-keys`): check the two
   rows (ESC/CTRL/ALT/SHIFT/BACKSLASH/UP/KEYBOARD and TAB/FN/HOME/END/LEFT/DOWN/RIGHT).
   Toggle **ALL-CAPS key labels**; terminal cursor becomes a blinking bar; scrollback grows. Set
   `terminal-cursor-style` in `termux.properties`; the user value wins.
6. Add an explicit `extra-keys`; plugin keys become overridden.
7. Create a plugin in the editor (theme + a color setting), save, enable, verify. Open Edit on
   Midnight Dark: metadata and the settings list are absent, no **Add contribution**, no
   contribution delete buttons, no **Advanced: edit JSON**. Its generated setting values form
   sits with the contribution editors; identity/schema stay locked. The detail screen shows
   one merged card (title + switch, chips, Details, description), contribution previews,
   dependencies and only Edit and Uninstall — no Settings section.
8. Install Midnight Dark, confirm the palette changes, then uninstall it while returning to
   the terminal promptly: the built-in colors must come back. Installing a second theme while
   the first is enabled must still confirm the exclusive-swap warning.
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
full app suite → gate `scripts/check-unit-tests.py --min-tests 104` (104 tests in this
revision; the historical `FileReceiverActivityTest` Robolectric-teardown quarantine is still
encoded and only matches its exact message) → emulator unit tests → `assembleDebug` →
APK checksums → `lintDebug`. Artifacts: `termux-debug-apk`, `plugin-verification-reports`.

`emulator-smoke` (API 34 x86_64, KVM enabled via `sudo chmod 0666 /dev/kvm`): fresh
install → first launch/bootstrap → terminal command with zero plugins → Settings →
Plugins → hub empty state → Get plugins (bundled + not-configured catalog) → install
Dev Terminal via the confirmation dialog → registry check → detail screen shows the merged
header/Details/description card and contribution previews → Edit shows the generated
**ALL-CAPS key labels** setting and **Reset to defaults** → force-stop/relaunch persistence.
Evidence artifact: screenshots, UI dumps, registry, logcat.

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