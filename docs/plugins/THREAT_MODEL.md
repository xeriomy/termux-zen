# Plugin System — Threat Model

Status: applies to plugin API v1, as implemented in `com.termux.app.plugins`.
Audience: maintainers, security reviewers, and repository operators.

## 1. Scope and security objectives

The plugin system adds declarative themes, extra-key layouts, and session presets. It
must not weaken the existing Termux security model or damage user data.

Objectives, in priority order:

1. **No plugin code execution.** A plugin cannot add Java/Kotlin bytecode, DEX, JAR,
   native code, shell scripts, or callbacks. The host interprets data only.
2. **No writes outside plugin storage.** Installing, enabling, disabling, updating, or
   uninstalling never modifies `$HOME`, `$PREFIX`, `.termux`, shell configuration,
   fonts, the bootstrap, or preferences.
3. **Fail closed.** Invalid, modified, incompatible, or dependency-broken plugins
   contribute nothing. A failure in one plugin does not crash the app or affect another.
4. **Publisher authenticity.** Repository metadata is accepted only with a valid
   signature from a key pinned in the APK. A checksum alone is not authenticity.
5. **Bounded resource use.** Archives, JSON documents, index entries, network
   responses, and recursive structures have explicit limits.
6. **User configuration wins.** A plugin never overrides an explicit user theme or
   extra-keys setting, and never changes user files to make itself visible.

Non-objectives for v1: sandboxing a compromised same-UID app, protecting against a
compromised Android OS or a rooted device, plugin revocation, and executable
extensions.

## 2. Assets

| Asset | Why it matters |
| --- | --- |
| `$HOME` and `$PREFIX` | User files, packages, shell configuration, and credentials |
| Terminal sessions | A preset must not execute a command or change the environment |
| `termux.properties`, `colors.properties`, `font.ttf` | Explicit user configuration and appearance |
| Plugin registry and installed JSON | Integrity of what the app will apply |
| Pinned repository public key | Root of trust for catalog metadata |
| App signing key | Authenticity of bundled plugins and the host code |
| Bootstrap archive and checksums | Existing bootstrap installation path, unchanged by this feature |

## 3. Trust boundaries

```
 untrusted network / repository
              |
       HTTPS + pinned RSA signature over index.json
              |
       signed index (URLs, SHA-256, sizes)
              |
       artifact ZIP bytes (bounded, in memory)
              |
   strict manifest/payload validation, host capability check
              |
   app-private installed/<id>/<version>/{manifest,payload}.json
              |
       PluginRuntime (revalidated, fail closed)
              |
   host extension points: theme, extra keys, session preset
              |
       user configuration files ($HOME/.termux) -- always win
```

Boundaries and assumptions:

- The repository operator and pinned key holder are trusted to publish safe
  declarative content. Signing does not make content safe; validation still runs.
- The APK signing key holder is trusted. Bundled assets inherit APK authenticity.
- Other apps with the same UID (`com.termux` companion apps) are **inside** the
  Android sandbox boundary. They can read and write app-private plugin files. The
  system protects against accidental corruption and remote attackers, not against a
  malicious same-UID app.
- The Android framework, package manager, and keystore are trusted.
- The user is trusted to confirm installs, enables, and uninstalls.

## 4. Threats and mitigations

### 4.1 Malicious or malformed artifacts

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Plugin ships executable code | Format has no code field; only JSON is read; host never loads classes or invokes scripts | None by design; adding executable plugins requires a new API and threat model |
| ZIP path traversal (`../`, absolute paths, backslashes) | Archive read entirely in memory; only exact names `manifest.json`, `payload.json`, `LICENSE` accepted; no extraction | None known |
| Symlink or device-file entry | No filesystem extraction; directory entries rejected; link payloads cannot be opened as files | None known |
| ZIP bomb / oversized entry | 512 KiB artifact, 128 KiB per entry, 256 KiB total uncompressed, checked while reading | None known; CPU for decompression is bounded by output size |
| Duplicate or unexpected entries | Exact allowlist; duplicate names rejected | None known |
| Unknown or mistyped manifest/payload fields | Strict allowlists and type checks at every level; unknown fields reject the plugin | Future format additions need a new API version |
| Oversized JSON or deeply nested data | Manifest 32 KiB, payload 128 KiB, extra-keys 8 KiB, one popup level; parser exceptions reject the plugin | A parser bug could still be a denial of service; install is user-initiated |
| Malformed theme color leaves partial palette | Every color validated before use; host resets and applies only a fully valid map | Host `TerminalColors` parsing is still the final gate |

