# Installing AndroidLM on a phone

AndroidLM never uses the network: the app does not declare the INTERNET permission. The model
and the corpus are therefore copied to the phone from a computer.

## What you need

- An Android phone with 12GB of RAM, arm64, Android 10 or newer, and about 36GB free.
  Developed for a Pixel 8 Pro; nothing here needs Google Play Services.
- A computer with about 35GB free, `adb` (Android platform-tools), `curl` and `python3`.
- A USB data cable, and USB debugging enabled on the phone
  (Settings > About phone > tap Build number 7 times, then Developer options > USB debugging).

| File | Size | What it is |
|---|---|---|
| `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` | 12.3GB | The language model (Apache-2.0), 2-bit quantization by Unsloth |
| `wiki.db` | 21.3GB | English Wikipedia: text, search index, redirects, pageviews (CC BY-SA 4.0) |
| `voyage.db` | 0.3GB | English Wikivoyage travel guides, optional (CC BY-SA 4.0) |

## Steps

```sh
git clone https://github.com/Phineas1500/AndroidLM && cd AndroidLM
# build the APK (see app-android/README.md) or download it from the releases page, then:
scripts/install.sh --apk path/to/androidlm.apk
```

The script downloads the three files into `./assets-cache` (resumable; run it again after an
interruption), checks their size and SHA-256 against `assets/manifest.json`, pushes them to
`/data/local/tmp/bmoe` on the phone, makes them readable by the app, and installs the APK.
Pushing 34GB over USB takes roughly 15-40 minutes depending on the cable and port.

Then, on the phone: turn on airplane mode, open AndroidLM, tap Refresh under "Add a model",
choose the model, leave Research switched on, and ask something. The first question loads the
model, which takes a while; later questions reuse it.

## Without the script

```sh
adb shell mkdir -p /data/local/tmp/bmoe/corpus
adb push Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf /data/local/tmp/bmoe/
adb push wiki.db voyage.db /data/local/tmp/bmoe/corpus/
adb shell 'chmod 755 /data/local/tmp/bmoe /data/local/tmp/bmoe/corpus; chmod 644 /data/local/tmp/bmoe/*.gguf /data/local/tmp/bmoe/corpus/*.db'
adb install -r androidlm.apk
```

`/data/local/tmp` is used because the app can open files there in place, without a storage
permission and with direct I/O, which the streaming engine needs for speed. Files under
`/sdcard` also work for the model but are slower, and the corpus cannot be opened from a file
picker location at all.

## Removing it

```sh
adb uninstall io.github.phineas1500.androidlm.dev   # or without .dev for the release flavour
adb shell rm -r /data/local/tmp/bmoe
```

## Status

The install flow has been run end to end on a Pixel 8 Pro (Android 16): download, checksum
verification, `adb push` of all three files and the APK install, followed by research questions in
the app. The app has no network permission (`aapt2 dump permissions` lists no
`android.permission.INTERNET`), so it cannot reach the network even with Wi-Fi on. Known gaps: there
is no signed release APK yet (build the dev debug APK, see `app-android/README.md`), and the corpus
can only be installed with adb (the model can also be imported with the in-app file picker).
