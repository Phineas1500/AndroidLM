# One long session: 68 questions back to back (2026-10-14)

Every phone eval so far started each question from a cold app start and let the phone cool to
30.5 C first. A person testing the app does neither: they ask one question after another in the
same session, on a phone that gets hotter, and some questions lean on the one before. The judges'
stated priority is consistent answer quality across longer sessions and a variety of challenging
questions.

## How

`scripts/phone_session.sh` asks every question of a file in one app session on the Pixel 8 Pro
(GrapheneOS, release 1.8.0, Network permission off, on USB power):

- the app is started once; each later question goes to the running app
  (`am start --activity-single-top`), so the loaded model and the previous exchange carry over;
- the next question is asked 30 s after the previous answer finished (reading time), with no
  waiting for the phone to cool;
- `session.tsv` records each question's skin temperature, battery, seconds, and the app's and the
  engine's process ids and memory.

The questions (`eval/questions_session.jsonl`) are the 61 of
[2026-09-28-vitalik-bar.md](2026-09-28-vitalik-bar.md) without the three that need a test GPS
position, in a fixed random order, with 10 follow-ups after their questions. Eight are phrased like
follow-ups ("What about Osaka?", "Which of them is faster to verify?"); two are not ("How much is
a taxi?" after the Lisbon airport question, "What should I avoid doing?" after the snake bite).

## Robustness

- **68 of 68 answered**, in 3 h 49 min. No crash, nothing in the crash log.
- **Memory:** the engine stayed between 5.6 and 5.9GB from the first question to the last.
- **One stall, in the script:** the log dropped one run's `completed` line (and, earlier, another
  run's `phase_done=DRAFTING`), so the script waited out its 20-minute timeout on cry-005, which
  had finished in 222 s, and then restarted the app. Both scripts now also accept the run's final
  `phase=DONE` line.

## Speed: heat, not the session

| Questions | Median time | Writing | First words (median) | Skin temperature |
|---|---|---|---|---|
| 1-10 | 125 s | 3.8 tokens/s | 24 s | 30.7 -> 45.2 C |
| 11-30 | 164 s | 3.2 | 39 s | 43.0 -> 46.7 C |
| 31-50 | 145 s | 3.1 | 55 s | 44.9 -> 46.6 C |
| 51-68 | 160 s | 3.3 | 45 s | 43.9 -> 45.9 C |

The skin reached 45 C by the tenth question and stayed at 44-47 C. Writing fell from 5.6 tokens/s
on the first question to about 3.2, where it held. The median question took 152 s (the ten cold-start
timing questions on GrapheneOS: 111 s). A question asked in the running session starts sooner (planning 2-5 s
instead of 10 s, the model already loaded), but heat costs more than that saves. The phone was on
USB power throughout, which adds heat.

## Quality: the same as cold starts

The 58 answers were graded blind in one sitting next to the 1.2.1 phone answers (cold starts,
2026-10-01) and the web reference (Claude with web search), one grader per group, as in
[2026-10-01-vitalik-bar-121.md](2026-10-01-vitalik-bar-121.md) (`eval/sets_session180_*.json`,
keys and grades alongside):

| Group | 1.2.1, cold starts | 1.8.0, one session | Errors 1.2.1 / 1.8.0 / reference |
|---|---|---|---|
| Restaurants (17) | 62% | 62% | 35 / 33 / 7 |
| Crypto (20) | 55% | 54% | 9 / 13 / 0 |
| Travel, emergencies, arithmetic (21) | 68% | 63% | 33 / 40 / 1 |
| All 58 | 62% | 60% | 77 / 86 / 8 |

The reference was the best answer on all 58. These graders were stricter than those of 10/01
(1.2.1 then came to 65%); within one sitting the two app versions are within a few points.

Late questions are not worse than early ones. By position in the session, 1.8.0's share follows
1.2.1's on the same questions: positions 1-22 65% (1.2.1: 67%), 23-45 57% (58%), 46-68 57% (61%).
The questions that moved most moved both ways, and are run-to-run differences of the 2-bit model:
"thank you" in Thai lost the male form (8 -> 2), the snake bite gained (6 -> 8).

## Follow-ups: 6 of 10

| Follow-up | In the session |
|---|---|
| What about Osaka? | rewritten "Tell me the best vegan restaurants in Osaka", list of 102 |
| Which of those are open late? | "Which of the vegan restaurants in Lisbon are open late?" |
| And at 34 baht to the dollar? | the whole bill question with the new rate |
| Tell me more about the smoky season | "...in Chiang Mai" |
| Which one has the smallest signatures? | with ML-DSA, FN-DSA and SLH-DSA; "FN-DSA (Falcon)" |
| And in Vietnam? | "What emergency numbers should I know in Vietnam?"; 113, 114, 115 |
| Which of them is faster to verify? | **left unchanged**; answered about aggregate signatures |
| How long are they kept? | **"How long are blobs kept?"**: Ethereum dropped, answered about cloud storage |
| How much is a taxi? | **not seen as a follow-up**: taxi fares in general |
| What should I avoid doing? | **not seen as a follow-up**; the plan was instructions ("Do not include any text other than the Wikipedia article titles.") and the answer recited the answer prompt ("Do not use preambles, restate the question, use LaTeX...") |

The rewrite prompt in the phone's format (instructions and the exchange in one user message)
reproduces the first two failures on the VM's llama-server, so it could be tested there.

## Fixes

- **The rewrite keeps the subject** (`Prompts.FOLLOWUP_SYSTEM`): the follow-up must make sense to
  someone who has not seen the previous question, with what it refers to and the subject it is
  about (the place, the technology, the situation); it is repeated unchanged only when it is about
  something new. On the VM: all 12 follow-ups rewritten as intended (the old prompt: 6 of the 8 it was run on), and
  all 11 questions that stand alone kept their meaning, including "what is ethereum" after an
  Ethereum answer and "Who discovered penicillin?" after a first-aid one. A first rewording, which
  listed the words to replace, dropped the bill amount from the baht follow-up and was not kept.
- **What counts as a follow-up** (`FollowUp.looksLike`): "I" is not a name (any question with "I"
  in it counted as naming its subject), and a question of five words or fewer that names nothing
  is a possible follow-up. Of 253 distinct eval questions, 9 more would be rewritten when asked
  after another ("Who discovered penicillin?", "what is ethereum"); the rewrite returns them
  unchanged, at 12-27 s (17 s for penicillin on the phone). At six words, "Which signature algorithms are quantum resistant?" would
  have been one, so the limit is five.
- **A plan line that is a sentence is not a title** (`Planner.isPromptEcho`, rag.py
  `is_prompt_echo`): five or more words ending in a full stop.
- **An answer that recites the instructions is replaced** (`PromptLeak`): three or more phrases of
  the answer prompts ("preamble", "LaTeX", "deliberation", "restate the question"...) that the
  question itself does not use. The answer becomes "I'm not sure what this question is about.
  Could you ask it again with its subject...", and an answer-first run ends there, with no search
  or source check.
- **A question that starts by asking a price is not a places request** (places.py `PRICE`,
  `Places.kt`): the rewritten "How much is a taxi from Lisbon airport to the city center?" had gone
  to a list of stations ("not provided in the available data"). Only at the start: "Best vegan
  restaurants in Buenos Aires, and how much should I tip?" still asks for places. All 263 eval
  questions parse as before, and the places golden file is unchanged.

The same failures again on the phone, in one session, with the fixed app:

| Question | Now |
|---|---|
| What should I avoid doing? (first question of the session) | the request to say what it is about, in 42 s |
| What should I avoid doing? (after the snake bite) | "...after being bitten by a snake while hiking..."; do not cut, suck, ice or use a tourniquet |
| How much is a taxi? (after Lisbon airport) | "How much is a taxi from Lisbon airport to the city center?"; with the price rule, the general route: "approximately €15 to €25", by destination, time and luggage |
| Which of them is faster to verify? | "Which is faster to verify, ML-DSA or SLH-DSA?"; the pack's sources had no figures and the answer said so |
| How long are they kept? | "How long are blobs kept in Ethereum?"; about 18 days |
| Who discovered penicillin? (after the follow-ups) | unchanged; Alexander Fleming, 1928 |

Released as 1.8.1 (2026-10-15). The release APKs, on the phone: the online one in one session,
blobs then "How long are they kept?" ("...in Ethereum?", about 18 days), then the Lisbon airport
question (not rewritten) and "How much is a taxi?" (€15 to €25); the offline one over it, "What
should I avoid doing?" as a first question (the request to say what it is about, in 42 s).

## Still open

- **Heat:** after ten questions the phone writes at about 60% of its cool speed. Nothing in the app
  changes that; shorter answers would, at a cost in quality.
- **A follow-up costs a rewrite:** 12-27 s before the run starts.
