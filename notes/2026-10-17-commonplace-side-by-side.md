# Side by side with Commonplace on one phone

2026-10-16/17. AndroidLM 1.8.2 and [Commonplace](https://github.com/0xPD33/commonplace) 0.2.1, the
other entry that answers from a library on the phone, on the same Pixel 8 Pro (GrapheneOS,
Android 17), each on Commonplace's own 63 questions and on our 58. Every answer was graded blind
next to a web reference by graders who were not told which answer came from which system. All
answers, keys and grades are in `eval/`, Commonplace's wins included.

| | Commonplace's 63 questions | Our 58 questions |
|---|---|---|
| AndroidLM, share of the web reference's score | **71%** | **67%** |
| Commonplace, share of the web reference's score | 53% | 36% |
| AndroidLM against Commonplace, question by question | **45 better, 13 worse, 5 equal** (sign test p = 0.00003) | **48 better, 6 worse, 4 equal** (p = 0.000000003) |
| Errors per 1,000 characters (AndroidLM / Commonplace) | 0.61 / 2.32 | 0.59 / 2.18 |
| Answers scored 3 or less (AndroidLM / Commonplace) | 7 / 16 | 2 / 32 |
| Time to a finished answer, median (AndroidLM / Commonplace) | 144 s / **33 s** | 141 s / **57 s** |
| First words, median (AndroidLM / Commonplace) | 20 s / **15.5 s** | 35 s / **19 s** |
| Answer length, median characters (AndroidLM / Commonplace) | 1,419 / 506 | 1,622 / 417 |

Commonplace's 53% on its own questions is the score its author reports for it (0.530 against
Claude with web search, `docs/BENCHMARKS.md`, measured on a desktop with the phone's settings), so
this grading is not harsher on it than its own. Commonplace is 2.5-4.4 times faster to a finished
answer, mostly because its answers are a third as long; AndroidLM is better on 93 of the 121
questions and worse on 19.

## Method

- **Questions.** Commonplace's 63 seed questions with its author's reference answers (Claude Opus
  with web search; Apache-2.0, `eval/questions_commonplace63.jsonl`,
  `eval/answers_web_commonplace63.jsonl`), and our 61 of
  [2026-09-28](2026-09-28-vitalik-bar.md) without the three that need a test GPS position
  (`eval/questions_vitalik58.jsonl`, references `eval/answers_web_vitalik.jsonl`). Commonplace's
  three follow-ups ("Why did he build it?") were asked by both apps in one chat or topic right
  after their first question ("Who built the Taj Mahal?"), which its own eval passes as history.
- **AndroidLM.** 1.8.2 (online build), Qwen3.6-35B-A3B, one app start per question 30 s apart
  (`scripts/phone_eval.sh`), from a phone at 30 °C that reached 43 °C. On our 58 the answers are
  from the 10/14 long session with 1.8.0 ([2026-10-14](2026-10-14-long-session.md)): all 58 in one
  running app, with no cooling. None of the 58 was rewritten as a follow-up there, and 1.8.1 and
  1.8.2 changed nothing that a question in a new chat goes through.
- **Commonplace.** The release APK (SHA-256 and signing certificate as published), with the
  Starter set and the Reference shelf (13.3 GB, both checked by the app's SHA-256 import), on its
  defaults (Ling-3.0-tiny Q4_0, CPU, 4 threads, 400-token answers, planning on, question rewrite
  off) with only "Show timings" turned on. `scripts/phone_commonplace.py` typed each question into
  a new topic, waited for the answer and read it from the screen. `adb` cannot type accents, so
  "Medellín" was typed as "Medellin" and "104°F" as "104 degrees F". Its 63 started from a
  phone at 30.6 °C; after 18 answers the run paused for six hours and resumed at 28 °C; its 58
  followed straight on, at 35-39 °C.
- **Grading.** For each question the three answers (AndroidLM, Commonplace, the web reference) in
  a random order, with the question set's note of what a good answer covers. Six graders (Claude,
  each in one sitting, with web search to check facts and places), three per question set, about
  20 questions each, scored every answer 0-10, listed its errors and picked the best.
  `scripts/h2h_grades.py` unblinds them.

## Where each wins

Commonplace's 63, by category (mean score out of 10):

| Category | n | AndroidLM | Commonplace | Web |
|---|---|---|---|---|
| Comparison | 8 | **6.6** | 5.4 | 8.9 |
| Explanation | 11 | **6.7** | 4.1 | 9.0 |
| Factual | 5 | **6.8** | 4.0 | 9.0 |
| How-to | 7 | **7.7** | 4.0 | 9.0 |
| Long tail | 3 | **6.0** | 5.3 | 9.0 |
| Multi-hop | 11 | **7.0** | 5.0 | 9.2 |
| Numeric | 5 | 4.2 | **7.0** | 9.4 |
| Not in the library | 4 | 3.2 | **4.2** | 9.0 |
| Travel | 9 | **6.8** | 4.9 | 8.9 |

Our 58:

| Category | n | AndroidLM | Commonplace | Web |
|---|---|---|---|---|
| Crypto and cryptography | 20 | **6.3** | 2.7 | 10.0 |
| Danger situations | 5 | **6.8** | 4.8 | 10.0 |
| Vegan restaurants in a city | 17 | **6.2** | 1.9 | 8.6 |
| Maths and reasoning | 6 | **8.2** | 5.3 | 10.0 |
| Practical travel | 10 | 5.5 | **5.6** | 9.8 |

Each of the six graders put AndroidLM ahead (means 6.0-6.7 against 3.3-5.0).

Commonplace's 19 wins, by what went wrong in AndroidLM's answer:
- **Arithmetic** (3): France's area taken as 6.4 million km² ("2.67 times", for about 26); a
  wrong subtraction for Everest minus Mont Blanc; a terse Great Wall walk.
- **Wrong facts it stated** (8): Oriel instead of The Queen's College for Berners-Lee, an invented
  "Charles E. Seele" for the Golden Gate Bridge, "khop khun ka" for men in Thai, the Lisbon
  airport metro and Aerobus, a service charge in Portugal, cream of tartar in baking powder, water
  as the RBMK moderator, a ten-year rule for Canberra.
- **A places search where none was asked for** (1): "Do you tip in restaurants in Japan?" went to
  the places route, and the answer listed a chicken restaurant and an internet cafe in Jahrom,
  Iran, under its correct text.
- **A newer Wikipedia** (1): Commonplace's (snapshot 2026-09-01) knows that Spain won the 2026
  World Cup; AndroidLM said Argentina, 2022.
- **Others** (6): the Roman Empire answer that contradicts itself in its first sentence, the
  latest iPhone (both out of date), 112 in Thailand, ride-hailing in Bangkok, the water for a
  hike, the Thai phrases.

Commonplace has no places data and no Ethereum library, which is most of the gap on our 58; on its
own 63 AndroidLM is ahead in seven of nine categories.

## Speed, and why Commonplace is slower here than its own numbers

Commonplace's benchmarks are from a Pixel 10 (Tensor G5): first word 7.2 s median, 16-17
tokens/s on the first questions, 8-9 tokens/s once the phone passes about 37 °C. On this Pixel 8
Pro (Tensor G3, whose mid cores run at up to 2.37 GHz against the G5's 3.05):

| Commonplace on the Pixel 8 Pro | First word, median | Writing, median |
|---|---|---|
| Skin below 34 °C (15 answers) | 13.2 s | 10.6 tokens/s |
| Skin 36.5 °C and above (61 answers) | 17-19 s | 2.7-3.8 tokens/s |

- The same first question took 42.8 s (4.4 tokens/s) when the phone was still warm from AndroidLM's
  run and 26.4 s (10.8 tokens/s) after it cooled to 30.6 °C. The warm start was discarded and the
  run restarted from a cool phone, as AndroidLM's had.
- Its threads run on the five fast cores (4-8), as they should. When hot, the mid cores were held
  at 1.84 GHz.
- Android 17's memory limiter caps the app at 6 GiB while it is on screen, and it sits at the cap
  (its packs' file pages count against it): the system logged it going over about every 40 s.
  About 2.9 GB was swapped out and back in around the model's start; during the answers sampled
  later almost nothing was.

AndroidLM's times are what [2026-10-14](2026-10-14-long-session.md) found: about 2-2.5 minutes,
first words in 20-35 s, with the source check after the answer.

## Caveats

- **Grader family.** The graders are Claude, and both sets' web references were written by Claude
  with web search. That can favour the references, not one app over the other.
- **Tuning.** Our 58 are questions AndroidLM has been tuned on; Commonplace's 63 are questions
  Commonplace has been tuned on. AndroidLM leads on both.
- **One phone.** On a Pixel 10 Commonplace writes 1.5-3 times faster (its own numbers against
  these) and AndroidLM would be faster too; the quality comparison should not change.
- **Settings and packs.** Commonplace ran its defaults with the packs its own benchmarks use (the
  Starter set and the breadth packs); its full English Wikipedia (19.2 GB) and Stack Exchange
  packs were not installed. Its
  question rewrite (off by default) might help its follow-ups; it won two of the three anyway.
- **Different days.** On our 58, AndroidLM's answers are from 10/14 (1.8.0), Commonplace's from
  10/17. Commonplace's 63 had a six-hour pause after 18 answers.
- **Screen reading.** Commonplace's answers were read from its screen with uiautomator; its citation
  numbers appear as plain digits ("riel 7"). Every answer was checked to be complete; screenshots
  of each are kept with the run, not in the repository.

## Files

- `eval/answers_phone182_cp63.jsonl`: AndroidLM on Commonplace's 63 (with timings and routes).
- `eval/answers_commonplace021_cp63.jsonl`, `eval/answers_commonplace021_v58.jsonl`: Commonplace,
  with the typed question, its timings line, skin temperatures and, for a follow-up, its first
  question and answer.
- `eval/sets_h2h_*.json`, `eval/key_sets_h2h_*.json`, `eval/grades_sets_h2h_*.json`: the blind
  sets, the keys and the six graders' grades (with which grader graded each).
- `scripts/phone_commonplace.py` (driver), `scripts/h2h_grades.py` (unblinding).
