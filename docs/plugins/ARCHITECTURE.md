# Plugin System — Architecture

Status: implementation design for the initial release (plugin API v1).
Audience: Termux app maintainers and plugin authors.

## 1. Goals and non-goals

Goals:

- Keep the app fully functional with zero installed or enabled plugins.
- Install, enable, disable, update, and uninstall declarative plugins.
- Give every plugin a stable ID, version, API version, minimum core version, type, declared
  capabilities, and dependencies.
- Apply contributions through explicit, reversible host extension points.
- Verify catalog metadata and artifact integrity before anything is committed.
- Never load downloaded bytecode, JARs, DEX files, native code, or scripts.
- Preserve user data: plugin operations never edit `$HOME`, `termux.properties`,
  `colors.properties`, `font.ttf`, or the bootstrap.

Non-goals for v1:

- Native Android companion-app plugins.
- Arbitrary executable extensions or shell-script plugins.
- Automatic installation of dependencies.
- Multiple simultaneous themes or extra-key layouts.
- Cross-app styling visibility (Termux:Styling and companion apps do not see plugin themes).
- A hosted catalog service; the repository is a signed static index.

## 2. Module and package layout

All new code lives in the `:app` module under `com.termux.app.plugins`. `termux-shared` is not
changed: its `com.termux.shared.termux.plugins` package already means companion-app /
`RUN_COMMAND` plumbing and must not be confused with declarative plugins.

```
com.termux.app.plugins
├── PluginApi                    # API version, types, capabilities, semver
├── PluginJson                   # strict JSON helpers
├── PluginManifest               # parsed, validated immutable manifest
├── PluginPayload                # parsed, validated type-specific payload
├── PluginArchive                # bounded in-memory ZIP reader
├── PluginSignatureVerifier      # SHA256withRSA verification, Base64, key size
├── PluginRepositoryClient       # HTTPS index/artifact fetch, signature check
├── PluginRegistry               # installed records, atomic persistence
├── PluginRuntime                # active contributions, fail-closed reload
├── PluginPermissionManager      # capability/type enforcement and labels
├── PluginInstaller              # validation, staging, commit, rollback, uninstall
├── PluginManager                # app-scoped facade and initialization
├── PluginManagerActivity        # Material tabs, cards, search, and confirmations
├── PluginCard                   # immutable card model for installed/available plugins
└── PluginCardAdapter            # RecyclerView binding and action callbacks
```

There is no separate `Semver`, catalog model, dependency resolver, or package class:
those responsibilities fit in `PluginApi`, `PluginRepositoryClient`, and `PluginInstaller`.
The UI keeps its display model separate from persistence and validation. See
`ROADMAP.md` before splitting it further.

The UI is a new activity because the specification requires catalog and per-plugin
operation views, not a single settings switch. The settings root gains one entry that
opens it.

## 3. Storage

Plugin state is app-private:

```
<context.getFilesDir()>/plugins/
├── registry.json                 # installed records, atomic writes
├── installed/<plugin-id>/<version>/
│   ├── manifest.json             # validated manifest exactly as installed
│   └── payload.json              # validated type-specific payload
└── .staging/                     # incomplete downloads/commits, cleared at startup
```

- The directory is derived from `getFilesDir()`, never a hard-coded path.
- Plugin IDs and versions are validated against path-safe grammars before being used as path
  segments, so neither can contain `/`, `..`, or a platform separator. Deletion additionally
  resolves canonical paths and refuses to leave the plugin storage root.
- No plugin operation writes to `$HOME`, `$PREFIX`, `.termux`, or shared preferences.
  Therefore no user configuration needs a backup: nothing is modified.
- An unreadable `registry.json` is renamed to `registry.json.corrupt-<time>` before the app
  continues with empty state, so the next save cannot destroy the evidence.
- Uninstall deletes only `installed/<plugin-id>` and the registry record. Orphaned version
  directories from an interrupted update may remain on disk but are never loaded unless the
  registry points at them; they are harmless and are removed by the next install cleanup.
  A process kill between the directory rename and the registry commit can leave a
  `<version>.old-<time>` directory; the plugin then fails hash validation and contributes
  nothing.

## 4. Lifecycle

### 4.1 Application startup

`TermuxApplication.onCreate` calls `PluginManager.init(applicationContext)` after shared
properties and the shell manager are initialized, before the theme is applied. Initialization:

1. Creates the storage directories.
2. Loads `registry.json`. A corrupt file is renamed to `registry.json.corrupt-<time>` and
   an empty in-memory registry is used; the app never crashes and never silently overwrites
   the old file.
