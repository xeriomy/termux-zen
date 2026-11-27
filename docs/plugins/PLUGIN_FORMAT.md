# Plugin Format — API v2

Status: format implemented by `ZenApi.API_VERSION = 2`. Audience: plugin authors and
repository maintainers. Supersedes the v1 manifest+payload format.

A plugin is **one JSON document** (`plugin.json`). For distribution it is shipped as a ZIP
containing that document and optionally a `LICENSE`. The host reads the ZIP in memory,
validates the document, and stores only the document text in app-private storage. Nothing
in an archive is extracted to disk or executed. The in-app editor produces the same format.

## 1. Artifact layout

```
plugin.zip
├── plugin.json      required, UTF-8 JSON, at most 256 KiB
└── LICENSE          optional, ignored by the app
```

Rules enforced by `PluginArchive`:

- Artifact at most 512 KiB; must start with the ZIP local-header magic.
- Only the two names above; directory entries, duplicates, absolute paths, `..`,
  backslashes and unknown files are rejected.
- Total expansion at most 256 KiB. The archive is never written to the filesystem.

Bundled plugins in `app/src/main/assets/plugins/` follow the same format and are trusted
because they are covered by the APK signature.

## 2. `plugin.json`

Unknown fields are rejected at every level; required fields must have the exact JSON type;
numbers must be integers.

```json
{
  "schemaVersion": 2,
  "id": "com.example.midnight",
  "name": "Midnight",
  "version": "1.0.0",
  "apiVersion": 2,
  "minCoreVersion": "0.118.0",
  "author": "Example",
  "description": "A dark blue palette with a configurable accent.",
  "settings": [
    { "id": "accent", "label": "Accent color", "type": "color", "default": "#6ba8ff" }
  ],
  "contributions": [
    {
      "type": "theme",
      "colors": {
        "foreground": "#e6e6e6",
        "background": "#101820",
        "cursor": "${settings.accent}"
      }
    }
  ],
  "dependencies": ["com.example.base@1.0.0"]
}
```

| Field | Required | Rules |
| --- | --- | --- |
| `schemaVersion` | yes | exactly `2` |
| `id` | yes | lowercase reverse-DNS `^[a-z][a-z0-9]*(\.[a-z][a-z0-9_-]*)+$`, 3–128 chars |
| `name` | yes | 1–64 chars, no control characters |
| `version` | yes | strict Semver 2.0.0, ≤64 chars |
| `apiVersion` | yes | exactly `2` |
| `minCoreVersion` | no | semver, default `0.0.0`; hard floor against the app version |
| `author` | no | ≤128 chars, no control characters |
| `description` | no | ≤512 chars, no control characters |
| `settings` | no | ≤32 entries, unique ids (see §3) |
| `contributions` | yes | 1–16 entries (see §4); ≤1 theme, ≤1 extra_keys, ≤1 terminal_settings, ≤16 presets |
| `dependencies` | no | ≤16 entries `id@minVersion`; no self, no duplicates |

Dependencies must already be installed (and enabled when the plugin will be enabled);
cycles are rejected at install and enable. There is no auto-install.

## 3. Settings schema

Each setting: `{ id, label, description?, type, default, … }` with
`id` matching `^[a-z][a-z0-9_]{0,31}$`, `label` 1–64 chars, `description` ≤256 chars.

| `type` | Extra fields | `default` rules |
| --- | --- | --- |
| `toggle` | — | `"true"` or `"false"` |
| `number` | `min?` (0), `max?` (1000000) | integer string within `[min, max]` |
| `text` | `maxLength?` (128, 1–512) | ≤maxLength, no control characters |
| `choice` | `options`: 2–16 `{value,label}`, unique values ≤64 chars | one of the option values |
| `color` | — | `#RRGGBB` |

The host renders these generically (switch, slider, text field, chips, color picker) and
stores user overrides in the registry record. Contribution string values may embed
`${settings.<id>}` tokens; every token must reference a declared id. At apply time tokens
are substituted and the resolved contribution is re-validated; a bad resolution makes the
plugin contribute nothing for that generation (fail-closed).

