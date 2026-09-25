# Taking the search off the critical path (2026-09-25)

After the faster prompt kernels (`notes/2026-09-25-iqk-port.md`), a sources-first question spent a
median 12.4 s (10.7-21.0) waiting for the search between the plan and the answer, about 12% of it.
Two changes, neither of which changes a search result, bring that to under a second for most
questions.

## Where the time went

The search's log line now breaks it down (`phase_done=SEARCHING ... stems= bm25= bm25_at=
bm25_needed= wait= titles=`, see `PhaseTiming.parts`). Four sources-first questions from the
long-tail set, in the app on the Pixel 8 Pro:

| Question | Stems | Whole-index BM25 | Wait after the plan | Planned articles' passages | Search wait |
|---|---|---|---|---|---|
| tail-01 | 5.0 s | 17.6 s | 11.3 s | 2.3 s | 13.6 s |
| tail-03 | 6.4 s | 18.1 s | 9.9 s | 7.2 s | 17.1 s |
| tail-05 | 2.9 s | 18.9 s | 10.6 s | 0.5 s | 11.1 s |
| tail-07 | 2.5 s | 21.2 s | 12.9 s | 0.4 s | 13.2 s |

The question's stems and its whole-index BM25 run in the background from the moment the question
arrives, but they did not finish within the 11-15 s the plan takes. With the engine idle the same
BM25 query takes 1-5 s; in the app it runs on the little cores, with the engine holding 8 GB and
reading 5.5 GB of experts for the plan. Running it at default priority instead of background
priority changed nothing (22.3 and 21.1 s against 21.0 and 22.6 s).

## 1. A word-count file (`wiki_df.db`, `scripts/build_df.py`)

The search ranks the question's words by how many passages contain them, which the full-text
index can only answer by reading each word's whole posting list. `wiki_df.db` (1.7 MB, published
beside `wiki.db` and in `assets/manifest.json`) holds those counts for the 119,003 stems found in
at least 256 passages; rarer stems are still counted from the index, where their lists are short.
`meta` records the chunk count and three check stems that a reader recounts from the index before
attaching the file, so a file built from another `wiki.db` is ignored. Corpus.kt and rag.py read
it the same way (`Corpus.wordCountsPath`, `rag.word_counts_path`).

Checks: the 24 long-tail questions give the same stems and BM25 hits with and without the file
(VM, full corpus; stems took 17.8 s over the 24 without it, 0.01 s with it); rag.py reproduces
`golden.json` byte for byte with a sample file built at min_doc 2; the Kotlin golden test runs its
701 comparisons again with that file attached (`GoldenWordCountsTest`). In the app the stems went
from 2.5-6.4 s to 24-40 ms, but BM25 got 2-5 s slower, because the stems had been warming the
same posting lists: about 2 s saved per question.

## 2. Not waiting for a BM25 whose hits cannot be used

A retrieval keeps the planned articles' passages first, then appends BM25 hits, then keeps the
first max(6, 2 × resolved titles). When the planned articles' passages already reach that number,
no BM25 hit can appear in the sources. With the phone's own plans that is true for 11 of the 18
sources-first long-tail questions (and 4 of the 6 answer-first ones, whose search is hidden behind
the draft anyway). So:

- `Corpus.retrieve` is split into `titleHits` and `withBm25`, and skips the BM25 when the titles'
  passages fill the result (rag.py `retrieve` likewise);
- the pipeline runs the question's stems and its BM25 as two background jobs; after the plan it
  computes the planned articles' passages on the main connection and, when they fill the sources,
  interrupts the BM25 query (`SqlDatabase.interrupt`, a CancellationSignal on Android) instead of
  waiting for it; otherwise it waits, having overlapped the passages with the query.

`skippingTheBm25ChangesNoRetrieval` compares retrieve() against the always-BM25 path for every
golden case, both kinds covered; two pipeline tests hold a BM25 query back to check that the run
interrupts it and returns the same result when the titles fill the sources, and waits for it
otherwise.

## Result, same four questions

| Question | Search wait before | After both changes | BM25 needed |
|---|---|---|---|
| tail-01 | 13.6 s | 0.78 s | no (stopped) |
| tail-03 | 17.1 s | 7.0 s | yes (its planned articles needed fuzzy title searches, 6.4 s, now overlapped with the BM25) |
| tail-05 | 11.1 s | 0.10 s | no |
| tail-07 | 13.2 s | 0.17 s | no |

Plans and sources were identical to the runs before the change for all four. Whole-question times
in these runs are not comparable (they started at 33-36 C after the previous runs); the search
wait is.
