# Plugin System — Roadmap and Limitations

Status: after the v1 MVP and Phase 6 verification scaffolding. Audience: maintainers.

## 1. What ships in this change

- Versioned declarative plugin API v1 with three types: `theme` (`terminal.theme`),
  `extra_keys` (`terminal.extra_keys`), and `session_preset` (`sessions.preset`).
- Strict manifest/payload/archive validation, bounded in-memory ZIP reading, and
  app-private storage.
- Signed repository index with a pinned RSA key, HTTPS-only fetching, size/checksum
  verification, signed-catalog identity matching, and no redirects.
- Atomic install/update/uninstall with staging, registry hashes, rollback on normal
  errors, and fail-closed runtime activation.
- Host-side capability enforcement, dependency checks, cycle detection, exclusive
  theme/extra-keys handling, and user-configuration precedence.
- A Material Plugin Manager screen with Installed/Bundled/Repository tabs, search,
  refresh, confirmation dialogs, three useful bundled plugins, enable/disable/uninstall, and
  Settings integration.
- JUnit tests for the core; exact results in `TESTING.md`.
- An x86_64 CI workflow for plugin tests, the full app suite, terminal-emulator tests,
  lint, and a debug APK, plus a baseline-aware JUnit gate and an emulator smoke job.
  It ran five times against the earlier revision; the run history and the four failures
  it took to get there are in `TESTING.md` §9.3. This revision still needs a new run.
- Architecture, format, threat model, audit, and testing documents.

## 2. Deliberate v1 limitations

These are known and documented, not accidental:

1. **No executable plugins.** No code, scripts, native libraries, JAR/DEX, or
   callbacks. This is a security boundary, not a missing feature to add casually.
2. **No automatic dependency installation.** Dependencies must already be installed;
   the UI shows the IDs.
3. **One active theme and one active extra-keys layout.** Multiple session presets are
   allowed.
4. **Updates are available only from a configured repository.** The Repository tab shows
   Update for a newer signed catalog entry. No repository is configured in this build, so
   bundled plugins have no update path yet.
5. **No repository configured in this build.** `TERMUX_PLUGIN_REPOSITORY_URL` and
   `TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY` are empty; bundled plugins are the only
   installable content.
6. **App-private state only.** Plugin files are not visible to Termux:Styling or
   companion apps. Plugin themes do not appear in other apps.
7. **CI verification must be re-run for this revision.** The aarch64 host cannot build
   the APK (x86_64 AAPT2/NDK). `.github/workflows/plugin-verification.yml` builds and
   tests on `ubuntu-24.04`; the last recorded run covered the earlier sample bundle,
   not the replacement bundled plugins and revised manager UI. See `TESTING.md` §6
   and §9.
8. **Crash recovery is best-effort.** Normal exceptions roll back; a process kill at
   the wrong moment can leave a `.old-*` directory and an invalid plugin.
9. **No revocation, expiry, or transparency log.** Key compromise requires an app
   release with a new pinned key; replayed old indexes are not detected.
10. **Single-screen UI.** The Material manager uses searchable tabs and cards with
    confirmation-gated actions, but device-level TalkBack and large-font verification is
    still pending.
11. **No same-UID isolation.** Companion apps share the Termux UID and can modify
    app-private plugin files. Impact is limited to declarative content, but integrity
    is not protected against a malicious same-UID app.
12. **No cross-version migration.** API v2 would need explicit host code and a new
    `apiVersion`; there is no dynamic loader.

## 3. Next milestones

Ordered by risk reduction, not feature count.

### M1 — Prove it on a device

- Run the workflow in `TESTING.md` §9 on a pushed branch and record its APK hash,
  JUnit counts, lint result, and emulator proof.
- Execute the manual checklist in `TESTING.md` §6.2 on Android 7, 10, and 14
  (API 21/29/34 minimum) with a clean `com.termux` install. The API 34 emulator job
  covers only install, first launch, one terminal command, the Plugins screen,
  bundled-plugin installation, and restart persistence.
- Verify styling on resume, extra-keys rebuild, preset working directories, process
  death persistence, tamper detection, and uninstall.