3. Clears `.staging`.
4. Reloads the runtime: for each enabled record, re-reads and validates its files against the
   registry hashes, checks API/core compatibility and dependencies, then enforces the
   capability/type mapping. A plugin that fails any check is skipped for this generation and
   contributes nothing. The failure is not persisted as a disable, so a later repair can
   activate it again.
5. The app continues even if all plugin state is invalid.

No network access and no catalog refresh happen at startup. The repository catalog is
fetched only when the user opens the **Repository** tab (and only if a URL and key are
configured). Bundled plugins are listed in the **Bundled** tab and installed only after
an explicit per-plugin confirmation.

### 4.2 Enable

1. Read the installed package from disk and validate it again (defends against on-disk
   tampering and core upgrades).
2. Check API/core compatibility.
3. Check every declared dependency is installed, enabled, and meets its minimum version.
4. Reject a dependency cycle and a dependency that would be replaced as the active
   exclusive provider (`theme`, `extra_keys`).
5. Enforce the capability/type mapping.
6. Set `enabled=true` and disable other enabled providers of the same exclusive type,
   including their dependants.
7. Persist the registry. If persistence fails, reload the old registry; the runtime is
   only refreshed after a successful action.

Enable is idempotent: enabling an already enabled plugin repeats validation and leaves one
record. Disable is idempotent for an already disabled plugin.

### 4.3 Disable

1. Set `enabled=false`.
2. Disable every enabled plugin that depends on it, transitively, so no record claims to
   be active while its dependency is gone.
3. Persist the registry. If persistence fails, reload the old registry.

There is no refusal path: disabling a dependency is allowed and disables its dependants.
The UI shows the resulting states.

### 4.4 Install

1. Validate the source (`bundled` or `repository`), optional expected size and SHA-256, the
   archive, manifest, payload, and API/core compatibility. Repository installs also pass the
   signed catalog entry's ID, version, and type; the artifact manifest must match them exactly,
   so a signed catalog can never install a different plugin than the one the user confirmed.
2. Reject a version lower than the installed version, a missing dependency, a dependency
   cycle, or, when the plugin will be enabled, a dependency that is disabled or an
   exclusive conflict. An update keeps the installed enabled state, so an enabled plugin
   cannot be updated into a state whose dependencies are disabled.
3. Write only the validated JSON documents to a temporary directory under
   `plugins/.staging`, fsync them, then rename the directory to
   `installed/<id>/<version>`.
4. Add a registry record and persist. If persistence fails, delete the new directory and
   restore any directory moved aside; the old registry is reloaded.
5. The UI installs bundled plugins with `enabled=true` after a confirmation that explicitly
   says the plugin will become active. Repository plugins are installed with `enabled=false`;
   the user enables them from the Installed tab. The installer API supports both behaviors.

### 4.5 Update

A package with the same ID replaces the installed version. The new version must not be lower;
an equal version is a reinstall.

1. Validate the new package, dependencies, dependency cycles, and exclusive conflicts.
2. Write the new version directory atomically. If the target version directory already
   exists, rename it to `<version>.old-<time>` first.
3. Update the registry record, preserving the existing enabled state.
4. If persistence fails, delete the new directory and restore the old directory; the old
   registry is reloaded.
5. Delete the old version directory and any other version left in the plugin directory only
   after the registry commit succeeds.

A process kill between steps 2 and 3 leaves the registry pointing at the old hash while the
directory contains the new files. The plugin fails validation and is skipped; the old copy
remains under `<version>.old-<time>` for manual recovery. This window is documented in the
threat model.

### 4.6 Uninstall

1. Disable the plugin and any enabled dependants, then remove the registry record and persist.
   If persistence fails, reload the old registry and keep the files.
2. Delete `installed/<id>` recursively, with canonical-path containment. Failure to delete is
   reported; the plugin is already unregistered, so it cannot be loaded.
3. No user file, setting, or other plugin directory is touched.

## 5. Extension points

All contributions are declarative and pull-based. There is no event bus and no plugin-provided
callback code. Pulling keeps ordering and disposal deterministic.

| Type | Capability | Contribution | Host hook |
| --- | --- | --- | --- |
| `theme` | `terminal.theme` | Color map (`foreground`, `background`, `cursor`, `color0`..`color15`) | `checkForFontAndColors` overlays the map into the in-memory `Properties` before `TerminalColorScheme.updateWith` |
| `extra_keys` | `terminal.extra_keys` | JSON array of rows of key names | `TermuxTerminalExtraKeys` uses it only when the user has not set `extra-keys` |
| `session_preset` | `sessions.preset` | Session name and a working directory under `$HOME` | New entry in the terminal pop-up menu; opening requires an explicit user tap |

### 5.1 Theme precedence and reversibility

- If `~/.termux/colors.properties` exists, the user's file wins and the plugin theme is inert.
  The manager still shows the plugin as enabled; it does not claim the theme is active.
