# Current architecture

This document describes the implementation in `app/src/main` as it exists today. Treat source code as authoritative when implementation details change.

## Runtime shape

The app is one Android application module, one `MainActivity`, and a Kotlin package (`com.photoanswer.app`). UI is built with Jetpack Compose and Material 3; CameraX owns camera preview and image capture. The UI observes repository snapshots triggered by database changes and calls into the service for retry/cancel actions.

```text
MainActivity / Compose navigation
  ├── CameraScreen ── CameraX capture ── private image file
  ├── AlbumScreen ── answer renderer / history / original photo
  └── SettingsScreen ── SettingsDraft / ProviderEditor
                 │
                 ├── TaskRepository → TaskStore / TaskDatabase (SQLite)
                 └── SolveService (foreground service)
                       ├── queue and concurrency coordination
                       ├── ProviderRouting / RequestCoordinator
                       └── ModelClient → ModelTransport → HTTPS + SSE/JSON
```

## UI and navigation

- Launcher artwork lives in `res/drawable/ic_launcher_*`: layered vector camera and gradient background, with a separate alpha-only monochrome mark for Android 13+ themed icons. Adaptive launcher resources retain the camera inside the central safe zone for different launcher masks. `docs/images/app-icon.png` is a rendered preview; Android uses the vector resources.
- App-authored UI copy uses neutral terms such as “照片、内容、记录、处理” and must not explicitly mention “题目” or “解答”. This includes accessibility text, notifications, empty states, and errors. Model-generated/user content is preserved as content and is not subject to this wording rule.
- `MainActivity.kt` owns the single-activity Compose host, page selection, system bar appearance, observable, paged task snapshots, and multi-window page behavior.
- `CameraScreen.kt` owns camera permission, CameraX lifecycle, focus/zoom/flash, capture, and insertion of captured files into the task database.
- The camera's album entry is an intentionally invisible double-tap hit area beside the shutter. It also exposes a TalkBack custom action. Keep it invisible and do not add an on-screen hint unless the user asks.
- `AlbumScreen.kt` presents task state, rendered answer, paging, retry/cancel/delete, and reading controls. Record switches wait for the matching full detail instead of rendering a stale record or a truncated history summary. Photo decoding shows distinct loading/failure states. `HistorySwitcher.kt` contains the history list/switcher.
- `AnswerContent.kt` renders Markdown and LaTeX with Markwon in a selectable Android `TextView` hosted by Compose. `MathMarkdown.kt` normalizes common math delimiters. Release keep rules preserve both the Android adapter and the reflection-based `org.scilab.forge.jlatexmath` engine; device tests render inline, integral, aligned and matrix content against the optimized release variant.
- `PhotoComponents.kt` loads thumbnails and full images off the UI thread and provides the zoomable original-photo dialog.
- `SettingsScreen.kt`, `ProviderEditor.kt`, and `SettingsDraft.kt` edit provider configuration as a draft, protect unsaved changes, and save through `SecurePrefs`. `SettingsDraft` owns the save coroutine and its progress/errors across rotation; connection editing is disabled during a save. Initial configuration appears before update settings. Foreground configuration changes wake durable queued work even if the save UI was recreated.

## Task data and background work

- `Data.kt` defines `SolveTask`, `TaskDatabase`, `TaskStore`, and `SecurePrefs`. Tasks and request-attempt diagnostics are stored in SQLite; the current schema is created/migrated by `SQLiteOpenHelper` (database version 3, with history and queue indexes).
- `TaskRepository.kt` is the UI boundary for asynchronous recovery, bounded history summaries, selected-record detail, counts, and image deletion. Database mutations publish a revision flow instead of UI polling. History initially loads 50 rows, with an explicit load-more action; summaries include at most 120 content characters and exclude full drafts/structured content. Full detail is loaded only for the selected record.
- `PhotoAnswerApplication` schedules startup recovery on the application IO scope. Consumers await repository initialization. A visible activity rechecks durable queued work and configured providers and starts the service as needed; interrupted in-flight requests are not automatically retried.
- Captured images live in the app's private `files/images` directory. A task stores the image path; deleting a task first invalidates its generation, removes its image on IO, then removes the task and attempt rows. A failed file deletion keeps the row available for retry.
- `SolveService.kt` is an Android foreground service. Its coroutine scheduler queries queued tasks directly, owns in-process jobs, performs retries/fallback and verification, and writes progress/results back to SQLite. Database changes, settings changes, commands and job completions wake the scheduler; timed wakeups handle cooldowns and verification deadlines. Permanently blocked normal providers fail waiting records even if a standby-only provider remains usable. The database is the durable source of task state; active coroutines and leases are process-local.
- The foreground service is `START_NOT_STICKY`; do not promise automatic recovery of an in-flight network request after process death. Startup recovery marks active work interrupted while preserving any already saved answer.
- `ProviderRouting.kt` contains provider configuration, endpoint eligibility, retries and request slot/cooldown coordination. Cooldown and review durations use a monotonic clock. These limits describe this process and are not a server-side exactly-once guarantee.

