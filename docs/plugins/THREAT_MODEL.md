# Plugin System — Threat Model (API v2)

Status: applies to plugin API v2 as implemented in `com.termux.app.plugins` (+ `.ui`).
Audience: maintainers, security reviewers, and repository operators. Supersedes the v1
threat model; the v1 boundaries are retained and the new v2 surfaces are analyzed here.

## 1. Scope and security objectives

The plugin system adds declarative themes, extra-key layouts, session presets, whitelisted
terminal settings, per-plugin user settings, and in-app authoring. It must not weaken the
existing Termux security model or damage user data.

Objectives, in priority order:

1. **No plugin code execution.** A plugin cannot add Java/Kotlin bytecode, DEX, JAR,
   native code, shell scripts, or callbacks. The host interprets data only.
2. **No writes outside plugin storage.** Installing, enabling, disabling, updating,
   editing, duplicating, importing, exporting or uninstalling never modifies `$HOME`,
   `$PREFIX`, `.termux`, shell configuration, fonts, the bootstrap, or preferences.
   (Export writes only to the app cache; sharing goes through a read-only FileProvider.)
3. **Fail closed.** Invalid, modified, incompatible, or dependency-broken plugins
   contribute nothing. A failure in one plugin does not crash the app or affect another.
4. **Publisher authenticity.** Repository metadata is accepted only with a valid signature
   from a key pinned in the APK. A checksum alone is not authenticity.
5. **Bounded resource use.** Archives, JSON documents, settings, contributions, index
   entries, network responses, and recursive structures have explicit limits.
6. **User configuration wins.** A plugin never overrides an explicit user theme,
   extra-keys, or terminal property, and never changes user files to make itself visible.

Non-objectives: sandboxing a compromised same-UID app, protecting against a compromised
OS or rooted device, plugin revocation, and executable extensions.

## 2. Assets

| Asset | Why it matters |
| --- | --- |
| `$HOME` and `$PREFIX` | User files, packages, shell configuration, and credentials |
| Terminal sessions | A preset must not execute a command or change the environment |
| `termux.properties`, `colors.properties`, `font.ttf` | Explicit user configuration and appearance |
| Plugin registry, installed documents, settings overrides | Integrity of what the app will apply |
| Pinned repository public key | Root of trust for catalog metadata |
| App signing key | Authenticity of bundled plugins and the host code |
| Bootstrap archive and checksums | Existing bootstrap installation path, unchanged |

## 3. Trust boundaries

```
 untrusted network / repository / user-picked import file
              |
       HTTPS + pinned RSA signature over index.json (catalog path)
              |
       artifact ZIP bytes (bounded, in memory; magic + allowlist)
              |
   strict plugin.json validation (types, bounds, whitelists, tokens)
              |
   app-private installed/<id>/<version>/plugin.json + registry hashes
              |
       PluginRuntime (revalidated every generation, fail closed)
              |
   host extension points: theme, extra keys, session presets, terminal settings
              |
       user configuration files ($HOME/.termux) -- always win
```

Assumptions: the repository operator and pinned-key holder are trusted to publish safe
declarative content (signing does not make content safe; validation still runs); the APK
signing key holder is trusted; companion apps sharing the `com.termux` UID are **inside**
the sandbox boundary and can read/write app-private plugin files (hashes detect tampering
and fail closed — detection, not prevention); the Android framework is trusted; the user
is trusted to confirm installs, enables, uninstalls, and their own edits.

## 4. Threats and mitigations

### 4.1 Malicious or malformed artifacts

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Plugin ships executable code | Format has no code field; only `plugin.json` is read; host never loads classes or invokes scripts | None by design; executable plugins would need a new API + threat model |
| ZIP path traversal / absolute / backslash names | In-memory read; exact allowlist (`plugin.json`, `LICENSE`); no extraction | None known |
| Symlink or device-file entry | No filesystem extraction; directory entries rejected | None known |
| ZIP bomb / oversized entry | 512 KiB artifact, 256 KiB total expansion checked while reading; ZIP magic required | Decompression CPU bounded by output size |
| Duplicate or unexpected entries | Allowlist + duplicate rejection (tested with raw duplicate local headers) | None known |
| Unknown/mistyped fields | Strict allowlists and exact types at every level; integers only where required | Future additions need a new API version |
| Oversized/deep documents | 256 KiB document; 16 contributions; 32 settings; 16 deps; 6 rows × 12 cells, one popup level | A parser bug could still DoS; install is user-initiated |
| Malformed color leaves partial palette | Every color validated before use and re-validated after settings resolution | Host `TerminalColors` parsing is the final gate |

### 4.2 Contribution and settings confusion

