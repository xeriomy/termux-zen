# Plugin System — Roadmap and Limitations (API v2)

Status: after the v2 rewrite (open contribution model, per-plugin settings, in-app editor,
Compose hub). Audience: maintainers.

## 1. What ships in v2

- **Open contribution model**: one plugin combines 1–16 typed contributions — `theme`,
  `extra_keys`, `session_preset` (many per plugin), and the new `terminal_settings`
  (whitelisted cursor/bell/margin/scrollback/all-caps values).
- **Per-plugin settings**: declarative schema (toggle/number/text/choice/color), generic
  host-rendered settings UI, `${settings.id}` tokens in contribution values with
  post-resolution re-validation.
- **In-app authoring**: create plugins with visual editors (HSV color picker + live palette
  preview, key-row builder with popups, preset fields, terminal-settings picker, settings
  designer) or raw JSON with the host parser as validator; edit local/imported plugins;
  duplicate any plugin into an editable copy; export/share ZIPs; import ZIPs via SAF.
- **Compose hub** (Material 3, dark): searchable installed list with switches and status
  chips, Get plugins (bundled + signed catalog), detail screen with previews and generated
  settings, confirmation gates for cascades/exclusive swaps/uninstall.
- **Kept from v1**: strict validation, bounded in-memory ZIP reading, app-private storage
  with hashes and atomic commits, fail-closed runtime, dependency cascades and cycle
  detection, exclusive theme/extra-keys providers, user-configuration precedence, signed
  static catalog with pinned RSA key, no code execution, no writes to user data.
- **v1 → v2 migration** at startup (best-effort, registry backup kept).
- Kotlin 2.4.21 + Compose BOM 2025.10.00 chosen to keep **minSdk 21** (Android 5/6).
- 82 pure-JVM core tests (parser, settings, archive, store, runtime, installer, migration,
  repository signatures, bundled artifacts) + the existing app/emulator suites; x86_64 CI
  with APK, lint and an emulator smoke job covering the new hub.

## 2. Deliberate limitations

1. **No executable plugins** — security boundary, not a missing feature.
2. **No automatic dependency installation**; the UI shows dependency state.
3. **One active theme and one active extra-keys provider**; presets and terminal settings
   can come from multiple plugins (settings merge in id order, later wins).
4. **No repository configured in this build** (`TERMUX_PLUGIN_REPOSITORY_URL`/`_KEY` empty);
   bundled plugins and user creations are the installable content.
5. **App-private state only**: contributions are invisible to Termux:Styling/companions.
6. **`terminal_settings` applies on interactive-terminal reloads**; service-only paths
   (`RUN_COMMAND`) keep file/default values.
7. **No physical-device verification yet**; CI emulator smoke covers install → hub →
   bundled install → generated settings → restart persistence. Manual checklist in
   TESTING.md remains open (TalkBack, large font, Android 7/10/14 matrix).
8. **Crash recovery is best-effort** around the update commit window (fail closed +
   orphan cleanup, no journal yet).
9. **No catalog revocation/expiry/replay protection**; key rotation needs an app release.
10. **No same-UID isolation** from companion apps (hashes detect tampering, fail closed).
11. **Editor scope**: dependencies are shown but not editable in the form (raw JSON mode
    can change them); no icon/binary assets; no plugin-to-plugin settings references.
12. **UI strings are English-only**, centralized in `ZenStrings` (upstream is English-only).

## 3. Next milestones

### M1 — Device proof
- Run the manual checklist (TESTING.md) on API 21/29/34 devices: theme/keys/preset/
  terminal-settings application and precedence, settings changes live-reloading, editor
  create/edit/duplicate/export/import round-trip, TalkBack and large font, process death.
- Record APK hash + CI run ids in TESTING.md.

### M2 — Repository operations
- Signing tool for `index.json`/`index.json.sig` from a directory of ZIPs; key-generation
  docs; sample repository.
- Expiry/monotonic index version (replay protection), key rotation via signed next-key.
- Dependency-first install flow (opt-in auto-install only with explicit user consent).

### M3 — Crash-safe lifecycle
- Journal/two-phase marker for update commits; restore previous version after a kill.
- Repair action for invalid entries and quarantine files in the hub.

### M4 — More declarative surface (new API version each)
- Font plugins (binary asset storage + format validation design first).
- Theme day/night variants; per-session preset environments (needs a new capability and
  explicit confirmation; environment values affect executed commands).
- Plugin icons (bounded raster assets) and richer catalog metadata.

### M5 — Product polish
- Localize `ZenStrings`; accessibility audit; onboarding for the editor.
- Validator CLI for authors (reuse the core parser on the JVM).
- Consider upstreamable split of the core if upstream wants a library.

## 4. Explicit non-goals

- Loading plugin code, scripts, JARs, DEX, native libraries, or remote APKs.
- Writing plugin values into `~/.termux` (breaks user precedence and companion visibility
  is a non-goal).
- Installing from untrusted sources without signature verification.
- Changing `com.termux` identity, prefix, bootstrap, or signing configuration.

## 5. Acceptance criteria for leaving v2 MVP

- Reproducible release APK from a clean checkout on a supported host.
- Device checklist green on at least API 21, 29, 34.
- CI green: full app suite + emulator suite + lint + emulator smoke, with recorded hashes.
- A user can install, create, edit, configure, enable/disable, update, export, import and
  uninstall without any write outside app-private plugin storage.
- Threat model re-reviewed after device testing (crash window, same-UID boundary, editor).