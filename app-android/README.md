# AndroidLM — Android app

An offline research assistant for a phone: a large mixture-of-experts language model, streamed
from flash by the [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge) engine, answering
questions with the help of an offline copy of Wikipedia (and optionally Wikivoyage) stored on
the device. Nothing leaves the phone: the app declares no `INTERNET` permission and cannot open
a socket.

The app is a fork of BigMoeOnEdge's Android demo (see [Attribution](#attribution)). Its chat
screen, settings and live telemetry (tok/s, compute vs flash wait, cache hits) are upstream's;
AndroidLM removed everything that used the network and added **research mode**.

## Research mode

With the **Research** switch on (the default when a corpus is found), Send runs a small pipeline
instead of a plain chat turn. It is a port of `scripts/rag.py` (`--mode auto`):

1. **Plan** — the model names up to four Wikipedia articles likely to hold the answer.
2. **Route** — if the first of them is a little-read article (under 5,000 monthly views), where
   the model's own memory is usually wrong, go *sources first*; otherwise *answer first*.
3. **Sources first**: search the corpus, then answer with the numbered passages in the prompt.
   **Answer first**: answer from the model's own knowledge straight away, then search and append
   a short **Source check** of that draft against the passages.

The screen shows the phase, the planned articles, the route and why, the streamed answer, the
source check, and the numbered sources (tap one to read the passage the model was given).

Code: the pipeline and the retrieval library are the pure-JVM module `research/`
(`ResearchPipeline`, `Corpus`, `Planner`, `Prompts`), unit-tested on the JVM against the Python
implementation's golden outputs. The app side is `RunService` (the engine process, the adapter
that turns its line protocol into `Engine.generate`, and the research entry point),
`org.androidlm.research.android` (SQLite/zstd storage, corpus discovery) and `ResearchScreen.kt`.

## Build

Needs JDK 17, the Android SDK (platform 34, build-tools) and, for the engine, the NDK and CMake
from the SDK manager. `ANDROID_HOME` must point at the SDK.

1. Build the engine and stage it into the app (the binaries are not in git):

   ```sh
   git clone https://github.com/Helldez/BigMoeOnEdge
   ../scripts/build-android-engine.sh BigMoeOnEdge app/src/main/jniLibs/arm64-v8a
   ```

   This cross-compiles `bmoe-cli` and the llama.cpp libraries for arm64 and copies them in as
   `libbmoe-cli.so`, `libllama.so`, `libggml*.so`. The CLI is shipped under a `lib*.so` name
   because that is the only kind of file Android extracts and lets an app execute; the app runs
   it as a subprocess from a foreground service (no JNI).

2. Build and install:

   ```sh
   ./gradlew :research:test            # retrieval + pipeline tests (JVM)
   ./gradlew :app:assembleDevDebug
   adb install app/build/outputs/apk/dev/debug/app-dev-debug.apk
   ```

   `:research:test` compares against fixtures (`golden.json`, `sample_wiki.db`,
   `sample_voyage.db`) in `$ANDROIDLM_FIXTURES`, default `~/androidlm-tools/fixtures`, and skips
   those tests when they are absent. `research/DEVICE_TESTING.md` describes the same comparison
   run on a phone.

Flavors: **dev** (application id `io.github.phineas1500.androidlm.dev`) also requests all-files
access, so it can read a model adb-pushed to shared storage; **play** has no storage permission
at all (`./gradlew :app:assemblePlayDebug`).

## Files on the device

Models and corpora are tens of GB, so both are opened in place; nothing is copied at run time.

**Model** — any MoE `.gguf` (dense models are filtered out by a header check; for a split
model, all shards in one directory). Either pick a file in the app ("Add a model"; it is copied
into the app's internal storage), or push it:

```sh
adb push model.gguf /data/local/tmp/bmoe/        # dev flavor; real filesystem, O_DIRECT works
adb push model.gguf /sdcard/Download/            # dev flavor; emulated storage, buffered reads
adb push model.gguf /sdcard/Android/data/io.github.phineas1500.androidlm.dev/files/
```

**Corpus** — `wiki.db` (required for research mode) and `voyage.db` (optional travel guides),
built by `scripts/build_corpus.py`, in a directory named `corpus` under one of: the app's
internal files dir, `/data/local/tmp/androidlm` (or `/data/local/tmp/bmoe`), or the app's
external files dir. The first readable file of each name wins. No permission is needed for any
of them, but files in `/data/local/tmp` must be world-readable:

```sh
adb shell mkdir -p /data/local/tmp/androidlm/corpus
adb push wiki.db voyage.db /data/local/tmp/androidlm/corpus/
adb shell chmod -R a+rX /data/local/tmp/androidlm
```

Without a `wiki.db` the Research switch is disabled and the main screen shows these
instructions; plain chat works regardless. The databases are opened read-only when the first
research question is asked and stay open until the model is unloaded.

Research prompts carry about 1,000 tokens of sources plus a 600-token draft, so keep the
session context at 4096 (the default) or more.

## No network, by design

Neither flavor declares `android.permission.INTERNET`; upstream's model downloader and catalog
were removed along with it. Check a built APK with:

```sh
$ANDROID_HOME/build-tools/<version>/aapt2 dump permissions app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

Expected: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`,
`WAKE_LOCK`, the library-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, and in the dev
flavor `MANAGE_EXTERNAL_STORAGE`. The Gradle build itself does download its dependencies
(Maven Central, Google, and JitPack for requery's `sqlite-android` only).

## Attribution

This directory is a derivative work of BigMoeOnEdge's `examples/android` (Apache-2.0). What was
taken, from which commit, and every change made to it are listed in [`NOTICE.md`](NOTICE.md);
the license text is [`LICENSE-BigMoeOnEdge`](LICENSE-BigMoeOnEdge).