| Threat | Mitigation |
| --- | --- |
| Unknown contribution type or API/schema version | Rejected at parse; `apiVersion`/`schemaVersion` must equal 2 |
| Plugin requires a newer host | Strict semver floor against `BuildConfig.VERSION_NAME`; rejected at install and reload |
| `terminal_settings` touches a dangerous key | Whitelist of 7 keys with hard value grammars (enums/ranges); anything else rejected |
| Settings token references an undeclared id | Parse-time token grammar + declared-id check; malformed `${` sequences rejected |
| Token resolves to an invalid value (e.g. text into a color) | Post-resolution re-validation of every contribution; failure fails the plugin closed |
| Stored settings override drifts from the schema | Overrides re-validated on every load; invalid/unknown values fall back to defaults; updates drop undeclared ids |
| Declarative data reaches a command path | Presets carry only name + relative dir; macros are extra-keys text sent as keystrokes by the existing host renderer, exactly like user-configured `extra-keys` — no new execution surface |

### 4.3 Dependencies and lifecycle

| Threat | Mitigation |
| --- | --- |
| Missing/too-old dependency | Install/enable rejects unless installed, version floor met, and enabled when the plugin will be enabled |
| Dependency cycle | DFS over enabled manifests plus the candidate; self-dependency rejected |
| Dependency disabled/removed | Disabling/uninstalling cascades to dependants transitively; runtime re-checks every generation |
| Two active exclusive providers | Installer swaps other enabled providers of the same exclusive type; runtime orders by id and marks losers Overridden |
| Exclusive swap disables the candidate's own dependency | Explicitly rejected after the swap computation |
| Downgrade attack via catalog | Lower versions rejected; equal version reinstall; no signed monotonic counter yet |
| Update failure loses the old version | Staged directory, old moved aside, registry commit last; exceptions restore; a kill in the window fails closed and orphans are cleaned at startup |

### 4.4 Repository and network

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| MITM changes catalog/artifact | HTTPS + pinned-key `SHA256withRSA` over exact index bytes; artifact size + SHA-256 from the signed index | Compromised TLS stack or pinned key compromises the catalog |
| Entry points at a different artifact than confirmed | Document id/version must match the signed entry exactly | A compromised key can publish wrong-but-consistent entries |
| Forged index from another key | Key compiled into the APK; RSA < 2048 bits rejected | Rotation requires an app release |
| Redirect to untrusted host | Redirects disabled; non-200 rejected | None known |
| Oversized/slow response | 15 s timeouts; index 256 KiB, signature 8 KiB, artifact 512 KiB enforced while reading | Traffic analysis remains |
| Replay of an old signed index | No expiry/monotonic version yet; local anti-downgrade still applies | See §6.2 |

### 4.5 Installed state and storage

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Installed document modified after validation | Registry SHA-256 rechecked on every load and before operations; mismatch → Invalid | Same-UID attacker can rewrite files + registry; impact limited to declarative content |
| Registry corrupted/truncated | Atomic tmp+fsync+rename; unreadable file quarantined (`registry.json.corrupt-<time>-<reason>`), never overwritten | Quarantine inspection is manual; no repair UI yet |
| Crash during save/commit | Rollback on normal errors; kill window fails closed; orphan cleanup at startup | Directory metadata not fsynced; power loss can lose newest state |
| Uninstall deletes user files | Deletion restricted to canonical `installed/<id>`; containment checked | Same-UID modification out of scope |
| Path injection via id/version | Grammars + `File(segment).name` equality before path use | None known |
| Plugin data in backups | Ordinary app-private declarative files, no secrets | Backup settings unchanged |

### 4.6 UI, authoring, and user intent

| Threat | Mitigation |
| --- | --- |
| Another app opens the hub or installs silently | Activity `exported=false`; bundled installs confirmed; repository/import installs land disabled; no background auto-install |
| Editor writes outside plugin storage | Editor saves go through the installer only; export writes to app cache `zen-exports/` and shares via a non-exported FileProvider with read-only grants |
| Editor produces a document the host would reject | Save round-trips through the strict parser; Advanced JSON mode uses the same validator |
| Editing someone else's installed plugin silently | In-place editing of bundled/repository artifacts needs an explicit confirmation that states the copy stops matching the original; saving re-labels the record as `local` so provenance stays visible. Duplicate & edit remains available for keeping the original |
| Rewriting a plugin's identity or settings from the UI | Name, id, version, author, description and the settings schema are author-defined in `plugin.json`; loaded drafts are schema-locked and raw-JSON mode is hidden, so only contribution values can change in-app. Setting values remain user-editable on the generated form |
| Id squatting via duplication | Copy ids derived deterministically (`.copy`, `.copy2`, …) and checked against the registry; id field locked when editing an existing plugin |
| Uninstall destroys settings | Confirmation states only plugin files/registration are removed; cascades are confirmed |
| Plugin name spoofs another | Unique ids displayed; names bounded, control chars rejected; users should check the id |
| Preset starts a command unexpectedly | Menu tap required; no command field exists; missing directory → no entry/error toast |
| Import of a hostile ZIP | SAF-picked bytes pass full artifact validation; installed disabled |

