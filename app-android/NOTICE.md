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

**Unchanged**

The engine foreground service (`RunService`), the `--session` line-protocol handling, chat UI,
settings, telemetry/metrics screens, GGUF header probing, SAF import, the Gradle wrapper and
`gradle.properties`. `README.md` is upstream's and still describes the upstream app, including
features removed here.

## Not in git

The engine binaries (`app/src/main/jniLibs/arm64-v8a/*.so`: `libbmoe-cli.so`, `libllama*.so`,
`libggml*.so`, `libc++_shared.so`) are built from BigMoeOnEdge / llama.cpp and staged on the
build machine; they are not committed. See `app/src/main/jniLibs/arm64-v8a/README.md`.
