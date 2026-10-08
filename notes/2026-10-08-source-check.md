# The source check lists what the sources say instead of judging the answer (2026-10-08)

A reviewer's screenshot of an answer-first question ("Explain Mozi's views on universal love.")
showed a source check whose "Corrections" corrected nothing the answer had said: "Mozi was born in
Tengzhou, Shandong, not just 'China'", "the Spring and Autumn and Warring States periods, not just
the Warring States period", "the 4th century BC, not earlier". The app also put a red line above
such an answer: "The source check below corrects part of this answer."

## How often the corrections are real

Every correction the shipped check wrote on the 58 answer-first drafts of the red team
(`eval/check_ab2_{base,passages,excerpts}.jsonl`, three runs of the check on identical drafts):
60 items from 26 checks, labelled one by one by a grader with web access against the draft and
the facts. Nine were the check's additions, split off by our own parsing. Of the other 51:

| Label | Items |
|---|---|
| Real: the draft is wrong and the correction is right | 14 |
| The correction itself is wrong (Mercury's 176-day solar day, Astana renamed "in 2023") | 12 |
| Corrects something the draft never says | 8 |
| The draft is only less precise ("not just China", "thousands of islands") | 6 |
| Agrees with the draft | 6 |
| Borderline (Nigeria: a newer population estimate) | 5 |

Fourteen of 51 are real. The real ones are worth having (France did not win the 2022 World Cup;
"SL-DSA" does not exist; the Moon on the basketball scale is 7.3 m away, not 10), but they come
with nearly three bad ones each, and the red line above the answer was wrong most of the times it
appeared.

## What did not fix it

- **Quoting the answer.** A check that must quote the answer's own words for each correction, so
  the app could drop corrections whose quote is not in the answer: the model listed statements
  the sources agree with as "corrections" ("Drivers might take long detours" -> "may take
  unnecessarily long routes"), which a quote filter cannot catch. Stopped after 6 drafts.
- **Asking again.** A further turn after the check asking, for each correction, whether the answer
  really says something the source contradicts (Yes/No): on the first 19 items it kept 1 of the 4
  real corrections and 2 of the 11 bad ones. The 2-bit model cannot judge contradiction reliably,
  as the stricter check prompts of 2026-09-20 already showed.

## What does: state the facts, not a verdict

The check now writes "From the sources:" and up to three short points, the facts from the sources
that matter most for the question and that the answer leaves out or states differently, each with
its citation, and is told not to say whether the answer was right or wrong (rag.py
`CHECK_FOLLOWUP`, `VERIFY_SYSTEM`; `Prompts.kt`). A real error still shows: under "France won"
the check says "Argentina won the 2022 final [1]". A precision becomes a plain fact: "Mozi was
born in Tengzhou, Shandong [1]".

Blind A/B on the same 58 drafts (`scripts/check_ab2.py --followup`, `check_pairs.py`,
`check_grades.py`; three graders, one per third, each pair in random order):

| n = 58 | Helped | Neutral | Hurt | False or misleading statements | Draft errors left standing | Preferred |
|---|---|---|---|---|---|---|
| Corrections / Additions (1.4.0) | 32 | 18 | 8 | 19 | 25 | 10 |
| From the sources | 34 | 22 | 2 | 11 | 27 | 14 |

34 ties. Two runs of the same check differed by up to 5 errors and 7 preferences on 2026-09-28,
so the drop in errors (19 to 11) and in checks that hurt (8 to 2) is beyond noise and the
preference is not. The check is as helpful as before and misleads less than half as often. The red
line above the answer is gone with the "Corrections" part (`checkCorrects` removed).

Outputs: `eval/check_ab2_facts.jsonl` against `eval/check_ab2_passages.jsonl`.

## On the phone

The same question on 1.4.0 today: done in 1 min 48 s (the reviewer's run: 4 min 59 s), the answer
complete at 69 s (382 tokens at 6.7 tokens/s), the check 39 s, "No corrections" and three sourced
additions. The reviewer's check ran at 1.1 tokens/s; ours at 5.1.