### 4.7 Host integration

| Threat | Mitigation |
| --- | --- |
| Plugin theme overrides user colors | Applied only when `~/.termux/colors.properties` absent; no file written |
| Plugin keys override user layout | Applied only when `extra-keys` absent; explicit `extra-keys-style` also wins |
| Plugin terminal settings override user properties | Overlay skips any key present in the user file; only the in-memory internal map is touched, never the raw cache used for precedence checks |
| Styling change missed while another activity is on top | `onResume` generation check reloads styling/properties |
| Invalid plugin crashes startup | Init is defensive; runtime skips invalid plugins; hooks handle a null runtime |
| R8 removes plugin classes | Manifest-declared activity/provider; no reflection or dynamic loading |

## 5. Security invariants to preserve

1. No filesystem extraction of untrusted archives.
2. No class loading, reflection, script execution, shell invocation, or `RUN_COMMAND`
   from plugin data.
3. Contribution types, settings types and terminal-settings keys are host whitelists,
   enforced at parse and reload.
4. Bounded reads before allocation or parse.
5. Registry and installed-file hashes checked on every activation; invalidity fails closed.
6. User configuration files are read-only inputs and always take precedence.
7. Plugin writes remain under `getFilesDir()/plugins` (exports under `cacheDir/zen-exports`);
   deletion stays inside the canonical plugin directory.
8. Repository metadata verified against a pinned key over the exact bytes; HTTPS alone is
   insufficient.
9. Settings resolution re-validates resolved literals before application.

## 6. Known limitations

- **Same-UID companion apps** can modify plugin files and the registry (existing Termux
  trust boundary; they already share `$HOME`).
- **No revocation or expiry**; a compromised signing key can serve malicious declarative
  content until a new APK pins a new key; old signed indexes can be replayed (§6.2).
- **No anti-rollback across app installs** (uninstall clears app-private plugin state).
- **Crash consistency is best-effort** around the update commit window.
- **No automatic dependency installation.**
- **Updates require a configured repository**; none is configured in this build.
- **`terminal_settings` applies on interactive-terminal reloads**; service-only paths keep
  file/default values.
- **Device-level accessibility verification pending** for the Compose hub.
- **APK assembly is blocked on this aarch64 host** by x86_64-only AAPT2; CI builds it.
  Tooling limitation, not a security control.

### 6.1 Catalog expiry and revocation

The index has `schemaVersion`, `repository`, `plugins` — no `generatedAt`, `expiresAt`,
sequence number, or revocation list. Removing an entry prevents new installs but does not
disable installed copies. Revocation currently means an app release. Remote catalog
remains **disabled** in this build (both build fields empty); bundled plugins and local
creation do not depend on it.

### 6.2 Replay and downgrade protection

No signed monotonic repository version or persisted sequence number: an older signed index
is accepted (stale listings, hidden updates). Local anti-downgrade rejects installing a
version lower than installed; equal is a reinstall. Before enabling a remote catalog, add
a signed monotonic counter and/or expiry checked against persisted state, with replay and
rollback tests.

### 6.3 Update visibility and failure recovery

The installer supports reinstall and higher-version updates; the hub shows Update for
newer signed entries. Normal failures roll back; a kill in the commit window leaves a
hash-mismatched plugin that contributes nothing, with orphan cleanup at startup. No repair
action yet.

### 6.4 Dependency handling and conflicts

`id@minVersion` floors only: no range solver, alternatives, optional deps, explicit
conflict declarations, or transactional multi-plugin install. Exclusive types are enforced
per contribution type at plugin granularity. Dependency data is validated like the rest
of the document.

### 6.5 Signing-key generation, custody, rotation, production configuration

No production plugin-signing key exists in this repository; both build fields are empty
and the catalog path is inert. The format expects a Base64 X.509 RSA public key ≥2048 bits
pinned at build time and `SHA256withRSA` over the exact index bytes; the app never needs
the private key. Key generation, offline custody, backup, a signing tool, and dual-key
rotation are not implemented. The debug APK uses the committed, intentionally untrusted
`app/testkey_untrusted.jks` and must not be distributed; release signing is unchanged from
upstream and no release secret is present. Before enabling a remote catalog: offline
key-generation/custody procedure, rotation/revocation plan, production build configuration
kept out of source control, and expiry/replay/rotation tests.

## 7. Reporting

Do not report a vulnerability by opening a public issue with exploit details. Contact the
fork maintainers privately with the affected version, a description, and a minimal
reproduction. If the issue also affects upstream Termux, follow upstream's security policy.