# AndroidLM: an offline research assistant for Android

An Android app that answers research questions with no network, on a 12GB phone, within 50GB of
storage. Built for the poidh bounty ["Build the Best Offline AI Research App for
Android"](https://poidh.xyz/mainnet/bounty/31).

## Approach

- **Model:** Qwen3.6-35B-A3B (35B parameters, 3B active per token) at 2-bit
  (`unsloth/Qwen3.6-35B-A3B-GGUF`, `UD-Q2_K_XL`, 12.3GB). The model file is larger than the
  memory the app uses: routed experts are streamed from flash into an in-RAM expert cache by
  [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge), which is built on llama.cpp. Two
  engine patches of ours (`patches/`) keep one pinned thread pool per session and let a
  follow-up turn reuse the conversation instead of re-reading it.
- **Corpus:** English Wikipedia (FineWiki, August 2025) in one 21GB SQLite file: the 2M most-read
  articles in full, lead sections for the rest, a BM25 full-text index, Wikipedia's redirect
  table, and monthly pageviews per article. Optional Wikivoyage (0.3GB) for travel questions.
- **Pipeline:** the model names the Wikipedia articles it wants; titles are resolved through
  redirects; a router sends little-read subjects retrieval-first (the model's memory of them is
  unreliable) and everything else answer-first, followed by a source check that cites passages.
- **Offline by construction:** the APK declares no `INTERNET` permission and has no Google Play
  Services dependency.

## Status

Running end to end on a Pixel 8 Pro (Android 16, 12GB RAM). Measured on that phone:

| | |
|---|---|
| Storage | 33.9GB (model 12.3GB, Wikipedia 21.3GB, Wikivoyage 0.3GB) plus the 72MB APK |
| Memory during a research question | about 7.8GB (engine 5.6GB including a 5GB expert cache, pinned dense weights 2.05GB, app 0.15GB) |
| Generation speed | 4.2-5.8 tokens/s in the app (lower when the phone is hot) |
| Model load | about 28 s on app start |
| Answer-first question | first words after about 22 s; answer plus cited source check in about 4 min |
| Retrieval-first question | first words after 75-150 s (longer source prompts take longer); cited answer done in 1.6-2.8 min |

Against Qwen3-1.7B answering the same 72 questions from memory, graded 0-10 by Claude with one
rubric ([`notes/2026-09-24-small-model-comparison.md`](notes/2026-09-24-small-model-comparison.md)):

| Questions | Qwen3-1.7B | AndroidLM |
|---|---|---|
| General research (28) | 4.2 | 7.4 |
| Travel (20) | 2.0 | 6.2 |
| Obscure subjects (24) | 1.3 | 7.2 |
| All (72) | 2.6 | 7.0 |

AndroidLM's answers in this table were produced on an ARM server with the same model and
pipeline as the app.

Measurements, eval rounds and decisions are in [`notes/`](notes/); the Pixel findings are in
[`notes/2026-09-23-pixel-first-day.md`](notes/2026-09-23-pixel-first-day.md) and
[`notes/2026-09-24-speed-levers.md`](notes/2026-09-24-speed-levers.md) (which of the phone's
cores, RAM, GPU and TPU help, and by how much). Known gaps: no
signed release APK yet, and the corpus is installed with adb (no in-app import).

## Install

[`INSTALL.md`](INSTALL.md): `scripts/install.sh` downloads the model and corpus on a computer,
checks their SHA-256, pushes them to the phone over USB and installs the APK. Building the app:
[`app-android/README.md`](app-android/README.md). Testing it: [`TESTING.md`](TESTING.md).

## Layout

| Path | What it is |
|---|---|
| `scripts/build_corpus.py` | FineWiki parquet shards -> `wiki.db` (tiered by pageviews) |
| `scripts/fetch_pageviews.sh` | Monthly Wikimedia pageviews -> per-article totals |
| `scripts/build_redirects.py` | Adds Wikipedia's redirect table to `wiki.db` |
| `scripts/rag.py` | The retrieval and answering pipeline (prototype of the on-device logic) |
| `scripts/eval_models.sh`, `run_eval.py` | Run an eval set against a memory-capped llama-server |
| `scripts/bench.sh`, `sbx.sh` | Benchmarks under a cgroup memory cap; sandbox for third-party code |
| `app-android/` | The Android app (a fork of BigMoeOnEdge's demo) and the `research/` pipeline module |
| `patches/` | Our patches to the BigMoeOnEdge engine, applied by the engine build script |
| `scripts/build-android-engine.sh` | Cross-compiles the patched engine for Android arm64 |
| `scripts/install.sh` | Downloads, verifies and pushes the model and corpus; installs the APK |
| `scripts/app_timing.sh` | Times research questions in the app over adb (dev build only) |
| `eval/` | Question sets, model answers and grades for each eval round |
| `notes/` | Dated write-ups of benchmarks and eval rounds |

The built corpus is published at
[rammingaway/androidlm-corpus](https://huggingface.co/datasets/rammingaway/androidlm-corpus)
(CC BY-SA 4.0); `scripts/install.sh` downloads it.

## Reproducing the corpus

Needs about 80GB of free disk, Python 3.10+, `pyarrow` and `zstandard`.

```sh
# 1. FineWiki English shards (36GB) from https://huggingface.co/datasets/HuggingFaceFW/finewiki
#    into corpus/finewiki_en/
# 2. Pageviews for one month
scripts/fetch_pageviews.sh 2026-08            # -> pageviews_en.tsv
# 3. Build (about 4 hours on 4 cores; keep other memory-heavy jobs off the machine)
python scripts/build_corpus.py --out wiki.db --pageviews pageviews_en.tsv \
       --full-top 2000000 corpus/finewiki_en/*.parquet
# 4. Redirects, from https://dumps.wikimedia.org/enwiki/latest/
python scripts/build_redirects.py wiki.db enwiki-latest-redirect.sql.gz \
       enwiki-latest-pages-articles-multistream-index.txt.bz2
```

## Licence and attribution

This project's own code is licensed under [Apache-2.0](LICENSE). Third-party components, the
model and the Wikipedia-derived corpus keep their own terms: see [`NOTICE.md`](NOTICE.md).
