# Release Signing — Arc VR Player

## What changed (2026-09-10)

Prior to this fix, `app/build.gradle.kts` pointed release builds at
`signingConfigs.getByName("debug")` with **no `debug` signingConfig block
declared anywhere in the project**. That silently fell through to the Android
Gradle Plugin's auto-generated debug keystore at `~/.android/debug.keystore`
— a file AGP creates fresh **the first time it's needed on a given machine**.

GitHub Actions runners are ephemeral (a new VM per run), so that file never
pre-existed, and AGP minted a **new random signing key on every single CI
build**. Confirmed by direct comparison: a locally-built v0.9.1 APK and the
GitHub Actions-built v0.9.2 APK carried different SHA-256 signing
fingerprints. Any release published this way is update-incompatible with the
one before it — installing over an existing install fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, forcing an uninstall (and full data
loss: resume positions, viewer profile, settings) on every release.

Astra's architectural review flagged this as finding **C7** — see
`docs/ASTRA_ARCHITECTURAL_REVIEW.md`.

## The fix

1. **Generated a permanent release keystore** (`arc-release`, RSA 2048,
   30-year validity, self-signed). This is the one true signing identity for
   Arc from v0.9.3 onward.
2. **Stored the keystore + credentials as GitHub Actions repo secrets** (never
   committed to git):
   - `ARC_RELEASE_KEYSTORE_BASE64` — the keystore file, base64-encoded
   - `ARC_RELEASE_KEYSTORE_PASSWORD`
   - `ARC_RELEASE_KEY_ALIAS` (`arc-release`)
   - `ARC_RELEASE_KEY_PASSWORD` (same as store password for this keystore)
3. **`app/build.gradle.kts`** now declares an explicit `release` signingConfig
   that reads the keystore path/credentials from environment variables set by
   CI. Falls back to the AGP debug keystore only when those env vars are
   absent (i.e. local dev builds on a workstation with no release secrets
   configured) — local dev builds still work, they're just not
   update-compatible with real releases (as expected).
4. **`.github/workflows/build-and-release.yml`** decodes the keystore secret
   into a temp file before the build, passes it via env vars, deletes the temp
   file immediately after the build step, and prints the resulting signing
   fingerprint in the build log so any future drift is visible immediately.
5. **Version metadata is now derived, not hardcoded.** `versionName` comes
   from the git tag (stripped of the leading `v`); `versionCode` comes from
   the GitHub Actions run number (strictly increasing across the repo's
   history, unlike a tag-derived number which could collide if tags are
   re-pushed). Untagged builds (plain pushes, PRs) get a `0.0.0-<shortsha>`
   versionName so they can never be mistaken for a real release.
6. **The release job now refuses to publish** unless the tag it's asked to
   publish under (a) actually exists as a real git tag, and (b) points at the
   exact commit that was built in this run. This closes the old silent
   `v0.1.0` fallback that could attach release artifacts to the wrong tag.

## Recovering the keystore if this machine is lost

The keystore itself lives at `/root/daydream-vr-player/keystore/arc-release.keystore`
(gitignored, never committed) with credentials in
`/root/arc_keystore_credentials.txt`. **Back both of these up somewhere
durable** (password manager, encrypted vault, Obsidian-adjacent secrets
store) — if this box dies before you've backed them up, all future release
builds will be unable to update existing installs, permanently, because
there is no way to regenerate the exact same private key.

To restore onto a new machine or re-derive the base64 secret:

```bash
# Re-encode the keystore for the GitHub secret (if you still have the .keystore file)
base64 -w0 /path/to/arc-release.keystore

# Update the GitHub secret
gh secret set ARC_RELEASE_KEYSTORE_BASE64 -R leboff/arc < keystore_base64.txt
```

If the keystore file itself is lost, there is **no recovery** — you'd have to
generate a new one and accept that all future releases break the upgrade
chain from everything before that point (users would need to uninstall once
more, then upgrades work normally going forward).

## Verifying signing consistency between releases

```bash
APKSIGNER=$ANDROID_HOME/build-tools/35.0.0/apksigner
$APKSIGNER verify --print-certs Arc-vX.Y.Z-release.apk | grep SHA-256
```

The SHA-256 signing fingerprint should be identical across every release from
v0.9.3 onward. If it ever changes, something is wrong with the CI secret
configuration — check before publishing.
