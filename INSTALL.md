# Installing AndroidLM on a phone

AndroidLM never uses the network: the app does not declare the INTERNET permission. The model
and the corpus are therefore copied to the phone from a computer.

## What you need

- An Android phone with 12GB of RAM, arm64, Android 10 or newer, and about 39GB free.
  Developed for a Pixel 8 Pro; nothing here needs Google Play Services.
- A computer with about 38GB free, `adb` (Android platform-tools), `curl` and `python3`.
- A USB data cable, and USB debugging enabled on the phone
  (Settings > About phone > tap Build number 7 times, then Developer options > USB debugging).

| File | Size | What it is |
|---|---|---|
| `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` | 12.3GB | The language model (Apache-2.0), 2-bit quantization by Unsloth |
| `wiki.db` | 21.3GB | English Wikipedia: text, search index, redirects, pageviews (CC BY-SA 4.0) |
| `wiki_df.db` | 1.7MB | Word counts for `wiki.db`'s index, so a search does not have to read them from it; optional (same results without it, slower) |
| `voyage.db` | 0.3GB | English Wikivoyage travel guides, optional (CC BY-SA 4.0) |
| `places.db` | 2.9GB | 21.1 million places worldwide (to eat, drink and stay, and pharmacies, ATMs, hospitals, supermarkets, stations...), for questions like "vegan restaurants in Lisbon" or "a pharmacy near me"; optional (ODbL: © OpenStreetMap contributors, Overture Maps Foundation, GeoNames) |

## Steps

```sh
git clone https://github.com/Phineas1500/AndroidLM && cd AndroidLM
# the signed app from the v1.2.1 release (or build it yourself: app-android/README.md)
curl -L -o androidlm-1.2.1.apk \
  https://github.com/Phineas1500/AndroidLM/releases/download/v1.2.1/androidlm-1.2.1.apk
shasum -a 256 androidlm-1.2.1.apk   # 307d39e966141cc8032bad64baf6f61338ffd0076cce519e1c869016e93ccd29
scripts/install.sh --apk androidlm-1.2.1.apk
```

The APK carries the Ethereum and cryptography library (19MB) and sets it up on first use.
The script downloads the model and corpus files into `./assets-cache` (resumable; run it again
after an interruption), checks their size and SHA-256 against `assets/manifest.json`, pushes them
to `/data/local/tmp/bmoe` on the phone, makes them readable by the app, and installs the APK.
The APK is signed with the project's release key (certificate SHA-256
`51:B8:C6:1C:8F:1A:43:5E:09:49:64:A5:F6:A3:1E:AC:97:F6:55:2C:2D:4E:D2:42:0C:55:39:C4:3B:B8:3D:A3`);
its package is `io.github.phineas1500.androidlm.dev`, the build that reads the model and corpus
from `/data/local/tmp`. An earlier build of that package signed with another key has to be
uninstalled first (see below).
Pushing 37GB over USB takes roughly 15-40 minutes depending on the cable and port.

Then, on the phone: turn on airplane mode, open AndroidLM (it finds the model and the corpus by
itself; if it was open during the install, tap Refresh; on first launch Android asks whether it
may show notifications, which the app uses for its progress while it works), leave Research
switched on, type a question and tap Research. The first question loads the model, about 30 s;
later questions reuse it. A question about places near you ("vegan food near me") asks for
location access the first time; the position comes from GPS, and the app still has no network
access.

## Without the script

```sh
adb shell mkdir -p /data/local/tmp/bmoe/corpus
adb push Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf /data/local/tmp/bmoe/
adb push wiki.db wiki_df.db voyage.db places.db /data/local/tmp/bmoe/corpus/
adb shell 'chmod 755 /data/local/tmp/bmoe /data/local/tmp/bmoe/corpus; chmod 644 /data/local/tmp/bmoe/*.gguf /data/local/tmp/bmoe/corpus/*.db'
adb install -r androidlm-1.2.1.apk
```

`/data/local/tmp` is used because the app can open files there in place, without a storage
permission and with direct I/O, which the streaming engine needs for speed. Files under
`/sdcard` also work for the model but are slower, and the corpus cannot be opened from a file
picker location at all.

## Removing it

```sh
adb uninstall io.github.phineas1500.androidlm.dev
adb shell rm -r /data/local/tmp/bmoe
```

## Status

The install flow has been run end to end on a Pixel 8 Pro (Android 16): download, checksum
verification, `adb push` of the files and the APK install, followed by research questions in the
app; the v1.0.0 APK was installed from scratch and answered a research question on that phone
before it was published. The app has no network permission (`aapt2 dump permissions` lists no
`android.permission.INTERNET`), so it cannot reach the network even with Wi-Fi on. Known gap: the
corpus can only be installed with adb (the model can also be imported with the in-app file
picker).
