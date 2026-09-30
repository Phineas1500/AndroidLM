# AndroidLM: an offline research assistant for Android

An Android app that answers research questions with no network, on a 12GB phone, within 50GB of
storage. Built for the poidh bounty ["Build the Best Offline AI Research App for
Android"](https://poidh.xyz/mainnet/bounty/31).

## Approach

- **Model:** Qwen3.6-35B-A3B (35B parameters, 3B active per token) at 2-bit
  (`unsloth/Qwen3.6-35B-A3B-GGUF`, `UD-Q2_K_XL`, 12.3GB). The model file is larger than the
  memory the app uses: routed experts are streamed from flash into an in-RAM expert cache by
  [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge), which is built on llama.cpp. Our
  engine patches (`patches/`) keep one pinned thread pool per session on the fast cores, let a
  follow-up turn reuse the conversation instead of re-reading it, repack the dense weights, and
  bring ik_llama.cpp's ARM kernels for the 2- and 3-bit experts into llama.cpp, which halves
  the time to read a prompt ([`notes/2026-09-25-iqk-port.md`](notes/2026-09-25-iqk-port.md)).
- **Corpus:** English Wikipedia (FineWiki, August 2025) in one 21GB SQLite file: the 2M most-read
  articles in full, lead sections for the rest, a BM25 full-text index, Wikipedia's redirect
  table, and monthly pageviews per article, plus a 1.7MB file of the index's word counts that
  keeps the search off the critical path. Optional Wikivoyage (0.3GB) for travel questions.
- **Places:** 21.1 million places worldwide in one 2.9GB SQLite file: where to eat, drink and
  stay, and what a traveller needs (pharmacies, ATMs and money changers, hospitals, supermarkets,
  stations, sights). Overture Maps places merged with OpenStreetMap's diet tags and opening hours,
  GeoNames cities, the Wikivoyage listings matched to them, and how widely read each place's
  Wikipedia article is. A question like "the best vegan restaurants in Lisbon" or "a pharmacy near
  me" (GPS, no network) gets a ranked list of real places in about 0.1 s, then the model's
  recommendations from it, written from the places' travel-guide listings and the start of their
  own Wikipedia articles ([`notes/2026-09-27-places.md`](notes/2026-09-27-places.md)).
- **Ethereum and cryptography library:** Ethereum's specifications (all 1,208 EIPs and ERCs, the
  consensus specs), ethereum.org's documentation, Ben Edgington's *Upgrading Ethereum* and NIST's
  post-quantum standards, in a 19MB SQLite file inside the APK. Each EIP carries its status and
  the network upgrade that shipped it ("Pectra, live on Ethereum mainnet since May 7, 2025").
  A question goes to it when one of its words is at least 20 times more common there than in
  Wikipedia and few of its words are foreign to it; it is then answered with the library's
  passages ahead of Wikipedia's.
- **Pipeline:** the model names the Wikipedia articles it wants; titles are resolved through
  redirects; a router sends little-read subjects retrieval-first (the model's memory of them is
  unreliable) and everything else answer-first, followed by a source check that cites passages.
- **Offline by construction:** the APK declares no `INTERNET` permission and has no Google Play
  Services dependency.

## Status

Running end to end on a Pixel 8 Pro (Android 16, 12GB RAM). Measured on that phone:

| | |
|---|---|
| Storage | 36.8GB (model 12.3GB, Wikipedia 21.3GB, places 2.9GB, Wikivoyage 0.3GB) plus the 69MB APK, which carries the 19MB Ethereum and cryptography library |
| Memory during a research question | about 7.9GB (engine 5.8GB including a 5GB expert cache, pinned dense weights 2.0GB, app 0.15GB) |
| Generation speed | 4-6 tokens/s in the app (lower when the phone is hot) |
| Prompt reading | 24-30 tokens/s in the app (a 1,000-token source prompt in 35-40 s) |
| Model load | about 28 s on app start |
| Answer-first question | first words after about 15 s, and after about 4 s from the second question of a session on (the engine keeps its fixed instructions read: [`notes/2026-09-30-speed.md`](notes/2026-09-30-speed.md)); the answer is done after about 50 s and the cited source check after 103 s (medians over 6 questions; a new question may be asked as soon as the answer is done) |
| Retrieval-first question | cited answer in about 1.6 min (median 98 s over 19 questions; first words after 44-70 s) |
| Places question ("best vegan restaurants in Lisbon") | list of places on screen after 0.2 s; the model's recommendations from it (six to eight places) start after 26 s and are done after 119 s (medians over 20 questions; 88-190 s) |

