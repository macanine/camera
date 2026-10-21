# GitHub Releases distribution

This file describes the checked-in implementation. GitHub-hosted workflows only run after these files are pushed to the repository; a successful local build is not evidence that a GitHub run or publication has occurred.

## Version source and ordering

`version.properties` is the single human-edited version source. Supported forms are `major.minor.patch` and `major.minor.patch-rc.N` (N from 1 to 98). GitHub tags must be exactly `v` followed by that value. The initial version in this checkout is `1.0.1`.

Android `versionCode` is computed by both Gradle and the tested release tool:

```text
major × 1,000,000 + minor × 10,000 + patch × 100 + slot
slot = RC number for a candidate, 99 for a stable release
```

Examples: `1.0.1-rc.1` → `1000101`; `1.0.1` → `1000199`; `1.0.2-rc.1` → `1000201`. Minor and patch components are each limited to 0–99; major is limited to 0–2099 so codes remain within Android's bounds. After publishing a stable version, candidates must use a higher base version. Every publication must advance beyond all already published stable and candidate codes. A published version cannot be rebuilt and replaced.

## First-time setup

Create the distribution identity once:

```sh
python3 scripts/create-signing-key.py
```

This writes `.signing/camera-release.p12` and `.signing/signing.properties` with private filesystem permissions. Both are ignored by Git. The tool refuses to replace an existing identity and never prints passwords. Back up both files in a secure location: subsequent APKs must use the same signing identity to update existing installations. Debug signing cannot replace this identity.

An operator with permission to manage repository secrets can then run:

```sh
gh auth status
python3 scripts/configure-signing-secrets.py --repository macanine/camera
```

The explicit operator command uploads `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` through standard input to GitHub Actions Secrets. No GitHub access token belongs in the APK. The workflow reconstructs its keystore only in the runner's temporary directory and removes it in an `always()` cleanup step. Repository-level secrets work with the `release` environment; environment secrets can also be used.

The **Prepare release** workflow uses the repository's built-in `GITHUB_TOKEN` to push its branch, create/update a PR and explicitly dispatch `ci.yml` against that branch. No personal token is required. Enable **Allow GitHub Actions to create and approve pull requests** in the repository Actions settings (the workflow only creates PRs; it does not approve or merge them). Explicit dispatch avoids the automatic-PR workflow approval requirement and starts checks on the exact branch commit. See [GitHub workflow triggering rules](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

Protect the default branch (`main` in CI configuration), require the CI checks, restrict who can push `v*` tags, and consider enabling GitHub immutable releases. The workflow uploads everything to a draft before publishing, which is compatible with immutable releases. Environment approvals can be configured by the repository owner if desired; the checked-in workflow does not require an additional approval itself.

## Verification gates

PRs and pushes to `main` call `.github/workflows/verify.yml`:

- Python tests for version transitions and release metadata.
- JVM/Robolectric tests, Android Lint, and an optimized release compilation.
- Emulator smoke tests on API 26 and 36: launch/recreation without camera permission, private service and update provider configuration. Both debug and optimized release variants run a four-formula rendering test, including matrix/aligned environments that use reflection.
- Reports retained as workflow artifacts. Actions are pinned to verified commit IDs, and Dependabot proposes weekly Actions/Gradle updates.

Gradle wrapper has an official SHA-256 distribution checksum. Dependency locks and artifact checksum verification metadata are checked into the project; CI uses strict verification. When intentionally changing dependencies, regenerate both with `--write-locks --write-verification-metadata sha256`, review the changes, and include any host-specific build tools used by Linux CI.

The current compatibility target is explicitly API 36. `OldTargetApi`'s online upgrade recommendation is excluded only for the app Gradle file; changing target SDK requires a deliberate compatibility migration. Other Lint warnings remain errors. Emulator jobs use a fixed Pixel 2 hardware profile. Explicitly dispatched release-branch verification posts a `Release CI` commit status so the automatic release PR shows its complete result without needing a personal token or approval of the duplicate automatic PR run.

No signing secrets are passed to PR verification. The `smokeRelease` property supplies debug signing only to the optimized release verification APK when distribution credentials are absent; the tag workflow rebuilds with the distribution key and requires all four signing secrets before publishing. Debug packages use `.debug`; release device-test builds use `.smoke` so tests cannot overwrite an installed distribution build. The final distribution build uses neither suffix. Debug packages do not check for distribution updates.

## Publish a version

For a new empty repository, initialize and push reviewed source once:

```sh
git init -b main
git remote add origin https://github.com/macanine/camera.git
git add .
git commit -m "Set up reliable processing and signed releases"
git push -u origin main
```

The ignored `.signing/`, build outputs and local SDK settings must remain outside version control. Configure the signing Secrets as described above before pushing a release tag. Previously installed debug-signed copies using the old `com.nic.photoanswer` identity cannot accept the new distribution signature; retain their data until a deliberate migration is arranged. Automatic migration across signing identities is not implemented.

