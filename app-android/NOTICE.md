# NOTICE — app-android

This directory is a derivative work of the Android demo app of **BigMoeOnEdge**:

- Upstream: https://github.com/Helldez/BigMoeOnEdge
- Path: `examples/android`
- Commit: `74ba18f`
- License: Apache License 2.0 — full text in [`LICENSE-BigMoeOnEdge`](LICENSE-BigMoeOnEdge)
  (copied from the upstream repository root; the demo directory has no license file of its own
  and upstream ships no `NOTICE` file). Upstream source files carry no per-file license headers,
  so none were removed.

Per Apache-2.0 section 4(b), the files below were changed from upstream.

## Changes made for AndroidLM

**Removed (everything that needed the network)**

- `android.permission.INTERNET` from `app/src/main/AndroidManifest.xml`.
- The in-app model downloader: `DownloadWorker.kt`, `ModelDownloader.kt`.
- The built-in model catalog and its unit test: `ModelCatalog.kt`,
  `app/src/test/.../ModelCatalogStatusTest.kt`.
- The manifest merge entry for WorkManager's `SystemForegroundService`.
- The `androidx.work:work-runtime-ktx` dependency (used only by the downloader).

**Modified**

- `app/build.gradle`: `applicationId` is `io.github.phineas1500.androidlm` (the `dev` flavor
  still appends `.dev`); `kotlinx-coroutines-android` is now declared directly, since it used to
  arrive transitively through WorkManager. `namespace` and the Kotlin source package remain
  upstream's `io.bigmoeonedge.example`.
- `ModelPickerUi.kt`: the "Get a model" card (catalog rows, URL field, download progress) became
  an "Add a model" card with only the Storage Access Framework import and per-model delete.
- `ModelManager.kt`: gained `gbLabel` (moved from the removed catalog) and `shardSetOf` (deleting
  a split gguf removes the whole shard set, a job the catalog used to do); empty-state hints no
  longer mention downloading.
- App label, main-screen title and the foreground-service notification title read "AndroidLM";
  `settings.gradle` root project name is `AndroidLM`.
- Comments in the manifests, `file_paths.xml` and `MainActivity.kt` that described the downloader.

**Added (AndroidLM's own; not from upstream)**

- The `research/` module (retrieval library and `ResearchPipeline`, a port of `scripts/rag.py`),
  `app/src/main/java/org/androidlm/research/android/` (SQLite/zstd storage, corpus discovery),
  `app/src/androidTest/`, and `ResearchScreen.kt` (the research toggle and result view).

**Modified for research mode**

- `RunService.kt`: a research entry point (`ACTION_RESEARCH`, or a question riding the
  session-start intent) that runs the pipeline in a service coroutine scope; an `Engine` adapter
  over the existing `--session` line protocol; generations that belong to a research run are kept
  out of the chat transcript; output of a cancelled research generation is dropped by request id.
  The plain chat path is otherwise as upstream wrote it.
- `settings.gradle`, `build.gradle`, `app/build.gradle`: the `:research` module and the Kotlin JVM
  plugin; requery `sqlite-android` (from JitPack, that one module only) and `zstd-jni` for the
  corpus storage; the instrumentation-test dependencies.
- `RunBus.kt`: `UiState.research` and the `ResearchUi` snapshot.
- `Telemetry.kt`: `TelemetryParser.lastDeltaText`.
- `AppSettings.kt`: the persisted `researchMode` switch.
- `MainActivity.kt`: corpus scan, the Research switch by the prompt box, the research result
  item, `launchResearch`.
- `README.md`: replaced with a description of this app.

**Unchanged**

The `--session` line-protocol handling and session lifecycle of the engine foreground service,
the chat UI, settings, telemetry/metrics screens, GGUF header probing, SAF import, the Gradle
wrapper and `gradle.properties`.

## Not in git

The engine binaries (`app/src/main/jniLibs/arm64-v8a/*.so`: `libbmoe-cli.so`, `libllama*.so`,
`libggml*.so`, `libc++_shared.so`) are built from BigMoeOnEdge / llama.cpp and staged on the
build machine; they are not committed. See `app/src/main/jniLibs/arm64-v8a/README.md`.
