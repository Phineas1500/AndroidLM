# Installing AndroidLM on a phone

AndroidLM answers without the network. It comes in two builds:
- **`androidlm-<version>.apk`** downloads its model and corpus itself, from its **Set up** card.
  That download is the only thing the app uses the internet for; on GrapheneOS its Network
  permission can be turned off afterwards. Installed over the offline build, it starts with
  Network off on GrapheneOS: turn it on (App info, Permissions, Network) before downloading.
- **`androidlm-<version>-offline.apk`** has no INTERNET permission at all: the files reach the
  phone another way and are imported.

The files reach the phone by one of these routes:
- **On the phone alone:** the app downloads them, or (offline build) the phone's browser does and
  the app imports them ([below](#on-the-phone-alone)). No computer and no adb.
- **From a computer:** `scripts/install.sh` downloads them, checks them and pushes them over USB
  ([Steps](#steps)).

## What you need

- An Android phone with 8GB of RAM or more (12GB recommended), arm64, Android 10 or newer, and
  about 39GB free (about 59GB while the files are imported on the phone alone). Developed for a
  Pixel 8 Pro; nothing here needs Google Play Services. See [Memory](#memory) for what the app
  takes on each size of phone.
- From a computer: a computer with about 47GB free, `adb` (Android platform-tools), `curl` and
  `python3`, a USB data cable, and USB debugging enabled on the phone (Settings > About phone >
  tap Build number 7 times, then Developer options > USB debugging).

| File | Size | What it is |
|---|---|---|
| `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` | 12.3GB | The language model (Apache-2.0), 2-bit quantization by Unsloth |
| `wiki.db` | 30.1GB | English Wikipedia: text, search index, redirects, pageviews (CC BY-SA 4.0) |
| `wiki_df.db` | 2.3MB | Word counts for `wiki.db`'s index, so a search does not have to read them from it; optional (same results without it, slower) |
| `voyage.db` | 0.3GB | English Wikivoyage travel guides, optional (CC BY-SA 4.0) |
| `places.db` | 2.9GB | 21.1 million places worldwide (to eat, drink and stay, and pharmacies, ATMs, hospitals, supermarkets, stations...), for questions like "vegan restaurants in Lisbon" or "a pharmacy near me"; optional (ODbL: © OpenStreetMap contributors, Overture Maps Foundation, GeoNames) |

## Steps

```sh
git clone https://github.com/Phineas1500/AndroidLM && cd AndroidLM
# the signed app from the v1.7.1 release (or build it yourself: app-android/README.md)
curl -L -o androidlm-1.7.1.apk \
  https://github.com/Phineas1500/AndroidLM/releases/download/v1.7.1/androidlm-1.7.1.apk
shasum -a 256 androidlm-1.7.1.apk   # 97cf08e4115c7fca29504ff7dddec9e05edd34e007612f03e977357c1f441cbb
scripts/install.sh --apk androidlm-1.7.1.apk
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
Pushing 46GB over USB takes roughly 20-50 minutes depending on the cable and port.

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
2. Open AndroidLM (allow its notifications: downloads and imports show their progress there). On
   Wi-Fi, tap **Download all missing** in its **Set up** card.
   - The five files are 46GB. The download goes on with the screen off. **Pause** stops it, and
     **Resume** (or a dropped connection, retried on its own) carries on where it stopped.
   - Each file is checked against its SHA-256 before the app uses it. A damaged file is deleted
     and downloaded again.
   - The files come from Hugging Face. To use another server instead, tap **Change** next to
     "From:" and enter its address. It must serve each file under its own name
     (`<server>/wiki.db`), and the same SHA-256 check applies.
3. Turn on airplane mode and ask a question (see the end of [Steps](#steps)).

**With the offline build**, the browser downloads the files and the app imports them:

1. Install the `-offline` APK as above and open it. Its **Set up** card lists the five files. Tap
   **Download** next to each one, coming back to the app after each: the browser downloads them
   all at once.
   - The files are 46GB in all, so use Wi-Fi. At about 8 MB/s that is about 95 minutes;
     `wiki.db` (30GB) is the last to finish.
   - A browser opened for the first time shows its own welcome screen first.
   - Leave the downloads running until they finish. Hugging Face's download links expire after an
     hour. A download that keeps going finishes past that (`wiki.db` did, 14 minutes after), but
     one that is interrupted later may have to start again.
2. When the downloads have finished, tap **Import files…**, open Downloads in the file picker,
   select all five files (press and hold the first, then tap the others) and tap **Select**.
   - The app recognises each file and checks its size and SHA-256 as it copies it into its own
     storage. A damaged or unfinished download is refused, and the card says which.
   - With **Delete each download once it is copied** ticked (the default), each download is
     deleted once its copy is checked. The phone needs about 76GB free in all: the downloads, plus
     room for the largest copy while it is written. Afterwards the files take 46GB.
   - All five took 4 minutes on a Pixel 8 Pro. The copy goes on with the screen off; a
     notification shows its progress.
3. Turn on airplane mode and ask a question.

**From a USB drive** instead of the browser (either build): on any computer, copy the five files
onto a USB-C drive formatted as exFAT (FAT32 cannot hold files over 4GB), plug it into the phone,
and pick the files on the drive with **Import files…**. The drive is only read, and the phone needs 46GB free.

## Without the script

```sh
adb shell mkdir -p /data/local/tmp/bmoe/corpus
adb push Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf /data/local/tmp/bmoe/
adb push wiki.db wiki_df.db voyage.db places.db /data/local/tmp/bmoe/corpus/
adb shell 'chmod 755 /data/local/tmp/bmoe /data/local/tmp/bmoe/corpus; chmod 644 /data/local/tmp/bmoe/*.gguf /data/local/tmp/bmoe/corpus/*.db'
adb install -r androidlm-1.7.1.apk
```

`/data/local/tmp` is used because the app can open files there in place, without a storage
permission and with direct I/O, which the streaming engine needs for speed. Files under
`/sdcard` also work for the model but are slower, and the corpus cannot be opened from a file
picker location at all.

## Updating from 1.7.0

Install the 1.7.1 APK over it (the offline one over the offline build). No file changes, and the
app keeps its files and settings.

## Updating from 1.6.1 or earlier

1.7.0 reads a new Wikipedia: `wiki.db` (30.1GB) has every article in full, where the old one
(21.3GB) kept only the opening section of the 4 million least-read, and `wiki_df.db` goes with
it. The other files stay as they are. Install the new APK over the old one, then:

- **Files downloaded or imported in the app:** the **Set up** card says "Update available". Until
  the new Wikipedia is in, the app answers from the old one. On Wi-Fi, tap **Download the update**
  (30.1GB, 60-90 minutes on Wi-Fi). The old file is replaced once the new one has passed its SHA-256
  check.
  - The new file needs about 31GB free next to the old one. With less, the app offers to delete
    the old one first; research mode then has no Wikipedia until the download is finished and
    checked.
  - With the offline build, **Update** next to each file downloads it in the browser; then
    **Import files…** as in the first setup. An import deletes the older version itself when the
    new one needs its room.
- **Files pushed with adb:** run `scripts/install.sh --apk androidlm-1.7.1.apk` again. It pushes
  only the files that changed.

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
before it was published. The offline build has no network permission (`aapt2 dump permissions`
lists no `android.permission.INTERNET`), so it cannot reach the network even with Wi-Fi on; the
online build declares INTERNET (and ACCESS_NETWORK_STATE) for its setup downloader alone.

The in-app download (1.6.0) was run on the same phone with two of the files missing:
- From a mirror, with the phone offline and the mirror reached over USB (`adb reverse`), the 1.7MB
  word counts and the 0.3GB travel guide downloaded and passed their SHA-256 checks. The guide was
  paused at 156MB; on Resume the app asked for `Range: bytes=156246016-` and fetched only the rest.
- From Hugging Face, on the phone's Wi-Fi: the word counts in 1.4 s, and the travel guide in 57 s
  (about 5.8 MB/s). Paused at 4.4MB and resumed, it fetched exactly the remaining 324,451,384
  bytes through Hugging Face's redirect to its CDN; both files passed their SHA-256 checks.
- Seven JVM tests (`SetupFilesTest`) cover a dropped connection, a server that ignores the range,
  a damaged or oversized file, giving up after retries, and a real HTTP server with a redirect.

The full-text `wiki.db` (30.1GB) was then downloaded in the app the same way, from Hugging Face
on the phone's Wi-Fi with a test build of the next release: 90 minutes (about 5.6 MB/s), and it
passed its SHA-256 check.

"On the phone alone" was run end to end on the same phone with the v1.3.0 APK, installed fresh
with no adb-pushed files:
- Chrome downloaded the five files from the Set up card in 75 minutes over Wi-Fi.
- The app imported all five in 4 minutes 8 s and deleted the downloads.
- It then answered research questions, a restaurant question among them, from its own storage at
  the usual speed (first words after 15 s on the first question).

Separately:
- a damaged file was refused and its download kept;
- `wiki.db` was imported with the screen off.

On GrapheneOS (2026100600, Android 17, same phone), the 1.7.0 release APK:
- **Download:** it downloaded all five files in the app in 1 hour 40 minutes, and each passed its
  check.
- **Answers:** it gave the same answers as on stock Android, about 9% sooner.
- **Offline build:** installed over it, the offline build imported the model from Downloads in
  about 70 s and answered. With 1.7.1, the same worked for a model downloaded in Vanadium,
  GrapheneOS's browser, from the Set up card's link.
- **Network off:** both builds answered with no network permission.
- **Memory tagging:** with the per-app Memory tagging switch on, it gave the same answers with no
  faults. The switch does not reach the engine, a separate program. From 1.7.1 the engine is built
  to be tagged, and GrapheneOS tags it whether the switch is on or not. It gave the same answers
  tagged, again with no faults.

Details: [`notes/2026-10-09-grapheneos.md`](notes/2026-10-09-grapheneos.md).
