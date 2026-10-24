# Plugin Format — API v1

Status: format implemented by `PluginApi.VERSION = 1`.
Audience: plugin authors and repository maintainers.

A plugin is one ZIP artifact containing two JSON documents. The host reads the ZIP in
memory, validates both documents, and writes only those two documents to app-private
storage. Nothing in the archive is extracted or executed.

## 1. Artifact layout

```
plugin.zip
├── manifest.json      required, UTF-8 JSON, at most 32 KiB
├── payload.json       required, UTF-8 JSON, at most 128 KiB
└── LICENSE            optional, ignored by the app
```

Rules enforced by `PluginArchive`:

- The artifact is at most 512 KiB.
- Only the three names above are allowed. Directory entries, duplicate names, absolute
  paths, `..`, backslashes, and unknown files are rejected.
- Each entry expands to at most 128 KiB; the whole archive expands to at most 256 KiB.
- The archive is never written to the filesystem, so ZIP path traversal and symlink
  metadata have no target.

Bundled samples in `app/src/main/assets/plugins/` follow the same format. They are
trusted because they are covered by the APK signature, not because they are bundled.

## 2. `manifest.json`

Unknown fields are rejected at every level. Required fields must have the exact JSON
type shown; numbers must be integers.

```json
{
  "schemaVersion": 1,
  "id": "com.example.termux.midnight",
  "name": "Midnight",
  "version": "1.0.0",
  "apiVersion": 1,
  "minCoreVersion": "0.118.0",
  "type": "theme",
  "capabilities": ["terminal.theme"],
  "description": "A dark blue terminal palette.",
  "author": "Example",
  "dependencies": ["com.example.termux.base@1.0.0"]
}
```

| Field | Required | Rules |
| --- | --- | --- |
| `schemaVersion` | yes | Must be `1` |
| `id` | yes | Lowercase reverse-DNS, `^[a-z][a-z0-9]*(\.[a-z][a-z0-9_-]*)+$`, 3–128 characters |
| `name` | yes | 1–64 characters, no control characters |
| `version` | yes | Strict Semver 2.0.0, at most 64 characters |
| `apiVersion` | yes | Must be exactly `1` |
| `minCoreVersion` | no | Semver, default `0.0.0`; must not exceed the app version |
| `type` | yes | `theme`, `extra_keys`, or `session_preset` |
| `capabilities` | yes | Exactly one entry, equal to the capability for `type` |
| `description` | no | At most 512 characters, no control characters |
| `author` | no | At most 128 characters, no control characters |
| `dependencies` | no | At most 16 entries; each is `id@minVersion` |

`capabilities` is not self-granted. `PluginPermissionManager` requires the single
capability to be exactly the one the host maps to the type:

| `type` | Required capability | User-visible effect |
| --- | --- | --- |
| `theme` | `terminal.theme` | Colors when the user has no `colors.properties` |
| `extra_keys` | `terminal.extra_keys` | Extra-keys bar when the user has no `extra-keys` property |
| `session_preset` | `sessions.preset` | A new-session menu entry for a directory under `$HOME` |

Dependency rules:

- The plugin cannot depend on itself.
- Dependency IDs must be unique within a manifest.
- The dependency must already be installed and meet `minVersion`.
- Enabling also requires the dependency to be enabled.
- Cycles are rejected before install and before enable.
- Uninstalling a dependency disables its dependants; it does not delete their files.

There is no dependency auto-install in v1. The repository UI lists dependency IDs so the
user can install them first.

## 3. `payload.json`

`payload.json` must contain `schemaVersion: 1` and a `type` equal to the manifest type.
Unknown fields are rejected. The remaining fields depend on the type.

### 3.1 `theme`

```json
{
  "schemaVersion": 1,
  "type": "theme",
  "colors": {
    "foreground": "#e6e6e6",
    "background": "#101820",
    "cursor": "#ffb000",
    "color0": "#101820",
    "color1": "#ff6b6b",
    "color2": "#7bd88f",
    "color3": "#ffd166",
    "color4": "#6ba8ff",
    "color5": "#c792ea",
    "color6": "#5fd7d7",
    "color7": "#d0d0d0",
    "color8": "#4b5563",
    "color9": "#ff8f8f",
    "color10": "#a3e6b0",
    "color11": "#ffe08a",
    "color12": "#93bfff",
    "color13": "#d7b3f5",
    "color14": "#8ee8e8",
    "color15": "#ffffff"
  }
}
```

- `foreground` and `background` are required.
- `cursor` and `color0` … `color15` are optional.
- Each value is a string in `#RGB`, `#RRGGBB`, `#RRRGGGBBB`, or `#RRRRGGGGBBBB`
  form, matching the host `TerminalColors` parser. `#RGBA`-style colors are not
  accepted.
- All colors are validated before the host applies them. A theme is applied only when
  `~/.termux/colors.properties` does not exist.

### 3.2 `extra_keys`

```json
{
  "schemaVersion": 1,
  "type": "extra_keys",
  "style": "default",
  "keys": "[[\"ESC\",\"TAB\",\"CTRL\",\"ALT\"],[\"LEFT\",\"DOWN\",\"UP\",\"RIGHT\"]]"
}
```

- `keys` is a required JSON string of 1–8192 characters. It is parsed as an array of
  rows, each row an array. A cell is either a key name string or an object with
  exactly one of `key` or `macro`, optional `display`, and optional `popup` (another
  button object).
