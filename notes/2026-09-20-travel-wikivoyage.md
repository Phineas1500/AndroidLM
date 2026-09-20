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