For subsequent releases, either run the **Prepare release** workflow from `main` or use its local equivalent:

```sh
printf '%s\n' '改进照片处理与记录加载。' > /tmp/camera-release-notes.md
python3 scripts/release.py prepare --bump patch --notes /tmp/camera-release-notes.md
# Review version.properties and RELEASE_NOTES.md, then run the normal CI.
git add version.properties RELEASE_NOTES.md
git commit -m "Prepare v1.0.2"
git push origin main
git tag v1.0.2
git push origin v1.0.2
```

The **Prepare release** workflow opens a version-and-notes pull request from `main`; after that PR is merged and its CI passes, run **Publish current version** on `main`. It creates the matching tag (or verifies an existing tag points to exactly this commit) and dispatches **Release APK** on that tag. Pushing a tag locally also works. Re-running it for the same increment merges the latest `main` into the existing release branch and updates its notes and PR, then dispatches checks for that exact head. A mismatched branch version or merge conflict stops the run. For a stable version, `prepare --bump rc` starts the next patch candidate, such as `1.0.2-rc.1`; selecting `patch` on that candidate promotes it to stable `1.0.2`. Release notes are validated before packaging and reject app-facing wording that violates the project's neutral-copy rule.

For the initial checkout use the existing `1.0.1` value and tag `v1.0.1`. The **Publish current version** workflow reads that version automatically; it requires no tag input. Test releases use a tag such as `v1.0.2-rc.1` after setting the corresponding version in the file.

The `publish-release.yml` entry point does not bypass verification. The tag workflow verifies that its commit belongs to the default branch, the tag matches the version source, and the version has not been published or superseded. It repeats the verification gates, builds a signed and shrunk APK, runs `apksigner verify`, extracts the actual signing certificate SHA-256 from that APK, and generates:

- `camera-v<version>.apk`
- `update.json` (package identity, version name/code, minimum SDK, exact APK URL, size, APK SHA-256, pinned signing certificate SHA-256, release notes)
- `SHA256SUMS`
- `signing-certificate.txt` (public certificate diagnostics)

Assets are uploaded to a draft, downloaded and checked before the release is published. Candidate tags become pre-releases and are never marked latest. Stable tags are marked latest only after the complete asset set is present. A failed draft may be retried; a published release may not be overwritten. Release jobs are serialized and are not canceled by a later push. A manual retry must select the same tag as both the workflow ref and `tag` input.

## Application update behavior

Distribution builds check `https://github.com/macanine/camera/releases/latest/download/update.json` when the activity enters the foreground, at most once every six hours after a successful check. Settings offer a manual check and a toggle to disable automatic checks. GitHub's latest stable release is the only automatic channel; RC builds are installed manually.

The user confirms download. The app downloads to private cache, follows GitHub's HTTPS asset redirect while checking every URL against the allowed GitHub hosts, enforces the declared size and a 150 MiB upper limit, and verifies the SHA-256. Before offering installation it verifies the APK package name, version name/code, and exact signing certificate set against the installed application and the certificate pin in `update.json`. These checks are repeated before installation. The manifest's checksum alone is not treated as proof of publisher identity.

FileProvider exposes only the dedicated update-cache subdirectory. Android's per-source installation permission is requested only after the user chooses installation. The system installer asks the user to confirm; this app does not silently install or replace itself. Downloads are process-local and may stop if Android kills the app; the user can retry, and a new download clears stale partial files. There is no promise of background download recovery.

GitHub availability affects update checks. Failure never prevents using the camera or accessing existing records. No additional mirror or backend is implemented.

## Rollback, hot fixes and hot updates

To recover from a bad release, publish corrected code using a higher version; do not lower the version code or replace an existing artifact. Keep database migrations compatible with preserved user data. If disabling a bad latest release, update GitHub's latest designation; already updated clients still require a higher-code corrective release.

For urgent fixes, use a patch release and the same draft-first workflow. After the tag is created, the workflow builds and verifies the APK before publishing. This shortens the recovery path but still requires the user to confirm Android's system installer; the user's device decides when the foreground update check runs.

This implementation ships full APK updates. It does not dynamically load remote DEX, native libraries or Compose code. Android app bundles and Play Core cannot hot-patch a sideloaded APK distributed through GitHub Releases. Native hot-patch systems such as Tinker would add a separate compatibility, signing, rollback and testing surface. Remote configuration, static resources or prompts could be delivered as validated, versioned data in a future design, but no such remote configuration mechanism is implemented here.

References: [Android alternative distribution](https://developer.android.com/distribute/marketing-tools/alternative-distribution), [secure FileProvider setup](https://developer.android.com/training/secure-file-sharing/setup-sharing), [dynamic code loading risks](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading), [GitHub immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases).
