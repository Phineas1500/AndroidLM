# AndroidLM on GrapheneOS (2026-10-09)

The bounty asks for Android and compatible GrapheneOS hardware, so the Pixel 8 Pro was wiped and
GrapheneOS installed on it. Everything below was then run the way a GrapheneOS user would, with
the 1.7.0 release APKs.

- **The phone:** GrapheneOS 2026100600 (Android 17), from the web installer, bootloader locked
  again afterwards (verified boot state yellow, as for any OS signed with its own key). It reports
  10.94 GiB of RAM, so the Auto memory preset chose the 12GB settings (5,000 MiB cache, 1,280-token
  prompt reading, dense weights in AHWB).
- **Install:** `androidlm-1.7.0.apk` from the GitHub release (SHA-256 checked) with `adb install`;
  GrapheneOS granted its Network permission at install.

## Downloading the files in the app

**Download all missing (45.6 GB)** on the **Set up** card fetched the five files from Hugging Face
over Wi-Fi in 1 hour 40 minutes (about 7.6 MB/s). Each one passed its SHA-256 check, and the card
then showed all five as downloaded and checked. The download ran on with the screen off and with
the USB cable out for a few minutes.

## Speed and answers

The ten timing questions from [the full-text Wikipedia note](2026-10-09-full-text-wikipedia.md),
with the same script (`scripts/phone_eval.sh`), against the stock Android 16 run on the same phone
and the same 12GB settings (`work/eval-full-timing12` against `work/eval-gos-timing`):

| Question | Total, s (stock / GrapheneOS) | First words, s | Writing, tokens/s |
|---|---|---|---|
| cry-017 ERC-4626 | 120 / 109 | 73 / 65 | 4.09 / 4.23 |
| cry-013 EIP-7702 | 124 / 116 | 71 / 65 | 3.94 / 4.10 |
| cry-009 zk-SNARK vs zk-STARK | 114 / 103 | 51 / 44 | 4.06 / 4.34 |
| mth-004 water for a hike | 64 / 49 | 43 / 34 | 3.99 / 5.33 |
| trv-010 ride-hailing in Bangkok | 131 / 112 | 59 / 46 | 4.06 / 4.43 |
| dng-005 water after a flood | 208 / 182 | 61 / 52 | 3.92 / 4.41 |
| cry-002 ML-DSA vs SLH-DSA | 189 / 168 | 69 / 65 | 3.63 / 4.20 |
| lead-14 Fiat 804, 1922 | 135 / 122 | 63 / 57 | 3.85 / 4.29 |
| lead-27 Ruyang dinosaurs | 115 / 104 | 51 / 47 | 3.81 / 4.29 |
| lead-30 Esso Brussels, 1973 | 83 / 74 | 48 / 43 | 3.92 / 4.31 |
| **Median** | **122 / 111** | **60 / 50** | **3.93 / 4.30** |

- **The same answers:** all ten answers are identical to the stock run, word for word (the same
  MD5 of the answer text).
- **Faster:** answers were done about 9% sooner, first words came 10 s sooner and writing was
  about 9% faster. We did not look into why. The two runs differ in more than the OS: Android 17
  against 16, and no Google Play services on GrapheneOS.
- **Restaurants:** "Tell me the best vegan restaurants in Lisbon" took the places route and named
  eight vegan restaurants with addresses, hours and distances: first words after 22 s, done after
  153 s.
- **No faults:** the crash log stayed empty through all of it.

## The offline build and its import

`androidlm-1.7.0-offline.apk` installed over the online build. It has the same package and
signature, so the downloaded files stayed, and it lists no INTERNET permission. Then:

1. **Delete:** the model was deleted with the app's own **Delete** (Details, Add a model). This
   works once the model is unloaded with **Unload model now**. The Set up card reopened with the
   model "needed".
2. **Copy to the phone:** the 12.3 GB model was pushed into `Download/` (6 minutes over USB).
3. **Import:** in the app, **Import files…** opened the system file picker. In Downloads, one tap
   on the file was enough. The app copied and checked it in about 70 s: "copied and checked; the
   download was deleted". Downloads was empty again.
4. **Answer:** lead-27 was answered word for word as with the online build, in 104 s.

## With the Network permission off

Installing the online build again over the offline one left its **Network** permission off.
GrapheneOS does not grant Network to an update that adds INTERNET back, only to a fresh install.
With the network blocked:

- **Answering:** lead-27 was answered word for word as before, in 109 s.
- **Downloading:** asking for a download showed "No connection: The phone is not connected to a
  network right now. Connect to Wi-Fi". That was wrong, since the phone was on Wi-Fi. GrapheneOS
  hides the network from the app instead of denying INTERNET:
  - `ConnectivityManager` reports no active network.
  - `checkSelfPermission(INTERNET)` still says granted. A test build that checked it showed the
    same dialog.

  So the app cannot tell a blocked app from a phone that is offline. The dialog now names both
  causes and has an **App info** button that opens AndroidLM's App info page, where Permissions
  has the Network switch. A test build showed it, and the button opened that page.

