# Against internet search + frontier AI, on Vitalik-style questions (2026-09-28)

The bounty asks for a research tool that is ">50% as good as internet search + frontier AI
models". This round measures that directly: the app on the phone against a frontier model
answering with web search, on the questions Vitalik's posts point at.

## Questions

The 61 questions of Boar's evaluation set v2 (`eval/dataset/questions.v2.jsonl` in pull request
#14 of [rferrari/boar-app](https://github.com/rferrari/boar-app), MIT licence), copied with a note
per question on what a good answer holds (`eval/questions_vitalik.jsonl`):

| Group | n | What |
|---|---|---|
| food | 20 | 16 in the phrasing of Vitalik's post of 2026-09-26, "Tell me the best vegan restaurants in X": 13 named cities and 3 "the city I am currently in" / "near me". Plus 3 vegetarian questions (Tbilisi, Kyoto, Medellín) and 1 in Portuguese (São Paulo) |
| cry | 20 | post-quantum signatures and Ethereum; "Which signature algorithms are quantum resistant?" is the post's own question |
| trv | 10 | travel facts (emergency numbers, plugs, tap water, airport transport, tipping, ride-hailing) |
| dng | 5 | emergencies (snakebite, hypothermia, a scald, an earthquake, flood water) |
| mth | 6 | travel arithmetic (fuel use, Naismith's rule, currency, water, battery, heat) |

## How

- **The app:** each question asked in the app on the Pixel 8 Pro (current main; the two questions
  that the cuisine-word bug sent to places, trv-007 and mth-003, were asked again after fb38a56
  fixed it). Every question started from a cold app start. The three location questions had a
  test GPS position (Barcelona, Hong Kong, Denver). The app has no internet permission.
- **What was graded:** what the user reads, meaning the answer, its source check when there is
  one and, for restaurants, the list the app shows with it (`scripts/app_answer_records.py`;
  `eval/answers_phone_vitalik.jsonl`).
- **The reference:** Claude (Opus 5.5) with web search, answering as a frontier assistant with
  internet search would, citing an average of 3.5 pages (`eval/answers_web_vitalik.jsonl`, with
  its sources).
- **Grading:** blind pairs in random order (`scripts/answer_pairs.py`; `eval/pairs_vitalik.json`,
  key in `eval/key_vitalik.json`). There were three graders, one each for restaurants, crypto and
  the rest. Each was a separate Claude instance, told only that one answer came from an offline
  phone app. Graders used web search to check that the places were real, open and vegan, and to
  check the facts. For each answer they gave a 0-10 score for how well it serves the asker, a
  list of factual errors, and which answer they preferred.
- **The share:** the app's total score as a share of the reference's (`scripts/ratio_grades.py`;
  `eval/grades_vitalik.json`).

## Result

| Group | n | App (mean /10) | Reference | App as share of reference | Preferred app / reference | Errors app / reference | First words (median) | Done (median) |
|---|---|---|---|---|---|---|---|---|
| Restaurants | 20 | 5.88 | 8.68 | **68%** | 0 / 20 | 21 / 6 | 16 s | 40 s |
| Crypto | 20 | 5.15 | 9.97 | **52%** | 0 / 20 | 32 / 0 | 18 s | 124 s |
| Travel | 10 | 6.20 | 9.30 | **67%** | 0 / 10 | 14 / 2 | 19 s | 112 s |
| Emergencies | 5 | 7.00 | 9.40 | **74%** | 0 / 5 | 2 / 1 | 36 s | 140 s |
| Arithmetic | 6 | 7.75 | 9.50 | **82%** | 0 / 6 | 1 / 0 | 36 s | 102 s |
| All | 61 | 5.97 | 9.34 | **64%** | 0 / 61 | 70 / 9 | 18 s | 102 s |

The app reaches 64% of the reference overall, and more than the bounty's 50% in every group.
It does not beat the reference on any question: the graders preferred the reference all 61
times. By this measure the app is about two thirds as good, not as good.

The app's speed, as medians over the phone runs (included in the table): restaurant answers are
done after 40 s. Other questions show their first words after 18-36 s and are done, with the
source check, after 102-140 s.

## Where the app falls short

**Crypto (52%, 32 errors against 0).** This is the weakest group, and it holds Vitalik's own
question.
- **Mechanics stated wrongly:**
  - Casper FFG called probabilistic and LMD-GHOST instant (score 1.5).
  - EIP-7702 delegation said to last one transaction (1.5).
  - The maximum effective balance after EIP-7251 given as 32 ETH instead of 2,048 (1.0).
- **Invented names:** a "LEED" lattice scheme, a "Payload Delivery API", RFC 8559 for LMS.
- **Why the check misses them:** Wikipedia says little about individual EIPs or the consensus
  specs, so the source check has nothing to correct against. The model's own memory stops before
  Pectra, which it still calls "expected in mid-2025".

**Restaurants (68%, 21 errors against 6).** The places are real and in the right
city, but the data has two kinds of fault:
- **Closed places still listed,** because the map data has not marked them: Living Vino
  (Tbilisi), Vegetalia Gòtic (Barcelona), May Veggie Home (Bangkok), Native Foods (Denver) and
  two in Mexico City.
- **Non-vegan places listed as vegan,** because the map data's category says so: MOS Burger in
  Taipei, Green Eat in Buenos Aires, Saravana Bhavan and Annalakshmi in Singapore, Maoz,
  Sweetgreen.

The app's answer mostly restates the list in short lines. The reference adds what to order, the
neighbourhood and when to book.

**Travel (67%, 14 errors against 2).** Time-sensitive transport details are wrong or out of date:
- Lisbon airport (2.5): the Aerobus is discontinued, and the red line does not reach
  Restauradores.
- Ride-hailing in Bangkok (2.5).
- Wrong reasons and facts: Thailand's left-hand driving put down to British colonial ties,
  Yi Peng placed in December.
- One answer (trv-008) was cut off mid-sentence.

**Emergencies (74%) and arithmetic (82%)** come nearest to the reference. The hypothermia answer mislabels its stage,
and the heat answer cites a guideline that does not exist.

## Caveats

- **Grader bias:** the reference and the graders are the same model family, so the graders may
  favour its style. They checked facts on both sides with web search and found 9 errors in the
  reference.
- **Run-to-run noise:** each group was graded once. In the check A/B
  ([2026-09-28-check-speed.md](2026-09-28-check-speed.md)), two gradings of the same answers
  differed by up to 7 preferences in 58.
- **What the share means:** it is one reading of "as good as". The preference count gives the
  other reading: the reference was better every time. The share measures how far behind the
  app is.
- **Stated for comparison, not checked here:** Boar's pull request #14 reports a ratio of
  0.59-0.66 for its app against its own frontier reference and grader, measured on a desktop,
  not a phone.
- **A fix to the eval records:** the app's answer records first split place names that contain
  " | " ("Zerö Kebab | Plant-Based Döner") into separate entries. One grader counted that as an
  app error. After the fix, the four affected restaurant answers (food-001, 003, 004, 010) were
  regraded blind by a fresh grader with the same instructions, and those grades replace the
  first ones:
  - The app's four scores went from 5, 6, 4.5 and 3.5 to 7, 6, 5 and 5; the reference's stayed
    at 8-9.
  - The restaurant share went from 65% to 68%, and the overall share from 63% to 64%.
  - That grader's web search hit a limit, so it checked the places in OpenStreetMap, the
    Michelin guide, Wikivoyage and the restaurants' own sites instead.

## What to work on

1. **Ethereum and post-quantum sources on the phone:**
   - the EIPs (CC0);
   - the consensus specs and ethereum.org's documentation;
   - NIST's post-quantum standards pages.

   Questions that name an EIP or a standard should go sources-first. This is the biggest gap,
   and it is on Vitalik's own subject.
2. **Restaurant data:**
   - Call a place vegan only when more than one source's category says so, or when
     OpenStreetMap has diet:vegan=only. One category is not enough: MOS Burger is "vegan" in
     one.
   - Prefer places that OpenStreetMap and Overture both list, since closed places are the
     main error.
   - Give the model the neighbourhood and cuisine, so it can write more than a restated list.
3. **Travel:** transport and money facts date quickly. Wikivoyage's "Get in" sections are newer
   than the model's memory. An answer-first travel question could check against them first.

## Follow-up (same day)

- **Crypto:** the library of item 1 is in the app
  ([2026-09-28-ethereum-pack.md](2026-09-28-ethereum-pack.md)). The crypto share went from 47% to
  58% and errors from 30 to 6, graded side by side with the earlier answers.
- **Restaurants:**
  - *At question time (in the app):* a vegan category from Overture no longer outweighs
    OpenStreetMap's "vegetarian only".
  - *At build time (places v21, not yet published):* a lone branch of a business whose other
    places are not vegan loses its vegan category and keeps "vegan options". This affects 129
    places worldwide.
  - *Effect:* on the 20 restaurant questions, 6 lists change. Lotos, Pine Tree Cafe, Los
    Vegetarianos, Green Eat and Sweetgreen leave them. Saravana Bhavan (two branches in
    Singapore, below the rule's minimum) and MOS Burger in Taipei (tagged vegan-only in
    OpenStreetMap itself) stay.
- **Restaurant answers (2026-09-29):** the model now writes six to eight places with what they
  serve, their street and hours, in the question's language
  ([2026-09-29-places-answers.md](2026-09-29-places-answers.md)). On the phone, graded side by
  side with the earlier answers, the share went from 45% to 71% of the reference, and it was
  better on all 20 questions. An answer now takes about two minutes instead of forty seconds.

