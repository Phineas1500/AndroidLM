# Draft-then-rewrite vs source check (2026-09-21)

All 72 eval questions in `--mode auto` with the current retrieval (commit d745190), once with
the ordinary source check and once with `--rewrite` (the model revises its own draft with the
passages in view). Routes were identical in both runs; the 27 retrieval-first answers were
byte-identical, so the comparison is the 45 answer-first questions, whose drafts were also
identical. Side by side: `eval/pairs_rewrite_vs_check.json`; grades: `eval/grades_rewrite.json`.

| Group (n) | Draft alone | Draft + source check | Rewrite |
|---|---|---|---|
| All (45) | 6.39 | 6.91 | 6.92 |
| General (22) | 7.66 | 7.68 | 7.57 |
| Travel (17) | 6.03 | 6.32 | 6.06 |
| Long-tail routed answer-first (6) | 2.75 | 5.75 | 7.00 |

Rewrite better by 1+ points on 7 questions, source check better by 1+ on 5, 33 within a point.
Factual errors left standing: 96 with the source check, 56 with the rewrite.

## Reading

- A tie overall, and the rewrite is slightly worse on general and travel questions, which are
  most of the traffic. It wins where the draft is mostly wrong (long-tail questions that leaked
  to answer-first, two numeric questions).
- The rewrite's main failure is deleting correct draft content because the passages do not
  mention it (26 of 45 questions, 3+ items in 17): treatment and onset removed from the diabetes
  answer, five Bronze Age theories cut to about two, practical hiking advice replaced by
  encyclopedia text. That is a worse failure than the source check's (extra noise after a
  complete answer). Much of its lower error count comes from deleting rather than correcting.
- It introduced new errors in 14 of 45 (about 8 factual, mostly minor; the rest false
  attributions), wrote a literal "[Draft]" marker in 6 answers despite the prompt, attached real
  citation markers to invented facts in 4, and talked about "the draft" to the user in 6.
- Cost on the VM: median 542 tokens and 178 s per rewrite vs about 100 s for a source check.

## Decision

The source check stays the default; `--rewrite` remains an experimental flag in the Python
pipeline only and is not ported to Kotlin. If it is revisited: forbid deleting draft statements
the passages do not contradict, replace contradicted sentences instead of appending, allow only
[n] markers, and consider using it only when the check finds a contradiction or the subject is
long-tail.
