# Declarative Plugins for Termux Zen

This directory documents the plugin system added to this Termux app fork. Plugins are
**declarative data**, not executables: themes, extra-keys layouts, session presets and
whitelisted terminal settings, optionally with their own user-configurable settings. The
app never downloads or runs Java/Kotlin bytecode, JARs, DEX, native code, or scripts.

Status: **API v2** implemented in `com.termux.app.plugins` (Kotlin core) and
`com.termux.app.plugins.ui` (Jetpack Compose hub + editor). The v2 rewrite replaces the
v1 MVP (one fixed type per plugin, no settings, XML tab manager). Local verification:
85 pure-JVM core tests green and the full Kotlin/Compose sources compile-checked on the
aarch64 host; x86_64 CI results are recorded in `TESTING.md`. Device verification of the
manual checklist remains pending.

## Documents

| Document | Contents |
| --- | --- |
| [AUDIT.md](AUDIT.md) | Phase 0 repository audit of the v1 baseline (historical) |
| [ARCHITECTURE.md](ARCHITECTURE.md) | v2 components, storage, lifecycle, extension points, versioning |
| [PLUGIN_FORMAT.md](PLUGIN_FORMAT.md) | plugin.json, settings schema, contributions, signed index, installed state |
| [THREAT_MODEL.md](THREAT_MODEL.md) | Assets, trust boundaries, threats, mitigations, residual risks |
| [TESTING.md](TESTING.md) | Exact commands, local v2 results, CI history, device checklist |
| [ROADMAP.md](ROADMAP.md) | v2 limitations, next milestones, acceptance criteria |

## Quick start

1. Build and install the debug APK (CI artifact or an x86_64 host; see TESTING.md).
2. Open **Settings → Plugins**.
3. **Get plugins** → install **Solarized Dark**, **Paper Light** or **Coding Keys**
   (confirmed, then active). Or tap **+** to create your own plugin: pick a contribution
   type, edit colors/keys/presets/terminal settings, declare user settings, save.
4. Tap a plugin for its detail screen: enable/disable, **generated settings form**,
   contribution previews, dependencies, Edit (customize the installed plugin in place;
   confirmed for bundled/repository plugins) / Duplicate & edit / Export / Uninstall.
5. User configuration wins: `~/.termux/colors.properties` disables plugin themes, an
   explicit `extra-keys` overrides plugin keys, and any `termux.properties` key you set
   overrides the plugin's terminal settings.

Bundled plugins are not installed automatically, so uninstall stays permanent.

## What is implemented (v2)

- Open contribution model: 1–16 contributions per plugin; types `theme`, `extra_keys`,
  `session_preset` (many), `terminal_settings` (whitelisted cursor/bell/margins/scrollback/
  all-caps). At most one theme/extra_keys/terminal_settings per plugin.
- Per-plugin settings schema (toggle/number/text/choice/color) rendered generically;
  `${settings.id}` tokens in contribution values with post-resolution re-validation.
- In-app editor: visual theme editor with HSV color picker + live preview, extra-keys
  row/cell builder with popups, preset fields, terminal-settings picker, settings designer,
  and an advanced raw-JSON mode validated by the host parser.
- Duplicate any plugin into an editable local copy; export/share ZIPs; import ZIPs (SAF).
- Strict validation everywhere; bounded in-memory ZIP reading; app-private storage with
  SHA-256 rechecks, atomic registry writes, quarantine, staged commits and rollback.
- Fail-closed runtime; dependency floors, cycle detection, cascading disable/uninstall;
  exclusive theme/extra-keys providers; generation counter for cross-activity refresh.
- Signed HTTPS catalog with pinned RSA key (≥2048-bit), artifact size/hash/identity checks;
  bundled assets trusted via the APK signature.
- Compose hub (Material 3, dark): searchable installed list, browse, detail + settings,
  editor; confirmation gates for cascades, exclusive swaps and uninstall.
- v1 → v2 migration at startup (best-effort, registry backup kept).
- 85 pure-JVM core tests + existing app/emulator suites; x86_64 CI with APK, lint and an
  emulator smoke job covering the new hub.

## What is not implemented

- No repository URL/key configured in this build (catalog path inert).
- No automatic dependency installation; no font/binary plugins; no executable plugins
  (by design).
- No revocation/expiry/replay protection for the catalog; no crash journal.
- No physical-device verification yet; accessibility audit pending.

See [ROADMAP.md](ROADMAP.md) for the full list and next steps.

## Repository configuration

`app/build.gradle` contains:

```gradle
buildConfigField "String", "TERMUX_PLUGIN_REPOSITORY_URL", "\"\""
buildConfigField "String", "TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY", "\"\""
```

Set the URL to an HTTPS base containing `index.json` and `index.json.sig`, and the key to a
Base64 X.509 RSA public key of at least 2048 bits. Leaving either empty disables the
catalog; bundled plugins and local creation remain available. Format and signature rules:
[PLUGIN_FORMAT.md](PLUGIN_FORMAT.md) §5.

## Non-negotiable constraints

- Keep `com.termux`, the existing prefix, and the existing signing/bootstrap setup.
- Do not use plugin data to execute code or commands.
- Do not write plugin output into `$HOME` or `.termux`.
- Do not let a plugin override explicit user configuration.
- Do not treat HTTPS or a checksum as publisher authenticity.
- Do not claim a device or release test you did not run.