### 4.2 Capability and type confusion

| Threat | Mitigation |
| --- | --- |
| Plugin declares `terminal.theme` but is an `extra_keys` plugin | Host requires exactly one capability, equal to the one mapped to the type |
| Plugin adds a capability it does not need | Exactly one capability is accepted; unknown capabilities are rejected |
| Unknown type or API version | Rejected at parse; `apiVersion` must equal `PluginApi.VERSION` |
| Plugin requires a newer host | Strict semver comparison against `BuildConfig.VERSION_NAME`; rejected |
| Declarative data reaches a code path that executes commands | Session presets contain only a name and relative directory; host creates a normal session and never runs preset text |

### 4.3 Dependencies and lifecycle

| Threat | Mitigation |
| --- | --- |
| Missing or too-old dependency | Install/enable rejects unless installed, version satisfies `id@minVersion`, and, when the plugin will be enabled, enabled |
| Dependency cycle | DFS over installed manifests plus the candidate; self-dependency rejected |
| Dependency disables a required plugin | Disabling a plugin disables dependants transitively |
| Uninstall leaves enabled dependants | Uninstall disables dependants before removing the record |
| Two active exclusive themes/keys | Installer disables other enabled plugins of the same exclusive type; enabling rejects a dependency that would be replaced |
| Downgrade or rollback attack through the catalog | Lower versions rejected; equal version is a reinstall; no signed monotonic counter |
| Update failure loses the old version | New directory staged, old renamed aside, registry commit last; exceptions restore the old directory; a crash can leave an inconsistent installed copy |

### 4.4 Repository and network

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| MITM changes catalog or artifact | HTTPS plus signature over exact index bytes with pinned RSA key; artifact size and SHA-256 from signed index | A compromised TLS stack or pinned key compromises the catalog |
| Signed catalog entry points at a different artifact than confirmed | The artifact manifest ID, version, and type must match the signed catalog entry exactly, otherwise the install is rejected | A compromised signing key can still publish a wrong-but-consistent entry |
| Forged index from another key | Key is compiled into the APK; RSA keys under 2048 bits rejected | Key rotation requires an app release |
| Redirect to an untrusted host | Redirects disabled; non-200 responses rejected | None known |
| Oversized or slow response | 15 s connect/read timeouts; index 256 KiB, signature 8 KiB, artifact 512 KiB enforced while reading | Network-level traffic analysis remains |
| Local/private URL from a signed index | Only HTTPS accepted; URL comes from signed metadata | A legitimate publisher can point at any HTTPS host; no host allowlist or egress policy |
| Replay of an old signed index | No expiry or monotonic version in v1 | A network attacker can replay an older signed catalog; installing an older artifact is rejected only if its version is lower than installed |

TLS is required for transport, but the signature is the authenticity decision. A bare
checksum in the index is not trusted until the index signature verifies.

### 4.5 Installed state and storage

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Installed files modified after validation | Registry stores SHA-256 of both documents; every load rechecks and fails closed | A same-UID attacker can rewrite both files and registry hashes; impact is limited to declarative content |
| Registry corrupted or truncated | Bounded read; invalid whole file is quarantined as `registry.json.corrupt-<time>`; single bad records are skipped | User must inspect/remove a quarantine file manually; no automatic recovery UI |
| Crash during registry save | Temporary file, `fsync`, atomic rename; fallback replace | Directory metadata is not fsynced; power loss can still lose the newest state |
| Crash between file commit and registry save | Old directory is moved to `<version>.old-<time>`; plugin then fails hash validation and is skipped | No automatic restore on next start; the old copy remains until a later install cleanup |
| Uninstall deletes user files | Deletion is restricted to the canonical plugin directory; uninstall only removes `installed/<id>` and the record | Deletion resolves canonical paths and refuses to leave plugin storage; same-UID modification remains out of scope |
| Plugin data appears in backups | Files are ordinary app-private files with no secrets | Android backup settings are unchanged; plugin data is declarative |

