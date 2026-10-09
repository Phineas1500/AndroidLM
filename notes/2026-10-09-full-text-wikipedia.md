# Every Wikipedia article in full (2026-10-09)

The corpus used to hold the full text of only the most-read articles (the top 2M by monthly
pageviews, which stops at 186 views a month) and the lead section, at most 2,500 characters, of
the other 4M. A question about anything past the lead of a less-read article could not be
answered from the sources. The 50GB budget had room for the rest: `build_corpus.py --full-top 0`
keeps every article in full.

| | Before | Now |
|---|---|---|
| `wiki.db` | 21.3GB | 30.1GB |
| Articles | 6.05M, 1.87M in full | 6.06M, all in full |
| Passages / in the BM25 index | 38.9M / 31.9M | 57.0M / 44.3M |
| `wiki_df.db` (word counts) | 1.7MB, 119,003 stems | 2.3MB, 155,285 stems |
| All files (model, Wikipedia, places, Wikivoyage) | 36.8GB | 45.6GB |

The new files are on Hugging Face under `v2/` (`v2/wiki.db`, `v2/wiki_df.db`), so the URLs that
1.6.0 and earlier download still give them the database they were tested with;
`scripts/publish_corpus.sh` now takes that prefix.

## Questions past the lead

31 questions (`eval/questions_leadonly.jsonl`) on articles the old build cut to their lead:
`scripts/sample_leadonly.py` picked articles with 20-180 views a month, over 9,000 bytes of text
and at most 3,000 characters in the old build, and each question was written from a passage after the lead, with a
reference answer and five key facts. Both builds answered them on the VM with the app's settings
(same model, `rag.py --mode auto`, the 2,000-character check), and a grader saw each pair blind:

| | Old build | Full text |
|---|---|---|
| Mean score (0-10) | 2.84 | **5.35** |
| Key facts | 28 / 155 | **68 / 155** |
| Answers that state something false | 13 | **4** |

The full text was better on 20 questions, the old build on 4 (each by one point), 7 tied. The big
gains are questions where the old answer said the sources had nothing or made something up: the
1828 campaign against the Makhosh, the Hittite city of Cybistra, who founded the PREDA Foundation,
the dinosaurs of Ruyang County (1-3 points before, 9 now). What is still weak: when the plan
names other articles, the answer comes from the model's memory and is often made up (the NORFACE
projects, the Michigan swimming coaches), and with the right article found, both builds still
gave the Fiat 804 a 1-2-3 finish at the 1922 French Grand Prix, where one of its drivers was
killed and another retired.

## What it changes elsewhere

`scripts/retrieval_diff.py` re-ran the search of 162 earlier questions with their saved plans:
143 get exactly the same passages. `scripts/context_diff.py` found 13 of the other 19 where the
passages that reach the prompt change. Answered again on both builds and graded blind
(`eval/grades_fulltext_blind.json`):

| 13 questions | Old build | Full text |
|---|---|---|
| Mean score | 7.08 | 6.62 |
| Key facts | 54 / 66 | 53 / 66 |
| Answers that state something false | 6 | 6 |

Old better on 5, the full text on 1, 7 tied. On the answer-first route the answer itself is the
same, and the difference is in the "From the sources:" list: the full text adds passages from
obscure articles that match the words but not the question (Indian train timetables for an
average-speed puzzle, a Medellín marathon for hospitals in Medellín), which the grader marks
down as padding. These are 8% of the questions and lose about half a point each; the questions
past the lead gain two and a half.

**A gate tried and dropped.** `rag.py --bm25-body-min-views 186` keeps the whole-index search
to the leads of the articles the old build had cut, while still reading their full text when the
plan names them. On the two regression questions it was meant for it changed nothing (9 and 7
points with or without it), and it took relevant passages away from 8 of the 31 questions past
the lead: 5.62 -> 4.12, key facts 24 -> 16 of 40. The flag stays in `rag.py`, off.

## Search speed on the phone