## 4. Contributions

### 4.1 `theme`

```json
{ "type": "theme", "colors": { "foreground": "#e6e6e6", "background": "#101820", "cursor": "#ffb000", "color0": "#101820", "color15": "#ffffff" } }
```

- `foreground` and `background` required; `cursor` and `color0`…`color15` optional.
- Values: `#RGB`, `#RRGGBB`, `#RRRGGBBB`, `#RRRRGGGGBBBB` (host `TerminalColors` forms) or
  settings tokens. Applied only when `~/.termux/colors.properties` is absent.

### 4.2 `extra_keys`

```json
{
  "type": "extra_keys",
  "style": "default",
  "keys": [
    [ {"key": "ESC"}, {"key": "TAB"}, {"key": "CTRL"}, {"key": "ALT"} ],
    [ {"key": "LEFT"}, {"key": "DOWN"}, {"key": "UP"}, {"key": "RIGHT"},
      {"macro": "echo $PWD", "display": "PWD", "sendOnPress": true} ]
  ]
}
```

- `keys`: 1–6 rows, 1–12 cells per row, ≤64 cells total. A cell is an object with exactly
  one of `key` (≤32 chars) or `macro` (≤512 chars), optional `display` (≤32 chars), and
  optional `popup` (one nested cell; no popup inside a popup). No control characters.
- `sendOnPress` (boolean, macro cells only; default `false`): when `true`, the host sends
  Enter after the macro's tokens so a typed command runs immediately; when `false` the
  macro only types text. Older documents ending a macro in the two literal characters
  `\n` are migrated at parse time to `sendOnPress: true` with that suffix removed. Macros
  newly authored in the in-app editor default to `true` and keep the author's flag when
  edited later.
- Macro grammar: tokens are space-separated. If the first token is a modifier (`CTRL`,
  `ALT`, `SHIFT`, `FN`) or a named key (for example `LEFT`), it is a key sequence and the
  spaces are separators only (`CTRL f d` sends Ctrl+f then `d`). Otherwise the macro is
  typed as text and spaces between text tokens are written literally (`echo hello world`
  types exactly that). Named keys embedded in a text macro still dispatch as key events.
- `style`: `default`, `arrows-only`, `arrows-all`, `all`, `none` (or a settings token).
- Applied only when the user has not set `extra-keys`; the plugin style only when the user
  has not set `extra-keys-style`. If the host `ExtraKeysInfo` parse fails at render time,
  the default layout is used.

### 4.3 `session_preset`

```json
{ "type": "session_preset", "name": "Projects", "workingDirectory": "projects" }
```

- `name` 1–48 chars; `workingDirectory` optional (default home), ≤256 chars, relative
  inside `$HOME`: no leading `/` or `~`, no `..`, no backslashes, no empty/`.` segments.
- At runtime the path is canonicalized, must stay under `$HOME` and exist as a directory;
  otherwise the preset is not listed. Selecting it creates a normal session after an
  explicit tap. No commands, scripts, or environment changes.

### 4.4 `terminal_settings`

```json
{ "type": "terminal_settings", "values": { "terminal-cursor-style": "bar", "terminal-cursor-blink-rate": "500", "extra-keys-text-all-caps": "${settings.all_caps}" } }
```

Whitelisted keys and value grammars (mirroring `TermuxPropertyConstants`):

| Key | Values |
| --- | --- |
| `bell-character` | `ignore` / `vibrate` / `beep` |
| `terminal-cursor-style` | `block` / `underline` / `bar` |
| `terminal-cursor-blink-rate` | `0` (off) or `100`–`2000` |
| `terminal-margin-horizontal` | `0`–`100` (dp) |
| `terminal-margin-vertical` | `0`–`100` (dp) |
| `terminal-transcript-rows` | `100`–`50000` |
| `extra-keys-text-all-caps` | `true` / `false` |