### 4.6 UI and user intent

| Threat | Mitigation |
| --- | --- |
| Another app opens the manager or installs silently | Activity is `exported=false`; installation requires a confirmation dialog; no background auto-install |
| Catalog install without user awareness | Install button, confirmation with version/type/permissions/size, then network and validation |
| Uninstall destroys settings | Confirmation explains that only plugin files and registration are removed |
| Plugin name spoofs another plugin | IDs are unique and displayed; names are size-limited and control characters rejected | A name can still look similar to another name; users should check the ID |
| A preset starts a command unexpectedly | Preset selection requires a menu tap; payload has no command field; only a working directory is used |

### 4.7 Host integration

| Threat | Mitigation |
| --- | --- |
| Plugin theme overrides user colors | Theme is applied only when `~/.termux/colors.properties` is absent; no file is written |
| Plugin extra keys override user layout | Applied only when `extra-keys` is absent from `termux.properties`; an explicit `extra-keys-style` also wins |
| Styling change is missed while another activity is on top | `TermuxActivity.onResume` compares the runtime generation and reloads styling |
| Invalid plugin crashes startup | `PluginManager.init` loads state; runtime skips invalid plugins; activities handle a null runtime |
| R8 removes or renames plugin classes | Activity is manifest-declared; no reflection or dynamic class loading; release shrinking keeps manifest-referenced components |

## 5. Security invariants to preserve

Any future change must keep these true:

1. No filesystem extraction of untrusted archives.
2. No class loading, reflection, script execution, shell invocation, or `RUN_COMMAND`
   from plugin data.
3. Exact capability/type mapping enforced in the host at parse and reload time.
4. Bounded reads before allocation or parse.
5. Registry and installed-file hashes checked on every activation; invalidity fails
   closed.
6. User configuration files are read-only inputs and always take precedence.
7. Plugin writes remain under `getFilesDir()/plugins`; deletion stays inside the
   canonical plugin directory.
8. Repository metadata is verified against a pinned key over the exact bytes; HTTPS
   alone is insufficient.

## 6. Known limitations

- **Same-UID companion apps** can modify plugin files and the registry. They are part
  of the existing Termux trust boundary and already share access to `$HOME`.
- **No revocation or expiry.** A compromised signing key can serve malicious
  declarative content until a new APK pins a new key. An old signed index can be
  replayed, subject to downgrade rejection.
- **No anti-rollback across app installs.** Uninstalling and reinstalling the app
  clears app-private plugin state.
- **Crash consistency is best-effort.** The normal error paths roll back, but a process
  kill between directory rename and registry commit can leave an orphaned `.old-*`
  directory; the plugin fails closed.
- **No automatic dependency installation.** Users must install dependencies first.
- **Updates require a configured repository.** The Repository tab exposes Update for a
  newer signed catalog entry, but no repository URL and key are configured in this build.
  Bundled plugins therefore have no update path yet.
- **The plugin manager uses a single Material screen.** It is confirmation-gated and
  supplies accessible labels and touch targets, but device-level TalkBack and large-font
  verification is still pending.
- **The release APK build is blocked on this aarch64 host** by Google's x86_64-only
  AAPT2/NDK. An x86_64 CI workflow builds and tests it, and its green runs cover this
  revision. This is a tooling limitation, not a security control. See `TESTING.md` §9.

### 6.1 Catalog expiry and revocation

The index schema has `schemaVersion`, `repository`, and `plugins`; it has no
`generatedAt`, `expiresAt`, sequence number, or revocation list. The client therefore
cannot distinguish a fresh catalog from an old one, and verification does not become
invalid with time.