Ten questions on the Pixel 8 Pro with the new database (downloaded in the app: 30.1GB in 90
minutes from Hugging Face, checksum passed) and the memory-preset fix below, compared with the
same questions in the 1.2.1 run ([`2026-10-01-vitalik-bar-121.md`](2026-10-01-vitalik-bar-121.md)).
"Wait" is how long the answer waited for the search after planning, "first words" the time from
the question to the first word of the answer:

| Question | Wait, 1.2.1 | Wait, full text | First words, 1.2.1 | First words, full text |
|---|---|---|---|---|
| cry-017 | 15.1 s | 18.4 s | 71 s | 73 s |
| cry-013 | 11.7 s | 14.6 s | 76 s | 71 s |
| dng-005 | 10.2 s | 16.9 s | 64 s | 61 s |
| cry-002 | 0 | 16.6 s | 63 s | 69 s |
| cry-009 | 0.9 s | 0 (BM25 stopped) | 54 s | 51 s |
| mth-004 | 15.0 s | 0 (BM25 stopped) | 71 s | 43 s |
| trv-010 | 7.0 s | 0 (BM25 stopped) | 62 s | 59 s |
| Total wait, median first words | 59.9 s | 66.5 s | 64 s | 61 s |

Where the whole-index search still runs, it is slower on the bigger index: 26-33 s against 9-25 s,
and the answer waited 3-17 s longer. With every article in full, the planned articles fill the six
sources more often, and then the whole-index search is stopped, since it could not have changed
the sources (`bm25_needed=0`): three of these seven, and all three questions past the lead
(lead-14, -27, -30: first words at 63, 51 and 48 s, no wait). First words came about as soon as
in 1.2.1 (median 61 s against 64 s), but the prompt reading has got faster since 1.2.1, which
hides the few seconds the search lost where it runs. Writing was 3.6-4.1 tokens/s (3.7-4.4 in
1.2.1), with the phone at 36.5-38.5 °C at the start of each question.

**The memory preset was wrong on this phone.** A first run with the new database wrote at
2.6-2.9 tokens/s, and a run started on a cooled phone was no faster, so neither heat nor the
database was the cause. Auto picked the 8GB preset (1,500 MiB expert cache, ubatch 512): it gave
the 12GB settings from 11.0 GiB of reported memory up, and the Pixel 8 Pro reports 10.9 GiB.
That has been the case since 1.5.0, and 1.4.0's `LARGE_RAM_GIB = 11.0` had the same cut.
`MemoryPreset.forRam` now puts the thresholds halfway between what 8, 12 and 16GB phones report
(12GB from 9.5 GiB, 16GB from 13.5); the run above is with that fix (`cache_mb=5000`,
`n_ubatch=1280` in the metrics).

## Updating a phone that has the old Wikipedia

1.7.0 treats an older `wiki.db` or `wiki_df.db` as an update, not a missing file. The Set up
card stays closed and says "Update available: Wikipedia (30.1 GB)", and the app goes on
answering from the old file. A download replaces the old file once the new one has passed its
SHA-256 check. `CorpusFiles` now carries the files' sizes and times, so the next question after
a replacement opens the new file instead of reading on from the old one, which also gives back
the old file's space.

When the new file does not fit next to the old one (for the 30.1GB file, under about 31GB free),
the card asks before deleting the old one. An import deletes it without asking, since the user
picked the new file. The model is unloaded first: an open database keeps its space until it is
closed.

Tested on the Pixel 8 Pro both ways:

| | Enough room | Too little room |
|---|---|---|
| Setup | A test build that takes the old `wiki.db` as the new one, with the full-text one on the phone, served from the computer over USB | 1.7.0 over the old `wiki.db`, with 19.9GB free (a filler file) |
| Download | 21.3GB in about 10 minutes, nothing asked | "Delete the older version first?", then 30.1GB from Hugging Face in 68 minutes, checksum passed |
| During the download | A question answered from the old file (66 s) | Research mode has no Wikipedia |
| Next question | The Ruyang County question got the old build's sources, and free space went from 31 to 59GB as the replaced file closed | The full-text sources, and the full-text answer (18 m, Liufugou Village) |
