# Travel eval: Wikipedia only vs Wikipedia + Wikivoyage (2026-09-20)

16 practical travel questions about mid-known destinations (`eval/questions_travel.jsonl`),
`--mode auto`, UD-Q2_K_XL, whole model in RAM on the VM. Grades: `eval/grades_travel.json`
(Claude subagent, calibrated to the earlier travel grades). The plan-title parsing fix
(leading digits) was NOT in the code these runs used.

| | Wikipedia only | + Wikivoyage | Draft alone |
|---|---|---|---|
| All 16 | 5.94 | 5.91 | n/a |
| Answer-first + check (13) | 6.38 | 6.38 | 6.08 |
| Retrieval-first (3) | 4.00 | 3.83 | n/a |

Retrieval rated good / partial / miss: 1 / 14 / 1 without Wikivoyage, 5 / 11 / 0 with it.
Source check helped / neutral / hurt: 7 / 5 / 1 without, 8 / 4 / 1 with. Wikivoyage passages
reached the model on 11 of 16 questions (18 passages).

## Reading

- Wikivoyage improved what was retrieved but not the final score. On 13 of 16 questions the
  user sees the model's own draft (identical in both runs), so Wikivoyage could only act through
  the 260-token source check, and that check uses little of what it is given.
- Gains: Petra (+1: See section corrected the Siq description and added the Monastery climb),
  food questions (Hokkaido Eat), journey times (Ella), alms-ceremony details (Luang Prabang).
- Losses: guide passages displaced useful Wikipedia passages (Hokkaido lost its climate
  passage, Luang Prabang lost a correction); one irrelevant section led to an invented claim
  (Faroe: "Vágar - Get in > By plane"); a garbled museum-pass claim (Cappadocia); leaked
  deliberation in one check (Stone Town).
- Travel is the weakest category overall (about 5.9 vs about 7.5 on general questions): the
  drafts for mid-known destinations are mediocre and the retrieval-first answers were hurt by
  title mismatches.

## Backlog from this round (not done)

1. Choose the guide section from the question's intent (etiquette -> Respect, scams -> Stay
   safe, transport -> Get around, cold -> Climate, fees -> Understand/Get in); today See or
   an arbitrary early section often wins.
2. Add guide passages instead of letting them displace Wikipedia passages; cover every part of
   the question.
3. Title resolution: a one-word planned title must not fuzzy-match a longer title ("Ger" ->
   Ger Canning, "Ella" -> Ella Mai, "Paro" -> Paros); Wikivoyage uses different names for
   some subjects (Camino de Santiago is "Way of St. James"); Oaxaca, Uzbekistan, Mongolia and
   Bhutan got no guide passage at all.
4. For travel questions with a good guide article, consider retrieval-first with the guide as
   the main source rather than answer-first.
5. Strip deliberation text ("But wait...") from source-check output.

Any change to retrieval now has to be made in both `scripts/rag.py` and the Kotlin port, with
the golden file regenerated.

## Re-run after the retrieval batch (same day)

20 travel questions (the 16 above plus the 4 original ones), Wikivoyage on, new retrieval
(commit d745190). Grades: `eval/grades_travel2.json`.

| | Normal router | Travel questions retrieval-first | Previous round |
|---|---|---|---|
| All 20 | 6.17 | 5.70 | n/a |
| The 16 new questions | 6.16 | 5.47 | 5.94 Wikipedia only / 5.91 old Wikivoyage retrieval |

- The batch helped modestly: +0.2 on the 16 questions, and 6.0 vs 5.21 on the 7 questions whose
  route and draft did not change. Wrong-place title matches are gone (Paros, Ella Mai, Ger
  Canning, Sultanahmet Jail). Guide passages reached 19 of 20 questions (11 of 16 before).
  Retrieval rated good / partial / miss: 6 / 10 / 0 (5 / 11 / 0 before); section choice is
  still often off (Sleep > Budget for a layout question, Eat > Mid-range, missing Respect).
- Retrieval-first for travel is worse and stays off: on the 12 questions routed differently it
  scored 5.38 vs 6.17, losing on 8. Those answers contain only what the passages say and drop
  the model's own correct knowledge ("the sources do not mention temperatures"). It won only
  where the draft was poor (Petra +2, Georgia +1.5).
- The trade-off is hallucination: uncorrected factual errors counted by the grader were 74
  (router) vs 30 (retrieval-first) over the 20 questions, 51 vs 7 on the 12 that differ.
- Untested idea from the grader: draft first, then rewrite the draft with the passages in
  context, to keep the model's knowledge and the sources' corrections. Cheaper: stop the source
  check from listing "not mentioned in the sources" as corrections.