Against Qwen3-1.7B answering the same 72 questions from memory, graded 0-10 by Claude with one
rubric ([`notes/2026-09-24-small-model-comparison.md`](notes/2026-09-24-small-model-comparison.md)):

| Questions | Qwen3-1.7B | AndroidLM |
|---|---|---|
| General research (28) | 4.2 | 7.4 |
| Travel (20) | 2.0 | 6.2 |
| Obscure subjects (24) | 1.3 | 7.2 |
| All (72) | 2.6 | 7.0 |

AndroidLM's answers in this table were produced on an ARM server with the same model and
pipeline as the app. The obscure-subject questions asked in the app on the phone scored the same,
graded blind against the server's answers: 7.1 vs 7.2, 84 vs 85 of 120 key facts
([`notes/2026-09-25-phone-eval.md`](notes/2026-09-25-phone-eval.md)); asked again with the
faster prompt kernels, 6.9 against the earlier phone answers' 7.1, 84 vs 83 key facts
([`notes/2026-09-25-iqk-port.md`](notes/2026-09-25-iqk-port.md)); and again with the faster
search and writing, 7.2 against 7.0, 84 vs 82
([`notes/2026-09-25-search-speed.md`](notes/2026-09-25-search-speed.md)).

The bounty's bar is ">50% as good as internet search + frontier AI models". We measured it on
61 Vitalik-style questions from Boar's evaluation set: vegan restaurants, post-quantum
signatures and Ethereum, travel, emergencies and travel arithmetic.
- The app answered on the phone.
- A frontier model (Claude Opus 5.5) answered with web search.
- The answers were graded blind in pairs, 0-10, with the graders checking facts on the web.
([`notes/2026-09-28-vitalik-bar.md`](notes/2026-09-28-vitalik-bar.md))

| Questions | AndroidLM | Internet + frontier AI | AndroidLM as a share |
|---|---|---|---|
| Vegan restaurants (20) | 5.9 | 8.7 | 68% |
| Post-quantum and Ethereum (20) | 5.2 | 10.0 | 52% |
| Travel (10) | 6.2 | 9.3 | 67% |
| Emergencies (5) | 7.0 | 9.4 | 74% |
| Travel arithmetic (6) | 7.8 | 9.5 | 82% |
| All (61) | 6.0 | 9.3 | 64% |

The reference was better on every question. The app made 70 factual errors to the reference's
9, 32 of them on Ethereum and post-quantum questions, where its sources were thinnest.

Since then the app carries an Ethereum and cryptography library, and its crypto questions are
answered from it. The earlier answers, the new ones and the reference were graded blind side by
side by one grader:
- the crypto share went from 47% to 58% of the reference;
- errors fell from 30 to 6;
- first words come later, after about a minute rather than 18 s, but the answer is done
  sooner, a median of 113 s against 124 s.

([`notes/2026-09-28-ethereum-pack.md`](notes/2026-09-28-ethereum-pack.md))

The restaurant answers now name six to eight places, each with what it serves, its street and
its hours, in the language of the question. Graded the same way on the phone, the restaurant
share went from 45% to 71% of the reference, and it was better on all 20 questions. The answer
takes about two minutes instead of forty seconds, and the list is still on screen at once.
([`notes/2026-09-29-places-answers.md`](notes/2026-09-29-places-answers.md))

Measurements, eval rounds and decisions are in [`notes/`](notes/); the Pixel findings are in
[`notes/2026-09-23-pixel-first-day.md`](notes/2026-09-23-pixel-first-day.md) and
[`notes/2026-09-24-speed-levers.md`](notes/2026-09-24-speed-levers.md) (which of the phone's
cores, RAM, GPU and TPU help, and by how much). Known gap: the corpus is installed with adb (no
in-app import).

## Install