## Exploit protection

GrapheneOS's App info page for AndroidLM shows:

| Setting | State |
|---|---|
| Hardened memory allocator | Default (Enabled) |
| Memory tagging | Default (Disabled) |
| Extended virtual address space | Enabled |
| Secure app spawning | Default (Enabled) |

All of the above therefore ran on GrapheneOS's hardened allocator (hardened_malloc), with nothing
switched off for compatibility. Memory tagging is off for this app by default. The next section
turns it on.

## Memory tagging

The phone's kernel runs with memory tagging (MTE) on (`bootloader.pixel.MTE_FORCE_ON` on its
command line). The per-app **Memory tagging** switch turns it on for AndroidLM, and GrapheneOS
then logs `FORCIBLY_ENABLE_MEMORY_TAGGING` as the app starts. The stock-Android check in
[the MTE note](2026-10-08-mte.md) ran under scudo; here the allocator is hardened_malloc.

- **The switch does not reach the engine.** The app starts the engine as a program of its own, and
  the kernel resets tag checking when a program starts. GrapheneOS then tags the program only if
  its binary asks for it. A probe that prints its own tagging state and then reads past a heap
  allocation (`work/memtest/mte_state.c`), run from adb:
  - **As built:** untagged, no fault.
  - **With `MEMTAG_OPTIONS=sync`:** still untagged. This is what tagged the engine on stock
    Android.
  - **Linked with `-fsanitize=memtag-heap -fsanitize-memtag-mode=sync`:** tagged. This marks the
    binary. The probe stopped at the read with `SIGSEGV, code 9 (SEGV_MTESERR)`.
- **Two runs:** the eleven questions above (the ten timing questions and the Lisbon restaurants),
  with the switch on:
  - **App only:** the 1.7.0 release APK. This tags the app process, where SQLite, zstd and the
    research pipeline run, but not the engine.
  - **App and engine:** a test build with the engine relinked as above, from the same object
    files. No faults.
- **Results:** in both runs every answer was identical to the untagged run, word for word, and the
  crash log stayed empty.

| Question | Total, s (untagged / app / app and engine) | First words, s | Writing, tokens/s |
|---|---|---|---|
| cry-017 ERC-4626 | 109 / 108 / 116 | 65 / 65 / 72 | 4.23 / 4.32 / 4.28 |
| cry-013 EIP-7702 | 116 / 114 / 119 | 65 / 65 / 70 | 4.10 / 4.23 / 4.24 |
| cry-009 zk-SNARK vs zk-STARK | 103 / 103 / 106 | 44 / 47 / 48 | 4.34 / 4.50 / 4.33 |
| mth-004 water for a hike | 49 / 58 / 59 | 34 / 38 / 39 | 5.33 / 4.05 / 4.17 |
| trv-010 ride-hailing in Bangkok | 112 / 115 / 121 | 46 / 49 / 55 | 4.43 / 4.42 / 4.41 |
| dng-005 water after a flood | 182 / 190 / 189 | 52 / 57 / 59 | 4.41 / 4.31 / 4.39 |
| cry-002 ML-DSA vs SLH-DSA | 168 / 172 / 172 | 65 / 66 / 65 | 4.20 / 4.09 / 4.04 |
| lead-14 Fiat 804, 1922 | 122 / 123 / 124 | 57 / 57 / 58 | 4.29 / 4.21 / 4.19 |
| lead-27 Ruyang dinosaurs | 104 / 105 / 105 | 47 / 47 / 48 | 4.29 / 4.12 / 4.19 |
| lead-30 Esso Brussels, 1973 | 74 / 75 / 76 | 43 / 45 / 45 | 4.31 / 4.37 / 4.30 |
| vegan-01 Lisbon restaurants | 153 / 155 / 154 | 22 / 22 / 22 | 4.16 / 4.12 / 4.16 |

- **Writing speed:** unchanged.
- **Total time:** against the untagged run, the median question took about 1 s longer with the app
  tagged and about 4 s longer with the engine tagged too (at most 9 s), almost all of it before the
  first words.
- **Heat accounts for part of that:** the gaps sit in the first questions of each run. There the
  untagged run started cooler (skin 31-34 °C, against about 35 °C with the engine tagged). The
  last five questions started at the same temperature and stayed within 2 s.

The release engine is not marked, so on GrapheneOS today the switch tags the app process only.
Marking the engine would tag it wherever MTE is on, at the cost above. On phones without MTE the
mark does nothing.

## Not tested here

- The offline build's browser route: Vanadium downloading the files from the Set up card's links.
  The import afterwards uses the same file picker as above.
- "Near me" questions with a real GPS fix.
