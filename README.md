# AndroidLM: an offline research assistant for Android

Work in progress toward the [poidh bounty "Build the Best Offline AI Research App for
Android"](https://poidh.xyz/mainnet/bounty/31): a casual lookup and research tool that runs with
no network on a 12GB phone, within 50GB of storage.

## Approach

- **Model:** Qwen3.6-35B-A3B (35B parameters, 3B active per token) at 2-bit
  (`unsloth/Qwen3.6-35B-A3B-GGUF`, `UD-Q2_K_XL`, 12.3GB). Routed experts are streamed from flash
  with an in-RAM expert cache by [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge), which is
  built on llama.cpp.
- **Corpus:** English Wikipedia (FineWiki, August 2025) in one 21GB SQLite file: the 2M most-read
  articles in full, lead sections for the rest, a BM25 full-text index, Wikipedia's redirect
  table, and monthly pageviews per article.
- **Pipeline:** the model names the Wikipedia articles it wants; titles are resolved through
  redirects; a router sends little-read subjects retrieval-first and everything else
  answer-first with a source check that cites passages.

Status, measurements and decisions are in [`notes/`](notes/). Nothing here has run on a phone
yet; all numbers so far come from a 4-core ARM server under a phone-sized memory cap.

## Layout

| Path | What it is |
|---|---|
| `scripts/build_corpus.py` | FineWiki parquet shards -> `wiki.db` (tiered by pageviews) |
| `scripts/fetch_pageviews.sh` | Monthly Wikimedia pageviews -> per-article totals |
| `scripts/build_redirects.py` | Adds Wikipedia's redirect table to `wiki.db` |
| `scripts/rag.py` | The retrieval and answering pipeline (prototype of the on-device logic) |
| `scripts/eval_models.sh`, `run_eval.py` | Run an eval set against a memory-capped llama-server |
| `scripts/bench.sh`, `sbx.sh` | Benchmarks under a cgroup memory cap; sandbox for third-party code |
| `scripts/build-android-engine.sh` | Cross-compiles the BigMoeOnEdge engine for Android arm64 |
| `eval/` | Question sets, model answers and grades for each eval round |
| `notes/` | Dated write-ups of benchmarks and eval rounds |

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

## Licenses and attribution

See [`NOTICE.md`](NOTICE.md).
