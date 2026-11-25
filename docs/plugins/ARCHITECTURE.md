# Plugin System — Architecture (API v2)

Status: implementation design for the v2 rewrite. Audience: maintainers and plugin authors.
Supersedes the v1 MVP architecture (one fixed type per plugin, no settings, XML manager).

## 1. Goals and non-goals

Goals:

- Keep the app fully functional with zero installed or enabled plugins.
- Let anyone **add, edit, remove** plugins: install from bundled/catalog/file, create and
  edit plugins in-app, duplicate any plugin into an editable copy, export/share, uninstall.
- Let every plugin declare **user settings** that the host renders and validates generically.
- Let one plugin combine **multiple typed contributions** (theme + keys + presets + terminal
  settings), instead of v1's single fixed type.
- Enable/disable with deterministic dependency cascades and exclusive-provider rules.
- Verify catalog metadata and artifact integrity before anything is committed.
- Never load downloaded bytecode, JARs, DEX files, native code, or scripts.
- Preserve user data: plugin operations never edit `$HOME`, `termux.properties`,
  `colors.properties`, `font.ttf`, or the bootstrap. User configuration always wins.

Non-goals for v2:

- Executable extensions of any kind (security boundary, see THREAT_MODEL.md).
- Native Android companion-app plugins (those remain separate APKs).
- Automatic dependency installation.
- Font plugins (binary payloads need a separate design).
- A hosted catalog service; the repository is a signed static index.

## 2. Module and package layout

Core and UI live in `:app`. The core is pure JVM (org.json + java.io + java.security +
Guava BaseEncoding) so it is unit-testable without Android; only `PluginManager` and
`PluginExporter` touch Android APIs. `termux-shared` gains one small, plugin-agnostic
addition: a property-overlay API used by the `terminal_settings` extension point
(`SharedProperties.setInternalProperty`, `TermuxSharedProperties.applyPropertyOverrides`).
Its existing `com.termux.shared.termux.plugins` package remains companion-app plumbing.

```
com.termux.app.plugins
├── ZenApi                 # API v2 constants, grammars, bounds, exclusive types
├── Semver                 # strict Semver 2.0.0 parse + precedence
├── PluginException        # single user-safe error type
├── PluginJson             # strict org.json helpers (types, bounds, unknown keys)
├── PluginModels           # immutable manifest/contribution/settings model
├── PluginParser           # strict parse + canonical serialization; terminal-settings schema
├── PluginSettings         # effective values, ${settings.id} resolution, re-validation
├── PluginArchive          # bounded in-memory ZIP read/write
├── PluginStore            # registry + installed documents, atomic writes, quarantine
├── PluginRuntime          # active contributions, fail-closed reload, generation counter
├── PluginInstaller        # install/update/uninstall/enable/disable/settings/duplicate
├── PluginRepository       # signed catalog client (HTTPS, pinned RSA key, no redirects)
├── PluginMigration        # best-effort v1 → v2 conversion
├── PluginManager          # app-scoped facade, single executor, main-thread results
└── PluginExporter         # shareable ZIP into cache (FileProvider)

com.termux.app.plugins.ui   # Kotlin + Jetpack Compose (Material 3)
├── PluginHubActivity      # single activity; replaces the v1 XML tab manager
├── HubController          # navigation + state + async operations
├── EditorDraft            # mutable editor model, validates via parser round-trip
├── Screens                # installed list, browse (bundled + catalog), detail + settings
├── EditorScreen           # metadata, contribution editors, settings designer, raw JSON
├── Components             # rows, chips, previews, settings form, HSV color picker
├── ZenTheme / ZenStrings  # dark terminal-matching theme; UI strings
```

Compose is pinned to BOM **2025.10.00** (ui 1.9.3 / material3 1.4.0): the newest line whose
UI still supports **minSdk 21**, preserving Termux's Android 5/6 support. Kotlin 2.4.21 with
the Kotlin-bundled Compose compiler plugin; activity-compose 1.10.1; coroutines 1.11.0.

## 3. Storage

```
<context.getFilesDir()>/plugins/
├── registry.json                 # schemaVersion 2 records, atomic writes
├── registry.v1.bak               # kept when a v1 registry was migrated
├── installed/<plugin-id>/<version>/plugin.json
└── .staging/                     # cleared at startup and around commits
```

- IDs and versions are validated against path-safe grammars before use as path segments;
  deletion additionally checks canonical-path containment.
- Registry records carry `enabled`, `source` (`bundled|repository|local|import`), the
  SHA-256 of the installed document, and the user's **settings overrides** (string-encoded).
- Writes are tmp-file + fsync + rename. An unreadable registry is quarantined as
  `registry.json.corrupt-<time>-<reason>` before continuing with empty state.
- No plugin operation writes to `$HOME`, `$PREFIX`, `.termux`, or shared preferences.

## 4. Lifecycle

Startup (`TermuxApplication.onCreate` → `PluginManager.init`): create directories, clear
staging, migrate a v1 registry if present (best-effort, per-plugin), reload the runtime,
clean orphaned version directories. No network at startup. The app continues even if all
plugin state is invalid.

