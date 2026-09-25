# Search on the phone, and running it while the engine works (2026-09-24)

In the app a search took 18-20 s on the Pixel 8 Pro against about 3 s on the ARM server.

## Where the time goes

`SearchTimingTest` (app androidTest) times each retrieval step on the installed 21GB corpus for five
real questions. With the phone otherwise idle:

| Step | Cold | Warm |
|---|---|---|
| Question stems (`fts_v` document counts) | 3.7-4.5 s | 0.25-0.34 s |
| Title resolution | 12-56 ms | 0-26 ms |
| Planned-article passages | 7-419 ms | 5-124 ms |
| Whole-index BM25 (OR of the rarest terms) | 1.9-4.8 s | 1.1-3.0 s |
| Topic stems | 41-1,218 ms | 20-181 ms |

With the engine loaded and holding its 5000 MiB cache (4.4GB left available), the cold stems took
3.9-5.3 s per question and the first BM25 query took 46 s. The FTS index is already a single
merged segment (`build_corpus.py` runs `optimize`). The cost is inherent: counting a word's
documents reads its whole posting list (detail=full), and on the phone little of the index stays
in the page cache while the model runs. A larger SQLite page cache (64 MB) and memory-mapped reads
(2 GB) made no difference, nor did the per-query cursor window.

## Change: search in the background

The stems and the whole-index BM25 depend only on the question, not on the plan
(`Corpus.questionSearch`; `retrieve(..., pre)` returns exactly what it would have computed). With a
`BackgroundSearch` (a second connection to the same files on its own thread), the pipeline starts
them as soon as a question arrives, so they run while the plan is written; on the answer-first
route the rest of the search also runs while the draft is written, and its result is reported only
once the draft is done, so the events keep their order. The app runs that thread at background
priority, which on Android keeps it on the little cores and off the engine's compute cores, and
raises it when the run waits for it. Results, prompts and events are unchanged (JVM tests compare
the background and sequential pipelines on the golden cases).

| Final build, on screen | Before | After |
|---|---|---|
| Dead Sea (answer first + check): search wait | 20.2 s | 0 s (question half during planning, title half, 0.7 s, during the draft) |
| Dead Sea: whole question (two runs) | 263 s | 224 s, 251 s |
| Big Motor (sources first): search wait | 18.0 s | 10.9 s |
| Big Motor: first words / done | 82 s / 105 s | 75 s / 98 s |

The two Dead Sea runs differ by the draft's speed (6.35 and 5.61 tok/s, phone temperature), not by
the search; the draft was not slowed by the background search compared with the runs before it
(5.77 tok/s). On the sources-first route the
question half of the search did not finish within the 14 s of planning on the little cores; the
remaining 11 s is the rest of it plus the title part.