- `style` is optional; allowed values are `default`, `arrows-only`, `arrows-all`,
  `all`, and `none`. Invalid values are rejected.
- The plugin payload validator checks this JSON shape. At render time the host builds
  `ExtraKeysInfo`; if that final parse fails, the host falls back to the default
  layout rather than applying a partial bar.
- The plugin supplies keys only when `extra-keys` is absent from `termux.properties`,
  and its style only when `extra-keys-style` is absent. Explicit user values always win.

### 3.3 `session_preset`

```json
{
  "schemaVersion": 1,
  "type": "session_preset",
  "name": "Projects",
  "workingDirectory": "projects"
}
```

- `name` is 1–48 characters with no control characters.
- `workingDirectory` is optional and defaults to the home directory. It must be a
  relative path inside `$HOME`: no leading `/` or `~`, no `..`, no backslashes, no
  empty or `.` segments, and at most 256 characters.
- At runtime the path is canonicalized, must still be under `$HOME`, and must already
  exist as a directory. A missing directory produces no menu entry; if it disappears
  later, selecting the entry shows an error instead of opening another directory.
- A preset contains no command, script, shell text, or environment change. Selecting
  it only creates a normal session in that directory after an explicit tap on the
  terminal context menu.

## 4. Repository index

The repository base URL is compiled into the APK as
`TERMUX_PLUGIN_REPOSITORY_URL`; the pinned RSA public key is
`TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY` (Base64 X.509, at least 2048 bits). Both are empty
in this build, so the catalog is disabled. Bundled samples remain available.

The app downloads:

- `<base>/index.json` — at most 256 KiB.
- `<base>/index.json.sig` — at most 8 KiB, standard Base64 (whitespace ignored,
  padding required), signature over the exact `index.json` bytes using
  `SHA256withRSA`.

TLS is required, but it is not treated as publisher authenticity. Only a signature
that verifies against the pinned key is accepted. Redirects are disabled; a redirect
response fails the request.

Index schema:

```json
{
  "schemaVersion": 1,
  "repository": "Example plugins",
  "plugins": [
    {
      "id": "com.example.termux.midnight",
      "name": "Midnight",
      "version": "1.0.0",
      "type": "theme",
      "description": "A dark blue terminal palette.",
      "minCoreVersion": "0.118.0",
      "dependencies": [],
      "url": "https://plugins.example/midnight-1.0.0.zip",
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "size": 1234
    }
  ]
}
```

- Unknown fields are rejected; `plugins` is limited to 500 entries.
- IDs and versions are validated with the same rules as manifests.
- `url` must start with `https://`; it may point at a different host, because the
  signed index is the authority for the artifact location.
- `sha256` is 64 hexadecimal characters; `size` is 1–524288 bytes.
- After download the app checks the size and SHA-256, then validates the artifact as a
  plugin. The installer repeats both checks before committing.
- `minCoreVersion` is compared to the app's `versionName`; incompatible entries are
  shown as unavailable rather than installed.

The index signature protects the catalog and the artifact hashes. It does not provide
revocation, expiry, or a transparency log; see `THREAT_MODEL.md`.

## 5. Installed state

Only validated documents are stored:

```
<getFilesDir()>/plugins/
├── registry.json
├── installed/<id>/<version>/{manifest.json,payload.json}
└── .staging/                 cleared at startup and after each install
```

`registry.json`:

```json
{
  "schemaVersion": 1,
  "plugins": [
    {
      "id": "com.example.termux.midnight",
      "version": "1.0.0",
      "type": "theme",
      "enabled": true,
      "source": "repository",
      "manifestSha256": "…",
      "payloadSha256": "…"
    }
  ]
}
```

- `source` is `bundled` or `repository`.
- The SHA-256 values identify the exact installed documents. They are rechecked on
  every load; modified files make the plugin invalid and it contributes nothing.
- The registry is written to `registry.json.tmp`, fsynced, then renamed over
  `registry.json`. An unreadable registry is moved to `registry.json.corrupt-<time>`
  instead of being overwritten on the next save.
- Uninstall removes only `installed/<id>` and the registry record. It never edits
  `$HOME`, `$PREFIX`, `.termux/termux.properties`, `colors.properties`, fonts, or the
  bootstrap.

## 6. Versioning

- `apiVersion` is exact, not a range. A v2 plugin is rejected by a v1 app.
- Adding a type, capability, or incompatible payload field requires a new API version
  and host code.
- `minCoreVersion` is a hard floor. The app rejects a plugin that needs a newer core.
- Updates with a lower version are rejected. The same version may be reinstalled;
  updates keep the plugin's enabled state and delete the old version directory after
  the registry commit succeeds.

## 7. Minimal author checklist

1. Use a unique lowercase reverse-DNS ID and a Semver version.
2. Declare exactly one supported type and its matching capability.
3. Validate the payload against the section for that type.
4. Put only `manifest.json`, `payload.json`, and optionally `LICENSE` in the ZIP.
5. If publishing, put the artifact hash and size in a signed index; never rely on TLS
   or a bare checksum for publisher authenticity.
6. Test on a device with no `colors.properties` and no explicit `extra-keys` if you
   want to see the contribution; user configuration intentionally wins.