There is no revocation mechanism. Removing an entry from a later index prevents new
installs, but does not uninstall or disable an already installed plugin. If the pinned
key is compromised, the attacker can sign valid-looking content until a new APK ships
a new key. Revocation currently means an app release, not a signed catalog operation.

Remote catalog remains **disabled** in this build
(`TERMUX_PLUGIN_REPOSITORY_URL` and `TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY` are empty).
Bundled plugins do not depend on the catalog and remain functional.

### 6.2 Replay and downgrade protection

There is no signed monotonic repository version or persisted catalog sequence number.
A network attacker can replay an older signed `index.json`; because the signature is
still valid, it is accepted. The practical effects are stale listings, hidden updates,
and re-offering a version the user already has.

Artifact installation has local anti-downgrade checks: a version lower than the
installed version is rejected; an equal version is a reinstall; a higher version is an
update. Those checks prevent a replayed old index from replacing a newer installed
plugin with an older one, but they do not prevent replay of current-version metadata.
Uninstalling and reinstalling the app clears the installed-version state, so there is
no anti-rollback across app installs.

Before enabling a remote catalog, the index needs a signed monotonic counter and/or
expiry, checked against persisted state, with tests for replay and rollback.

### 6.3 Update visibility and failure recovery

The installer supports same-version reinstall and higher-version updates. When a
repository is configured, the manager shows Update for a catalog entry newer than the
installed version and confirms the old and new versions before downloading. No
repository is configured in this build, so bundled plugins have no update path yet.

Normal update failures roll back: the new version is staged, the old directory is
renamed aside, the registry commit is last, and exceptions restore the old directory.
A process kill in the commit window can leave a `.old-*` directory and a plugin that
fails hash validation and therefore contributes nothing. Recovery is manual; there is
no repair action, and orphan cleanup happens only on a later install.

### 6.4 Dependency handling and conflicts

Dependencies are declared as `id@minVersion`. Install and enable reject a missing,
too-old, or disabled dependency when the plugin will be enabled. Dependency cycles and
self-dependencies are rejected by DFS. Disabling a plugin transitively disables its
dependants; uninstalling disables them before removing the record. One active theme and
one active extra-keys layout are enforced, and enabling a plugin that would replace a
required dependency is rejected.

There is no automatic dependency installation, no version-range solver beyond a
minimum version, no alternatives or optional dependencies, no explicit conflict
declarations, and no transactional multi-plugin install. A user must install
dependencies first; the UI lists the IDs. Dependency data is validated like the rest of
the manifest before use.

### 6.5 Signing-key generation, custody, rotation, and production configuration

No production plugin-signing key was generated, committed, or configured in this
phase. Both repository build fields are empty, so the catalog code path is inert; there
is no remote artifact fetch or signature verification against a production key. The
Repository tab shows the "not configured" state.

The format expects a Base64 X.509 RSA public key of at least 2048 bits, pinned in the
APK at build time, and a `SHA256withRSA` signature over the exact `index.json` bytes.
The private key is never needed by the app. Key generation, offline custody, backup,
and a signing tool are not implemented. Rotation has no dual-key or signed-next-key
transition: it requires an app release that pins the new key, so old and new catalogs
cannot overlap during a migration.

The debug APK is signed with the committed, intentionally untrusted
`app/testkey_untrusted.jks`; it must not be distributed. Release signing is unchanged
from upstream and no release key or signing secret is present in the repository or CI.
Before enabling a remote catalog, the project needs an offline key-generation and
custody procedure, a rotation/revocation plan, a production build configuration kept
out of source control, and tests that prove expiry, replay, and rotation behavior.
Until then remote catalog functionality stays disabled.

## 7. Reporting

Do not report a vulnerability by opening a public issue with exploit details. Contact
the fork maintainers privately, including the affected version, a description, and a
minimal reproduction. If the issue also affects upstream Termux, follow upstream's
security policy.
