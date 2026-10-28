# Declarative Plugins for Termux

This directory documents the plugin system added to this Termux app fork. Plugins are
**declarative data**, not executables: a theme, an extra-keys layout, or a session
preset. The app never downloads or runs Java/Kotlin bytecode, JARs, DEX, native code,
or scripts.

Status: API v1 MVP implemented in `com.termux.app.plugins`. The x86_64 CI workflow and
the emulator smoke job are green for this revision (run history in `TESTING.md` §9.3).
Device verification of the manual steps in `TESTING.md` §6.2 remains pending.

## Documents

| Document | Contents |
| --- | --- |
| [AUDIT.md](AUDIT.md) | Phase 0 repository audit: identity, prefix, hooks, existing tests, risks |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components, storage, lifecycle, host extension points, versioning |
| [PLUGIN_FORMAT.md](PLUGIN_FORMAT.md) | Manifest, payload, archive, signed repository index, installed state |
| [THREAT_MODEL.md](THREAT_MODEL.md) | Assets, trust boundaries, threats, mitigations, residual risks |
| [TESTING.md](TESTING.md) | Exact commands, baseline and plugin test results, build/install notes |
| [ROADMAP.md](ROADMAP.md) | MVP limitations, next milestones, acceptance criteria |

## Quick start (when a build is available)

1. Build and install the debug APK as described in [TESTING.md](TESTING.md) §6.
2. Open **Settings → Plugins**.
3. Open the **Bundled** tab and install **Solarized Dark**, **Paper Light**, or
   **Coding keys**. Each install is confirmed before it is added and enabled.
4. Open **Installed** to review its status, disable it, or uninstall it.
5. User configuration wins: a `~/.termux/colors.properties` disables plugin themes,
   and an explicit `extra-keys` or `extra-keys-style` in `termux.properties` overrides
   the plugin keys.

The bundled plugins are not installed automatically. This keeps uninstall permanent:
the app does not recreate them on the next launch.

## What is implemented

- API v1 types/capabilities:
  - `theme` / `terminal.theme`
  - `extra_keys` / `terminal.extra_keys`
  - `session_preset` / `sessions.preset`
- Strict manifest/payload validation; exactly one capability matching the type.
- Semver versions, `minCoreVersion`, dependencies, cycle detection, downgrade
  rejection, and exclusive theme/extra-keys providers.
- Bounded in-memory ZIP reading; only `manifest.json`, `payload.json`, optional
  `LICENSE`; no extraction.
- App-private storage under `getFilesDir()/plugins`; atomic registry writes; staged
  installs; rollback on normal errors; SHA-256 revalidation on every activation.
- Signed HTTPS repository index with a pinned RSA key; artifact size and hash checks;
  bundled assets trusted by the APK signature.
- Host-side capability enforcement and fail-closed runtime.
- Settings entry, Material plugin manager with Installed/Bundled/Repository tabs,
  search, loading/empty/error states, confirmations, and enable/disable/uninstall.
- Three useful bundled plugins: Solarized Dark, Paper Light, and Coding keys.
- Plugin unit tests cover the archive, manifest, payload, registry, installer,
  repository client, and signature verifier. Exact current counts are in
  [TESTING.md](TESTING.md).

## What is not implemented

- No repository URL or pinned key is configured in this build, so the Repository tab
  cannot install or update plugins in this build.
- No automatic dependency installation.
- No device-level verification of this revision yet. The aarch64 host cannot build the
  APK; the x86_64 workflow and emulator job cover this revision, but the manual
  checklist in `TESTING.md` §6.2 still needs a physical device.
- No revocation/expiry for the repository index.
- No crash journal; normal rollback only.
- No executable plugins, by design.

See [ROADMAP.md](ROADMAP.md) for the full list and next steps.

## Repository configuration

`app/build.gradle` contains:

```gradle
buildConfigField "String", "TERMUX_PLUGIN_REPOSITORY_URL", "\"\""
buildConfigField "String", "TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY", "\"\""
```

Set the URL to an HTTPS base containing `index.json` and `index.json.sig`, and the key
to a Base64 X.509 RSA public key of at least 2048 bits. Leaving either empty disables
the catalog; the bundled plugins remain available. The index format and signature
rules are in [PLUGIN_FORMAT.md](PLUGIN_FORMAT.md) §4.

## Non-negotiable constraints

- Keep `com.termux`, the existing prefix, and the existing signing/bootstrap setup.
- Do not use plugin data to execute code or commands.
- Do not write plugin output into `$HOME` or `.termux`.
- Do not let a plugin override explicit user configuration.
- Do not treat HTTPS or a checksum as publisher authenticity.
- Do not claim a device or release test you did not run.