The signed APK is in the [v1.2.1 release](https://github.com/Phineas1500/AndroidLM/releases/tag/v1.2.1).
[`INSTALL.md`](INSTALL.md): `scripts/install.sh` downloads the model and corpus on a computer,
checks their SHA-256, pushes them to the phone over USB and installs the APK. Building the app:
[`app-android/README.md`](app-android/README.md). Testing it: [`TESTING.md`](TESTING.md).

## Layout

| Path | What it is |
|---|---|
| `scripts/build_corpus.py` | FineWiki parquet shards -> `wiki.db` (tiered by pageviews) |
| `scripts/fetch_pageviews.sh` | Monthly Wikimedia pageviews -> per-article totals |
| `scripts/build_redirects.py` | Adds Wikipedia's redirect table to `wiki.db` |
| `scripts/build_df.py` | `wiki_df.db`: the index's word counts, so the search can rank a question's words without reading them from the index |
| `scripts/build_places.py`, `fetch_osm_diet.py` | `places.db`: Overture Maps places, OpenStreetMap diet tags, GeoNames cities, Wikivoyage listings |
| `scripts/fetch_pack_sources.sh`, `build_pack.py`, `finish_pack.py` | `ethereum.db`: the Ethereum and cryptography library (EIPs, ERCs, consensus specs, ethereum.org, Upgrading Ethereum, NIST) |
| `scripts/places.py` | Places questions: parsing, finding the city, ranking (prototype of the app's `Places.kt`) |
| `scripts/rag.py` | The retrieval and answering pipeline (prototype of the on-device logic) |
| `scripts/eval_models.sh`, `run_eval.py` | Run an eval set against a memory-capped llama-server |
| `scripts/bench.sh`, `sbx.sh` | Benchmarks under a cgroup memory cap; sandbox for third-party code |
| `app-android/` | The Android app (a fork of BigMoeOnEdge's demo) and the `research/` pipeline module |
| `patches/` | Our patches to the BigMoeOnEdge engine and (`patches/llama.cpp/`) to its llama.cpp, applied by the engine build script |
| `scripts/build-android-engine.sh` | Cross-compiles the patched engine for Android arm64 |
| `scripts/install.sh` | Downloads, verifies and pushes the model and corpus; installs the APK |
| `scripts/app_timing.sh` | Times research questions in the app over adb (dev build only) |
| `eval/` | Question sets, model answers and grades for each eval round |
| `notes/` | Dated write-ups of benchmarks and eval rounds |

The built corpus is published at
[rammingaway/androidlm-corpus](https://huggingface.co/datasets/rammingaway/androidlm-corpus)
(CC BY-SA 4.0), and the places database at
[rammingaway/androidlm-places](https://huggingface.co/datasets/rammingaway/androidlm-places)
(ODbL 1.0); `scripts/install.sh` downloads them.

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
# 5. Word counts for the search (about 6 minutes; after any change to the index)
python scripts/build_df.py wiki.db wiki_df.db
```

The places database (about 15GB of downloads, 6 minutes to build on a laptop; needs `duckdb`):

```sh
# Overture Maps places, one release (16 parquet files, 11GB), from
#   s3://overturemaps-us-west-2/release/2026-09-23.1/theme=places/type=place/
# GeoNames cities1000.zip (unzipped), admin1CodesASCII.txt and countryInfo.txt from
#   https://download.geonames.org/export/dump/
python scripts/fetch_osm_diet.py osm-diet.json          # OpenStreetMap diet tags, one QLever query
python scripts/build_places.py --overture overture/ --osm osm-diet.json --geonames geonames/ \
       --voyage voyage.db --work work/ --out places.db
```

The Ethereum and cryptography library (about 500MB of downloads, a minute to build):

```sh
scripts/fetch_pack_sources.sh src/        # shallow clones of the EIPs, ERCs, consensus specs,
                                          # ethereum.org and Upgrading Ethereum; NIST's PDFs and pages
python scripts/build_pack.py --src src --out pack.parquet --views views.tsv --sources sources.tsv
python scripts/build_corpus.py --out ethereum.db --pageviews views.tsv --full-top 100000 \
       --min-chars 300 pack.parquet
python scripts/finish_pack.py ethereum.db sources.tsv "September 2026" src/ethereum-org-website/LICENSE
# the APK carries it: app-android/app/src/main/assets/ethereum.db, with its SHA-256 in ethereum.db.sha256
```

## Licence and attribution

This project's own code is licensed under [Apache-2.0](LICENSE). Third-party components, the
model and the Wikipedia-derived corpus keep their own terms: see [`NOTICE.md`](NOTICE.md).