- If the file is absent, an enabled theme plugin's validated colors are applied to an
  in-memory `Properties` object. No file is created or modified.
- Disabling the plugin makes the next styling load start from an empty property set, restoring
  the built-in defaults.
- All colors are validated before `TerminalColorScheme.updateWith` is called, so a malformed
  plugin cannot leave a partially applied palette.

### 5.2 Extra keys precedence and reversibility

- An explicit non-empty `extra-keys` value in `termux.properties` always wins.
- An explicit `extra-keys-style` value also wins; the plugin style is used only when the
  user did not set one.
- Otherwise, an enabled `extra_keys` plugin supplies the JSON used to build `ExtraKeysInfo`.
- Otherwise, the existing default two-row layout is used.
- Enabling or disabling recreates the activity (or reloads it on resume) so the toolbar is
  rebuilt; the underlying user property is never changed.

### 5.3 Session presets

- A preset only names a session and selects an existing directory under `$HOME`. It does not
  contain a command, script, or environment mutation.
- The terminal context menu lists enabled presets. Selecting one creates a normal session in
  that directory through the existing `TermuxTerminalSessionActivityClient.addNewSession`
  path. Nothing runs merely because a plugin was installed.
- A preset whose directory does not exist is not listed and contributes nothing. If the
  directory disappears after the runtime reload, selecting the entry shows an error and
  creates no session.

## 6. UI behavior

Settings → Plugins opens `PluginManagerActivity`, a Material screen with a summary and
three searchable tabs:

- **Installed**: name, version, ID, type, enabled/disabled/invalid/overridden status,
  permissions, dependencies, description, and preset directory. Actions are Enable,
  Disable, Details, and Uninstall.
- **Bundled**: the three plugins shipped in the APK: Solarized Dark, Paper Light, and
  Coding keys. Each card shows its type, description, permission, and dependencies;
  Install is available only when the plugin is not already installed. Confirmation states
  that the plugin becomes active after installation and that another theme or extra-keys
  provider will be disabled.
- **Repository**: when a repository is configured, signed entries with name, version, type,
  compatibility, permissions, dependencies, and Install or Update. The catalog is fetched
  only when this tab is opened; Retry and Refresh recover from a failed fetch or load a newer
  signed index.

The screen provides explicit loading, empty, search-empty, and error states. Cards use
48 dp minimum action targets and descriptive labels for assistive technology.

Confirmations:

- Enable shows declared permissions and dependencies.
- Install shows version, type, permissions, artifact source, the authenticated artifact
  size, whether the plugin becomes active immediately, and states that explicit user
  settings continue to win.
- Update shows the installed and available versions, the artifact source, and the
  authenticated artifact size.
- Uninstall explains that only plugin files and registrations are removed; Termux settings
  and home files are not touched.
- Disable is reversible and is confirmed before any dependent plugins are disabled.

Registry, installed-entry validation, and catalog work run on a single background
executor. Results are applied to the UI thread. `onResume` refreshes installed state and
retries a failed catalog only when its tab is visible.

## 7. Compatibility and versioning

- `PluginApi.VERSION = 1`. A package with any other `apiVersion` is rejected.
- `minCoreVersion` is compared to `BuildConfig.VERSION_NAME` using strict semver. A plugin that
  requires a newer core is rejected, not best-effort loaded.
- Adding a new plugin type or capability, or changing a payload schema incompatibly, requires a
  new API version and corresponding host code. There is no reflection or dynamic dispatch.
- The app version and `com.termux` identity are unchanged.

## 8. Relationship to companion apps

- "Plugins" in this document are local, declarative extension packages.
- Termux:API, Termux:Boot, Termux:Float, Termux:Styling, Termux:Tasker, and Termux:Widget
  remain separate APKs with `sharedUserId com.termux`; they are not managed by this system.
- The new system never invokes `RUN_COMMAND`, `TermuxService`, or a plugin APK.
- Plugin themes are not visible to Termux:Styling or other apps because no files are written.
  This is a documented limitation, not an accidental side effect.

## 9. Why these boundaries

- App module: the runtime needs app resources, terminal classes, and the settings activity;
  `termux-shared` is a published library for companion apps and should not grow a private
  plugin runtime.
- App-private storage: plugin integrity checks are meaningful only if the app owns the
  installed copy. A user-writable `~/.termux/plugins` directory would let any same-UID process
  replace an "installed" package after validation.
- Declarative data: the security model depends on never executing plugin-supplied code.
- One active provider per exclusive contribution: prevents ambiguous theme/key precedence and
  makes disable/recovery deterministic.
- User configuration wins: plugin operations remain reversible and never silently replace an
  explicit user choice.