Applied only for keys the user has not set in `termux.properties`; the file is never
modified. Multiple enabled providers merge in plugin-id order (later wins).

## 5. Repository index

Base URL and pinned RSA public key are compiled in as `TERMUX_PLUGIN_REPOSITORY_URL` /
`TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY` (both empty in this build → catalog disabled).
The app downloads `<base>/index.json` (≤256 KiB) and `<base>/index.json.sig` (≤8 KiB,
standard Base64, whitespace ignored). TLS is required but is not publisher authenticity:
only a `SHA256withRSA` signature verifying against the pinned key (≥2048-bit RSA) is
accepted. Redirects are refused.

```json
{
  "schemaVersion": 2,
  "repository": "Example plugins",
  "plugins": [
    {
      "id": "com.example.midnight",
      "name": "Midnight",
      "version": "1.0.0",
      "description": "A dark blue palette.",
      "minCoreVersion": "0.118.0",
      "dependencies": [],
      "types": ["theme"],
      "url": "https://plugins.example/midnight-1.0.0.zip",
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "size": 1234
    }
  ]
}
```

≤500 entries; unknown fields rejected; ids/versions validated as in manifests; `url` must
be `https://`; `sha256` is 64 hex chars; `size` 1–524288. After download the app checks
size + SHA-256, validates the artifact, and requires the document's id/version to match
the signed entry exactly. `types` is display-only. Incompatible entries are shown but
cannot be installed.

## 6. Installed state

```
<getFilesDir()>/plugins/
├── registry.json
├── installed/<id>/<version>/plugin.json
└── .staging/
```

`registry.json`:

```json
{
  "schemaVersion": 2,
  "plugins": [
    {
      "id": "com.example.midnight",
      "version": "1.0.0",
      "enabled": true,
      "source": "local",
      "sha256": "…",
      "settings": { "accent": "#ff6b6b" }
    }
  ]
}
```

`source` is `bundled`, `repository`, `local` (created/duplicated in-app) or `import` (user
ZIP). Any installed plugin's contributions can be customized in place; the hub confirms
first for bundled/repository artifacts and saving re-labels the record as `local`. Plugin
identity, version and the settings schema are author-defined in `plugin.json` and cannot be
changed from the in-app editor (raw-JSON mode, metadata and settings are hidden for loaded
plugins). Contributions cannot be added or removed from a loaded draft either; only their
values are editable, along with setting values in the editor's generated form (written to
the registry, not to `plugin.json`). The on-disk `source` values still include `local` copies created by
earlier builds (`<id>.copy`, `<id>.copy2`, …), whose contributions can be customized while
their metadata and settings stay author-defined.
The SHA-256 is
rechecked on every load; modified files make the plugin invalid. Registry writes are
atomic; unreadable registries are quarantined, never silently overwritten.

## 7. Versioning and migration

- `apiVersion` is exact, not a range. Updates may not lower the version; equal version is a
  reinstall. Updates keep the enabled state and compatible setting overrides.
- v1 plugins (manifest.json + payload.json) are migrated once at startup: the single v1
  type becomes one contribution, v1 string key cells become `{ "key": … }` objects,
  enabled state and source survive. The old registry is kept as `registry.v1.bak`.

## 8. Author checklist

1. Unique lowercase reverse-DNS id, semver version, `apiVersion: 2`, `schemaVersion: 2`.
2. One to sixteen contributions; at most one each of theme/extra_keys/terminal_settings.
3. Declare settings for anything the user should tune; reference them with `${settings.id}`.
4. Validate locally: the in-app editor's Advanced JSON mode runs the host's exact parser.
5. Ship only `plugin.json` (+ optional `LICENSE`) in the ZIP.
6. Publishing: put the artifact hash and size in a signed index; TLS alone is not authenticity.
7. Test with no `colors.properties` and no explicit `extra-keys`/terminal properties if you
   want to see the contribution; user configuration intentionally wins.