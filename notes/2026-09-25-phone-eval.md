# The long-tail questions asked on the phone (2026-09-25)

All 24 long-tail questions (`eval/questions_tail.jsonl`) asked in the app on the Pixel 8 Pro, one
after another from 02:07 to 04:57, with the repack build (57aad19: patches 0001-0006, cache 5000
MiB, nice -16, dotprod). Each question restarts the app (the dev-only `research_question` extra), so
every run loads the model first (about 25-30 s, not in the times below); each started at 30.5 C or
four minutes after the previous one, which on this night meant about 33.5 C. Runner:
`scripts/phone_eval.sh`; answers: `eval/answers_phone_tail.jsonl`.

## Timings (from the question being sent, model loaded)

| Route | Questions | First words, median (range) | Done, median (range) |
|---|---|---|---|
| Sources first | 19 | 111 s (77-134) | 148 s (102-191) |
| Answer first, then source check | 5 | 25 s (21-26) | 206 s (177-253) |

Writing ran at 3.7-4.5 tok/s (median 4.0). No run failed, stalled or came back empty over three
hours of continuous use.

## Quality: the phone's answers against the server's

The phone's 24 answers and the server's answers to the same questions (the ones in
`notes/2026-09-24-small-model-comparison.md`), graded blind in random A/B order by a Claude grader
with the long-tail rubric (`eval/pairs_phone_vs_server_tail_blind.json`, grades
`eval/grades_phone_vs_server_tail_blind.json`, key `eval/pairs_phone_vs_server_tail_key.json`):

| | Phone | Server |
|---|---|---|
| Mean score (0-10) | 7.08 | 7.21 |
| Key facts found | 84 / 120 | 85 / 120 |
| Answers with invented specifics left standing | 3 | 3 |

13 ties, 4 better on the phone, 7 better on the server; the difference is within grading noise.
The five questions that differ by 3 or more points go both ways, and each is the same failure: a
draft that invents details and a source check that misses them (or, the other way, catches them).
Three questions took a different route on the phone than on the server because the plan named
different articles. The comparison with Qwen3-1.7B (1.3 vs 7.2 on these questions) therefore holds
for answers produced on the phone.