## Model requests and local secrets

- `ModelClient.kt` prepares EXIF-corrected, bounded JPEG uploads and shares an HTTP transport. `ModelProtocol.kt` parses compatible streaming and non-streaming responses. `ModelTransport.kt` and protocol-related types live in `ModelProtocol.kt`.
- `SolveService` calls the model client for primary generation and optional independent verification. Attempt diagnostics are stored without API keys or raw reasoning text.
- Transport responses have an 8 MiB cumulative wire limit and a 128 Ki-character final-content limit, plus bounded events and format-sniff prefixes. The progress watchdog uses fixed-delay scheduling. These local bounds do not send a model token limit.
- Provider API keys are encrypted with an Android Keystore AES-GCM key before storage in app-private preferences by `SecurePrefs`. Loading/decryption failures are explicit and settings saves wait for durable commit success. Configuration loads and crypto run on IO. Local unexpected errors produce a diagnostic ID with exception type and stack frames, excluding exception messages and model content. Do not log credentials or place them in docs, URLs, or diagnostics.
- The configured endpoint is HTTPS-only in the current provider validation. “OpenAI compatible” does not guarantee that every provider implements the same image, streaming, or response behavior.

## Distribution and update boundary

- `version.properties` supplies the release name. Gradle computes the monotonic Android version code, loads optional ignored local/environment signing credentials, separates debug identity with `.debug` and instrumented release tests with `.smoke`, and enables release code/resource shrinking. Unused Room, DataStore and KSP dependencies have been removed; SQLiteOpenHelper and encrypted SharedPreferences remain the storage implementation.
- `.github/workflows/verify.yml` defines JVM, warning-free Lint, optimized compilation, release-metadata round-trip and API 26/36 emulator smoke gates. `ci.yml` runs these for PRs/main; `prepare-release.yml` opens or updates a version-and-notes PR and explicitly dispatches CI using the built-in token; `publish-release.yml` tags the current reviewed `main` version and dispatches release verification; `release.yml` repeats verification for a version tag, signs an APK, pins the actual signing certificate in update metadata and publishes a complete draft to public GitHub Releases. These workflows require pushing the source and configuring signing secrets; this document does not assert a hosted run has completed.
- `AppUpdates.kt` checks the stable GitHub release metadata at foreground entry, with a six-hour success interval and a user-controlled automatic-check setting. It uses a separate HTTPS client restricted to GitHub release hosts, without provider credentials. Debug builds do not request distribution updates.
- User-confirmed downloads are process-local application-scope work in private `cache/updates`. Metadata identity, size, APK SHA-256, pinned signing certificate, and APK signing/package/version are validated. `AppUpdateUi.kt` owns prompts and the settings controls; installation permission is requested on user action, and FileProvider grants access only to the update-cache directory for the system installer. Installation still requires system confirmation. Neither unattended installation nor remote code hot-patching is implemented.
- `scripts/release.py` validates version ordering and emits APK/update/checksum artifacts; signing setup tools keep private material outside version control. See [RELEASE.md](RELEASE.md) for operations and limitations.

## Where to change things

| Concern | Main files |
| --- | --- |
| Page flow and app-level window behavior | `MainActivity.kt` |
| Camera and capture UX | `CameraScreen.kt` |
| History, answer state, and actions | `AlbumScreen.kt`, `HistorySwitcher.kt` |
| Markdown, formulas, and original photos | `AnswerContent.kt`, `MathMarkdown.kt`, `PhotoComponents.kt` |
| Provider settings and draft behavior | `SettingsScreen.kt`, `ProviderEditor.kt`, `SettingsDraft.kt` |
| Durable tasks, attempts, and encrypted preferences | `Data.kt` |
| UI data observation, paging, recovery, IO deletion | `TaskRepository.kt` |
| Scheduling, retries, cancellation, verification | `SolveService.kt`, `ProviderRouting.kt` |
| Image upload and model transport/protocol | `ModelClient.kt`, `ModelProtocol.kt` |
| Application updates and installer UI | `AppUpdates.kt`, `AppUpdateUi.kt` |
| Build, dependencies, versions and publishing | `app/build.gradle.kts`, `version.properties`, `scripts/`, `.github/workflows/` |

## Verification

The project has JVM/Robolectric unit tests under `app/src/test`, device smoke tests under `app/src/androidTest`, and release-tool tests under `scripts/tests`. `UiFlowTest` exercises retained connection drafts/saves and complete record switches through real navigation while preserving model-generated text; it writes screenshots into Gradle's additional test output directory, which CI retains with device reports. Run JVM tests, Lint and optimized release compilation after relevant changes, Python tests for release tooling, and connected device tests where an emulator is available. Dependency locks, wrapper distribution checksum and artifact verification metadata support reproducible verification. UI screenshots are review artifacts from a particular build/device configuration, not guaranteed to match the latest source unless regenerated.