Runtime reload (fail-closed, per plugin): hash check → strict parse → registry identity
match → `minCoreVersion` ≤ app version → dependencies installed/enabled/version →
settings resolution + contribution re-validation → cycle detection. A failing plugin
contributes nothing this generation and is reported `Invalid(reason)`; the failure is not
persisted as a disable. Exclusive types (`theme`, `extra_keys`): first valid provider in
id order wins; later providers are `Overridden(byId)`. `terminal_settings` merge in id
order (later wins). Presets are canonicalized under `$HOME` and listed only when the
directory exists. Each reload bumps a generation counter; `TermuxActivity.onResume`
re-applies styling when it changed while another activity was on top.

Install/update: validate document (and signed-catalog identity when applicable) → check
downgrade/dependencies/cycles → exclusive swap when enabling (which may never disable the
candidate's own dependency) → stage + commit directory → registry save (rollback the
directory on failure) → delete old version → reload. Updates preserve the enabled state
and keep setting overrides whose ids are still declared. Fresh bundled installs enable;
repository/import installs disable; local saves preserve the record's state.

Enable/disable: enable re-validates everything and swaps exclusive providers; disable
cascades to enabled dependants transitively. Both are idempotent.

Uninstall: cascade-disable dependants, remove the record, persist, then delete only
`installed/<id>`. Files that cannot be deleted are reported; the plugin is already
unregistered and can never load.

## 5. Extension points

| Contribution | Host hook | Precedence |
| --- | --- | --- |
| `theme` | `checkForFontAndColors` overlays validated colors into in-memory `Properties` | user `colors.properties` wins |
| `extra_keys` | `TermuxTerminalExtraKeys.setExtraKeys` uses plugin JSON/style | user `extra-keys` / `extra-keys-style` win |
| `session_preset` | terminal context menu → `addNewSession(false, name, dir)` | explicit tap required; missing dir → no entry |
| `terminal_settings` | `TermuxActivity.reloadProperties` → `applyPropertyOverrides` | any key the user set in `termux.properties` wins |

`terminal_settings` whitelist (values validated at parse and after resolution):
`bell-character` (ignore/vibrate/beep), `terminal-cursor-style` (block/underline/bar),
`terminal-cursor-blink-rate` (0 or 100–2000), `terminal-margin-horizontal` (0–100),
`terminal-margin-vertical` (0–100), `terminal-transcript-rows` (100–50000),
`extra-keys-text-all-caps` (true/false). The overlay only touches the in-memory internal
value map; the raw user-file cache is unchanged, so precedence checks stay honest.
The overlay applies on interactive-terminal property reloads; service-only paths
(e.g. `RUN_COMMAND`) keep file/default values.

Settings tokens: any contribution string value may embed `${settings.<id>}`. Tokens must
reference declared settings (checked at parse); values are substituted at runtime reload
and the resolved contribution is re-validated. A bad resolution fails the whole plugin
closed for that generation.

## 6. UI

`PluginHubActivity` (Compose, Material 3, dark terminal-matching theme):

- **Installed**: searchable list with enable switches, status chips (Active / Disabled /
  Invalid + reason / Overridden), type chips; FAB creates a plugin; top-bar actions import
  a ZIP (SAF) and open Get plugins.
- **Get plugins**: bundled section (APK-signed artifacts) and repository section
  (not-configured / loading / error+retry / signed entries with Install/Update).
- **Detail**: header + enable switch (confirmation when a cascade or exclusive swap would
  occur), contribution previews (palette, key rows, presets, settings values), the
  plugin's **generated settings form**, dependencies with live state, metadata, and
  Edit (in place for any source, confirmed for bundled/repository) / Duplicate & edit /
  Export / Uninstall (confirmed).
- **Editor**: metadata form; per-type contribution editors (18-color theme editor with HSV
  picker and live preview, extra-keys row/cell builder with click-to-edit keys and a
  separate delete button, preset fields, terminal-settings key/value editor with editable
  values and `${settings.id}` tokens); a settings-schema designer (toggle/number/text/
  choice/color); and an advanced raw-JSON mode with validation. Saving round-trips through
  the strict parser, so the editor cannot produce a document the host would reject.

All operations run on the manager's single background executor; results post to the main
thread; a runtime change listener refreshes the UI. Snackbars report success/failure with
the core's user-safe error messages.

## 7. Compatibility and versioning

- `apiVersion` and `schemaVersion` must be exactly `2`; anything else is rejected.
- `minCoreVersion` is a hard floor against `BuildConfig.VERSION_NAME` (strict semver).
- v1 installed plugins are migrated once at startup (type → single contribution, v1 string
  key cells → v2 cell objects); enabled state and source survive; unconvertible plugins are
  skipped and cleaned as orphans. The v1 registry is preserved as `registry.v1.bak`.
- Adding a contribution type or changing a payload schema incompatibly requires a new API
  version and host code. No reflection, no dynamic dispatch, no plugin code.

## 8. Relationship to companion apps

Termux:API/Boot/Float/Styling/Tasker/Widget remain separate APKs with `sharedUserId
com.termux`; this system never invokes `RUN_COMMAND`, `TermuxService`, or a plugin APK.
Plugin contributions are not visible to companion apps because no files are written —
a documented limitation, unchanged from v1.