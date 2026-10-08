# Installing AndroidLM on a phone

AndroidLM never uses the network: the app does not declare the INTERNET permission. The model
and the corpus reach the phone another way, by one of two routes:
- **On the phone alone:** download the files with the phone's browser, then import them in the
  app ([below](#on-the-phone-alone)). No computer and no adb.
- **From a computer:** `scripts/install.sh` downloads them, checks them and pushes them over USB
  ([Steps](#steps)).

## What you need

- An Android phone with 8GB of RAM or more (12GB recommended), arm64, Android 10 or newer, and
  about 39GB free (about 59GB while the files are imported on the phone alone). Developed for a
  Pixel 8 Pro; nothing here needs Google Play Services. See [Memory](#memory) for what the app
  takes on each size of phone.
- From a computer: a computer with about 38GB free, `adb` (Android platform-tools), `curl` and
  `python3`, a USB data cable, and USB debugging enabled on the phone (Settings > About phone >
  tap Build number 7 times, then Developer options > USB debugging).

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
# the signed app from the v1.5.0 release (or build it yourself: app-android/README.md)
curl -L -o androidlm-1.5.0.apk \
  https://github.com/Phineas1500/AndroidLM/releases/download/v1.5.0/androidlm-1.5.0.apk
shasum -a 256 androidlm-1.5.0.apk   # 58008e4ef93ec3ea17802061e1869a000c26043d8f93ead50b9d3bebbff65ce7
scripts/install.sh --apk androidlm-1.5.0.apk
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

## On the phone alone

1. In the phone's browser, open the [latest release](https://github.com/Phineas1500/AndroidLM/releases/latest),
   download the APK and open it to install it (allow the browser to install apps when asked).
2. Open AndroidLM (allow its notifications: the import shows its progress there). Its **Set up**
   card lists the five files. Tap **Download** next to each one, coming back to the app after
   each: the browser downloads them all at once.
   - The files are 37GB in all, so use Wi-Fi. On Wi-Fi at about 8 MB/s they took 75 minutes;
     `wiki.db` (21GB) is the last to finish.
   - A browser opened for the first time shows its own welcome screen first.
   - Leave the downloads running until they finish. Hugging Face's download links expire after an
     hour. A download that keeps going finishes past that (`wiki.db` did, 14 minutes after), but
     one that is interrupted later may have to start again.
3. When the downloads have finished, tap **Import files…**, open Downloads in the file picker,
   select all five files (press and hold the first, then tap the others) and tap **Select**.
   - The app recognises each file and checks its size and SHA-256 as it copies it into its own
     storage. A damaged or unfinished download is refused, and the card says which.
   - With **Delete each download once it is copied** ticked (the default), each download is
     deleted once its copy is checked. The phone needs about 59GB free in all: the downloads, plus
     room for the largest copy while it is written. Afterwards the files take 37GB.
   - All five took 4 minutes on a Pixel 8 Pro. The copy goes on with the screen off; a
     notification shows its progress.
4. Turn on airplane mode and ask a question (see the end of [Steps](#steps)).

**From a USB drive** instead of the browser: on any computer, copy the five files onto a USB-C
drive formatted as exFAT (FAT32 cannot hold files over 4GB), plug it into the phone, and pick the
files on the drive in step 3. The drive is only read, and the phone needs 37GB free.

## Without the script

```sh
adb shell mkdir -p /data/local/tmp/bmoe/corpus
adb push Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf /data/local/tmp/bmoe/
adb push wiki.db wiki_df.db voyage.db places.db /data/local/tmp/bmoe/corpus/
adb shell 'chmod 755 /data/local/tmp/bmoe /data/local/tmp/bmoe/corpus; chmod 644 /data/local/tmp/bmoe/*.gguf /data/local/tmp/bmoe/corpus/*.db'
adb install -r androidlm-1.5.0.apk
```

`/data/local/tmp` is used because the app can open files there in place, without a storage
permission and with direct I/O, which the streaming engine needs for speed. Files under
`/sdcard` also work for the model but are slower, and the corpus cannot be opened from a file
picker location at all.

## Optional: the larger model

A second, larger model, Qwen3.8-Flash-Next (the IQ3_XXS build by ISTA-DASLab), can sit next to the
main one.
- **What it gives:** on the 61 research questions of
  [`notes/2026-10-04-flash-next.md`](notes/2026-10-04-flash-next.md), its answers scored 67% of a
  web search + frontier AI answer, against 63% for the main model.
- **What it costs:** each answer takes about 3 to 4 times as long (first words after about 2
  minutes, done after about 8 on a Pixel 8 Pro).
- **Space:** it is two files of 75.8 GB together, and both models with the libraries take about
  113 GB, more than a 128 GB phone holds. The app streams its experts from storage, so it runs on a
  12 GB phone.
- **Licence:** Qwen Community License 1.0 (the GGUF repository lists Apache-2.0).
- **Choosing it:** once it is on the phone, pick it in the app's Model list. The app keeps that
  choice, and the Set up card and the Model list say what the larger model costs.

Three ways to install it:
- **On the phone alone:** the Set up card has an "Optional: a larger model" section with Download
  buttons for its two parts. Import both, as with the other files.
- **With the script:** `scripts/install.sh --flash-next` downloads, checks and pushes the two parts
  too.
- **Without the script:** push both parts next to the main model; they must keep their names:

```sh
adb push Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00001-of-00002.gguf /data/local/tmp/bmoe/
adb push Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00002-of-00002.gguf /data/local/tmp/bmoe/
adb shell 'chmod 644 /data/local/tmp/bmoe/*.gguf'
```

## Memory

The app sizes itself to the phone: Settings > Memory preset, Auto by default, picks the preset
for the RAM the phone reports.

| Preset | The app takes | Expert cache | Speed on a Pixel 8 Pro |
|---|---|---|---|
| 8 GB phone | about 4GB | 1,500 MiB | writes about 3 tokens/s; questions take 3-4 minutes |
| 12 GB phone | about 8GB | 5,000 MiB | writes 5-7 tokens/s; questions take 1-2 minutes |
| 16 GB phone or more | about 11GB | 8,000 MiB | not yet measured on a 16GB phone |

The 8GB figures were measured on the Pixel 8 Pro with 4GB of its memory held by another process,
which leaves it what a Pixel 8 has (`notes/2026-10-08-memory-presets.md`). A smaller preset only
gives up speed: the answers are the same.

### In the Android emulator

The emulator's memory is the computer's. Give the virtual device 8GB of RAM (not 12) and at least
64GB of internal storage, and close other large programs on the computer; Auto then picks the 8 GB
preset, and the Settings screen says so. An arm64 system image is needed (Apple Silicon Macs run
one natively). With 12GB given to the emulator, a reviewer's laptop swapped and the app wrote
about 0.2 tokens/s. The 8GB setting has been measured on a phone, not yet in an emulator.

## Removing it

Uninstalling the app also deletes the files it imported. Files pushed with adb are removed with
the second line:

```sh
adb uninstall io.github.phineas1500.androidlm.dev
adb shell rm -r /data/local/tmp/bmoe
```

## Status

The install flow has been run end to end on a Pixel 8 Pro (Android 16): download, checksum
verification, `adb push` of the files and the APK install, followed by research questions in the
app; the v1.0.0 APK was installed from scratch and answered a research question on that phone
before it was published. The app has no network permission (`aapt2 dump permissions` lists no
`android.permission.INTERNET`), so it cannot reach the network even with Wi-Fi on.

"On the phone alone" was run end to end on the same phone with the v1.3.0 APK, installed fresh
with no adb-pushed files:
- Chrome downloaded the five files from the Set up card in 75 minutes over Wi-Fi.
- The app imported all five in 4 minutes 8 s and deleted the downloads.
- It then answered research questions, a restaurant question among them, from its own storage at
  the usual speed (first words after 15 s on the first question).

Separately:
- a damaged file was refused and its download kept;
- `wiki.db` was imported with the screen off.