- Run TalkBack and large-font checks on the plugin manager.

Exit criterion: recorded APK hash, device matrix results, and no user-data changes
outside `getFilesDir()/plugins`.

### M2 — Make CI honest

Delivered in Phase 6:

- `.github/workflows/plugin-verification.yml` runs the plugin suite, the full `:app`
  suite, `:terminal-emulator:testDebugUnitTest`, `:app:assembleDebug`, and
  `:app:lintDebug` on `ubuntu-24.04`, and uploads the APK when assembly succeeded,
  together with test/lint reports. The emulator job runs after a lint-only failure.
- `scripts/check-unit-tests.py` fails on any unit-test failure and on a reduced suite.
  It has no failure quarantine: the whole `:app` suite is green.
- `scripts/emulator-smoke.sh` exercises install, first launch, one terminal command,
  Settings → Plugins, the disabled-repository state, bundled-plugin install, and restart
  persistence on an API 34 x86_64 emulator.

Still open:

- Add a pure-JVM test for `PluginRuntime` with a fake installer, covering user-config
  precedence and preset path containment without Android.
- Add tests for the crash-consistency window and for registry quarantine recovery.

### M3 — Repository operations

- Add a small signing tool that produces `index.json` and `index.json.sig` from a
  directory of plugin ZIPs; document key generation and offline storage.
- Decide whether the index gets `generatedAt` / expiry / a monotonic repository
  version. If yes, add replay protection and tests.
- Add key rotation: accept a signature from either the current or a signed next key,
  then pin the next key in the following release.
- Add a dependency-first install flow. The Update button already exists for newer
  signed catalog entries. Keep auto-install off unless the user opts in.

### M4 — Crash-safe lifecycle

- Add a small journal or two-phase marker for update commits, so a process kill
  between directory rename and registry save restores the previous version on next
  start.
- Clean `.old-*` and orphan version directories at startup, not only after the next
  install.
- Add a "repair" action in the manager for invalid entries and quarantine files.

### M5 — More declarative surface (only with a new API version)

Candidates, each with a security review and a host enforcement point:

- Font plugin (`terminal.font`): a font file would require asset storage and careful
  size/format validation; do not add it until the file path and rendering behavior are
  designed.
- Cursor style / bell / scrollback settings: pure values, low risk, but must not
  override user properties.
- Session environment presets: higher risk because environment variables affect
  executed commands; keep presets command-free or require a separate explicit
  capability and user confirmation.
- Theme variants (day/night): host currently applies one map; variants add selection
  logic and conflict rules.

Do not add executable extensions, script hooks, or plugin-provided Intents without a
new threat model and a sandbox that does not exist in Android for same-UID code.

### M6 — Product polish

- Complete device-level TalkBack and large-font verification for the Material manager.
- Add localized strings for every new label.
- Document plugin authoring with a small validator tool and sample repository.
- Consider an upstreamable split: keep the runtime in `:app` unless upstream wants a
  library; `termux-shared` is published for companion apps and should not absorb a
  private plugin runtime.

## 4. Explicit non-goals

- Loading plugin code, scripts, JARs, DEX, native libraries, or remote APKs.
- Writing plugin themes or keys into `~/.termux` to make them visible to
  Termux:Styling; that would break user-configuration precedence.
- Automatic installation from an untrusted source without signature verification.
- Changing `com.termux`, the prefix, the bootstrap, or the signing key to make
  coexistence with official Termux possible.
- Claiming that a checksum or HTTPS alone proves publisher authenticity.

## 5. Acceptance criteria for leaving MVP

- A reproducible release APK is built from a clean checkout on a supported host.
- The device checklist passes on at least API 21, 29, and 34.
- Plugin tests and the existing test suite are green in CI, or every remaining failure
  has a documented baseline and owner.
- Repository signing, key rotation, and replay behavior are specified and tested.
- A user can install, enable, disable, update, and uninstall without any write outside
  app-private plugin storage.
- The threat model is reviewed after device testing, especially around crash
  consistency and the same-UID boundary.
